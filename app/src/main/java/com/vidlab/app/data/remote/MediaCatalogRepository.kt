package com.vidlab.app.data.remote

import android.content.Context
import com.vidlab.app.data.model.*
import com.vidlab.app.data.scraper.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredBinding(
    val title: String,
    val mediaType: String,
    val year: String? = null,
    val posterUrl: String? = null,
    val tmdbId: String? = null
)

class MediaCatalogRepository(
    private val context: Context,
    val scraper: MetadataScraper = MetadataScraper(),
    private val json: Json = Json { ignoreUnknownKeys = true; prettyPrint = false }
) {
    val webDavClient: WebDavClient
        get() = WebDavClient(
            if (RcloneEngine.isRcloneModeEnabled(context) && RcloneEngine.hasValidConfig(context)) {
                RcloneEngine.STREAM_BASE_URL
            } else {
                RcloneEngine.getWebDavBaseUrl(context)
            }
        )
    private val cacheFile: File get() = File(context.filesDir, "catalog_cache_${RcloneEngine.getSourceMode(context)}_v243.json")
    private val manualBindingsFile = File(context.filesDir, "manual_bindings_v5.json")

    private val _ambiguousMatchesFlow = MutableSharedFlow<AmbiguousPrompt>(replay = 10)
    val ambiguousMatchesFlow: SharedFlow<AmbiguousPrompt> = _ambiguousMatchesFlow.asSharedFlow()

    private val ignoredCategoryFolders = setOf(
        "vidlab", "shows", "tv shows", "tv", "anime", "movies", "documentaries", "kdrama", "animation",
        "media", "disk", "root", "webdav", "videos", "extracted_stream", ""
    )

    init {
        try {
            context.filesDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("catalog_cache_") && !f.name.endsWith("_v243.json")) {
                    f.delete()
                }
            }
        } catch (_: Exception) {}
    }

    private fun getManualBindings(): MutableMap<String, StoredBinding> {
        return try {
            if (manualBindingsFile.exists()) {
                val map: Map<String, StoredBinding> = json.decodeFromString(manualBindingsFile.readText())
                map.toMutableMap()
            } else mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    fun saveManualBinding(
        mediaKey: String,
        targetTitle: String,
        mediaType: String,
        year: String? = null,
        posterUrl: String? = null,
        tmdbId: String? = null
    ) {
        if (mediaKey.isBlank() || mediaKey in ignoredCategoryFolders) return
        val bindings = getManualBindings()
        val clean = targetTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()
        bindings[mediaKey] = StoredBinding(clean, mediaType, year, posterUrl, tmdbId)
        manualBindingsFile.writeText(json.encodeToString(bindings))
        try {
            if (cacheFile.exists()) {
                val sections: List<MediaFeedSection> = json.decodeFromString(cacheFile.readText())
                val updated = sections.map { sec ->
                    sec.copy(items = sec.items.filterNot { it.id == mediaKey })
                }.filter { it.items.isNotEmpty() }
                cacheFile.writeText(json.encodeToString(updated))
            }
        } catch (_: Exception) {
            cacheFile.delete()
        }
    }

    fun loadCachedFeed(): List<MediaFeedSection> {
        return try {
            if (cacheFile.exists()) json.decodeFromString(cacheFile.readText()) else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    private fun categorizeItems(scrapedItems: List<ScrapedMediaItem>): List<MediaFeedSection> {
        val buckets = linkedMapOf(
            "Anime" to mutableListOf<ScrapedMediaItem>(),
            "TV Shows" to mutableListOf<ScrapedMediaItem>(),
            "Movies" to mutableListOf<ScrapedMediaItem>(),
            "KDrama" to mutableListOf<ScrapedMediaItem>(),
            "Documentaries" to mutableListOf<ScrapedMediaItem>(),
            "Animation" to mutableListOf<ScrapedMediaItem>()
        )
        for (item in scrapedItems) {
            val pathUpper = item.primaryFile.path.uppercase()
            val hasKnownMeta = item.metadata != null && (!item.metadata.posterUrl.isNullOrBlank() || !item.metadata.overview.isNullOrBlank() || !item.metadata.releaseYear.isNullOrBlank())
            when {
                pathUpper.contains("/VIDLAB/ANIME/") -> buckets["Anime"]!!.add(item)
                pathUpper.contains("/VIDLAB/MOVIES/") -> buckets["Movies"]!!.add(item)
                pathUpper.contains("/VIDLAB/SHOWS/") -> buckets["TV Shows"]!!.add(item)
                pathUpper.contains("/VIDLAB/KDRAMA/") -> buckets["KDrama"]!!.add(item)
                pathUpper.contains("/VIDLAB/DOCUMENTARIES/") -> buckets["Documentaries"]!!.add(item)
                pathUpper.contains("/VIDLAB/ANIMATION/") -> buckets["Animation"]!!.add(item)
                item.metadata?.genres?.any { it.contains("documentary", true) } == true -> buckets["Documentaries"]!!.add(item)
                item.metadata?.genres?.any { it.contains("animation", true) } == true -> buckets["Anime"]!!.add(item)
                !item.isSeries && hasKnownMeta -> buckets["Movies"]!!.add(item)
                item.isSeries && hasKnownMeta -> buckets["TV Shows"]!!.add(item)
                else -> {
                    val parentSegs = item.primaryFile.path.trim('/').split('/').dropLast(1)
                    val genericSegs = setOf("vidlab", "0", "emulated", "storage", "sdcard", "download", "downloads", "media", "videos")
                    val folderLabel = parentSegs.lastOrNull { seg ->
                        seg.isNotBlank() && seg.lowercase() !in genericSegs && !seg.endsWith(":")
                    }?.replace('_', ' ')?.trim()?.ifBlank { null } ?: "Others"
                    buckets.getOrPut(folderLabel) { mutableListOf() }.add(item)
                }
            }
        }
        return buckets.map { (title, items) ->
            MediaFeedSection(title, items.sortedBy { it.title })
        }.filter { it.items.isNotEmpty() }
    }

    private val localVideoExts = setOf("mp4", "mkv", "avi", "webm", "mov", "m4v", "ts", "flv", "wmv")

    private fun scanLocalVidLabDirectory(): List<MediaFile> {
        val vidLabRoot = DownloadTaskManager.ensureVidLabCategoryFolders(context)
        val scanMode = context.getSharedPreferences("vidlab_offline_prefs", android.content.Context.MODE_PRIVATE)
            .getString("local_scan_mode", "full") ?: "full"
        val extRoot = android.os.Environment.getExternalStorageDirectory()
        val scanRoot = if (scanMode == "full" && extRoot.exists()) extRoot else vidLabRoot
        if (!scanRoot.exists()) return emptyList()
        val found = mutableListOf<MediaFile>()
        val seenPaths = mutableSetOf<String>()
        fun addLocalFile(file: File) {
            if (!file.exists() || !file.isFile || file.name.endsWith(".part") || file.length() <= 0L) return
            if (file.extension.lowercase() !in localVideoExts) return
            val logicalPath = if (file.absolutePath.startsWith(vidLabRoot.absolutePath)) {
                "/VidLab/" + file.relativeTo(vidLabRoot).path.replace('\\', '/')
            } else if (file.absolutePath.startsWith(extRoot.absolutePath)) {
                "/" + file.relativeTo(extRoot).path.replace('\\', '/')
            } else file.absolutePath
            if (seenPaths.add(logicalPath)) {
                found.add(MediaFile(name = file.name, path = logicalPath, size = file.length(), isDirectory = false))
            }
        }
        runCatching {
            scanRoot.walkTopDown()
                .onEnter { dir -> !dir.name.startsWith(".") && !dir.name.equals("Android", ignoreCase = true) }
                .forEach { f -> addLocalFile(f) }
        }
        runCatching {
            val proj = arrayOf(android.provider.MediaStore.Video.Media.DATA)
            val sel = if (scanMode == "full") null else "${android.provider.MediaStore.Video.Media.DATA} LIKE ?"
            val args = if (scanMode == "full") null else arrayOf("%/VidLab/%")
            context.contentResolver.query(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj, sel, args, null)?.use { c ->
                val idx = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DATA)
                while (c.moveToNext()) { c.getString(idx)?.let { addLocalFile(File(it)) } }
            }
        }
        return found
    }

    suspend fun crawlAllStorageFiles(): List<MediaFile> = withContext(Dispatchers.IO) {
        if (RcloneEngine.isLocalStorageModeEnabled(context)) {
            scanLocalVidLabDirectory()
        } else if (RcloneEngine.isRcloneModeEnabled(context) && RcloneEngine.hasValidConfig(context)) {
            if (RcloneEngine.isRcloneServeWebDavEnabled(context)) RcloneEngine.startForegroundServeService(context)
            RcloneEngine.ensureVidLabVaultFolders(context)
            RcloneEngine.scanEnabledRemotes(context).getOrThrow()
        } else {
            val activeDav = WebDavClient(RcloneEngine.getWebDavBaseUrl(context), RcloneEngine.getWebDavUsername(context), RcloneEngine.getWebDavPassword(context))
            activeDav.ensureVidLabVaultFolders()
            val collected = mutableListOf<MediaFile>()
            val queue = ArrayDeque<String>()
            queue.add("/")
            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")
            val visited = mutableSetOf<String>()

            while (queue.isNotEmpty()) {
                val currentPath = queue.removeFirst()
                val norm = "/" + currentPath.trim('/')
                if (norm in visited) continue
                visited.add(norm)

                val entries = activeDav.listDirectory(norm).getOrThrow()
                for (entry in entries) {
                    val entryNorm = "/" + entry.path.trim('/')
                    if (entry.isDirectory) {
                        if (!entry.name.startsWith(".") && entryNorm !in visited) queue.add(entryNorm)
                    } else {
                        val ext = entry.name.substringAfterLast('.', "").lowercase()
                        if (ext in videoExtensions) {
                            collected.add(entry.copy(path = entryNorm))
                        }
                    }
                }
            }
            collected
        }
    }

    private fun isInsideVidLabVault(path: String): Boolean {
        val segs = path.trim('/').split('/')
        return segs.any { it.equals("VidLab", ignoreCase = true) }
    }

    private fun buildInstantSkeletonItems(files: List<MediaFile>, cachedById: Map<String, ScrapedMediaItem>): List<ScrapedMediaItem> {
        val seasonFolderCheck = Regex("""(?i)^(?:season|s)\s*0*\d{1,2}$""")
        val seriesBuckets = linkedMapOf<String, MutableList<MediaFile>>()
        val movies = mutableListOf<MediaFile>()
        for (file in files) {
            val parts = file.path.trim('/').split('/')
            val parentDirs = parts.dropLast(1)
            val inMovies = parentDirs.any { it.equals("MOVIES", ignoreCase = true) }
            val inSeries = parentDirs.any { it.equals("ANIME", true) || it.equals("SHOWS", true) || it.equals("TV SHOWS", true) || it.equals("TV", true) || seasonFolderCheck.matches(it.trim()) }
            val parsed = FilenameSanitizer.parse(file.name, forceMovie = false)
            if (inMovies || (!parsed.isSeries && !inSeries)) {
                movies.add(file)
            } else {
                val key = parsed.seriesKey.takeIf { parsed.cleanTitle.length > 2 && it !in ignoredCategoryFolders }
                    ?: parentDirs.reversed().map { FilenameSanitizer.parse(it) }.firstOrNull { it.cleanTitle.length > 2 && it.seriesKey !in ignoredCategoryFolders && !it.cleanTitle.startsWith("Season", true) }?.seriesKey
                if (key != null) seriesBuckets.getOrPut(key) { mutableListOf() }.add(file) else movies.add(file)
            }
        }
        val out = mutableListOf<ScrapedMediaItem>()
        for ((key, list) in seriesBuckets) {
            val cachedItem = cachedById[key]; if (cachedItem != null) { out.add(cachedItem); continue }
            val sample = list.first()
            val parsed = FilenameSanitizer.parse(sample.name, forceMovie = false)
            val title = parsed.cleanTitle.takeIf { it.length > 2 } ?: sample.name.substringBeforeLast('.')
            val eps = list.sortedBy { it.name }.mapIndexed { idx, f ->
                val epP = FilenameSanitizer.parse(f.name)
                val epN = epP.episode ?: (idx + 1)
                ProcessedEpisode(file = f, seasonNumber = epP.season, episodeNumber = epN, title = String.format("S%02dE%02d • Episode %d", epP.season, epN, epN))
            }
            out.add(ScrapedMediaItem(id = key, title = title, isSeries = true, primaryFile = list.first(), seasons = listOf(SeasonGroup(1, eps))))
        }
        for (f in movies) {
            val parsed = FilenameSanitizer.parse(f.name, forceMovie = true)
            val key = "movie_${parsed.seriesKey}_${f.path.hashCode()}"
            val cachedItem = cachedById[key]; if (cachedItem != null) { out.add(cachedItem); continue }
            out.add(ScrapedMediaItem(id = key, title = parsed.cleanTitle.ifBlank { f.name.substringBeforeLast('.') }, releaseYear = parsed.year, isSeries = false, primaryFile = f))
        }
        return out
    }

    suspend fun buildMediaCenterFeed(
        onProgress: ((List<MediaFeedSection>) -> Unit)? = null
    ): Result<List<MediaFeedSection>> = withContext(Dispatchers.IO) {
        runCatching {
            val cachedById = loadCachedFeed().flatMap { it.items }.associateBy { it.id }
            val allFiles = if (RcloneEngine.isRcloneModeEnabled(context) && RcloneEngine.hasValidConfig(context)) {
                if (RcloneEngine.isRcloneServeWebDavEnabled(context)) RcloneEngine.startForegroundServeService(context)
                RcloneEngine.streamEnabledRemotes(context) { partialFiles ->
                    val quick = buildInstantSkeletonItems(partialFiles, cachedById)
                    if (quick.isNotEmpty()) onProgress?.invoke(categorizeItems(quick))
                }.getOrThrow()
            } else {
                val crawled = crawlAllStorageFiles()
                if (RcloneEngine.isLocalStorageModeEnabled(context)) probeAllMediaSpecs(crawled)
                if (crawled.isNotEmpty()) {
                    onProgress?.invoke(categorizeItems(buildInstantSkeletonItems(crawled, cachedById)))
                }
                crawled
            }
            val scrapedItems = groupAndScrapeMedia(allFiles) { partialItems ->
                onProgress?.invoke(categorizeItems(partialItems))
            }
            val finalSections = categorizeItems(scrapedItems)
            runCatching { cacheFile.writeText(json.encodeToString(finalSections)) }
            finalSections
        }
    }

    private suspend fun groupAndScrapeMedia(
        files: List<MediaFile>,
        onItemReady: ((List<ScrapedMediaItem>) -> Unit)? = null
    ): List<ScrapedMediaItem> = coroutineScope {
        val completedList = java.util.Collections.synchronizedList(mutableListOf<ScrapedMediaItem>())
        val manualBindings = getManualBindings()
        val cachedItemsById = loadCachedFeed().flatMap { it.items }.associateBy { it.id }
        val seriesBuckets = mutableMapOf<String, MutableList<MediaFile>>()
        val movieFiles = mutableListOf<MediaFile>()

        val seasonFolderCheck = Regex("""(?i)^(?:season|s)\s*0*\d{1,2}$""")
        for (file in files) {
            val parts = file.path.trim('/').split('/')
            val parentDirs = parts.dropLast(1)
            val inMoviesFolder = parentDirs.any { it.equals("MOVIES", ignoreCase = true) }
            val inExplicitSeriesFolder = parentDirs.any {
                it.equals("ANIME", ignoreCase = true) ||
                it.equals("SHOWS", ignoreCase = true) ||
                it.equals("TV SHOWS", ignoreCase = true) ||
                it.equals("TV", ignoreCase = true) ||
                seasonFolderCheck.matches(it.trim())
            }

            val fileParsed = FilenameSanitizer.parse(file.name, forceMovie = false)

            // Standalone movie if inside /MOVIES, or if filename has no episode/season markers and isn't in a series/season folder
            if (inMoviesFolder || (!fileParsed.isSeries && !inExplicitSeriesFolder)) {
                movieFiles.add(file)
                continue
            }

            var detectedKey: String? = null
            if (fileParsed.cleanTitle.length > 2 && fileParsed.seriesKey !in ignoredCategoryFolders) {
                detectedKey = fileParsed.seriesKey
            }

            if (detectedKey == null) {
                for (seg in parentDirs.reversed()) {
                    val p = FilenameSanitizer.parse(seg, forceMovie = false)
                    if (p.cleanTitle.length > 2 && p.seriesKey !in ignoredCategoryFolders && !p.cleanTitle.startsWith("Season", ignoreCase = true)) {
                        detectedKey = p.seriesKey
                        break
                    }
                }
            }

            if (detectedKey != null && detectedKey !in ignoredCategoryFolders) {
                seriesBuckets.getOrPut(detectedKey) { mutableListOf() }.add(file)
            } else {
                movieFiles.add(file)
            }
        }

        // Process Series (ANIME & SHOWS)
        val seriesDeferred = seriesBuckets.entries.map { (key, fileList) ->
            async {
                val sampleFile = fileList.first()
                val sampleParsed = FilenameSanitizer.parse(sampleFile.name, forceMovie = false)

                var cleanShowName = if (sampleParsed.cleanTitle.length > 2) {
                    sampleParsed.cleanTitle
                } else {
                    val parts = sampleFile.path.trim('/').split('/')
                    var folderFallback = sampleFile.name.substringBeforeLast('.')
                    for (seg in parts.reversed().drop(1)) {
                        val p = FilenameSanitizer.parse(seg)
                        if (p.cleanTitle.length > 2 && p.seriesKey !in ignoredCategoryFolders && !p.cleanTitle.startsWith("Season", ignoreCase = true)) {
                            folderFallback = p.cleanTitle
                            break
                        }
                    }
                    folderFallback
                }

                cleanShowName = cleanShowName.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

                val binding = manualBindings[key]
                val forcedType = binding?.mediaType ?: "tv"
                val forcedYear = binding?.year
                val forcedPoster = binding?.posterUrl
                val forcedId = binding?.tmdbId
                if (binding != null) {
                    cleanShowName = binding.title
                }

                val prevCached = cachedItemsById[key]
                val hasValidCachedMeta = prevCached?.metadata != null &&
                    (binding == null || prevCached.title.equals(binding.title, ignoreCase = true))

                var finalMeta: MediaMetadata? = if (hasValidCachedMeta) prevCached?.metadata else null
                var finalEpisodesMap = emptyMap<String, ScrapedEpisode>()

                if (!hasValidCachedMeta) {
                    val matchResult = runCatching {
                        scraper.scrapeWithVerification(cleanShowName, true, forcedId, forcedType, forcedYear)
                    }.getOrDefault(MatchResult.NoMatch)

                    when (matchResult) {
                        is MatchResult.ConfidentMatch -> {
                            finalMeta = matchResult.metadata
                            finalEpisodesMap = matchResult.episodes
                        }
                        is MatchResult.Ambiguous -> {
                            if (matchResult.candidates.isNotEmpty() && prevCached?.metadata == null) {
                                _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, cleanShowName, matchResult.candidates))
                            }
                        }
                        is MatchResult.NoMatch -> {
                            // Never pop up an empty/broken dialog or overwrite valid metadata when network is weak
                            if (prevCached?.metadata == null) {
                                val fallbackCandidates = runCatching { scraper.searchMetadata(cleanShowName) }.getOrDefault(emptyList())
                                if (fallbackCandidates.size > 1) {
                                    _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, cleanShowName, fallbackCandidates))
                                } else if (fallbackCandidates.size == 1) {
                                    val only = fallbackCandidates.first()
                                    if (only.title.equals(cleanShowName, ignoreCase = true)) {
                                        finalMeta = MediaMetadata(only.title, only.posterUrl, only.posterUrl, only.year, listOf("TV Series"))
                                    }
                                }
                            }
                        }
                    }
                }

                if (finalMeta == null && prevCached?.metadata != null) {
                    finalMeta = prevCached.metadata
                }
                if (forcedPoster != null && finalMeta == null) {
                    finalMeta = MediaMetadata(cleanShowName, forcedPoster, forcedPoster, forcedYear, listOf("TV Series"))
                }

                val officialShowTitle = finalMeta?.title ?: cleanShowName
                val seasonMap = mutableMapOf<Int, MutableList<ProcessedEpisode>>()
                val sortedFiles = fileList.sortedBy { it.name }
                val seasonFolderRegex = Regex("""(?i)\b(?:season|s)\s*0*(\d{1,2})\b""")

                for ((idx, epFile) in sortedFiles.withIndex()) {
                    val epParts = epFile.path.trim('/').split('/')
                    val epParsed = FilenameSanitizer.parse(epFile.name)

                    var sNum = epParsed.season
                    if (sNum == 1) {
                        for (part in epParts) {
                            val m = seasonFolderRegex.find(part)
                            if (m != null) {
                                sNum = m.groupValues[1].toIntOrNull() ?: 1
                                break
                            }
                        }
                    }

                    val epNum = if (epParsed.isAbsoluteEpisode && (epParsed.episode ?: 0) > 50) {
                        idx + 1
                    } else {
                        epParsed.episode ?: (idx + 1)
                    }

                    val epKey = "S${sNum}E${epNum}"
                    val cachedEp = prevCached?.seasons
                        ?.firstOrNull { it.seasonNumber == sNum }
                        ?.episodes
                        ?.firstOrNull { it.episodeNumber == epNum }
                    val scrapedEp = finalEpisodesMap[epKey] ?: cachedEp?.let {
                        ScrapedEpisode(sNum, epNum, it.title.substringAfter(" • ", it.title), "", it.thumbnail)
                    }
                    val formattedEp = String.format("S%02dE%02d", sNum, epNum)

                    // Always prefer the official database episode title over anything in the local filename
                    val sTitle = scrapedEp?.title
                    val epTitle = when {
                        !sTitle.isNullOrBlank() && !sTitle.startsWith("Episode ", ignoreCase = true) -> sTitle
                        !epParsed.existingEpisodeTitle.isNullOrBlank() -> epParsed.existingEpisodeTitle
                        else -> "Episode $epNum"
                    }
                    val finalEpTitle = "$formattedEp • $epTitle"

                    seasonMap.getOrPut(sNum) { mutableListOf() }.add(
                        ProcessedEpisode(
                            file = epFile,
                            seasonNumber = sNum,
                            episodeNumber = epNum,
                            title = finalEpTitle,
                            thumbnail = scrapedEp?.thumbnail ?: finalMeta?.backdropUrl ?: finalMeta?.posterUrl
                        )
                    )
                }

                val seasonGroups = seasonMap.map { (sNum, eps) ->
                    SeasonGroup(seasonNumber = sNum, episodes = eps.sortedBy { it.episodeNumber })
                }.sortedBy { it.seasonNumber }

                val firstPlayable = seasonGroups.firstOrNull()?.episodes?.firstOrNull()?.file ?: sampleFile

                val builtShow = ScrapedMediaItem(
                    id = key,
                    title = officialShowTitle,
                    posterUrl = finalMeta?.posterUrl,
                    backdropUrl = finalMeta?.backdropUrl ?: finalMeta?.posterUrl,
                    releaseYear = forcedYear ?: finalMeta?.releaseYear,
                    isSeries = true,
                    primaryFile = firstPlayable,
                    seasons = seasonGroups,
                    metadata = finalMeta
                )
                completedList.add(builtShow)
                onItemReady?.invoke(completedList.toList())
                builtShow
            }
        }

        // Process Movies (/MOVIES)
        val movieDeferred = movieFiles.map { file ->
            async {
                val parsed = FilenameSanitizer.parse(file.name, forceMovie = true)
                val key = parsed.seriesKey.ifBlank { file.name.hashCode().toString() }

                var queryName = parsed.cleanTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()
                val binding = manualBindings[key]
                val forcedType = "movie"
                val forcedYear = binding?.year ?: parsed.year
                val forcedPoster = binding?.posterUrl
                val forcedId = binding?.tmdbId
                if (binding != null) {
                    queryName = binding.title
                }

                val prevCached = cachedItemsById[key]
                val hasValidCachedMeta = prevCached?.metadata != null &&
                    (binding == null || prevCached.title.equals(binding.title, ignoreCase = true))

                var finalMeta: MediaMetadata? = if (hasValidCachedMeta) prevCached?.metadata else null

                if (!hasValidCachedMeta) {
                    val matchResult = runCatching {
                        scraper.scrapeWithVerification(queryName, false, forcedId, forcedType, forcedYear)
                    }.getOrDefault(MatchResult.NoMatch)

                    when (matchResult) {
                        is MatchResult.ConfidentMatch -> finalMeta = matchResult.metadata
                        is MatchResult.Ambiguous -> {
                            if (matchResult.candidates.isNotEmpty() && prevCached?.metadata == null) {
                                _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, queryName, matchResult.candidates))
                            }
                        }
                        is MatchResult.NoMatch -> {
                            if (prevCached?.metadata == null) {
                                val fallbackCandidates = runCatching { scraper.searchMetadata(queryName) }.getOrDefault(emptyList())
                                if (fallbackCandidates.size == 1 && fallbackCandidates.first().title.equals(queryName, ignoreCase = true)) {
                                    val only = fallbackCandidates.first()
                                    finalMeta = MediaMetadata(only.title, only.posterUrl, only.posterUrl, only.year, listOf("Movie"))
                                } else {
                                    _ambiguousMatchesFlow.tryEmit(AmbiguousPrompt(key, queryName, fallbackCandidates))
                                }
                            }
                        }
                    }
                }

                if (finalMeta == null && prevCached?.metadata != null) {
                    finalMeta = prevCached.metadata
                }
                if (forcedPoster != null && finalMeta == null) {
                    finalMeta = MediaMetadata(queryName, forcedPoster, forcedPoster, forcedYear, listOf("Movie"))
                }

                val officialMovieTitle = finalMeta?.title ?: queryName

                val builtMovie = ScrapedMediaItem(
                    id = key,
                    title = officialMovieTitle,
                    posterUrl = finalMeta?.posterUrl,
                    backdropUrl = finalMeta?.backdropUrl ?: finalMeta?.posterUrl,
                    releaseYear = forcedYear ?: finalMeta?.releaseYear,
                    isSeries = false,
                    primaryFile = file,
                    metadata = finalMeta
                )
                completedList.add(builtMovie)
                onItemReady?.invoke(completedList.toList())
                builtMovie
            }
        }

        (seriesDeferred + movieDeferred).awaitAll()
    }

    // STRICT DATABASE NAMING CONTRACT:
    // Anime: /ANIME/{Database Title}/{Database Title Season X}/{Database Title - SxxExx - Database Episode Name.mkv}
    // Shows: /SHOWS/{Database Title}/{Database Title Season X}/{Database Title - SxxExx - Database Episode Name.mkv}
    // Movies: /MOVIES/{Database Movie Title (Year)}/{Database Movie Title (Year).mkv}
    fun resolveVaultCategory(item: ScrapedMediaItem, overrideCategory: String? = null): String {
        if (!overrideCategory.isNullOrBlank()) return overrideCategory
        val pathUpper = item.primaryFile.path.uppercase()
        val genres = item.metadata?.genres ?: emptyList()
        return when {
            pathUpper.contains("/ANIME") || genres.any { it.equals("Anime", true) } -> "Anime"
            pathUpper.contains("/KDRAMA") || genres.any { it.contains("Korean", true) } -> "KDrama"
            pathUpper.contains("/DOCUMENTAR") || genres.any { it.contains("Documentary", true) } -> "Documentaries"
            !item.isSeries -> "Movies"
            genres.any { it.contains("Animation", true) } -> "Anime"
            else -> "Shows"
        }
    }

    fun generateRenamePlan(
        item: ScrapedMediaItem,
        customTitle: String? = null,
        customYear: String? = null,
        isManualOverride: Boolean = false,
        overrideCategory: String? = null
    ): List<RenameOperation> {
        val plan = mutableListOf<RenameOperation>()
        val plannedTargets = mutableSetOf<String>()
        if (item.metadata == null && !isManualOverride) return emptyList()

        var cleanTitle = sanitizeFsName(customTitle ?: item.metadata?.title ?: item.title)
        cleanTitle = cleanTitle.replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

        if (cleanTitle.lowercase() in ignoredCategoryFolders || cleanTitle.isBlank()) {
            return emptyList()
        }

        val vaultCategory = resolveVaultCategory(item, overrideCategory)
        val year = customYear ?: item.metadata?.releaseYear ?: item.releaseYear
        val yearSuffix = if (!year.isNullOrBlank() && !cleanTitle.endsWith("($year)")) " ($year)" else ""

        if (item.isSeries) {
            val showFolder = "$cleanTitle$yearSuffix"
            for (season in item.seasons) {
                val seasonFolderName = String.format("Season %02d", season.seasonNumber)

                for (ep in season.episodes) {
                    val oldPath = ep.file.path
                    val ext = ep.file.name.substringAfterLast('.', "mkv").ifBlank { "mkv" }

                    val rawEpTitle = ep.title.substringAfter("•").trim()
                    var epTitleClean = sanitizeFsName(rawEpTitle)
                    epTitleClean = epTitleClean.replace(Regex("[=+\\[\\]]"), "").replace(Regex("(?i)(tv|movie)\\d{4}"), "").trim()

                    val isUnverifiedEpisodeTitle = epTitleClean.isBlank() ||
                        epTitleClean.matches(Regex("(?i)^episode\\s*\\d+$")) ||
                        epTitleClean.matches(Regex("(?i)^s\\d+e\\d+$")) ||
                        rawEpTitle.contains("..") ||
                        Regex("(?i)\\b(1080p|720p|480p|2160p|web-?dl|webrip|bluray|bdrip|ddp\\d|aac|h\\.?264|h\\.?265|x264|x265|hevc)\\b").containsMatchIn(rawEpTitle)

                    if (isUnverifiedEpisodeTitle && !isManualOverride) {
                        continue
                    }

                    val newFileName = if (epTitleClean.startsWith("Episode ", ignoreCase = true) || epTitleClean.isBlank()) {
                        String.format("%s - S%02dE%02d.%s", cleanTitle, season.seasonNumber, ep.episodeNumber, ext)
                    } else {
                        String.format("%s - S%02dE%02d - %s.%s", cleanTitle, season.seasonNumber, ep.episodeNumber, epTitleClean, ext)
                    }

                    val newFullPath = "/VidLab/$vaultCategory/$showFolder/$seasonFolderName/$newFileName"
                    if (oldPath != newFullPath && plannedTargets.add(newFullPath.lowercase())) {
                        plan.add(RenameOperation(oldPath, newFullPath, ep.file.name, newFileName))
                    }
                }
            }
        } else {
            val oldPath = item.primaryFile.path
            val ext = item.primaryFile.name.substringAfterLast('.', "mkv").ifBlank { "mkv" }
            val movieFolderName = "$cleanTitle$yearSuffix"
            val newFileName = "$cleanTitle$yearSuffix.$ext"

            val newFullPath = "/VidLab/$vaultCategory/$movieFolderName/$newFileName"
            if (oldPath != newFullPath) {
                plan.add(RenameOperation(oldPath, newFullPath, item.primaryFile.name, newFileName))
            }
        }
        return plan
    }

    private fun sanitizeFsName(name: String): String {
        return name.replace(Regex("[/\\\\?%*:|\"<>=+]"), "").trim()
    }

    private suspend fun probeAllMediaSpecs(files: List<MediaFile>) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val prefs = context.getSharedPreferences("vidlab_media_specs_cache", android.content.Context.MODE_PRIVATE)
        val uncached = files.filter { prefs.getString(it.path, null).isNullOrBlank() }
        if (uncached.isEmpty()) return@withContext
        val semaphore = kotlinx.coroutines.sync.Semaphore(8)
        kotlinx.coroutines.coroutineScope {
            uncached.forEach { file ->
                launch {
                    semaphore.acquire()
                    try {
                        val urlStr = runCatching { RcloneEngine.buildStreamUrl(context, file.path) }.getOrNull() ?: return@launch
                        val tags = runCatching {
                            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                                connectTimeout = 3500
                                readTimeout = 3500
                                setRequestProperty("Range", "bytes=0-262143")
                            }
                            val bytes = conn.inputStream.use { inp ->
                                val buf = java.io.ByteArrayOutputStream()
                                val tmp = ByteArray(8192)
                                var tot = 0
                                while (tot < 262144) {
                                    val r = inp.read(tmp)
                                    if (r <= 0) break
                                    buf.write(tmp, 0, r)
                                    tot += r
                                }
                                buf.toByteArray()
                            }
                            if (bytes.size < 64) return@runCatching emptyList<String>()
                            val out = mutableListOf<String>()
                            val ascii = String(bytes, Charsets.ISO_8859_1)
                            var maxW = 0
                            var maxH = 0
                            for (i in 0 until bytes.size - 4) {
                                val b0 = bytes[i].toInt() and 0xFF
                                val b1 = bytes[i + 1].toInt() and 0xFF
                                if (b0 == 0xB0 && (b1 == 0x82 || b1 == 0x81)) {
                                    val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                                    if (v in 320..8192) maxW = maxOf(maxW, v)
                                } else if (b0 == 0xBA && (b1 == 0x82 || b1 == 0x81)) {
                                    val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                                    if (v in 240..4320) maxH = maxOf(maxH, v)
                                }
                            }
                            when {
                                maxW >= 3600 || maxH >= 2000 -> out.add("4K")
                                maxW >= 1800 || maxH >= 1000 -> out.add("1080p")
                                maxW >= 1200 || maxH >= 700 -> out.add("720p")
                            }
                            when {
                                ascii.contains("V_MPEGH/ISO/HEVC") || ascii.contains("hvc1") || ascii.contains("hev1") -> out.add("HEVC")
                                ascii.contains("V_AV1") || ascii.contains("av01") -> out.add("AV1")
                                ascii.contains("V_MPEG4/ISO/AVC") || ascii.contains("avc1") -> out.add("AVC")
                            }
                            out.distinct()
                        }.getOrDefault(emptyList())
                        if (tags.isNotEmpty()) {
                            prefs.edit().putString(file.path, tags.joinToString("|")).apply()
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
        }
    }

}
