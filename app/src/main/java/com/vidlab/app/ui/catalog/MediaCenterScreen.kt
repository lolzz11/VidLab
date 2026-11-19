package com.vidlab.app.ui.catalog

import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.ScrapedMediaItem
import kotlinx.coroutines.delay

private val DeepPurpleBg = Color(0xFF050608)
private val SurfaceDark = Color(0xFF0D1015)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF244985)
private val SecondaryText = Color(0xFF94A3B8)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(
    items: List<ScrapedMediaItem>,
    onWatchNow: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { items.size })

    LaunchedEffect(items.size) {
        if (items.size > 1) {
            while (true) {
                delay(5000L)
                val next = (pagerState.currentPage + 1) % items.size
                pagerState.animateScrollToPage(next)
            }
        }
    }

    Box(modifier = modifier.fillMaxWidth().height(428.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val item = items[page]
            val posterUrl = item.posterUrl?.replace("/w300/", "/w500/") ?: item.backdropUrl
            val ambientUrl = item.backdropUrl ?: posterUrl

            Box(modifier = Modifier.fillMaxSize()) {
                // Soft ambient blurred backdrop behind the compact poster
                if (!ambientUrl.isNullOrBlank()) {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val ambReq = remember(ambientUrl) {
                        ImageRequest.Builder(ctx)
                            .data(ambientUrl)
                            .size(200, 300)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = ambReq,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.28f,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Ambient radial + bottom fade into DeepPurpleBg (#111318)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0x99111318),
                                    Color(0x4D191C22),
                                    DeepPurpleBg
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 26.dp, start = 20.dp, end = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Compact rounded poster card
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 10.dp),
                        modifier = Modifier
                            .width(156.dp)
                            .height(224.dp)
                            .clickable { onWatchNow(item) }
                    ) {
                        if (!posterUrl.isNullOrBlank()) {
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            val posterReq = remember(posterUrl) {
                                ImageRequest.Builder(ctx)
                                    .data(posterUrl)
                                    .size(480, 720)
                                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                    .precision(Precision.INEXACT)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = posterReq,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().background(SurfaceDark),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(36.dp))
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = item.title,
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    val genres = item.metadata?.genres?.take(3) ?: emptyList()
                    if (genres.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            genres.forEach { genre ->
                                Surface(
                                    color = SurfaceDark.copy(alpha = 0.85f),
                                    shape = CircleShape,
                                    border = androidx.compose.foundation.BorderStroke(0.6.dp, BorderPurple)
                                ) {
                                    Text(
                                        text = genre,
                                        color = Color(0xFFD4D8E2),
                                        fontSize = 10.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = { onWatchNow(item) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = CircleShape,
                        modifier = Modifier.width(164.dp).height(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color(0xFFE8F0FE),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(text = "Watch Now", color = Color(0xFFE8F0FE), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (items.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(items.size) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(5.dp)
                            .width(if (selected) 18.dp else 5.dp)
                            .clip(CircleShape)
                            .background(if (selected) AccentViolet else Color.White.copy(alpha = 0.32f))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResumePlaybackBar(
    entries: List<Pair<ScrapedMediaItem, String>>,
    onResume: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (entries.isEmpty()) return
    val pagerState = rememberPagerState(pageCount = { entries.size })

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalPager(
            state = pagerState,
            contentPadding = PaddingValues(horizontal = 16.dp),
            pageSpacing = 12.dp,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            val (item, episodeLabel) = entries[page]
            Surface(
                onClick = { onResume(item) },
                color = SurfaceDark,
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.width(44.dp).height(62.dp)
                    ) {
                        val thumbUrl = item.posterUrl?.replace("/w300/", "/w500/") ?: item.backdropUrl
                        if (!thumbUrl.isNullOrBlank()) {
                            val resCtx = androidx.compose.ui.platform.LocalContext.current
                            val resReq = remember(thumbUrl) {
                                ImageRequest.Builder(resCtx)
                                    .data(thumbUrl)
                                    .size(140, 200)
                                    .precision(Precision.INEXACT)
                                    .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                                    .crossfade(false)
                                    .build()
                            }
                            AsyncImage(
                                model = resReq,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize().background(DeepPurpleBg),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Movie, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(25.dp))
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "CONTINUE WATCHING",
                            color = AccentViolet,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Text(
                            text = item.title,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (episodeLabel.isNotBlank()) {
                            Text(
                                text = episodeLabel,
                                color = SecondaryText,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Button(
                        onClick = { onResume(item) },
                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color(0xFFE8F0FE), modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Resume", color = Color(0xFFE8F0FE), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (entries.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(entries.size) { index ->
                    val active = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .height(4.dp)
                            .width(if (active) 14.dp else 4.dp)
                            .clip(CircleShape)
                            .background(if (active) AccentViolet else Color.White.copy(alpha = 0.28f))
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaCenterCard(
    item: ScrapedMediaItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(124.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(182.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(0.5.dp, BorderPurple, RoundedCornerShape(8.dp)),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                val posterUrl = item.posterUrl?.replace("/w300/", "/w780/")?.replace("/w500/", "/w780/") ?: item.backdropUrl
                if (!posterUrl.isNullOrBlank()) {
                    val cardCtx = androidx.compose.ui.platform.LocalContext.current
                    val cardReq = remember(posterUrl) {
                        ImageRequest.Builder(cardCtx)
                            .data(posterUrl)
                            .size(360, 540)
                            .precision(Precision.INEXACT)
                            .bitmapConfig(android.graphics.Bitmap.Config.RGB_565)
                            .crossfade(false)
                            .build()
                    }
                    AsyncImage(
                        model = cardReq,
                        contentDescription = item.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xFF221A36), Color(0xFF050608))))
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = item.title,
                            color = AccentViolet.copy(alpha = 0.8f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                if (item.isSeries && item.seasons.isNotEmpty()) {
                    Surface(
                        color = Color(0xEB0C0A14),
                        shape = RoundedCornerShape(topStart = 6.dp),
                        modifier = Modifier.align(Alignment.BottomEnd)
                    ) {
                        val countText = if (item.seasons.size > 1) "${item.seasons.size}S • ${item.totalEpisodesCount} eps" else "${item.totalEpisodesCount} eps"
                        Text(
                            text = countText,
                            color = Color(0xFFE2DCF8),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = item.title,
            color = Color(0xFFF3F0FA),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        val subtitle = if (item.isSeries && item.seasons.size > 1) "${item.seasons.size} Seasons" else item.releaseYear ?: ""
        if (subtitle.isNotBlank()) {
            Text(text = subtitle, color = SecondaryText, fontSize = 11.sp)
        }
    }
}

@Composable
fun MediaCenterScreen(
    sections: List<MediaFeedSection>,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenDrawer: () -> Unit,
    onRescan: () -> Unit,
    onSelectItem: (ScrapedMediaItem) -> Unit,
    onRematchItem: (ScrapedMediaItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val focusManager = LocalFocusManager.current

    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingSelectRemotes by remember { mutableStateOf<List<com.vidlab.app.data.remote.RcloneRemoteConfig>?>(null) }
    val topImportPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                com.vidlab.app.data.remote.RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    pendingSelectRemotes = list
                }.onFailure { err ->
                    android.widget.Toast.makeText(context, "Import failed: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    pendingSelectRemotes?.let { list ->
        AlertDialog(
            onDismissRequest = {
                pendingSelectRemotes = null
                onRescan()
            },
            containerColor = SurfaceDark,
            title = { Text("Select Remotes to Use", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose which remotes VidLab should scan and stream from:", color = SecondaryText, fontSize = 12.sp)
                    list.forEachIndexed { idx, rem ->
                        Surface(
                            color = DeepPurpleBg,
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, if (rem.enabled) AccentViolet else BorderPurple),
                            onClick = {
                                pendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = !r.enabled) else r }
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rem.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Type: ${rem.type}", color = SecondaryText, fontSize = 11.sp)
                                }
                                Checkbox(
                                    checked = rem.enabled,
                                    onCheckedChange = { checked ->
                                        pendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = checked) else r }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = SecondaryText)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    com.vidlab.app.data.remote.RcloneEngine.saveConfiguredRemotes(context, list)
                    com.vidlab.app.data.remote.RcloneEngine.setRcloneModeEnabled(context, true)
                    com.vidlab.app.data.remote.RcloneEngine.restartServeIfNeeded(context)
                    pendingSelectRemotes = null
                    onRescan()
                }) { Text("Save & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
            }
        )
    }
    val progressStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(context) }
    val heroItem by remember(sections) {
        derivedStateOf {
            @Suppress("UNUSED_VARIABLE")
            val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
            val allItems = sections.flatMap { it.items }.distinctBy { it.id }
            progressStore.getLastWatchedItem(allItems)
                ?: allItems.firstOrNull { !it.backdropUrl.isNullOrBlank() }
                ?: allItems.firstOrNull()
        }
    }

    val dlRev = com.vidlab.app.data.remote.DownloadTaskManager.downloadRevision.intValue
    val filterPrefs = remember { context.getSharedPreferences("vidlab_catalog_filters", android.content.Context.MODE_PRIVATE) }
    var showDownloadedOnly by remember { mutableStateOf(filterPrefs.getBoolean("show_downloaded_only", false)) }
    var showVidLabFolderOnly by remember { mutableStateOf(filterPrefs.getBoolean("show_vidlab_folder_only", false)) }
    var selectedCategory by remember { mutableStateOf(filterPrefs.getString("selected_category", "All") ?: "All") }
    var sortOption by remember { mutableStateOf(filterPrefs.getString("sort_option", "Default") ?: "Default") }
    var selectedQuality by remember { mutableStateOf(filterPrefs.getString("selected_quality", "All") ?: "All") }
    var watchStatusFilter by remember { mutableStateOf(filterPrefs.getString("watch_status_filter", "All") ?: "All") }
    var probeRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(sections) {
        val allItems = sections.flatMap { it.items }.distinctBy { it.id }
        val sampleFiles = allItems.flatMap { item ->
            if (item.isSeries) item.seasons.flatMap { it.episodes }.map { it.file }.take(2)
            else listOf(item.primaryFile)
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val prefs = context.getSharedPreferences("vidlab_media_specs_cache", android.content.Context.MODE_PRIVATE)
            var updatedAny = false
            for (f in sampleFiles) {
                if (prefs.contains(f.path)) continue
                val urlStr = runCatching { com.vidlab.app.data.remote.RcloneEngine.buildStreamUrl(context, f.path) }.getOrNull() ?: continue
                val tags = runCatching {
                    val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                        connectTimeout = 3000; readTimeout = 3000
                        setRequestProperty("Range", "bytes=0-65535")
                    }
                    val bytes = conn.inputStream.use { inp ->
                        val buf = java.io.ByteArrayOutputStream(); val tmp = ByteArray(8192); var tot = 0
                        while (tot < 65536) { val r = inp.read(tmp); if (r <= 0) break; buf.write(tmp, 0, r); tot += r }
                        buf.toByteArray()
                    }
                    val out = mutableListOf<String>()
                    val ascii = String(bytes, Charsets.ISO_8859_1)
                    var maxW = 0; var maxH = 0
                    for (i in 0 until bytes.size - 4) {
                        val b0 = bytes[i].toInt() and 0xFF; val b1 = bytes[i + 1].toInt() and 0xFF
                        if (b0 == 0xB0 && (b1 == 0x82 || b1 == 0x81)) {
                            val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                            if (v in 320..8192) maxW = maxOf(maxW, v)
                        } else if (b0 == 0xBA && (b1 == 0x82 || b1 == 0x81)) {
                            val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                            if (v in 240..4320) maxH = maxOf(maxH, v)
                        }
                    }
                    when { maxW >= 3600 || maxH >= 2000 -> out.add("4K"); maxW >= 1800 || maxH >= 1000 -> out.add("1080p"); maxW >= 1200 || maxH >= 700 -> out.add("720p") }
                    when { ascii.contains("V_MPEGH/ISO/HEVC") || ascii.contains("hvc1") || ascii.contains("hev1") -> out.add("HEVC"); ascii.contains("V_AV1") || ascii.contains("av01") -> out.add("AV1"); ascii.contains("V_MPEG4/ISO/AVC") || ascii.contains("avc1") -> out.add("AVC") }
                    out.distinct()
                }.getOrDefault(emptyList())
                if (tags.isNotEmpty()) {
                    prefs.edit().putString(f.path, tags.joinToString("|")).apply()
                    updatedAny = true
                }
            }
            if (updatedAny) probeRevision++
        }
    }
    val cachedProbeSpecs = remember(sections, probeRevision) {
        val prefs = context.getSharedPreferences("vidlab_media_specs_cache", android.content.Context.MODE_PRIVATE)
        prefs.all.mapValues { (_, v) -> (v as? String).orEmpty().uppercase() }
    }
    var isFilterSheetOpen by remember { mutableStateOf(false) }

    val availableCategories = remember(sections) {
        listOf("All") + sections.map { it.title }.distinct()
    }

    val downloadedNames = remember(dlRev, showDownloadedOnly) {
        if (showDownloadedOnly) com.vidlab.app.data.remote.DownloadTaskManager.getDownloadedFileNames(context)
        else emptySet()
    }

    val filteredSections = remember(sections, searchQuery, showDownloadedOnly, showVidLabFolderOnly, selectedCategory, selectedQuality, watchStatusFilter, sortOption, downloadedNames, cachedProbeSpecs) {
        var base = if (selectedCategory == "All") sections else sections.filter { it.title.equals(selectedCategory, ignoreCase = true) }
        if (showVidLabFolderOnly) {
            base = base.mapNotNull { sec ->
                val v = sec.items.filter { it.primaryFile.path.trim('/').startsWith("VidLab/", ignoreCase = true) }
                if (v.isNotEmpty()) sec.copy(items = v) else null
            }
        }
        if (selectedQuality != "All") {
            val qUp = selectedQuality.uppercase()
            base = base.mapNotNull { sec ->
                val qItems = sec.items.filter { item ->
                    val filesToCheck = if (item.isSeries) item.seasons.flatMap { it.episodes }.map { it.file } else listOf(item.primaryFile)
                    filesToCheck.any { f ->
                        val pathUp = f.path.uppercase()
                        val probedUp = cachedProbeSpecs[f.path].orEmpty()
                        val gb = f.size / (1024.0 * 1024.0 * 1024.0)
                        val probedTokens = probedUp.split("|").map { it.trim() }
                        when (qUp) {
                            "4K" -> Regex("""\b(2160P|4K|UHD)\b""").containsMatchIn(pathUp) || "4K" in probedTokens
                            "1080P" -> Regex("""\b1080P\b""").containsMatchIn(pathUp) || "1080P" in probedTokens
                            "720P" -> Regex("""\b720P\b""").containsMatchIn(pathUp) || "720P" in probedTokens
                            "BLU-RAY" -> Regex("""\b(BLU-?RAY|BDRIP|BRRIP|REMUX)\b""").containsMatchIn(pathUp)
                            "HEVC" -> Regex("""\b(HEVC|X265|H\.?265)\b""").containsMatchIn(pathUp) || "HEVC" in probedTokens
                            else -> true
                        }
                    }
                }
                if (qItems.isNotEmpty()) sec.copy(items = qItems) else null
            }
        }
        if (showDownloadedOnly) {
            base = base.mapNotNull { sec ->
                val o = sec.items.filter { item ->
                    if (downloadedNames.isEmpty()) false
                    else if (item.isSeries && item.seasons.isNotEmpty()) item.seasons.any { sn -> sn.episodes.any { it.file.name in downloadedNames } }
                    else item.primaryFile.name in downloadedNames
                }
                if (o.isNotEmpty()) sec.copy(items = o) else null
            }
        }
        if (watchStatusFilter != "All") {
            base = base.mapNotNull { sec ->
                val wItems = sec.items.filter { item ->
                    val isItemWatched = if (item.isSeries) {
                        val eps = item.seasons.flatMap { it.episodes }
                        eps.isNotEmpty() && eps.all { progressStore.getProgress(it.file.path)?.watched == true }
                    } else {
                        progressStore.getProgress(item.primaryFile.path)?.watched == true
                    }
                    if (watchStatusFilter == "Watched") isItemWatched else !isItemWatched
                }
                if (wItems.isNotEmpty()) sec.copy(items = wItems) else null
            }
        }
        if (searchQuery.isNotBlank()) {
            base = base.mapNotNull { sec ->
                val m = sec.items.filter { it.title.contains(searchQuery, ignoreCase = true) }
                if (m.isNotEmpty()) sec.copy(items = m) else null
            }
        }
        when (sortOption) {
            "A-Z" -> base.map { it.copy(items = it.items.sortedBy { item -> item.title.lowercase() }) }
            "Z-A" -> base.map { it.copy(items = it.items.sortedByDescending { item -> item.title.lowercase() }) }
            "Most Episodes" -> base.map { it.copy(items = it.items.sortedByDescending { item -> if (item.isSeries) item.seasons.sumOf { sn -> sn.episodes.size } else 1 }) }
            else -> base
        }
    }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val isFabVisible by remember {
        var prevIndex = 0
        var prevOffset = 0
        derivedStateOf {
            val idx = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset
            val visible = when {
                idx == 0 && off < 40 -> true
                idx < prevIndex -> true
                idx > prevIndex -> false
                off < prevOffset -> true
                off > prevOffset -> false
                else -> true
            }
            prevIndex = idx
            prevOffset = off
            visible
        }
    }

    val activeFilterCount = (if (showDownloadedOnly) 1 else 0) +
        (if (showVidLabFolderOnly) 1 else 0) +
        (if (selectedCategory != "All") 1 else 0) +
        (if (sortOption != "Default") 1 else 0) +
        (if (selectedQuality != "All") 1 else 0) +
        (if (watchStatusFilter != "All") 1 else 0)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepPurpleBg)
    ) {
        val carouselItems = remember(filteredSections) {
            val buckets = filteredSections.map { sec ->
                val withBackdrop = sec.items.filter { !it.backdropUrl.isNullOrBlank() }
                val withoutBackdrop = sec.items.filter { it.backdropUrl.isNullOrBlank() }
                (withBackdrop + withoutBackdrop).distinctBy { it.id }
            }.filter { it.isNotEmpty() }
            val mixed = mutableListOf<ScrapedMediaItem>()
            val seenIds = mutableSetOf<String>()
            var idx = 0
            while (mixed.size < 8) {
                var added = false
                for (b in buckets) {
                    if (idx < b.size && seenIds.add(b[idx].id)) {
                        mixed.add(b[idx])
                        added = true
                        if (mixed.size >= 8) break
                    }
                }
                if (!added) break
                idx++
            }
            mixed
        }
        val resumeEntries = remember(sections) {
            val allItems = sections.flatMap { it.items }.distinctBy { it.id }
            val recentList = mutableListOf<Triple<Long, ScrapedMediaItem, String>>()
            for (item in allItems) {
                val candidates = if (item.isSeries) {
                    item.seasons.flatMap { it.episodes }.mapNotNull { ep ->
                        val p = progressStore.getProgress(ep.file.path)
                        if (p != null && !p.watched && p.positionMs > 0L) Triple(p.updatedAt, ep, p) else null
                    }
                } else {
                    val p = progressStore.getProgress(item.primaryFile.path)
                    if (p != null && !p.watched && p.positionMs > 0L) listOf(Triple(p.updatedAt, null, p)) else emptyList()
                }
                val latest = candidates.maxByOrNull { it.first }
                if (latest != null) {
                    val ep = latest.second
                    val label = if (item.isSeries && ep != null) {
                        val cleanEp = ep.title.substringAfter("•", ep.title).replace(Regex("""(?i)^S\d{1,2}E\d{1,3}\s*[-•:]?\s*"""), "").trim()
                        if (cleanEp.isNotBlank()) String.format("S%02dE%02d • %s", ep.seasonNumber, ep.episodeNumber, cleanEp)
                        else String.format("S%02dE%02d", ep.seasonNumber, ep.episodeNumber)
                    } else {
                        "Movie"
                    }
                    recentList.add(Triple(latest.first, item, label))
                }
            }
            recentList.sortedByDescending { it.first }.map { it.second to it.third }.take(10)
        }
        var seeAllSectionTitle by remember { mutableStateOf<String?>(null) }
        val activeSeeAllSection = remember(sections, seeAllSectionTitle) {
            sections.find { it.title == seeAllSectionTitle }
        }
        androidx.activity.compose.BackHandler(enabled = activeSeeAllSection != null) {
            seeAllSectionTitle = null
        }

        val hasHero = searchQuery.isBlank() && carouselItems.isNotEmpty()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                top = if (hasHero) 0.dp else 84.dp,
                bottom = 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (searchQuery.isBlank() && carouselItems.isNotEmpty()) {
                item(key = "hero_carousel") {
                    HeroCarousel(
                        items = carouselItems,
                        onWatchNow = { onSelectItem(it) }
                    )
                }
            }
            if (searchQuery.isBlank() && resumeEntries.isNotEmpty()) {
                item(key = "resume_playback_bar") {
                    ResumePlaybackBar(
                        entries = resumeEntries,
                        onResume = { onSelectItem(it) }
                    )
                }
            }

                items(filteredSections, key = { it.title }, contentType = { "section" }) { section ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = section.title,
                                    color = Color.White,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "(${section.items.size})",
                                    color = SecondaryText,
                                    fontSize = 12.sp
                                )
                            }
                            Text(
                                text = "See All ›",
                                color = AccentViolet,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { seeAllSectionTitle = section.title }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(section.items, key = { it.id }, contentType = { "poster" }) { item ->
                                MediaCenterCard(
                                    item = item,
                                    onClick = { onSelectItem(item) },
                                    onLongClick = { onRematchItem(item) }
                                )
                            }
                        }
                    }
                }
            }


        if (isLoading && sections.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.TopCenter),
                color = AccentViolet,
                trackColor = Color.Transparent
            )
        }

        if (activeSeeAllSection != null) {
            Surface(
                color = DeepPurpleBg,
                modifier = Modifier.fillMaxSize()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(onClick = { seeAllSectionTitle = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }
                        Text(
                            text = "${activeSeeAllSection.title} (${activeSeeAllSection.items.size})",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(activeSeeAllSection.items, key = { it.id }, contentType = { "grid_poster" }) { item ->
                            MediaCenterCard(
                                item = item,
                                onClick = { onSelectItem(item) },
                                onLongClick = { onRematchItem(item) }
                            )
                        }
                    }
                }
            }
        }

        if (sections.isEmpty() && !isSearchActive) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = AccentViolet, strokeWidth = 3.dp)
                    Text(
                        "Scanning media library...",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        "Fetching files & matching metadata",
                        color = SecondaryText,
                        fontSize = 12.sp
                    )
                } else {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val isConfigured = remember(sections, errorMessage) {
                        com.vidlab.app.data.remote.RcloneEngine.hasAnyConfiguredSource(ctx)
                    }
                    var showPasteDialog by remember { mutableStateOf(false) }
                    var pasteText by remember { mutableStateOf("") }
                    var showWebDavDialog by remember { mutableStateOf(false) }
                    var webDavInput by remember { mutableStateOf("http://127.0.0.1:8080") }



                    if (showPasteDialog) {
                        AlertDialog(
                            onDismissRequest = { showPasteDialog = false },
                            containerColor = SurfaceDark,
                            title = { Text("Paste rclone.conf", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                OutlinedTextField(
                                    value = pasteText,
                                    onValueChange = { pasteText = it },
                                    placeholder = { Text("[gdrive]\ntype = drive\n...", color = SecondaryText, fontSize = 12.sp) },
                                    modifier = Modifier.fillMaxWidth().height(170.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color.White,
                                        unfocusedTextColor = Color.White,
                                        focusedBorderColor = AccentViolet,
                                        unfocusedBorderColor = BorderPurple
                                    )
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.vidlab.app.data.remote.RcloneEngine.importConfigText(ctx, pasteText).onSuccess { list ->
                                        showPasteDialog = false
                                        pasteText = ""
                                        pendingSelectRemotes = list
                                    }.onFailure { err ->
                                        android.widget.Toast.makeText(ctx, "Invalid config: ${err.message}", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }) { Text("Save & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showPasteDialog = false }) { Text("Cancel", color = SecondaryText) }
                            }
                        )
                    }

                    if (showWebDavDialog) {
                        AlertDialog(
                            onDismissRequest = { showWebDavDialog = false },
                            containerColor = SurfaceDark,
                            title = { Text("Connect WebDAV Server", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Enter the URL of your local or remote WebDAV media server.", color = SecondaryText, fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = webDavInput,
                                        onValueChange = { webDavInput = it },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = AccentViolet,
                                            unfocusedBorderColor = BorderPurple
                                        )
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    com.vidlab.app.data.remote.RcloneEngine.setWebDavBaseUrl(ctx, webDavInput)
                                    com.vidlab.app.data.remote.RcloneEngine.setRcloneModeEnabled(ctx, false)
                                    showWebDavDialog = false
                                    onRescan()
                                }) { Text("Connect & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showWebDavDialog = false }) { Text("Cancel", color = SecondaryText) }
                            }
                        )
                    }

                    if (!isConfigured && errorMessage.isNullOrBlank()) {
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text("Welcome to VidLab", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    "Stream your personal cloud drives and WebDAV servers with automatic posters, episode tracking, and hardware/software playback.",
                                    color = SecondaryText,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("1. Connect a cloud remote via rclone.conf or a WebDAV URL.", color = Color(0xFF3B6CB7), fontSize = 12.sp)
                                    Text("2. Movies, shows, and anime are matched automatically from any folder layout.", color = Color(0xFF3B6CB7), fontSize = 12.sp)
                                    Text("3. Open the top-left menu anytime to switch sources, decoders, or subtitles.", color = Color(0xFF3B6CB7), fontSize = 12.sp)
                                }
                                Button(
                                    onClick = {
                                    if (com.vidlab.app.data.remote.RcloneEngine.isLocalStorageModeEnabled(context)) {
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
                                            runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))) }
                                        } else {
                                            context.getSharedPreferences("vidlab_offline_prefs", android.content.Context.MODE_PRIVATE).edit().putString("local_scan_mode", "full").apply()
                                            onRescan()
                                        }
                                    } else topImportPicker.launch(arrayOf("*/*"))
                                },
                                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Import rclone.conf File", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { showPasteDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Paste Config", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    OutlinedButton(
                                        onClick = { showWebDavDialog = true },
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("WebDAV URL", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            if (!errorMessage.isNullOrBlank()) "Connection Failed" else "No Media Found",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            errorMessage ?: "No supported video files were found in the selected source.",
                            color = if (!errorMessage.isNullOrBlank()) Color(0xFFFF8A80) else SecondaryText,
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = onRescan,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("Rescan", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(
                                onClick = { topImportPicker.launch(arrayOf("*/*")) },
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, BorderPurple)
                            ) {
                                Text(if (com.vidlab.app.data.remote.RcloneEngine.isLocalStorageModeEnabled(context)) "Scan Full Storage" else "Import Config", color = Color.White, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Subtle top gradient scrim so floating Hamburger & Search buttons stay crisp over bright posters
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xD907090D), Color.Transparent)
                    )
                )
        )

        Row(
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(color = Color(0xD907090D), shape = RoundedCornerShape(14.dp), modifier = Modifier.border(0.8.dp, BorderPurple, RoundedCornerShape(14.dp))) {
                IconButton(onClick = onOpenDrawer, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White, modifier = Modifier.size(25.dp))
                }
            }
            if (isSearchActive) {
                TextField(
                    value = searchQuery, onValueChange = { searchQuery = it },
                    placeholder = { Text("Search title, series, anime...", color = SecondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color(0xEE131A29), unfocusedContainerColor = Color(0xEE131A29), focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = AccentViolet, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                    modifier = Modifier.weight(1f).height(50.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, AccentViolet.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
                )
            } else { Spacer(modifier = Modifier.weight(1f)) }
            Surface(color = Color(0xD907090D), shape = CircleShape, modifier = Modifier.border(0.8.dp, BorderPurple, CircleShape)) {
                IconButton(onClick = { isSearchActive = !isSearchActive; if (!isSearchActive) searchQuery = "" }, modifier = Modifier.size(48.dp)) {
                    Icon(if (isSearchActive) Icons.Default.Close else Icons.Default.Search, contentDescription = "Search", tint = Color.White, modifier = Modifier.size(26.dp))
                }
            }
        }

        androidx.compose.animation.AnimatedVisibility(
            visible = isFabVisible,
            enter = androidx.compose.animation.slideInVertically { it * 2 } + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it * 2 } + androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 20.dp, bottom = 20.dp)
        ) {
            Surface(
                onClick = { isFilterSheetOpen = true },
                color = Color(0xF00D1015),
                contentColor = Color(0xFFE2E8F0),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, if (activeFilterCount > 0) AccentViolet else Color(0xFF242C3A)),
                shadowElevation = 8.dp
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = if (activeFilterCount > 0) 14.dp else 13.dp, vertical = 12.dp)
                ) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filters",
                        tint = if (activeFilterCount > 0) AccentViolet else Color(0xFFE2E8F0),
                        modifier = Modifier.size(25.dp)
                    )
                    if (activeFilterCount > 0) {
                        Text(
                            "$activeFilterCount",
                            color = Color(0xFFE2E8F0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (isFilterSheetOpen) {
            @OptIn(ExperimentalMaterial3Api::class)
            ModalBottomSheet(
                onDismissRequest = { isFilterSheetOpen = false },
                containerColor = Color(0xFF0D1015),
                contentColor = Color.White,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Filter & Sort", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        if (activeFilterCount > 0) {
                            TextButton(onClick = {
                                showDownloadedOnly = false; showVidLabFolderOnly = false; selectedCategory = "All"; sortOption = "Default"; selectedQuality = "All"; watchStatusFilter = "All"
                                filterPrefs.edit().clear().apply()
                            }) { Text("Reset", color = AccentViolet, fontWeight = FontWeight.SemiBold) }
                        }
                    }

                    Text("WATCH STATUS", color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("All", "Unwatched", "Watched")) { ws ->
                            val sel = watchStatusFilter.equals(ws, ignoreCase = true)
                            FilterChip(
                                selected = sel,
                                onClick = { watchStatusFilter = ws; filterPrefs.edit().putString("watch_status_filter", ws).apply() },
                                label = { Text(ws, color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentViolet,
                                    containerColor = Color(0xFF171D29)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = sel,
                                    borderColor = BorderPurple,
                                    selectedBorderColor = AccentViolet
                                )
                            )
                        }
                    }

                    Text("QUALITY & CODEC", color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("All", "4K", "1080p", "720p", "Blu-Ray", "HEVC")) { q ->
                            val sel = selectedQuality.equals(q, ignoreCase = true)
                            FilterChip(
                                selected = sel,
                                onClick = { selectedQuality = q; filterPrefs.edit().putString("selected_quality", q).apply() },
                                label = { Text(q, color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AccentViolet,
                                    containerColor = Color(0xFF171D29)
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = sel,
                                    borderColor = Color(0xFF242C3A),
                                    selectedBorderColor = AccentViolet
                                )
                            )
                        }
                    }

                    Text("CATEGORY", color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(availableCategories) { cat ->
                            val sel = selectedCategory.equals(cat, ignoreCase = true)
                            FilterChip(
                                selected = sel,
                                onClick = { selectedCategory = cat; filterPrefs.edit().putString("selected_category", cat).apply() },
                                label = { Text(cat, color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF171D29),
                                    selectedContainerColor = AccentViolet
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = sel,
                                    borderColor = BorderPurple, selectedBorderColor = AccentViolet
                                )
                            )
                        }
                    }

                    Text("SORT BY", color = SecondaryText, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Default", "A-Z", "Z-A", "Most Episodes").forEach { opt ->
                            val sel = sortOption == opt
                            FilterChip(
                                selected = sel,
                                onClick = { sortOption = opt; filterPrefs.edit().putString("sort_option", opt).apply() },
                                label = { Text(opt, color = Color.White, fontSize = 12.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color(0xFF171D29),
                                    selectedContainerColor = AccentViolet
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = sel,
                                    borderColor = BorderPurple, selectedBorderColor = AccentViolet
                                )
                            )
                        }
                    }

                    HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
                    val swColors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AccentViolet, uncheckedThumbColor = Color(0xFF244985), uncheckedTrackColor = BorderPurple, uncheckedBorderColor = Color(0xFF244985))

                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            val n = !showDownloadedOnly; showDownloadedOnly = n; filterPrefs.edit().putBoolean("show_downloaded_only", n).apply()
                        }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Show Downloaded Only", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Instant filter for offline saved titles", color = SecondaryText, fontSize = 12.sp)
                        }
                        Switch(checked = showDownloadedOnly, onCheckedChange = { showDownloadedOnly = it; filterPrefs.edit().putBoolean("show_downloaded_only", it).apply() }, colors = swColors)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                            val n = !showVidLabFolderOnly; showVidLabFolderOnly = n; filterPrefs.edit().putBoolean("show_vidlab_folder_only", n).apply()
                        }.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Show VidLab Folder Only", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Text("Hide files outside /VidLab directory", color = SecondaryText, fontSize = 12.sp)
                        }
                        Switch(checked = showVidLabFolderOnly, onCheckedChange = { showVidLabFolderOnly = it; filterPrefs.edit().putBoolean("show_vidlab_folder_only", it).apply() }, colors = swColors)
                    }
                }
            }
        }
    }
}
