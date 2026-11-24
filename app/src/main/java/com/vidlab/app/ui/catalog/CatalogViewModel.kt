package com.vidlab.app.ui.catalog

import android.app.Application
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidlab.app.data.model.*
import com.vidlab.app.data.remote.MediaCatalogRepository
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RenamerBackgroundService
import com.vidlab.app.data.scraper.FilenameSanitizer
import com.vidlab.app.data.scraper.MatchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ManualQuarantineItem(
    val file: MediaFile,
    val detectedTitle: String,
    val detectedYear: String?,
    val isSeries: Boolean,
    val season: Int,
    val episode: Int,
    val suggestedCategory: String,
    val reason: String
)

data class RenameProgressState(
    val isRunning: Boolean = false,
    val currentStep: String = "Idle",
    val processedCount: Int = 0,
    val totalCount: Int = 0,
    val currentFile: String = "",
    val logs: List<String> = emptyList(),
    val unmatchedItems: List<ManualQuarantineItem> = emptyList(),
    val isCompleted: Boolean = false,
    val error: String? = null
)

class CatalogViewModel(application: Application) : AndroidViewModel(application) {

    val repository = MediaCatalogRepository(application)

    private val _feedSections = MutableStateFlow<List<MediaFeedSection>>(emptyList())
    val feedSections: StateFlow<List<MediaFeedSection>> = _feedSections.asStateFlow()

    private val _isFeedLoading = MutableStateFlow(false)
    val isFeedLoading: StateFlow<Boolean> = _isFeedLoading.asStateFlow()

    private val _feedError = MutableStateFlow<String?>(null)
    val feedError: StateFlow<String?> = _feedError.asStateFlow()

    private val _progressState = MutableStateFlow(RenameProgressState())
    val progressState: StateFlow<RenameProgressState> = _progressState.asStateFlow()

    private val _singleRenamePlan = MutableStateFlow<List<RenameOperation>?>(null)
    val singleRenamePlan: StateFlow<List<RenameOperation>?> = _singleRenamePlan.asStateFlow()

    private val _isSingleRenaming = MutableStateFlow(false)
    val isSingleRenaming: StateFlow<Boolean> = _isSingleRenaming.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TmdbSearchResult>>(emptyList())
    val searchResults: StateFlow<List<TmdbSearchResult>> = _searchResults.asStateFlow()

    val ambiguousPrompts: SharedFlow<AmbiguousPrompt> = repository.ambiguousMatchesFlow

    private val vaultOrganizedCats = setOf("movies", "shows", "tv shows", "anime", "kdrama", "documentaries", "animation")
    private val vaultMessyTagRegex = Regex("(?i)(\\[[^\\]]+\\]|\\b(?:1080p|720p|480p|2160p|4k|uhd|web-?dl|webrip|bluray|bdrip|x264|x265|hevc|remux|10bit)\\b)")
    private val vaultDotReleaseRegex = Regex("[A-Za-z0-9]\\.[A-Za-z0-9]+\\.[A-Za-z0-9]")

    init {
        loadMediaCenter()
    }

    private fun extractVidLabRelSegments(path: String): Pair<String, List<String>>? {
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        val vidLabIdx = segs.indexOfFirst { it.equals("VidLab", ignoreCase = true) }
        if (vidLabIdx == -1) return null
        val prefix = if (vidLabIdx > 0) "/" + segs.take(vidLabIdx).joinToString("/") else ""
        val afterVidLab = segs.drop(vidLabIdx + 1)
        return prefix to afterVidLab
    }

    private fun isCleanlyOrganizedInVault(f: MediaFile): Boolean {
        val (_, afterVidLab) = extractVidLabRelSegments(f.path) ?: return false
        val inCategorySubdir = afterVidLab.size >= 3 && afterVidLab.firstOrNull()?.lowercase() in vaultOrganizedCats
        if (!inCategorySubdir) return false
        val baseName = f.name.substringBeforeLast('.')
        val parentFolder = afterVidLab.dropLast(1).lastOrNull().orEmpty()
        val hasMessyTags = vaultMessyTagRegex.containsMatchIn(f.name) || vaultMessyTagRegex.containsMatchIn(parentFolder)
        val hasDotRelease = (!baseName.contains(' ') && vaultDotReleaseRegex.containsMatchIn(baseName)) ||
            (!parentFolder.contains(' ') && vaultDotReleaseRegex.containsMatchIn(parentFolder))
        return !hasMessyTags && !hasDotRelease
    }

