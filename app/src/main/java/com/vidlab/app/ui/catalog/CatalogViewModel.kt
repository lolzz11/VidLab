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

    init {
        loadMediaCenter()
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
                    val res = if (RcloneEngine.isRcloneModeEnabled(app)) RcloneEngine.moveFile(app, op.oldPath, op.newPath) else repository.webDavClient.moveFile(op.oldPath, op.newPath)
                    if (res.isFailure) failed++
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

            val destPath = if (isSeriesCat) {
                val showFolder = "$cleanTitle$yearSuffix"
                val seasonFolder = String.format("Season %02d", seasonNum.coerceAtLeast(1))
                val epClean = episodeTitle?.replace(Regex("[/\\\\?%*:|\"<>=+]"), "")?.trim().orEmpty()
                val newFileName = if (epClean.isNotBlank()) {
                    String.format("%s - S%02dE%02d - %s.%s", cleanTitle, seasonNum.coerceAtLeast(1), episodeNum.coerceAtLeast(1), epClean, ext)
                } else {
                    String.format("%s - S%02dE%02d.%s", cleanTitle, seasonNum.coerceAtLeast(1), episodeNum.coerceAtLeast(1), ext)
                }
                "/VidLab/$chosenCategory/$showFolder/$seasonFolder/$newFileName"
            } else {
                val movieFolder = "$cleanTitle$yearSuffix"
                val newFileName = "$cleanTitle$yearSuffix.$ext"
                "/VidLab/$chosenCategory/$movieFolder/$newFileName"
            }

            val moveRes = when {
                RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, qItem.file.path, destPath)
                RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, qItem.file.path, destPath)
                else -> repository.webDavClient.moveFile(qItem.file.path, destPath)
            }

            if (moveRes.isSuccess) {
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

    fun triggerLibraryBatchRename() {
        val app = getApplication<Application>()
        if (_progressState.value.isRunning) return

        viewModelScope.launch(Dispatchers.IO) {
            RenamerBackgroundService.startOrUpdate(app, "Organizing VidLab Vault", "Scanning files outside /VidLab...")
            if (RcloneEngine.isRcloneModeEnabled(app)) {
                RcloneEngine.ensureServerRunning(app)
            }

            _progressState.update {
                it.copy(
                    isRunning = true,
                    currentStep = "Scanning all files outside /VidLab...",
                    processedCount = 0,
                    totalCount = 0,
                    currentFile = "",
                    logs = listOf("Starting full storage crawl outside /VidLab..."),
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

            val inboxFiles = allFiles.filterNot { f ->
                f.path.trim('/').split('/').any { seg -> seg.equals("VidLab", ignoreCase = true) }
            }

            if (inboxFiles.isEmpty()) {
                _progressState.update {
                    it.copy(
                        isRunning = false,
                        isCompleted = true,
                        currentStep = "All files are already inside /VidLab!",
                        logs = it.logs + "✓ 0 unorganized files found outside /VidLab."
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
                    logs = it.logs + "Found ${inboxFiles.size} file(s) outside /VidLab to process..."
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

            kotlinx.coroutines.coroutineScope {
                inboxFiles.map { file ->
                    async(kotlinx.coroutines.Dispatchers.IO) {
                        semaphore.acquire()
                        try {
                            val parsed = FilenameSanitizer.parse(file.name, forceMovie = false)
                            var queryTitle = parsed.cleanTitle
                            if (queryTitle.length <= 2) {
                                val parentFolder = file.path.trim('/').split('/').dropLast(1).lastOrNull().orEmpty()
                                val parentParsed = FilenameSanitizer.parse(parentFolder, forceMovie = false)
                                if (parentParsed.cleanTitle.length > 2) queryTitle = parentParsed.cleanTitle
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
                                quarantine.add(ManualQuarantineItem(file, file.name.substringBeforeLast('.'), parsed.year, hintedSeries, parsed.season, parsed.episode ?: 1, suggestedCategory, "Unreadable filename — manual match required"))
                                recordStep(file.name, "⚠ Needs Input: ${file.name} (unreadable title)")
                                return@async
                            }

                            val cacheKey = "${queryTitle.lowercase()}|$hintedSeries|${parsed.year.orEmpty()}"
                            val matchRes = scrapeCache.computeIfAbsent(cacheKey) {
                                async {
                                    runCatching {
                                        repository.scraper.scrapeWithVerification(queryTitle, hintedSeries, null, if (hintedSeries) "tv" else "movie", parsed.year)
                                    }.getOrDefault(MatchResult.NoMatch)
                                }
                            }.await()

                            val confident = matchRes as? MatchResult.ConfidentMatch
                            if (confident == null) {
                                quarantine.add(ManualQuarantineItem(file, queryTitle, parsed.year, hintedSeries, parsed.season, parsed.episode ?: 1, suggestedCategory, "No high-confidence database match"))
                                recordStep(file.name, "⚠ Needs Input: ${file.name} (no confident match for \"$queryTitle\")")
                                return@async
                            }

                            val meta = confident.metadata
                            val cleanOfficialTitle = meta.title.replace(Regex("[/\\\\?%*:|\"<>+=]"), "").trim()
                            val year = meta.releaseYear ?: parsed.year
                            val yearSuffix = if (!year.isNullOrBlank() && !cleanOfficialTitle.endsWith("($year)")) " ($year)" else ""
                            val ext = file.name.substringAfterLast('.', "mkv").ifBlank { "mkv" }

                            val finalCategory = when {
                                pathUpper.contains("/ANIME") || (meta.genres.any { it.contains("Animation", true) } && hintedSeries) -> "Anime"
                                pathUpper.contains("/KDRAMA") -> "KDrama"
                                meta.genres.any { it.contains("Documentary", true) } -> "Documentaries"
                                !hintedSeries -> "Movies"
                                else -> "Shows"
                            }

                            val destPath = if (hintedSeries) {
                                val sNum = parsed.season.coerceAtLeast(1)
                                val epNum = parsed.episode
                                if (epNum == null) {
                                    quarantine.add(ManualQuarantineItem(file, cleanOfficialTitle, year, true, sNum, 1, finalCategory, "Missing episode number for series \"$cleanOfficialTitle\""))
                                    recordStep(file.name, "⚠ Needs Input: ${file.name} (missing episode number)")
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
                                "/VidLab/$finalCategory/$showFolder/$seasonFolder/$newFileName"
                            } else {
                                val movieFolder = "$cleanOfficialTitle$yearSuffix"
                                "/VidLab/$finalCategory/$movieFolder/$cleanOfficialTitle$yearSuffix.$ext"
                            }

                            val moveResult = when {
                                RcloneEngine.isLocalStorageModeEnabled(app) -> RcloneEngine.moveLocalFile(app, file.path, destPath)
                                RcloneEngine.isRcloneModeEnabled(app) -> RcloneEngine.moveFile(app, file.path, destPath)
                                else -> repository.webDavClient.moveFile(file.path, destPath)
                            }

                            val logLine = if (moveResult.isSuccess) {
                                movedAtomic.incrementAndGet()
                                val destParent = "/" + destPath.trim('/').substringBeforeLast('/', "")
                                val targetDedupeRoot = if (destParent.substringAfterLast('/').startsWith("Season ", ignoreCase = true)) {
                                    destParent.substringBeforeLast('/', destParent)
                                } else {
                                    destParent
                                }
                                if (targetDedupeRoot.length > 1) touchedParentFolders.add(targetDedupeRoot)
                                val srcParent = "/" + file.path.trim('/').substringBeforeLast('/', "")
                                if (srcParent.length > 1 && !srcParent.contains("/VidLab", ignoreCase = true)) {
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
