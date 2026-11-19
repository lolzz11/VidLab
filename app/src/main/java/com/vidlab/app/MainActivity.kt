package com.vidlab.app

import android.widget.Toast
import androidx.compose.foundation.BorderStroke

import android.content.Intent
import android.os.Bundle
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vidlab.app.data.model.AmbiguousPrompt
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.ui.catalog.*
import com.vidlab.app.ui.player.PlayerActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: CatalogViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handleOAuthRedirect(intent) }

    private fun handleOAuthRedirect(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "vidlab" || uri.host != "auth") return
        val provider = uri.pathSegments.firstOrNull() ?: return
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        if (provider == "trakt") {
            val code = uri.getQueryParameter("code") ?: return
            scope.launch { com.vidlab.app.data.remote.TraktManager(this@MainActivity).exchangeAuthorizationCode(code).onSuccess { Toast.makeText(this@MainActivity, "Connected to Trakt as $it", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@MainActivity, "Trakt auth failed: ${it.message}", Toast.LENGTH_LONG).show() } }
        } else if (provider == "anilist") {
            val tok = uri.fragment.orEmpty().split("&").firstOrNull { it.startsWith("access_token=") }?.substringAfter("access_token=") ?: uri.getQueryParameter("access_token") ?: return
            scope.launch { com.vidlab.app.data.remote.AniListManager(this@MainActivity).verifyAndSaveToken(tok).onSuccess { Toast.makeText(this@MainActivity, "Connected to AniList as $it", Toast.LENGTH_SHORT).show() }.onFailure { Toast.makeText(this@MainActivity, "AniList auth failed: ${it.message}", Toast.LENGTH_LONG).show() } }
        }
    }

    override fun onResume() {
        super.onResume()
        com.vidlab.app.data.remote.DownloadTaskManager.ensureVidLabCategoryFolders(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vidlab.app.data.remote.CloudflareDns.enabled = com.vidlab.app.data.prefs.PlayerPreferences(applicationContext).cloudflareDnsEnabled
        coil.Coil.setImageLoader(
            coil.ImageLoader.Builder(applicationContext)
                .memoryCache {
                    coil.memory.MemoryCache.Builder(applicationContext)
                        .maxSizePercent(0.25)
                        .build()
                }
                .diskCache {
                    coil.disk.DiskCache.Builder()
                        .directory(java.io.File(cacheDir, "vidlab_image_cache"))
                        .maxSizeBytes(150L * 1024 * 1024)
                        .build()
                }
                .crossfade(150)
                .respectCacheHeaders(false)
                .build()
        )
        val permsToRequest = buildList {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.Q) {
                add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permsToRequest.isNotEmpty()) {
            val missing = permsToRequest.filter {
                androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (missing.isNotEmpty()) {
                registerForActivityResult(
                    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) {}.launch(missing.toTypedArray())
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            runCatching {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, android.net.Uri.parse("package:$packageName"))
                startActivity(intent)
            }.onFailure {
                runCatching { startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
            }
        }
        com.vidlab.app.data.remote.DownloadTaskManager.ensureVidLabCategoryFolders(this)
        handleOAuthRedirect(intent)
        if (com.vidlab.app.data.remote.RcloneEngine.getSourceMode(this) == "rclone") {
            com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(this)
        }

        setContent {
            val sections by viewModel.feedSections.collectAsState()
            val isLoading by viewModel.isFeedLoading.collectAsState()
            val errorMessage by viewModel.feedError.collectAsState()
            val progressState by viewModel.progressState.collectAsState()
            val singleRenamePlan by viewModel.singleRenamePlan.collectAsState()
            val isSingleRenaming by viewModel.isSingleRenaming.collectAsState()
            val searchResults by viewModel.searchResults.collectAsState()

            var selectedItem by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var itemForOptions by remember { mutableStateOf<ScrapedMediaItem?>(null) }
            var showRenamerModal by remember { mutableStateOf(false) }
            var renamerInitialTab by remember { mutableIntStateOf(0) }
            LaunchedEffect(intent?.action) {
                when (intent?.action) {
                    com.vidlab.app.data.remote.RenamerBackgroundService.ACTION_OPEN_RENAMER -> {
                        renamerInitialTab = 0
                        showRenamerModal = true
                    }
                    com.vidlab.app.data.remote.RenamerBackgroundService.ACTION_OPEN_MANUAL_QUEUE -> {
                        renamerInitialTab = 1
                        showRenamerModal = true
                    }
                }
            }
            var showRecentHistoryModal by remember { mutableStateOf(false) }
            var showSourceSheet by remember { mutableStateOf(false) }
            var showDownloadsModal by remember { mutableStateOf(false) }
            val launchModePrefs = remember { getSharedPreferences("vidlab_ui_prefs", android.content.Context.MODE_PRIVATE) }
            var showLaunchModePicker by remember { mutableStateOf(!launchModePrefs.getBoolean("source_mode_chosen", false)) }
            var activeAmbiguousPrompt by remember { mutableStateOf<AmbiguousPrompt?>(null) }

            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val coroutineScope = rememberCoroutineScope()
            var simklConnected by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                viewModel.ambiguousPrompts.collectLatest { prompt ->
                    activeAmbiguousPrompt = prompt
                }
            }

            BackHandler(enabled = drawerState.isOpen || showDownloadsModal || selectedItem != null || showRenamerModal || singleRenamePlan != null || itemForOptions != null) {
                if (drawerState.isOpen) coroutineScope.launch { drawerState.close() }
                else if (showDownloadsModal) showDownloadsModal = false
                else if (singleRenamePlan != null) viewModel.dismissSingleRenamePlan()
                else if (itemForOptions != null) itemForOptions = null
                else if (showRenamerModal) showRenamerModal = false
                else selectedItem = null
            }

            if (showLaunchModePicker) {
                val activeMode = remember { com.vidlab.app.data.remote.RcloneEngine.getSourceMode(this@MainActivity) }
                AlertDialog(
                    onDismissRequest = { launchModePrefs.edit().putBoolean("source_mode_chosen", true).apply(); showLaunchModePicker = false },
                    containerColor = Color(0xFF171D29),
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Choose Source Mode", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Select how VidLab should load your library", color = Color(0xFF94A3B8), fontSize = 12.sp)
                        }
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf(
                                Triple("rclone", "Rclone Mode", "Stream & organize cloud remotes via embedded Rclone"),
                                Triple("webdav", "WebDAV Mode", "Connect directly to an external WebDAV server URL"),
                                Triple("local", "Local Storage Mode", "Browse & play offline videos from your VidLab folder")
                            ).forEach { (modeKey, title, desc) ->
                                val isCurrent = activeMode == modeKey
                                Surface(
                                    onClick = {
                                        com.vidlab.app.data.remote.RcloneEngine.setSourceMode(this@MainActivity, modeKey)
                                        if (modeKey == "rclone") {
                                            com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(this@MainActivity)
                                            com.vidlab.app.data.remote.RcloneEngine.restartServeIfNeeded(this@MainActivity)
                                        } else {
                                            com.vidlab.app.data.remote.RcloneEngine.stopServer()
                                        }
                                        if (modeKey == "local") {
                                            com.vidlab.app.data.remote.DownloadTaskManager.ensureVidLabCategoryFolders(this@MainActivity)
                                        }
                                        launchModePrefs.edit().putBoolean("source_mode_chosen", true).apply(); showLaunchModePicker = false
                                        viewModel.loadMediaCenter(force = true)
                                    },
                                    color = if (isCurrent) Color(0xFF244985) else Color(0xFF10141D),
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, if (isCurrent) Color(0xFF3B6CB7) else Color(0xFF222E46)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Text(desc, color = if (isCurrent) Color(0xFFD3E3FD) else Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { launchModePrefs.edit().putBoolean("source_mode_chosen", true).apply(); showLaunchModePicker = false }) {
                            Text("Continue", color = Color(0xFF3B6CB7), fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    VidLabDrawerContent(
                        simklConnected = simklConnected,
                        onSimklToggle = { simklConnected = it },
                        onRescanClick = {
                            Toast.makeText(this@MainActivity, "Scanning /VidLab vault...", Toast.LENGTH_SHORT).show()
                            viewModel.loadMediaCenter(force = true) { count ->
                                val msg = if (count >= 0) "Scan complete: $count title(s) in /VidLab" else "Scan failed — check connection"
                                Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRunRenamerClick = {
                            coroutineScope.launch { drawerState.close() }
                            if (!progressState.isRunning) {
                                viewModel.triggerLibraryBatchRename()
                            }
                            renamerInitialTab = 0
                            showRenamerModal = true
                        },
                        onBrowseStorageClick = {
                            coroutineScope.launch { drawerState.close() }
                            showSourceSheet = true
                        },
                        onRecentClick = {
                            coroutineScope.launch { drawerState.close() }
                            showRecentHistoryModal = true
                        },
                        onOpenFullDownloads = {
                            coroutineScope.launch { drawerState.close() }
                            showDownloadsModal = true
                        },
                        isOrganizerRunning = progressState.isRunning,
                        organizerStatusLabel = when {
                            progressState.isRunning -> "Organizing ${progressState.processedCount}/${progressState.totalCount}"
                            else -> ""
                        },
                        sections = sections,
                        onSelectHistoryItem = { item ->
                            coroutineScope.launch { drawerState.close() }
                            selectedItem = item
                        }
                    )
                }
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050608))) {
                    if (selectedItem != null) {
                        MediaDetailScreen(
                            item = selectedItem!!,
                            allLibraryItems = remember(sections) { sections.flatMap { it.items } },
                            onSelectOtherItem = { selectedItem = it },
                            onBack = { selectedItem = null },
                            onPlayFile = { file -> launchPlayer(file, selectedItem) },
                            onRematch = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            },
                            onRenamePermanently = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            }
                        )
                    } else {
                        MediaCenterScreen(
                            sections = sections,
                            isLoading = isLoading,
                            errorMessage = errorMessage,
                            onOpenDrawer = { coroutineScope.launch { drawerState.open() } },
                            onRescan = {
                                Toast.makeText(this@MainActivity, "Scanning /VidLab vault...", Toast.LENGTH_SHORT).show()
                                viewModel.loadMediaCenter(force = true) { count ->
                                    val msg = if (count >= 0) "Scan complete: $count title(s) in /VidLab" else "Scan failed — check connection"
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                            },
                            onSelectItem = { item -> selectedItem = item },
                            onRematchItem = { item ->
                                itemForOptions = item
                                viewModel.searchCandidates(item.title)
                            }
                        )
                    }

                                        if (showRecentHistoryModal) {
                        val historyStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(this@MainActivity) }
                        val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
                        val historyList = remember(sections, rev) {
                            historyStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
                        }
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { showRecentHistoryModal = false },
                            containerColor = Color(0xFF0D1015),
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Recent Watch History", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    if (historyList.isNotEmpty()) {
                                        TextButton(onClick = { historyStore.clearHistory() }) {
                                            Text("Clear", color = Color(0xFF94A3B8), fontSize = 12.sp)
                                        }
                                    }
                                }
                            },
                            text = {
                                if (historyList.isEmpty()) {
                                    Text(
                                        "No watch history yet. Play any movie or episode and it will appear here.",
                                        color = Color(0xFF244985),
                                        fontSize = 13.sp
                                    )
                                } else {
                                    androidx.compose.foundation.lazy.LazyColumn(
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.heightIn(max = 420.dp)
                                    ) {
                                        items(historyList.size) { idx ->
                                            val entry = historyList[idx]
                                            Surface(
                                                onClick = {
                                                    showRecentHistoryModal = false
                                                    selectedItem = entry.item
                                                },
                                                color = Color(0xFF1A2236),
                                                shape = RoundedCornerShape(10.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    coil.compose.AsyncImage(
                                                        model = entry.item.posterUrl ?: entry.item.backdropUrl,
                                                        contentDescription = entry.item.title,
                                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                        modifier = Modifier.size(width = 46.dp, height = 66.dp).clip(RoundedCornerShape(6.dp))
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(entry.item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        val sub = buildString {
                                                            if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                                            if (entry.progress.watched) append("Watched")
                                                            else append("${(entry.progress.fraction * 100).toInt()}% watched")
                                                        }
                                                        Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                        Spacer(modifier = Modifier.height(6.dp))
                                                        LinearProgressIndicator(
                                                            progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                                            color = Color(0xFF244985),
                                                            trackColor = Color(0xFF222E46),
                                                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showRecentHistoryModal = false }) {
                                    Text("Close", color = Color(0xFF244985), fontWeight = FontWeight.Bold)
                                }
                            }
                        )
                    }

                    // Dialog to pick Movie vs Show vs Manual Custom Name
                    if (itemForOptions != null) {
                        RenameOptionsDialog(
                            item = itemForOptions!!,
                            searchResults = searchResults,
                            onSearch = { query -> viewModel.searchCandidates(query) },
                            onSelectMatch = { cand ->
                                viewModel.applyCandidateSelection(itemForOptions!!, cand)
                                itemForOptions = null
                            },
                            onManualApply = { title, year ->
                                viewModel.applyManualCustomRename(itemForOptions!!, title, year)
                                itemForOptions = null
                            },
                            onDismiss = { itemForOptions = null }
                        )
                    }

                    // Operation Preview Sheet
                    if (singleRenamePlan != null) {
                        RenamePreviewBottomSheet(
                            operations = singleRenamePlan!!,
                            isRenaming = isSingleRenaming,
                            onConfirm = {
                                viewModel.executeSingleItemRename {
                                    selectedItem = null
                                }
                            },
                            onDismiss = { viewModel.dismissSingleRenamePlan() }
                        )
                    }

                    // Minimized Background Organizer Floating Pill (Zero touch-blocking scrim)
                    if (!showRenamerModal && (progressState.isRunning || progressState.unmatchedItems.isNotEmpty())) {
                        Surface(
                            onClick = {
                                renamerInitialTab = if (!progressState.isRunning && progressState.unmatchedItems.isNotEmpty()) 1 else 0
                                showRenamerModal = true
                            },
                            color = Color(0xFF1A1629),
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, Color(0xFF244985)),
                            shadowElevation = 8.dp,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(bottom = 16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (progressState.isRunning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        color = Color(0xFF244985),
                                        strokeWidth = 2.dp
                                    )
                                    Text(
                                        text = if (progressState.totalCount > 0) "Organizing ${progressState.processedCount}/${progressState.totalCount}" else "Scanning storage...",
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                } else {
                                    Text(
                                        text = "⚠ ${progressState.unmatchedItems.size} file(s) need manual match",
                                        color = Color(0xFFFFB74D),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Full-Screen Storage Renamer Overlay (Dismissible anytime via Minimize)
                    if (showRenamerModal) {
                        Surface(
                            color = Color(0xFF0D1015),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            AutoRenameScreen(
                                state = progressState,
                                searchResults = searchResults,
                                onSearch = { q -> viewModel.searchCandidates(q) },
                                onSkipItem = { viewModel.skipQuarantineItem(it) },
                        onManualMatchApply = { qItem, title, cat, year, s, ep, epTitle ->
                                    viewModel.applyManualQuarantineMatch(qItem, title, cat, year, s, ep, epTitle)
                                },
                                onRestart = { viewModel.triggerLibraryBatchRename() },
                                onMinimize = { showRenamerModal = false },
                                initialTab = renamerInitialTab
                            )
                        }
                    }

                    // Ambiguous Confirmation Prompt
                    // Ambiguous home-screen popup disabled
                }
            }
        }
    }

    private fun launchPlayer(file: MediaFile, seriesItem: ScrapedMediaItem? = null) {
        val allEpisodes = seriesItem?.seasons
            ?.sortedBy { it.seasonNumber }
            ?.flatMap { season -> season.episodes.sortedBy { it.episodeNumber } }
            ?: emptyList()

        val paths = ArrayList<String>()
        val names = ArrayList<String>()
        if (allEpisodes.isNotEmpty()) {
            for (ep in allEpisodes) {
                paths.add(ep.file.path)
                names.add(ep.file.name)
            }
        } else {
            paths.add(file.path)
            names.add(file.name)
        }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("FILE_PATH", file.path)
            putExtra("FILE_NAME", file.name)
            putStringArrayListExtra("PLAYLIST_PATHS", paths)
            putStringArrayListExtra("PLAYLIST_NAMES", names)
        }
        startActivity(intent)
    }
}