    fun triggerLibraryBatchRename() {
        runBatchOrganizer(vaultOnly = false)
    }

    fun triggerVidLabFolderFix() {
        runBatchOrganizer(vaultOnly = true)
    }

    fun loadMediaCenter(force: Boolean = false, onFinished: ((Int) -> Unit)? = null) {
        viewModelScope.launch {
            if (!force && !RcloneEngine.hasAnyConfiguredSource(getApplication())) {
                _isFeedLoading.value = false
                _feedError.value = null
                onFinished?.invoke(0)
                return@launch
            }
            val cached = withContext(Dispatchers.IO) { repository.loadCachedFeed() }
            _feedSections.value = cached
            _isFeedLoading.value = true
            _feedError.value = null
            val result = repository.buildMediaCenterFeed { partialSections ->
                if (partialSections.isNotEmpty()) {
                    _feedSections.value = partialSections
                }
            }
            result.onSuccess { sections ->
                _feedSections.value = sections
                _isFeedLoading.value = false
                onFinished?.invoke(sections.sumOf { it.items.size })
            }.onFailure { err ->
                _feedError.value = err.localizedMessage
                _isFeedLoading.value = false
                onFinished?.invoke(-1)
            }
        }
    }

    fun searchCandidates(query: String) {
        viewModelScope.launch {
            _searchResults.value = repository.scraper.searchMetadata(query)
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
    }

    fun applyCandidateSelection(item: ScrapedMediaItem, candidate: TmdbSearchResult) {
        repository.saveManualBinding(
            mediaKey = item.id,
            targetTitle = candidate.title,
            mediaType = candidate.mediaType,
            year = candidate.year,
            posterUrl = candidate.posterUrl
        )
        val updatedItem = item.copy(
            title = candidate.title,
            releaseYear = candidate.year,
            isSeries = candidate.mediaType.equals("tv", ignoreCase = true),
            posterUrl = candidate.posterUrl ?: item.posterUrl
        )
        _singleRenamePlan.value = repository.generateRenamePlan(
            item = updatedItem,
            customTitle = candidate.title,
            customYear = candidate.year,
            isManualOverride = true
        )
        loadMediaCenter()
    }

    fun applyManualCustomRename(item: ScrapedMediaItem, title: String, year: String?) {
        val type = if (item.isSeries) "tv" else "movie"
        repository.saveManualBinding(item.id, title, type, year, null)
        val updatedItem = item.copy(title = title, releaseYear = year)
        _singleRenamePlan.value = repository.generateRenamePlan(
            item = updatedItem,
            customTitle = title,
            customYear = year,
            isManualOverride = true
        )
        loadMediaCenter()
    }

    fun dismissSingleRenamePlan() {
        _singleRenamePlan.value = null
    }

    fun executeSingleItemRename(onSuccess: () -> Unit) {
        val ops = _singleRenamePlan.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isSingleRenaming.value = true
            val app = getApplication<Application>()
            var failed = 0
            for (op in ops) {
                if (op.oldPath != op.newPath) {
                    val res = when {
                        RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, op.oldPath, op.newPath)
                        RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, op.oldPath, op.newPath)
                        else -> repository.webDavClient.moveFile(op.oldPath, op.newPath)
                    }
                    if (res.isSuccess) com.vidlab.app.data.prefs.MediaSpecsCache.migrateOnRename(app, op.oldPath, op.newPath) else failed++
                }
            }
            val singleSrcDirs = ops.filter { it.oldPath != it.newPath }.map { "/" + it.oldPath.trim('/').substringBeforeLast('/', "") }
            RcloneEngine.pruneEmptyDirectories(app, singleSrcDirs)
            _isSingleRenaming.value = false
            _singleRenamePlan.value = null

