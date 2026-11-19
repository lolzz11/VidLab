package com.vidlab.app.ui.catalog

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.CastMember
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.data.prefs.WatchProgress
import com.vidlab.app.data.prefs.WatchProgressStore
import com.vidlab.app.data.remote.DownloadTaskManager
import android.widget.Toast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close

private val DeepPurpleBg = Color(0xFF0A0E17)
private val SurfaceDark = Color(0xFF131A29)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF244985)

@Composable
fun MediaDetailScreen(
    item: ScrapedMediaItem,
    allLibraryItems: List<ScrapedMediaItem> = emptyList(),
    onSelectOtherItem: (ScrapedMediaItem) -> Unit = {},
    onBack: () -> Unit = {},
    onPlayFile: (MediaFile) -> Unit = {},
    onPlay: (MediaFile) -> Unit = onPlayFile,
    onRematch: (ScrapedMediaItem) -> Unit = {},
    onRenamePermanently: (ScrapedMediaItem) -> Unit = {},
    onBackClick: () -> Unit = onBack,
    onPlayClick: (MediaFile) -> Unit = onPlay,
    onOpenExternalPlayer: ((MediaFile) -> Unit)? = null,
    onRenameClick: () -> Unit = { onRenamePermanently(item) },
    onFixMatchClick: () -> Unit = { onRematch(item) }
) {
    val context = LocalContext.current
    val progressStore = remember { WatchProgressStore(context) }
    val progressRevision by WatchProgressStore.revision

    var selectedSeasonIndex by remember { mutableStateOf(0) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showPlotExpanded by remember { mutableStateOf(false) }
    var showFileDetailsDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val selectedEpisodePaths = remember { mutableStateListOf<String>() }
    var actionTargetFile by remember { mutableStateOf<MediaFile?>(null) }
    var episodeToDelete by remember { mutableStateOf<MediaFile?>(null) }
    var selectedCastMember by remember { mutableStateOf<CastMember?>(null) }

    val launchExternal: (MediaFile) -> Unit = { file ->
        if (onOpenExternalPlayer != null) {
            onOpenExternalPlayer(file)
        } else {
            if (com.vidlab.app.data.remote.RcloneEngine.isRcloneServeWebDavEnabled(context)) com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(context)
            val streamUri = Uri.parse(com.vidlab.app.data.remote.RcloneEngine.buildStreamUrl(context, file.path))
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(streamUri, "video/*")
                putExtra(Intent.EXTRA_TITLE, file.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    val seasons = item.seasons
    val currentSeason = seasons.getOrNull(selectedSeasonIndex)
    val heroImg = item.backdropUrl ?: item.posterUrl
    val metadata = item.metadata

    val isLocalMode = remember { com.vidlab.app.data.remote.RcloneEngine.isLocalStorageModeEnabled(context) }
    val allEpisodes = remember(seasons) { seasons.flatMap { it.episodes } }
    var offlineRefreshTick by remember { mutableIntStateOf(0) }
    val dlRev = DownloadTaskManager.downloadRevision.intValue
    val targetFilesForOffline = remember(item.id, allEpisodes.size) {
        if (item.isSeries && allEpisodes.isNotEmpty()) allEpisodes.map { it.file } else listOf(item.primaryFile)
    }
    var savedOfflinePaths by remember(item.id) { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(targetFilesForOffline, offlineRefreshTick, dlRev) {
        savedOfflinePaths = if (!isLocalMode) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                targetFilesForOffline.filter { DownloadTaskManager.findDownloadedFile(context, it.path) != null }
                    .map { it.path }.toSet()
            }
        } else emptySet()
    }
    val nextEpisodeState = remember(item.id) { mutableStateOf(allEpisodes.firstOrNull()) }
    var watchedCountState by remember(item.id) { mutableIntStateOf(0) }
    val primaryProgressState = remember(item.id) { mutableStateOf<WatchProgress?>(null) }

    LaunchedEffect(item.id, allEpisodes, progressRevision) {
        val computed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            var watched = 0
            var firstUnwatched: com.vidlab.app.data.model.ProcessedEpisode? = null
            for (ep in allEpisodes) {
                val p = progressStore.getProgress(ep.file.path)
                if (p?.watched == true) {
                    watched++
                } else if (firstUnwatched == null) {
                    firstUnwatched = ep
                }
            }
            val chosenEp = firstUnwatched ?: allEpisodes.firstOrNull()
            val targetF = if (item.isSeries) (chosenEp?.file ?: item.primaryFile) else item.primaryFile
            val primProg = progressStore.getProgress(targetF.path)
            Triple(chosenEp, watched, primProg)
        }
        nextEpisodeState.value = computed.first
        watchedCountState = computed.second
        primaryProgressState.value = computed.third
    }
    val nextEpisode = nextEpisodeState.value
    val primaryProgress = primaryProgressState.value
    val primaryTargetFile = if (item.isSeries) (nextEpisode?.file ?: item.primaryFile) else item.primaryFile

    val primaryButtonLabel = remember(item, nextEpisode, primaryProgress) {
        if (item.isSeries) {
            val epTag = if (nextEpisode != null) {
                "S%02dE%02d".format(nextEpisode.seasonNumber, nextEpisode.episodeNumber)
            } else "S01E01"
            if (primaryProgress != null && !primaryProgress.watched && primaryProgress.positionMs > 3000L) {
                "Resume $epTag"
            } else {
                "Play $epTag"
            }
        } else {
            if (primaryProgress != null && !primaryProgress.watched && primaryProgress.positionMs > 3000L) {
                "Resume Movie"
            } else if (primaryProgress?.watched == true) {
                "Watch Again"
            } else {
                "Play Movie"
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepPurpleBg)
    ) {
    val seasonTimeline = remember(currentSeason) {
        if (currentSeason == null) emptyList() else {
            val sorted = currentSeason.episodes.sortedBy { it.episodeNumber }
            val presentNums = sorted.map { it.episodeNumber }.toSet()
            val maxEp = sorted.maxOfOrNull { it.episodeNumber } ?: 0
            val timeline = mutableListOf<Any>()
            if (maxEp > 1) {
                for (epNum in 1..maxEp) {
                    if (epNum in presentNums) {
                        sorted.filter { it.episodeNumber == epNum }.forEach { timeline.add(it) }
                    } else {
                        timeline.add(Pair(currentSeason.seasonNumber, epNum))
                    }
                }
            } else {
                timeline.addAll(sorted)
            }
            timeline
        }
    }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 40.dp)
        ) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp)
                ) {
                    if (!heroImg.isNullOrBlank()) {
                        val heroReq = remember(heroImg) {
                            coil.request.ImageRequest.Builder(context)
                                .data(heroImg)
                                .size(960, 540)
                                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(
                            model = heroReq,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.Black.copy(alpha = 0.25f),
                                        DeepPurpleBg.copy(alpha = 0.78f),
                                        DeepPurpleBg
                                    ),
                                    startY = 140f
                                )
                            )
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onBackClick,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { menuExpanded = true },
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(alpha = 0.55f))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More Options",
                                    tint = Color.White
                                )
                            }

                            MaterialTheme(
                                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp))
                            ) {
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false },
                                modifier = Modifier
                                    .background(SurfaceDark)
                                    .border(1.dp, BorderPurple, RoundedCornerShape(16.dp))
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (item.isSeries) "Mark Entire Series Watched" else "Mark Movie as Watched", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        if (item.isSeries && allEpisodes.isNotEmpty()) {
                                            allEpisodes.forEach { progressStore.markWatched(it.file.path, true) }
                                        } else {
                                            progressStore.markWatched(item.primaryFile.path, true)
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (item.isSeries) "Reset Series Progress" else "Reset Movie Progress", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        if (item.isSeries && allEpisodes.isNotEmpty()) {
                                            allEpisodes.forEach { progressStore.markWatched(it.file.path, false) }
                                        } else {
                                            progressStore.markWatched(item.primaryFile.path, false)
                                        }
                                    }
                                )
                                HorizontalDivider(color = BorderPurple, thickness = 0.8.dp, modifier = Modifier.padding(vertical = 4.dp))
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.AutoFixHigh, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Fix Metadata Match", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        onFixMatchClick()
                                    }
                                )

                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Storage & File Info", color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        menuExpanded = false
                                        showFileDetailsDialog = true
                                    }
                                )
                            }
                            }
                        }
                    }

                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 20.dp, vertical = 14.dp)
                    ) {
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        @OptIn(ExperimentalLayoutApi::class)
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (metadata?.rating != null && metadata.rating > 0.0) {
                                MetaBadge(text = "★ ${String.format("%.1f", metadata.rating)}")
                            }
                            if (!item.releaseYear.isNullOrBlank()) {
                                MetaBadge(text = item.releaseYear!!)
                            }
                            val isFullyWatched = if (item.isSeries) {
                            item.totalEpisodesCount > 0 && watchedCountState >= item.totalEpisodesCount
                        } else {
                            primaryProgress?.watched == true
                        }
                        if (isFullyWatched) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFF14532D).copy(alpha = 0.92f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF22C55E).copy(alpha = 0.55f))
                            ) {
                                Text(
                                    text = "✓ Watched",
                                    color = Color(0xFF86EFAC),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        if (item.isSeries) {
                            MetaBadge(text = "${seasons.size} Season${if (seasons.size > 1) "s" else ""} • $watchedCountState/${item.totalEpisodesCount} Watched")
                        } else {
                            val sizeGb = String.format("%.2f GB", item.primaryFile.size / 1024.0 / 1024.0 / 1024.0)
                            MetaBadge(text = "Movie • $sizeGb")
                        }
                        val qualityTags = remember(primaryTargetFile.name, primaryTargetFile.path, item.primaryFile.path, allEpisodes) {
                            val firstEpName = allEpisodes.firstOrNull()?.file?.name ?: ""
                            val n = "${primaryTargetFile.name} ${primaryTargetFile.path} ${item.primaryFile.path} $firstEpName"
                            buildList {
                                when {
                                    Regex("""(?i)(?<!\d)(2160p?|4k|uhd)(?!\d)""").containsMatchIn(n) -> add("4K")
                                    Regex("""(?i)(?<!\d)1080p?(?!\d)""").containsMatchIn(n) -> add("1080p")
                                    Regex("""(?i)(?<!\d)720p?(?!\d)""").containsMatchIn(n) -> add("720p")
                                    Regex("""(?i)(?<!\d)480p?(?!\d)""").containsMatchIn(n) -> add("480p")
                                }
                                when {
                                    Regex("""(?i)(?<![a-z0-9])(hevc|x265|h\.?265)(?![a-z0-9])""").containsMatchIn(n) -> add("HEVC")
                                    Regex("""(?i)(?<![a-z0-9])av1(?![a-z0-9])""").containsMatchIn(n) -> add("AV1")
                                    Regex("""(?i)(?<![a-z0-9])(avc|x264|h\.?264)(?![a-z0-9])""").containsMatchIn(n) -> add("AVC")
                                }
                                when {
                                    Regex("""(?i)(?<![a-z0-9])(remux)(?![a-z0-9])""").containsMatchIn(n) -> add("REMUX")
                                    Regex("""(?i)(?<![a-z0-9])(blu[\s._-]?ray|bdrip|brrip)(?![a-z0-9])""").containsMatchIn(n) -> add("BluRay")
                                    Regex("""(?i)(?<![a-z0-9])(web[\s._-]?dl|webrip)(?![a-z0-9])""").containsMatchIn(n) -> add("WEB-DL")
                                }
                                if (Regex("""(?i)(?<![a-z0-9])(hdr10\+?|hdr|dolby[\s._-]?vision|dv)(?![a-z0-9])""").containsMatchIn(n)) add("HDR")
                                if (Regex("""(?i)(?<![a-z0-9])(10[\s._-]?bit|hi10p)(?![a-z0-9])""").containsMatchIn(n)) add("10-bit")
                            }
                        }
                        qualityTags.forEach { tag -> MetaBadge(text = tag) }
                        }

                        if (!metadata?.genres.isNullOrEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = metadata!!.genres.joinToString(" • "),
                                color = Color(0xFFCBD5E1),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            item {
                Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = { onPlayClick(primaryTargetFile) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = DeepPurpleBg,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = primaryButtonLabel,
                                color = DeepPurpleBg,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        var showDownloadMenu by remember { mutableStateOf(false) }
                        var showEpisodePickerDialog by remember { mutableStateOf(false) }
                        var showLocalDeleteConfirm by remember { mutableStateOf(false) }
                        val savedOfflineCount = savedOfflinePaths.size
                        val hasOfflineSaved = savedOfflineCount > 0
                        val allSaved = savedOfflineCount == targetFilesForOffline.size && targetFilesForOffline.isNotEmpty()

                        if (showLocalDeleteConfirm) {
                            AlertDialog(
                                onDismissRequest = { showLocalDeleteConfirm = false },
                                containerColor = SurfaceDark,
                                title = { Text("Delete from Local Storage?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                text = { Text("This will permanently delete ${targetFilesForOffline.size} local file(s) for ${item.title} from your device.", color = Color(0xFFCBD5E1), fontSize = 13.sp) },
                                confirmButton = {
                                    TextButton(onClick = {
                                        showLocalDeleteConfirm = false
                                        var deleted = 0
                                        targetFilesForOffline.forEach { f ->
                                            val localF = java.io.File(f.path)
                                            if (localF.exists() && localF.delete()) deleted++
                                            else if (DownloadTaskManager.deleteDownloadedFile(context, f.path)) deleted++
                                        }
                                        offlineRefreshTick++
                                        DownloadTaskManager.downloadRevision.intValue++
                                        Toast.makeText(context, "Deleted $deleted file(s)", Toast.LENGTH_SHORT).show()
                                        if (isLocalMode) onBackClick()
                                    }) { Text("Delete", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
                                },
                                dismissButton = { TextButton(onClick = { showLocalDeleteConfirm = false }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
                            )
                        }

                        if (showEpisodePickerDialog && item.isSeries) {
                            val selectedEpPaths = remember {
                                mutableStateMapOf<String, Boolean>().apply {
                                    allEpisodes.forEach { ep ->
                                        if (DownloadTaskManager.findDownloadedFile(context, ep.file.path) == null) put(ep.file.path, false)
                                    }
                                }
                            }
                            AlertDialog(
                                onDismissRequest = { showEpisodePickerDialog = false },
                                containerColor = SurfaceDark,
                                title = {
                                    Column {
                                        Text("Select Episodes to Download", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text("Destination: Downloads/VidLab/${item.title}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Surface(color = BorderPurple.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                                allEpisodes.forEach { ep -> if (DownloadTaskManager.findDownloadedFile(context, ep.file.path) == null) selectedEpPaths[ep.file.path] = true }
                                            }) { Text("Select All", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                                            Surface(color = BorderPurple.copy(alpha = 0.7f), shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                                allEpisodes.forEach { ep ->
                                                    val already = DownloadTaskManager.findDownloadedFile(context, ep.file.path) != null
                                                    val watched = progressStore.getProgress(ep.file.path)?.watched == true
                                                    if (!already) selectedEpPaths[ep.file.path] = !watched
                                                }
                                            }) { Text("Select Unwatched", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                                            Surface(color = BorderPurple.copy(alpha = 0.4f), shape = RoundedCornerShape(6.dp), modifier = Modifier.clickable {
                                                selectedEpPaths.keys.forEach { k -> selectedEpPaths[k] = false }
                                            }) { Text("Clear", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                                        }
                                        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 320.dp)) {
                                            items(allEpisodes.size) { idx ->
                                                val ep = allEpisodes[idx]
                                                val isDownloaded = DownloadTaskManager.findDownloadedFile(context, ep.file.path) != null
                                                val checked = isDownloaded || (selectedEpPaths[ep.file.path] == true)
                                                val epLabel = String.format("S%02dE%02d • %s", ep.seasonNumber, ep.episodeNumber, ep.title)
                                                val sizeMb = String.format("%.0f MB", ep.file.size / (1024.0 * 1024.0))
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFF0A0E17))
                                                        .clickable(enabled = !isDownloaded) { selectedEpPaths[ep.file.path] = !(selectedEpPaths[ep.file.path] ?: false) }
                                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Checkbox(checked = checked, onCheckedChange = if (isDownloaded) null else { c -> selectedEpPaths[ep.file.path] = c }, colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = Color(0xFF64748B)))
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(epLabel, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        Text(if (isDownloaded) "$sizeMb • Downloaded ✓" else sizeMb, color = if (isDownloaded) AccentViolet else Color(0xFF94A3B8), fontSize = 10.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                                confirmButton = {
                                    val chosen = allEpisodes.filter { selectedEpPaths[it.file.path] == true }.map { it.file }
                                    TextButton(enabled = chosen.isNotEmpty(), onClick = {
                                        showEpisodePickerDialog = false
                                        DownloadTaskManager.enqueueBatch(context, chosen, item.title)
                                        offlineRefreshTick++
                                        Toast.makeText(context, "Downloading ${chosen.size} episode(s) to Downloads/VidLab", Toast.LENGTH_SHORT).show()
                                    }) { Text("Download (${chosen.size})", color = if (chosen.isNotEmpty()) AccentViolet else Color(0xFF64748B), fontWeight = FontWeight.Bold) }
                                },
                                dismissButton = { TextButton(onClick = { showEpisodePickerDialog = false }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
                            )
                        }

                        if (isLocalMode || allSaved) {
                            OutlinedButton(
                                onClick = { showLocalDeleteConfirm = true },
                                border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.65f)),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFFEF5350).copy(alpha = 0.14f)),
                                modifier = Modifier.height(52.dp).width(56.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete Downloaded Media",
                                    tint = Color(0xFFEF5350),
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        } else {
                        Box {
                            OutlinedButton(
                                onClick = { showDownloadMenu = true },
                                border = BorderStroke(1.dp, BorderPurple),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.outlinedButtonColors(containerColor = SurfaceDark),
                                modifier = Modifier.height(52.dp).width(56.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Download,
                                    contentDescription = "Download",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                            if (showDownloadMenu) {
                                AlertDialog(
                                    onDismissRequest = { showDownloadMenu = false },
                                    containerColor = SurfaceDark,
                                    title = { Text("Download Options", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (!item.isSeries && !allSaved) {
                                                Surface(
                                                    onClick = {
                                                        showDownloadMenu = false
                                                        DownloadTaskManager.enqueueBatch(context, listOf(item.primaryFile), item.title)
                                                        offlineRefreshTick++
                                                        Toast.makeText(context, "Downloading to Downloads/VidLab", Toast.LENGTH_SHORT).show()
                                                    },
                                                    color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                                        Text("Download Movie", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                            } else if (item.isSeries) {
                                                val remainingAll = allEpisodes.filter { it.file.path !in savedOfflinePaths }.map { it.file }
                                                if (remainingAll.isNotEmpty()) {
                                                    Surface(
                                                        onClick = {
                                                            showDownloadMenu = false
                                                            DownloadTaskManager.enqueueBatch(context, remainingAll, item.title)
                                                            offlineRefreshTick++
                                                            Toast.makeText(context, "Downloading ${remainingAll.size} episode(s) to Downloads/VidLab", Toast.LENGTH_SHORT).show()
                                                        },
                                                        color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                                            Text("Download All Episodes (${remainingAll.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                        }
                                                    }
                                                }
                                                if (seasons.size > 1 && currentSeason != null) {
                                                    val remainingSeason = currentSeason.episodes.filter { it.file.path !in savedOfflinePaths }.map { it.file }
                                                    if (remainingSeason.isNotEmpty()) {
                                                        Surface(
                                                            onClick = {
                                                                showDownloadMenu = false
                                                                DownloadTaskManager.enqueueBatch(context, remainingSeason, item.title)
                                                                offlineRefreshTick++
                                                                Toast.makeText(context, "Downloading Season ${currentSeason.seasonNumber} (${remainingSeason.size} ep)", Toast.LENGTH_SHORT).show()
                                                            },
                                                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                                Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                                                Text("Download Current Season (${remainingSeason.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                            }
                                                        }
                                                    }
                                                }
                                                val remainingUnwatched = allEpisodes.filter {
                                                    it.file.path !in savedOfflinePaths && progressStore.getProgress(it.file.path)?.watched != true
                                                }.map { it.file }
                                                if (remainingUnwatched.isNotEmpty()) {
                                                    Surface(
                                                        onClick = {
                                                            showDownloadMenu = false
                                                            DownloadTaskManager.enqueueBatch(context, remainingUnwatched, item.title)
                                                            offlineRefreshTick++
                                                            Toast.makeText(context, "Downloading ${remainingUnwatched.size} unwatched episode(s)", Toast.LENGTH_SHORT).show()
                                                        },
                                                        color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                            Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                                            Text("Download Unwatched (${remainingUnwatched.size})", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                        }
                                                    }
                                                }
                                                if (allEpisodes.size > 1) {
                                                    Surface(
                                                        onClick = { showDownloadMenu = false; showEpisodePickerDialog = true },
                                                        color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                            Icon(Icons.Default.SelectAll, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(20.dp))
                                                            Text("Select Episodes to Download...", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                        }
                                                    }
                                                }
                                            }
                                            if (hasOfflineSaved) {
                                                Surface(
                                                    onClick = { showDownloadMenu = false; showLocalDeleteConfirm = true },
                                                    color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, Color(0xFFEF5350).copy(alpha = 0.5f)), modifier = Modifier.fillMaxWidth()
                                                ) {
                                                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(20.dp))
                                                        Text(if (item.isSeries) "Delete Downloaded ($savedOfflineCount)" else "Delete Download", color = Color(0xFFEF5350), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    confirmButton = { TextButton(onClick = { showDownloadMenu = false }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
                                )
                            }
                        }
                        }
                    }

                    if (primaryProgress != null && !primaryProgress.watched && primaryProgress.fraction > 0.01f) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { primaryProgress.fraction },
                            color = AccentViolet,
                            trackColor = BorderPurple,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                        )
                    }
                }
            }

            val overviewText = metadata?.overview
            if (!overviewText.isNullOrBlank()) {
                item(key = "overview_section", contentType = "overview") {
                    OverviewBlock(overviewText = overviewText)
                }
            }

            if (!metadata?.cast.isNullOrEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)) {
                        Text(
                            text = "Cast & Crew",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(metadata!!.cast, key = { it.name + (it.role ?: "") }, contentType = { "cast" }) { member ->
                                CastItemCard(member = member, onClick = { selectedCastMember = member })
                            }
                        }
                    }
                }
            }

            if (item.isSeries && seasons.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(
                            text = "Seasons & Episodes",
                            color = Color.White,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )

                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(seasons.size, key = { seasons[it].seasonNumber }, contentType = { "season_chip" }) { idx ->
                                val s = seasons[idx]
                                val isSelected = idx == selectedSeasonIndex
                                Surface(
                                    onClick = { selectedSeasonIndex = idx },
                                    color = if (isSelected) AccentViolet else SurfaceDark,
                                    shape = RoundedCornerShape(10.dp),
                                    border = if (!isSelected) BorderStroke(1.dp, BorderPurple) else null
                                ) {
                                    Text(
                                        text = "Season ${s.seasonNumber} (${s.episodes.size})",
                                        color = if (isSelected) Color.White else Color(0xFF94A3B8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            if (currentSeason != null) {
                if (selectionMode) {
                    item(key = "multi_select_bar") {
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AccentViolet),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("${selectedEpisodePaths.size} selected", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                TextButton(onClick = {
                                    val seasonPaths = currentSeason.episodes.map { it.file.path }
                                    if (seasonPaths.all { it in selectedEpisodePaths }) selectedEpisodePaths.removeAll(seasonPaths)
                                    else seasonPaths.forEach { if (it !in selectedEpisodePaths) selectedEpisodePaths.add(it) }
                                }) { Text("Select All", color = AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                                Button(
                                    onClick = {
                                        val picked = allEpisodes.filter { it.file.path in selectedEpisodePaths }.map { it.file }
                                        if (picked.isNotEmpty()) {
                                            DownloadTaskManager.enqueueBatch(context, picked, item.title, saveInApp = false)
                                            Toast.makeText(context, "Downloading ${picked.size} episode(s)", Toast.LENGTH_SHORT).show()
                                        }
                                        selectedEpisodePaths.clear()
                                        selectionMode = false
                                    },
                                    enabled = selectedEpisodePaths.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF0A0E17), modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Download", color = Color(0xFF0A0E17), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = {
                                        val picked = allEpisodes.filter { it.file.path in selectedEpisodePaths }.map { it.file }
                                        if (picked.isNotEmpty()) {
                                            DownloadTaskManager.enqueueBatch(context, picked, item.title, saveInApp = true)
                                            Toast.makeText(context, "Saving ${picked.size} episode(s) offline", Toast.LENGTH_SHORT).show()
                                        }
                                        selectedEpisodePaths.clear()
                                        selectionMode = false
                                    },
                                    enabled = selectedEpisodePaths.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = Color(0xFF0A0E17), modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("Offline", color = Color(0xFF0A0E17), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                val selectedHasOffline = selectedEpisodePaths.any { p -> DownloadTaskManager.findDownloadedFile(context, p) != null }
                                if (selectedHasOffline) {
                                    OutlinedButton(
                                        onClick = {
                                            var removed = 0
                                            selectedEpisodePaths.forEach { p ->
                                                if (DownloadTaskManager.deleteDownloadedFile(context, p)) removed++
                                            }
                                            Toast.makeText(context, "Deleted $removed offline file(s)", Toast.LENGTH_SHORT).show()
                                            selectedEpisodePaths.clear()
                                            selectionMode = false
                                        },
                                        border = BorderStroke(1.dp, Color(0xFFEF5350)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color(0xFFEF5350), modifier = Modifier.size(15.dp))
                                        Spacer(modifier = Modifier.width(3.dp))
                                        Text("Delete", color = Color(0xFFEF5350), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                                IconButton(onClick = { selectedEpisodePaths.clear(); selectionMode = false }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                items(
                    items = seasonTimeline,
                    key = { entry ->
                        when (entry) {
                            is com.vidlab.app.data.model.ProcessedEpisode -> entry.file.path
                            is Pair<*, *> -> "missing_S${entry.first}_E${entry.second}"
                            else -> entry.hashCode()
                        }
                    },
                    contentType = { entry ->
                        if (entry is com.vidlab.app.data.model.ProcessedEpisode) "episode" else "missing_episode"
                    }
                ) { entry ->
                    if (entry is Pair<*, *>) {
                        val sNum = entry.first as Int
                        val eNum = entry.second as Int
                        Surface(
                            color = SurfaceDark.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.6.dp, BorderPurple.copy(alpha = 0.6f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BorderPurple.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Info, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Episode $eNum",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "S${sNum}E${eNum} • Missing from library",
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                        return@items
                    }
                    val ep = entry as com.vidlab.app.data.model.ProcessedEpisode
                    val sizeStr = remember(ep.file.size) {
                        if (ep.file.size > 0) String.format("%.2f GB", ep.file.size / 1024.0 / 1024.0 / 1024.0)
                        else "WebDAV Stream"
                    }

                    val cleanEpisodeName = remember(ep.title, ep.episodeNumber) {
                        ep.title
                            .substringAfter("•", ep.title)
                            .replace(Regex("""(?i)^S\d{1,2}E\d{1,3}\s*[-•:]?\s*"""), "")
                            .trim()
                            .ifEmpty { "Episode ${ep.episodeNumber}" }
                    }

                    val epProgress = remember(ep.file.path, progressRevision) {
                        progressStore.getProgress(ep.file.path)
                    }

                    val isSelected = ep.file.path in selectedEpisodePaths
                    val isSavedOffline = ep.file.path in savedOfflinePaths
                    EpisodeCard(
                        isSavedOffline = isSavedOffline,
                        showDownloadAction = !isLocalMode,
                        episodeTitle = cleanEpisodeName,
                        seasonNumber = ep.seasonNumber,
                        episodeNumber = ep.episodeNumber,
                        thumbnailUrl = ep.thumbnail,
                        fileSize = sizeStr,
                        progress = epProgress,
                        selectionMode = selectionMode,
                        isSelected = isSelected,
                        onPlayClick = {
                            if (selectionMode) {
                                if (isSelected) selectedEpisodePaths.remove(ep.file.path) else selectedEpisodePaths.add(ep.file.path)
                            } else onPlayClick(ep.file)
                        },
                        onLongClick = {
                            if (selectionMode) {
                                if (isSelected) selectedEpisodePaths.remove(ep.file.path) else selectedEpisodePaths.add(ep.file.path)
                            } else actionTargetFile = ep.file
                        },
                        onSetWatched = { watchedState ->
                            progressStore.markWatched(ep.file.path, watchedState)
                            Toast.makeText(context, if (watchedState) "Marked S${ep.seasonNumber}E${ep.episodeNumber} watched" else "Marked S${ep.seasonNumber}E${ep.episodeNumber} unwatched", Toast.LENGTH_SHORT).show()
                        },
                        onDownloadClick = {
                            if (isSavedOffline) {
                                episodeToDelete = ep.file
                            } else {
                                DownloadTaskManager.enqueueFile(context, ep.file, buildCanonicalDownloadFolder(item.title, item.metadata?.releaseYear, item.isSeries, ep.file), saveInApp = false)
                                offlineRefreshTick++
                                Toast.makeText(context, "Downloading S${ep.seasonNumber}E${ep.episodeNumber} to VidLab", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }

        episodeToDelete?.let { delFile ->
            AlertDialog(
                onDismissRequest = { episodeToDelete = null },
                containerColor = SurfaceDark,
                title = { Text("Delete Downloaded Episode?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                text = { Text("Remove ${delFile.name} from local downloads?", color = Color(0xFFCBD5E1), fontSize = 13.sp) },
                confirmButton = {
                    TextButton(onClick = {
                        val localF = java.io.File(delFile.path)
                        val foundF = DownloadTaskManager.findDownloadedFile(context, delFile.path)
                        val ok = (foundF != null && foundF.delete()) || (localF.exists() && localF.delete()) || DownloadTaskManager.deleteDownloadedFile(context, delFile.path)
                        offlineRefreshTick++
                        DownloadTaskManager.downloadRevision.intValue++
                        Toast.makeText(context, if (ok) "Deleted downloaded episode" else "Episode removed from downloads", Toast.LENGTH_SHORT).show()
                        episodeToDelete = null
                    }) { Text("Delete", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { episodeToDelete = null }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
            )
        }

        selectedCastMember?.let { actor ->
            val matchingShows = remember(actor.name, allLibraryItems) {
                allLibraryItems.filter { media ->
                    media.metadata?.cast?.any { it.name.equals(actor.name, ignoreCase = true) } == true
                }.distinctBy { it.id }
            }
            AlertDialog(
                onDismissRequest = { selectedCastMember = null },
                containerColor = SurfaceDark,
                title = {
                    Column {
                        Text(actor.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (actor.role.isNotBlank()) Text(actor.role, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = if (matchingShows.isEmpty()) "No other titles in your library" else "Appears in ${matchingShows.size} title(s) in your library:",
                            color = Color(0xFFCBD5E1),
                            fontSize = 12.sp
                        )
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                            items(matchingShows, key = { it.id }) { show ->
                                Surface(
                                    onClick = {
                                        selectedCastMember = null
                                        if (show.id != item.id) onSelectOtherItem(show)
                                    },
                                    color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(show.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val roleInShow = show.metadata?.cast?.firstOrNull { it.name.equals(actor.name, ignoreCase = true) }?.role.orEmpty()
                                            val sub = listOfNotNull(show.releaseYear, roleInShow.takeIf { it.isNotBlank() }).joinToString(" • ")
                                            if (sub.isNotBlank()) Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFF94A3B8), modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedCastMember = null }) { Text("Close", color = Color(0xFF94A3B8), fontWeight = FontWeight.Bold) } }
            )
        }

        actionTargetFile?.let { targetFile ->
            AlertDialog(
                onDismissRequest = { actionTargetFile = null },
                containerColor = SurfaceDark,
                title = { Text(targetFile.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                selectionMode = true
                                if (targetFile.path !in selectedEpisodePaths) selectedEpisodePaths.add(targetFile.path)
                                actionTargetFile = null
                            },
                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.SelectAll, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                                Text("Select Episodes", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Surface(
                            onClick = {
                                DownloadTaskManager.enqueueFile(context, targetFile, item.title, saveInApp = false)
                                Toast.makeText(context, "Downloading to Downloads/VidLab", Toast.LENGTH_SHORT).show()
                                actionTargetFile = null
                            },
                            color = DeepPurpleBg, shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, BorderPurple), modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Default.Download, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(20.dp))
                                Column {
                                    Text("Download Episode", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    Text("Save to public Downloads/VidLab folder", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }

                    }
                },
                confirmButton = { TextButton(onClick = { actionTargetFile = null }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
            )
        }

        if (showFileDetailsDialog) {
            val allFiles = remember(item) {
                val eps = item.seasons
                    .sortedBy { it.seasonNumber }
                    .flatMap { s -> s.episodes.sortedBy { it.episodeNumber }.map { it.file } }
                if (eps.isNotEmpty()) eps else listOf(item.primaryFile)
            }
            val totalBytes = remember(allFiles) { allFiles.sumOf { it.size } }
            val totalSizeFormatted = remember(totalBytes) {
                val gb = totalBytes / (1024.0 * 1024.0 * 1024.0)
                if (gb >= 1.0) String.format("%.2f GB", gb)
                else String.format("%.2f MB", totalBytes / (1024.0 * 1024.0))
            }
            val rootFolder = remember(allFiles) {
                val firstDir = allFiles.firstOrNull()?.path?.substringBeforeLast('/', "") ?: ""
                if (item.seasons.size > 1) firstDir.substringBeforeLast('/', firstDir) else firstDir
            }

            AlertDialog(
                onDismissRequest = { showFileDetailsDialog = false },
                containerColor = SurfaceDark,
                title = { Text("Storage & File Info", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Title: ${item.title}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (rootFolder.isNotBlank()) {
                            Text("Folder: $rootFolder", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                        if (allFiles.size > 1) {
                            Text(
                                "Summary: ${item.seasons.size} Season(s) • ${allFiles.size} Episodes • $totalSizeFormatted Total",
                                color = AccentViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(allFiles, key = { it.path }) { f ->
                                    val fSizeStr = if (f.size >= 1024L * 1024L * 1024L) {
                                        String.format("%.2f GB", f.size / (1024.0 * 1024.0 * 1024.0))
                                    } else {
                                        String.format("%.2f MB", f.size / (1024.0 * 1024.0))
                                    }
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(DeepPurpleBg)
                                            .padding(horizontal = 10.dp, vertical = 7.dp)
                                    ) {
                                        Text(f.name, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(fSizeStr, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                }
                            }
                        } else {
                            Text("Path: ${item.primaryFile.path}", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            Text("File Size: $totalSizeFormatted", color = AccentViolet, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showFileDetailsDialog = false }) {
                        Text("Close", color = AccentViolet, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun OverviewBlock(overviewText: String) {
    var expanded by remember(overviewText) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Overview",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = if (expanded) "Collapse" else "Expand",
                color = Color(0xFF60A5FA),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = overviewText,
            color = Color(0xFFE2E8F0),
            fontSize = 13.sp,
            lineHeight = 19.sp,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded }
        )
    }
}

@Composable
private fun CastItemCard(member: CastMember, onClick: () -> Unit = {}) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(82.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(SurfaceDark),
            contentAlignment = Alignment.Center
        ) {
            if (!member.profileUrl.isNullOrBlank()) {
                AsyncImage(
                    model = member.profileUrl,
                    contentDescription = member.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color(0xFF7B7394),
                    modifier = Modifier.size(34.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = member.name,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 13.sp
        )

        if (member.role.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = member.role,
                color = Color(0xFF94A3B8),
                fontSize = 10.sp,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
private fun MetaBadge(text: String) {
    Surface(
        color = SurfaceDark.copy(alpha = 0.85f),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BorderPurple)
    ) {
        Text(
            text = text,
            color = Color(0xFFDCD6EC),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeCard(
    episodeTitle: String,
    seasonNumber: Int,
    episodeNumber: Int,
    thumbnailUrl: String?,
    fileSize: String,
    progress: WatchProgress?,
    isSavedOffline: Boolean = false,
    showDownloadAction: Boolean = true,
    selectionMode: Boolean = false,
    isSelected: Boolean = false,
    onPlayClick: () -> Unit,
    onLongClick: () -> Unit,
    onSetWatched: (Boolean) -> Unit,
    onDownloadClick: () -> Unit
) {
    var rawDragX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val snapBackX by animateFloatAsState(
        targetValue = if (isDragging) rawDragX else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "episodeSwipe"
    )
    val animatedOffsetX = if (isDragging) rawDragX else snapBackX
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
    ) {
        if (kotlin.math.abs(animatedOffsetX) > 4f) {
            val isRight = animatedOffsetX > 0f
            Row(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isRight) Color(0xFF153B2E) else Color(0xFF282032))
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isRight) Arrangement.Start else Arrangement.End
            ) {
                Text(
                    text = if (isRight) "✓ Mark Watched" else "↺ Mark Unwatched",
                    color = if (isRight) Color(0xFF4ADE80) else Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Surface(
            color = if (isSelected) Color(0xFF1E263D) else SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(if (isSelected) 1.2.dp else 0.5.dp, if (isSelected) AccentViolet else BorderPurple),
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { translationX = animatedOffsetX }
                .draggable(
                    orientation = Orientation.Horizontal,
                    enabled = !selectionMode,
                    onDragStarted = { isDragging = true },
                    state = rememberDraggableState { delta ->
                        rawDragX = (rawDragX + delta).coerceIn(-160f, 160f)
                    },
                    onDragStopped = {
                        isDragging = false
                        if (rawDragX > 80f) onSetWatched(true)
                        else if (rawDragX < -80f) onSetWatched(false)
                        rawDragX = 0f
                    }
                )
                .clip(RoundedCornerShape(12.dp))
                .combinedClickable(onClick = onPlayClick, onLongClick = onLongClick)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onPlayClick() },
                        colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = Color(0xFF94A3B8)),
                        modifier = Modifier.padding(end = 6.dp)
                    )
                }
                Box(
                    modifier = Modifier.width(114.dp).height(66.dp).clip(RoundedCornerShape(8.dp)).background(BorderPurple)
                ) {
                    if (!thumbnailUrl.isNullOrBlank()) {
                        val thumbCtx = androidx.compose.ui.platform.LocalContext.current
                        val thumbReq = remember(thumbnailUrl) {
                            coil.request.ImageRequest.Builder(thumbCtx)
                                .data(thumbnailUrl)
                                .size(240, 140)
                                .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                .crossfade(false)
                                .build()
                        }
                        AsyncImage(model = thumbReq, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)).align(Alignment.Center),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                    }
                    if (progress != null && progress.fraction > 0.01f && !progress.watched) {
                        Box(modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.6f))) {
                            Box(modifier = Modifier.fillMaxWidth(progress.fraction).fillMaxHeight().background(AccentViolet))
                        }
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(vertical = 2.dp)) {
                    Text(
                        text = episodeTitle,
                        color = if (progress?.watched == true) Color(0xFF94A3B8) else Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(text = "S${seasonNumber}E${episodeNumber} • $fileSize", color = Color(0xFF94A3B8), fontSize = 11.sp)
                        if (progress?.watched == true) {
                            Surface(
                                color = BorderPurple.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(0.6.dp, BorderPurple)
                            ) {
                                Text(
                                    text = "Watched",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }
                }
                if (!selectionMode && showDownloadAction) {
                    IconButton(onClick = onDownloadClick, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = if (isSavedOffline) Icons.Default.CheckCircle else Icons.Default.Download,
                            contentDescription = if (isSavedOffline) "Downloaded" else "Download Episode",
                            tint = Color(0xFF94A3B8),
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }
            }
        }
    }
}

private val AccentTeal = Color(0xFF94A3B8)

private fun buildCanonicalDownloadFolder(title: String, releaseYear: String?, isSeries: Boolean, file: MediaFile): String {
    val cleanTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "General" }
    val year = releaseYear?.takeIf { it.isNotBlank() }
    val base = if (year != null) "$cleanTitle ($year)" else cleanTitle
    return if (isSeries) {
        val seasonMatch = Regex("""(?i)(?:S|Season\s*)(\d{1,2})""").find(file.path)
        val sNum = seasonMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (sNum != null) "$base/Season ${sNum.toString().padStart(2, '0')}" else base
    } else base
}