            withContext(Dispatchers.Main) {
                if (failed == 0) {
                    Toast.makeText(app, "Moved to /VidLab successfully!", Toast.LENGTH_SHORT).show()
                    onSuccess()
                } else {
                    Toast.makeText(app, "$failed file(s) failed to move", Toast.LENGTH_LONG).show()
                }
            }
            loadMediaCenter(force = true)
        }
    }

    fun skipQuarantineItem(qItem: ManualQuarantineItem) {
        val app = getApplication<Application>()
        val prefs = app.getSharedPreferences("vidlab_organizer_skipped", android.content.Context.MODE_PRIVATE)
        val existing = prefs.getStringSet("skipped_paths", emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add(qItem.file.path)
        prefs.edit().putStringSet("skipped_paths", existing).apply()
        _progressState.update { st ->
            st.copy(
                unmatchedItems = st.unmatchedItems.filterNot { it.file.path == qItem.file.path },
                logs = (listOf("↷ Skipped (Ignored): ${qItem.file.name}") + st.logs).take(120)
            )
        }
    }

    fun applyManualQuarantineMatch(
        qItem: ManualQuarantineItem,
        chosenTitle: String,
        chosenCategory: String,
        chosenYear: String?,
        seasonNum: Int,
        episodeNum: Int,
        episodeTitle: String? = null
    ) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val cleanTitle = chosenTitle.replace(Regex("[/\\\\?%*:|\"<>=+]"), "").trim()
            if (cleanTitle.isBlank()) return@launch
            val ext = qItem.file.name.substringAfterLast('.', "mkv").ifBlank { "mkv" }
            val yearSuffix = if (!chosenYear.isNullOrBlank()) " ($chosenYear)" else ""
            val isSeriesCat = chosenCategory != "Movies" && (qItem.isSeries || chosenCategory in listOf("Anime", "Shows", "KDrama"))
            val knownRemotes = if (RcloneEngine.isRcloneModeEnabled(app)) {
                RcloneEngine.getConfiguredRemotes(app).map { it.name }.toSet()
            } else emptySet()
            val firstSeg = qItem.file.path.trim('/').substringBefore('/')
            val remotePrefix = if (firstSeg in knownRemotes) "/$firstSeg" else ""

            val destPath = if (isSeriesCat) {
                val showFolder = "$cleanTitle$yearSuffix"
                val seasonFolder = String.format("Season %02d", seasonNum.coerceAtLeast(1))
                val epClean = episodeTitle?.replace(Regex("[/\\\\?%*:|\"<>=+]"), "")?.trim().orEmpty()
                val newFileName = if (epClean.isNotBlank()) {
                    String.format("%s - S%02dE%02d - %s.%s", cleanTitle, seasonNum.coerceAtLeast(1), episodeNum.coerceAtLeast(1), epClean, ext)
                } else {
                    String.format("%s - S%02dE%02d.%s", cleanTitle, seasonNum.coerceAtLeast(1), episodeNum.coerceAtLeast(1), ext)
                }
                "$remotePrefix/VidLab/$chosenCategory/$showFolder/$seasonFolder/$newFileName"
            } else {
                val movieFolder = "$cleanTitle$yearSuffix"
                val newFileName = "$cleanTitle$yearSuffix.$ext"
                "$remotePrefix/VidLab/$chosenCategory/$movieFolder/$newFileName"
            }

            val moveRes = when {
                RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, qItem.file.path, destPath)
                RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, qItem.file.path, destPath)
                else -> repository.webDavClient.moveFile(qItem.file.path, destPath)
            }

            if (moveRes.isSuccess) {
                com.vidlab.app.data.prefs.MediaSpecsCache.migrateOnRename(app, qItem.file.path, destPath)
                val srcParent = "/" + qItem.file.path.trim('/').substringBeforeLast('/', "")
                RcloneEngine.pruneEmptyDirectories(app, listOf(srcParent))
                _progressState.update { st ->
                    st.copy(
                        unmatchedItems = st.unmatchedItems.filterNot { it.file.path == qItem.file.path },
                        logs = (listOf("✓ Manual Match Moved: ${qItem.file.name} -> $destPath") + st.logs).take(120)
                    )
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Moved to $destPath", Toast.LENGTH_SHORT).show()
                }
                loadMediaCenter(force = true)
            } else {
                val err = moveRes.exceptionOrNull()?.localizedMessage ?: "Move failed"
                _progressState.update { st ->
                    st.copy(logs = (listOf("✗ Manual Move Failed: ${qItem.file.name} ($err)") + st.logs).take(120))
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(app, "Failed: $err", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runBatchOrganizer(vaultOnly: Boolean) {
        val app = getApplication<Application>()
        if (_progressState.value.isRunning) return

        viewModelScope.launch(Dispatchers.IO) {
            val scopeTitle = if (vaultOnly) "Cleaning Up /VidLab Folder" else "Organizing VidLab Vault"
            val scanMsg = if (vaultOnly) "Auditing files inside /VidLab..." else "Scanning files for /VidLab..."
            RenamerBackgroundService.startOrUpdate(app, scopeTitle, scanMsg)
            if (RcloneEngine.isRcloneModeEnabled(app)) {
                RcloneEngine.ensureServerRunning(app)
            }

            _progressState.update {
                it.copy(
                    isRunning = true,
                    currentStep = scanMsg,
                    processedCount = 0,
                    totalCount = 0,
                    currentFile = "",
                    logs = listOf(if (vaultOnly) "Auditing /VidLab folder across active source..." else "Starting storage scan..."),
                    unmatchedItems = emptyList(),
                    isCompleted = false,
                    error = null
                )
            }

            val allFiles = runCatching { repository.crawlAllStorageFiles() }.getOrElse { err ->
                val msg = err.localizedMessage ?: "Storage crawl failed"
                _progressState.update { it.copy(isRunning = false, error = msg) }
                RenamerBackgroundService.stop(app, "Scan failed: $msg")
                return@launch
            }

            val manualBindingsJson = runCatching {
                val f = java.io.File(app.filesDir, "manual_bindings_v5.json")
                if (f.exists()) f.readText() else ""
            }.getOrDefault("")

            val inboxFiles = if (vaultOnly) {
                allFiles.filter { f ->
                    val inVault = extractVidLabRelSegments(f.path) != null
                    if (!inVault) return@filter false
                    val parsedKey = FilenameSanitizer.parse(f.name, forceMovie = false).seriesKey
                    val isManuallyBound = parsedKey.length > 2 && manualBindingsJson.contains('"' + parsedKey + '"')
                    !isManuallyBound
                }
            } else {
                allFiles.filterNot { f ->
                    val inUnmatched = f.path.trim('/').split('/').any { it.equals("VidLab_Unmatched", ignoreCase = true) }
                    inUnmatched || isCleanlyOrganizedInVault(f)
                }
            }

            if (inboxFiles.isEmpty()) {
                _progressState.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = true,
                        currentStep = if (vaultOnly) "Everything inside /VidLab is already clean!" else "All files are already inside /VidLab!",
                        logs = it.logs + (if (vaultOnly) "✓ 0 unorganized files found inside /VidLab." else "✓ 0 unorganized files found outside /VidLab.")
                    )
                }
                RenamerBackgroundService.stop(app, "All media is already organized in /VidLab")
                loadMediaCenter(force = true)
                return@launch
            }

            _progressState.update {
                it.copy(
                    totalCount = inboxFiles.size,
                    currentStep = "Matching metadata & organizing...",
                    logs = it.logs + (if (vaultOnly) "Found ${inboxFiles.size} file(s) inside /VidLab to audit..." else "Found ${inboxFiles.size} file(s) outside /VidLab to process...")
                )
            }

            RcloneEngine.clearDirectoryCache()
            RcloneEngine.clearRcloneDirectoryCache()
            repository.webDavClient.clearDirectoryCache()

            val touchedParentFolders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val emptiedSourceFolders = java.util.Collections.synchronizedSet(mutableSetOf<String>())
            val quarantine = java.util.Collections.synchronizedList(mutableListOf<ManualQuarantineItem>())
            val processedAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val movedAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val failedAtomic = java.util.concurrent.atomic.AtomicInteger(0)
            val lastNotifMs = java.util.concurrent.atomic.AtomicLong(0L)
            val scrapeCache = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<MatchResult>>()
            val semaphore = kotlinx.coroutines.sync.Semaphore(4)

            fun recordStep(fileName: String, logLine: String) {
                val done = processedAtomic.incrementAndGet()
                val now = System.currentTimeMillis()
                val prev = lastNotifMs.get()
                if (done == 1 || done == inboxFiles.size || done % 5 == 0 || (now - prev >= 500L && lastNotifMs.compareAndSet(prev, now))) {
                    RenamerBackgroundService.startOrUpdate(app, "Organizing VidLab Vault ($done/${inboxFiles.size})", fileName, done, inboxFiles.size)
                }
                _progressState.update {
                    it.copy(currentFile = fileName, processedCount = done, unmatchedItems = quarantine.toList(), logs = (listOf(logLine) + it.logs).take(120))
                }
            }

            suspend fun handleUnmatchedFile(
                file: MediaFile,
                qTitle: String,
                qYear: String?,
                qIsSeries: Boolean,
                qSeason: Int,
                qEpisode: Int,
                qCategory: String,
                reason: String
            ) {
                var finalFile = file
                var logPrefix = "⚠ Needs Input: ${file.name}"
                if (vaultOnly) {
                    val relInfo = extractVidLabRelSegments(file.path)
                    if (relInfo != null) {
                        val (prefix, afterVidLab) = relInfo
                        val subRel = afterVidLab.joinToString("/").ifBlank { file.name }
                        val unmatchedDest = "$prefix/VidLab_Unmatched/$subRel"
                        val moveOut = when {
                            RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, file.path, unmatchedDest)
                            RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, file.path, unmatchedDest)
                            else -> repository.webDavClient.moveFile(file.path, unmatchedDest)
                        }
                        if (moveOut.isSuccess) {
                            com.vidlab.app.data.prefs.MediaSpecsCache.migrateOnRename(app, file.path, unmatchedDest)
                            finalFile = file.copy(path = unmatchedDest)
                            val srcParent = "/" + file.path.trim('/').substringBeforeLast('/', "")
                            val protectedVaultRoots = setOf("vidlab", "vidlab_unmatched", "movies", "shows", "tv shows", "anime", "kdrama", "documentaries", "animation")
                            if (srcParent.length > 1 && srcParent.substringAfterLast('/').lowercase() !in protectedVaultRoots) {
                                emptiedSourceFolders.add(srcParent)
                            }
                            logPrefix = "↷ Moved to /VidLab_Unmatched: ${file.name}"
                        }
                    }
                }
                quarantine.add(ManualQuarantineItem(finalFile, qTitle, qYear, qIsSeries, qSeason, qEpisode, qCategory, reason))
                recordStep(file.name, "$logPrefix ($reason)")
            }

            kotlinx.coroutines.coroutineScope {
                inboxFiles.map { file ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val parsed = FilenameSanitizer.parse(file.name, forceMovie = false)
                            var queryTitle = parsed.cleanTitle
                            var effectiveYear = parsed.year
                            val skipDirs = setOf("vidlab", "vidlab_test", "download", "downloads", "movies", "shows", "tv shows", "tv", "anime", "kdrama", "documentaries", "animation", "videos", "media")
                            val seasonDir = Regex("(?i)^(?:season|s)\\s*0*\\d{1,2}$")
                            if (queryTitle.length <= 2) {
                                for (seg in file.path.trim('/').split('/').dropLast(1).reversed()) {
                                    if (seg.lowercase() in skipDirs || seasonDir.matches(seg.trim())) continue
                                    val pp = FilenameSanitizer.parse(seg, forceMovie = false)
                                    if (pp.cleanTitle.length > 2) {
                                        queryTitle = pp.cleanTitle
                                        if (effectiveYear == null) effectiveYear = pp.year
                                        break
                                    }
                                }
                            }
                            queryTitle = queryTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

                            val pathUpper = file.path.uppercase()
                            val hintedSeries = parsed.isSeries || pathUpper.contains("/ANIME") || pathUpper.contains("/SHOWS") || pathUpper.contains("/TV") || pathUpper.contains("/KDRAMA")
                            val suggestedCategory = when {
                                pathUpper.contains("/ANIME") -> "Anime"
                                pathUpper.contains("/KDRAMA") -> "KDrama"
                                pathUpper.contains("/DOCUMENTAR") -> "Documentaries"
                                pathUpper.contains("/MOVIES") || !hintedSeries -> "Movies"
                                else -> "Shows"
                            }

                            if (queryTitle.length < 2) {
                                handleUnmatchedFile(file, file.name.substringBeforeLast('.'), parsed.year, hintedSeries, parsed.season, parsed.episode ?: 1, suggestedCategory, "unreadable title")
                                return@async
                            }

                            val cacheKey = "${queryTitle.lowercase()}|$hintedSeries|${effectiveYear.orEmpty()}"
                            val matchRes = scrapeCache.computeIfAbsent(cacheKey) {
                                async {
                                    runCatching {
                                        repository.scraper.scrapeWithVerification(queryTitle, hintedSeries, null, if (hintedSeries) "tv" else "movie", effectiveYear)
                                    }.getOrDefault(MatchResult.NoMatch)
                                }
                            }.await()

                            val confident = matchRes as? MatchResult.ConfidentMatch
                            if (confident == null) {
                                handleUnmatchedFile(file, queryTitle, parsed.year, hintedSeries, parsed.season, parsed.episode ?: 1, suggestedCategory, "no confident match for \"$queryTitle\"")
                                return@async
                            }

                            val meta = confident.metadata
                            val cleanOfficialTitle = meta.title.replace(Regex("[/\\\\?%*:|\"<>+=]"), "").trim()
                            val year = meta.releaseYear ?: effectiveYear
                            val yearSuffix = if (!year.isNullOrBlank() && !cleanOfficialTitle.endsWith("($year)")) " ($year)" else ""
                            val ext = file.name.substringAfterLast('.', "mkv").ifBlank { "mkv" }

                            val finalCategory = when {
                                pathUpper.contains("/ANIME") || (meta.genres.any { it.contains("Animation", true) } && hintedSeries) -> "Anime"
                                pathUpper.contains("/KDRAMA") -> "KDrama"
                                meta.genres.any { it.contains("Documentary", true) } -> "Documentaries"
                                !hintedSeries -> "Movies"
                                else -> "Shows"
                            }

                            val knownRemotes = if (RcloneEngine.isRcloneModeEnabled(app)) {
                                RcloneEngine.getConfiguredRemotes(app).map { it.name }.toSet()
                            } else emptySet()
                            val firstSeg = file.path.trim('/').substringBefore('/')
                            val remotePrefix = if (firstSeg in knownRemotes) "/$firstSeg" else ""

                            val destPath = if (hintedSeries) {
                                val sNum = parsed.season.coerceAtLeast(1)
                                val epNum = parsed.episode
                                if (epNum == null) {
                                    handleUnmatchedFile(file, cleanOfficialTitle, year, true, sNum, 1, finalCategory, "missing episode number")
                                    return@async
                                }
                                val scrapedEp = confident.episodes["S${sNum}E${epNum}"]
                                val rawEpTitle = scrapedEp?.title ?: parsed.existingEpisodeTitle.orEmpty()
                                val epClean = rawEpTitle.replace(Regex("[/\\\\?%*:|\"<>+=]"), "").trim()
                                val showFolder = "$cleanOfficialTitle$yearSuffix"
                                val seasonFolder = String.format("Season %02d", sNum)
                                val newFileName = if (epClean.isNotBlank() && !epClean.equals("Episode $epNum", true)) {
                                    String.format("%s - S%02dE%02d - %s.%s", cleanOfficialTitle, sNum, epNum, epClean, ext)
                                } else {
                                    String.format("%s - S%02dE%02d.%s", cleanOfficialTitle, sNum, epNum, ext)
                                }
                                "$remotePrefix/VidLab/$finalCategory/$showFolder/$seasonFolder/$newFileName"
                            } else {
                                val movieFolder = "$cleanOfficialTitle$yearSuffix"
                                "$remotePrefix/VidLab/$finalCategory/$movieFolder/$cleanOfficialTitle$yearSuffix.$ext"
                            }

                            if (("/" + file.path.trim('/')) == ("/" + destPath.trim('/'))) {
                                recordStep(file.name, "✓ Already clean: ${file.name}")
                                return@async
                            }

                            val moveResult = when {
                                RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, file.path, destPath)
                                RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, file.path, destPath)
                                else -> repository.webDavClient.moveFile(file.path, destPath)
                            }

                            val logLine = if (moveResult.isSuccess) {
                                com.vidlab.app.data.prefs.MediaSpecsCache.migrateOnRename(app, file.path, destPath)
                                movedAtomic.incrementAndGet()
                                val destParent = "/" + destPath.trim('/').substringBeforeLast('/', "")
                                val targetDedupeRoot = if (destParent.substringAfterLast('/').startsWith("Season ", ignoreCase = true)) {
                                    destParent.substringBeforeLast('/', destParent)
                                } else {
                                    destParent
                                }
                                if (targetDedupeRoot.length > 1) touchedParentFolders.add(targetDedupeRoot)
                                val srcParent = "/" + file.path.trim('/').substringBeforeLast('/', "")
                                val protectedVaultRoots = setOf("vidlab", "movies", "shows", "tv shows", "anime", "kdrama", "documentaries", "animation")
                                val leafName = srcParent.substringAfterLast('/').lowercase()
                                if (srcParent.length > 1 && leafName !in protectedVaultRoots) {
                                    emptiedSourceFolders.add(srcParent)
                                }
                                "✓ Organized: ${file.name} -> $destPath"
                            } else {
                                failedAtomic.incrementAndGet()
                                "✗ Failed: ${file.name} (${moveResult.exceptionOrNull()?.localizedMessage})"
                            }
                            recordStep(file.name, logLine)
                        } finally {
                            semaphore.release()
                        }
                    }
                }.awaitAll()
            }

            val movedCount = movedAtomic.get()
            val failedCount = failedAtomic.get()

            if (emptiedSourceFolders.isNotEmpty()) {
                RcloneEngine.pruneEmptyDirectories(app, emptiedSourceFolders)
            }

            if (movedCount > 0 && RcloneEngine.isRcloneModeEnabled(app)) {
                _progressState.update { it.copy(currentStep = "Running scoped deduplication & cleanup...") }
                for (folder in touchedParentFolders) {
                    RcloneEngine.dedupeFolder(app, folder)
                }
                for (srcFolder in emptiedSourceFolders) {
                    RcloneEngine.removeEmptyDirs(app, srcFolder)
                }
            }

            if (quarantine.isNotEmpty()) {
                RenamerBackgroundService.notifyManualInputNeeded(app, quarantine.size, quarantine.first().file.name)
            }

            val summary = buildString {
                append("Moved $movedCount")
                if (quarantine.isNotEmpty()) append(" • ${quarantine.size} need manual input")
                if (failedCount > 0) append(" • $failedCount failed")
            }

            _progressState.update {
                it.copy(
                    isRunning = false,
                    isCompleted = true,
                    unmatchedItems = quarantine.toList(),
                    currentStep = summary
                )
            }

            RenamerBackgroundService.stop(app, summary)
            loadMediaCenter(force = true)
        }
    }
}
