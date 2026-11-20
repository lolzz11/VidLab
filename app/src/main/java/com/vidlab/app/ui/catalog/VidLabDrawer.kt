package com.vidlab.app.ui.catalog

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.remote.SimklManager
import com.vidlab.app.data.remote.SimklPinSession
import com.vidlab.app.data.remote.SimklPollState
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.DownloadStatus
import com.vidlab.app.data.remote.AppUpdater
import com.vidlab.app.data.remote.AppReleaseInfo
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Close
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val DeepPurpleBg = Color(0xFF0B0F17)
private val SurfaceDark = Color(0xFF10141D)
private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF3B6CB7)

@Composable
fun VidLabDrawerContent(
    simklConnected: Boolean,
    onSimklToggle: (Boolean) -> Unit,
    onRescanClick: () -> Unit,
    onRunRenamerClick: () -> Unit,
    onBrowseStorageClick: () -> Unit = {},
    onRecentClick: () -> Unit = {},
    onOpenFullDownloads: () -> Unit = {},
    isOrganizerRunning: Boolean = false,
    organizerStatusLabel: String = "",
    sections: List<com.vidlab.app.data.model.MediaFeedSection> = emptyList(),
    onSelectHistoryItem: (com.vidlab.app.data.model.ScrapedMediaItem) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerPrefs = remember { PlayerPreferences(context) }
    val simklManager = remember { SimklManager(context) }
    val traktManager = remember { com.vidlab.app.data.remote.TraktManager(context) }
    val aniListManager = remember { com.vidlab.app.data.remote.AniListManager(context) }
    var traktSignedIn by remember { mutableStateOf(traktManager.isSignedIn) }
    var traktUser by remember { mutableStateOf(traktManager.username ?: "Trakt Member") }
    var traktScrobble by remember { mutableStateOf(traktManager.scrobbleEnabled) }
    var traktDeviceSession by remember { mutableStateOf<com.vidlab.app.data.remote.TraktDeviceSession?>(null) }
    var traktStatusMsg by remember { mutableStateOf<String?>(null) }
    var isRequestingTrakt by remember { mutableStateOf(false) }
    var aniSignedIn by remember { mutableStateOf(aniListManager.isSignedIn) }
    var aniUser by remember { mutableStateOf(aniListManager.username ?: "AniList Member") }
    var aniScrobble by remember { mutableStateOf(aniListManager.scrobbleEnabled) }
    var showAniTokenInput by remember { mutableStateOf(false) }
    var aniTokenText by remember { mutableStateOf("") }
    var isVerifyingAni by remember { mutableStateOf(false) }
    val progressStore = remember { com.vidlab.app.data.prefs.WatchProgressStore(context) }
    var sourceMode by remember { mutableStateOf(RcloneEngine.getSourceMode(context)) }
    var useRclone by remember { mutableStateOf(sourceMode == "rclone") }
    var webDavUrl by remember { mutableStateOf(RcloneEngine.getWebDavBaseUrl(context)) }
    var webDavUser by remember { mutableStateOf(RcloneEngine.getWebDavUsername(context)) }
    var webDavPass by remember { mutableStateOf(RcloneEngine.getWebDavPassword(context)) }
    var rcloneServeWebDav by remember { mutableStateOf(RcloneEngine.isRcloneServeWebDavEnabled(context)) }
    var rcloneServeUser by remember { mutableStateOf(RcloneEngine.getRcloneServeUsername(context)) }
    var rcloneServePass by remember { mutableStateOf(RcloneEngine.getRcloneServePassword(context)) }
    var rcloneRemotes by remember { mutableStateOf(RcloneEngine.getConfiguredRemotes(context)) }
    LaunchedEffect(sections) {
        sourceMode = RcloneEngine.getSourceMode(context); useRclone = sourceMode == "rclone"
        webDavUrl = RcloneEngine.getWebDavBaseUrl(context)
        rcloneRemotes = RcloneEngine.getConfiguredRemotes(context)
    }
    val drawerPrefs = remember { context.getSharedPreferences("vidlab_drawer_prefs", android.content.Context.MODE_PRIVATE) }
    var showMediaSourceSection by remember { mutableStateOf(drawerPrefs.getBoolean("show_media_source_section", true)) }
    var showRcloneRemotes by remember { mutableStateOf(drawerPrefs.getBoolean("show_rclone_remotes", false)) }
    var showPasteConfDialog by remember { mutableStateOf(false) }
    var pasteConfText by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) }
    val installedVersion = remember { AppUpdater.getCurrentVersionName(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
    var availableRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }

    var drawerPendingSelectRemotes by remember { mutableStateOf<List<RcloneRemoteConfig>?>(null) }
    val pickConfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()?.let { content ->
                RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    rcloneRemotes = list
                    useRclone = true
                    drawerPendingSelectRemotes = list
                }.onFailure { err ->
                    Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    var isSignedIn by remember { mutableStateOf(simklManager.isSignedIn) }
    var simklUser by remember { mutableStateOf(simklManager.username ?: "Simkl Member") }
    var scrobbleActive by remember { mutableStateOf(simklManager.scrobbleEnabled) }
    LaunchedEffect(selectedTab) {
        isSignedIn = simklManager.isSignedIn
        simklUser = simklManager.username ?: "Simkl Member"
        scrobbleActive = simklManager.scrobbleEnabled
    }
    var pinSession by remember { mutableStateOf<SimklPinSession?>(null) }
    var isRequestingPin by remember { mutableStateOf(false) }
    var pinPollStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pinSession) {
        val activeSession = pinSession ?: return@LaunchedEffect
        pinPollStatus = "Waiting for approval on simkl.com/pin..."
        var waitSeconds = activeSession.interval.coerceAtLeast(4).toLong()
        val deadline = System.currentTimeMillis() + (activeSession.expiresIn.coerceAtLeast(120) * 1000L)
        while (pinSession != null && System.currentTimeMillis() < deadline) {
            delay(waitSeconds * 1000L)
            if (pinSession == null) break
            when (val st = simklManager.pollPinStatus(activeSession)) {
                is SimklPollState.Authorized -> {
                    isSignedIn = true
                    simklUser = simklManager.username ?: "Simkl Member"
                    scrobbleActive = simklManager.scrobbleEnabled
                    pinSession = null
                    pinPollStatus = null
                    onSimklToggle(true)
                    Toast.makeText(context, "Connected to Simkl as $simklUser", Toast.LENGTH_SHORT).show()
                    break
                }
                is SimklPollState.Pending -> {
                    if (st.slowDown) waitSeconds += 2L
                    pinPollStatus = "Waiting for approval on simkl.com/pin..."
                }
                is SimklPollState.Expired -> { pinPollStatus = st.message; pinSession = null; break }
                is SimklPollState.Error -> { pinPollStatus = "Simkl error: ${st.message}"; pinSession = null; break }
            }
        }
    }

    var defaultSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var seekStep by remember { mutableIntStateOf(playerPrefs.seekStepSeconds) }
    var resizeMode by remember { mutableIntStateOf(playerPrefs.defaultResizeMode) }
    var autoEnglish by remember { mutableStateOf(playerPrefs.autoEnglishTracks) }
    var preferFfmpeg by remember { mutableStateOf(playerPrefs.preferFfmpegAudio) }
    var decoderMode by remember { mutableIntStateOf(playerPrefs.decoderMode) }
    var longPressBoost by remember { mutableStateOf(playerPrefs.longPressBoostEnabled) }
    var showRenamerConfirmDialog by remember { mutableStateOf(false) }
    var volGesture by remember { mutableStateOf(playerPrefs.volumeGestureEnabled) }
    var brightGesture by remember { mutableStateOf(playerPrefs.brightnessGestureEnabled) }
    var skipEnabled by remember { mutableStateOf(playerPrefs.skipSegmentsEnabled) }
    var autoSkip by remember { mutableStateOf(playerPrefs.autoSkipSegments) }
    var autoDeleteWatched by remember { mutableStateOf(playerPrefs.autoDeleteAfterWatch) }
    var cloudflareDns by remember { mutableStateOf(playerPrefs.cloudflareDnsEnabled) }
    var subSize by remember { mutableFloatStateOf(playerPrefs.subtitleFontSizeSp) }
    var subColorIdx by remember { mutableIntStateOf(playerPrefs.subtitleColorIndex) }
    var subBottomPct by remember { mutableIntStateOf(playerPrefs.subtitleBottomPaddingPct) }
    var subBgBox by remember { mutableStateOf(playerPrefs.subtitleBgBox) }

    val downloadTasks = DownloadTaskManager.tasks
    val activeDownloadCount = downloadTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

    if (showPasteConfDialog) {
        AlertDialog(
            onDismissRequest = { showPasteConfDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Paste rclone.conf", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = pasteConfText,
                    onValueChange = { pasteConfText = it },
                    placeholder = { Text("[gdrive]\ntype = drive\n...", color = Color(0xFF64748B), fontSize = 12.sp) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentViolet,
                        unfocusedBorderColor = BorderPurple,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    RcloneEngine.importConfigText(context, pasteConfText).onSuccess { list ->
                        rcloneRemotes = list
                        useRclone = true
                        showPasteConfDialog = false
                        drawerPendingSelectRemotes = list
                    }.onFailure { err ->
                        Toast.makeText(context, err.message ?: "Invalid config", Toast.LENGTH_LONG).show()
                    }
                }) { Text("Save", color = AccentViolet, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showPasteConfDialog = false }) { Text("Cancel", color = Color(0xFF94A3B8)) }
            }
        )
    }

    drawerPendingSelectRemotes?.let { list ->
        AlertDialog(
            onDismissRequest = { drawerPendingSelectRemotes = null },
            containerColor = SurfaceDark,
            title = { Text("Select Remotes to Use", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Choose which remotes VidLab should scan and stream from:", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    list.forEachIndexed { idx, rem ->
                        Surface(
                            color = DeepPurpleBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.8.dp, if (rem.enabled) AccentViolet else BorderPurple),
                            onClick = {
                                drawerPendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = !r.enabled) else r }
                            }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rem.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text("Type: ${rem.type}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                                Checkbox(
                                    checked = rem.enabled,
                                    onCheckedChange = { checked ->
                                        drawerPendingSelectRemotes = list.mapIndexed { i, r -> if (i == idx) r.copy(enabled = checked) else r }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentViolet, uncheckedColor = Color(0xFF94A3B8))
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    RcloneEngine.saveConfiguredRemotes(context, list)
                    RcloneEngine.setRcloneModeEnabled(context, true)
                    RcloneEngine.restartServeIfNeeded(context)
                    rcloneRemotes = list
                    drawerPendingSelectRemotes = null
                    onRescanClick()
                }) { Text("Save & Scan", color = AccentViolet, fontWeight = FontWeight.Bold) }
            }
        )
    }

    if (showRenamerConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRenamerConfirmDialog = false },
            containerColor = SurfaceDark,
            title = { Text("Run Vault Organizer & Renamer?", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "What this does on your connected storage:\n\n" +
                    "• Scans unorganized video files outside /VidLab/ and matches official titles & years via TMDB.\n" +
                    "• Renames & moves files into canonical folders: /VidLab/{Movies,Shows,Anime,KDrama,Documentaries,Animation}/Title (Year)/ (with Season XX subfolders for series).\n" +
                    "• Leaves already-organized files inside /VidLab/ untouched.\n" +
                    "• Sends ambiguous or low-confidence matches to the Needs Input tab for manual review.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenamerConfirmDialog = false
                    onRunRenamerClick()
                }) { Text("Proceed & Organize", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showRenamerConfirmDialog = false }) { Text("Cancel", color = Color(0xFF94A3B8)) }
            }
        )
    }

    ModalDrawerSheet(
        drawerContainerColor = DeepPurpleBg,
        drawerContentColor = Color.White,
        modifier = Modifier.width(330.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VidLab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    val headerMode = sourceMode
                    Text(
                        when (headerMode) {
                            "local" -> "Local Storage • Media Center"
                            "rclone" -> "Rclone Engine • Media Center"
                            else -> "WebDAV • Media Center"
                        },
                        color = Color(0xFF94A3B8), fontSize = 11.sp
                    )
                }
                Surface(color = SurfaceDark, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.8.dp, BorderPurple)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50)))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                        when (sourceMode) {
                            "local" -> "Local"
                            "rclone" -> "Rclone"
                            else -> "WebDAV"
                        },
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold
                    )
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfaceDark).border(0.8.dp, BorderPurple, RoundedCornerShape(12.dp)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val tabs = listOf("Settings", "Source", "Recent", if (activeDownloadCount > 0) "Downloads ($activeDownloadCount)" else "Downloads")
                tabs.forEachIndexed { index, label ->
                    val isSelected = selectedTab == index
                    Surface(
                        color = if (isSelected) AccentViolet else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(if (index == 3 && activeDownloadCount > 0) 1.35f else 1f).clickable { selectedTab = index }
                    ) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(label, color = if (isSelected) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (selectedTab) {
                    1 -> {
                        Text("MEDIA SOURCE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0E17)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Surface(
                                            color = if (sourceMode == "rclone") AccentViolet else BorderPurple,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).clickable { sourceMode = "rclone"; useRclone = true; RcloneEngine.setSourceMode(context, "rclone"); RcloneEngine.restartServeIfNeeded(context); onRescanClick() }
                                        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("Rclone", color = if (sourceMode == "rclone") Color.White else Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
                                        Surface(
                                            color = if (sourceMode == "webdav") AccentViolet else BorderPurple,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).clickable {
                                                useRclone = false
                                                sourceMode = "webdav"; RcloneEngine.setSourceMode(context, "webdav")
                                                RcloneEngine.stopServer()
                                                onRescanClick()
                                            }
                                        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("WebDAV", color = if (sourceMode == "webdav") Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                                        Surface(
                                            color = if (sourceMode == "local") AccentViolet else BorderPurple,
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.weight(1f).clickable {
                                                useRclone = false
                                                sourceMode = "local"; RcloneEngine.setSourceMode(context, "local")
                                                RcloneEngine.stopServer()
                                                com.vidlab.app.data.remote.DownloadTaskManager.ensureVidLabCategoryFolders(context)
                                                onRescanClick()
                                            }
                                        ) { Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("Local", color = if (sourceMode == "local") Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) } }
                                    }
                                    if (sourceMode == "local") {
                                        var localScope by remember {
                                            mutableStateOf(
                                                context.getSharedPreferences("vidlab_offline_prefs", android.content.Context.MODE_PRIVATE)
                                                    .getString("local_scan_mode", "full") ?: "full"
                                            )
                                        }
                                        Text("LOCAL SCAN SCOPE", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                            listOf(
                                                Pair("vidlab_only", "VidLab Folder Only"),
                                                Pair("full", "Full Internal Storage")
                                            ).forEach { (sKey, sLabel) ->
                                                val isScopeSel = localScope == sKey
                                                Surface(
                                                    color = if (isScopeSel) AccentViolet else BorderPurple,
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.weight(1f).clickable {
                                                        localScope = sKey
                                                        context.getSharedPreferences("vidlab_offline_prefs", android.content.Context.MODE_PRIVATE)
                                                            .edit().putString("local_scan_mode", sKey).apply()
                                                        onRescanClick()
                                                    }
                                                ) {
                                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                                        Text(sLabel, color = if (isScopeSel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (sourceMode == "rclone") {
                                        Text("Embedded Rclone scans, streams & organizes your selected remotes directly.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Button(
                                                onClick = { pickConfLauncher.launch(arrayOf("*/*")) },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Import rclone.conf", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                            OutlinedButton(
                                                onClick = {
                                                    val found = listOf(File("/storage/emulated/0/Download/rclone.conf"), File("/storage/emulated/0/rclone.conf")).firstOrNull { it.exists() && it.canRead() }
                                                    if (found != null) {
                                                        RcloneEngine.importConfigText(context, found.readText()).onSuccess { list ->
                                                            rcloneRemotes = list; useRclone = true
                                                            drawerPendingSelectRemotes = list
                                                        }
                                                    } else {
                                                        pasteConfText = if (RcloneEngine.getConfigFile(context).exists()) RcloneEngine.getConfigFile(context).readText() else ""
                                                        showPasteConfDialog = true
                                                    }
                                                },
                                                border = BorderStroke(1.dp, BorderPurple),
                                                shape = RoundedCornerShape(8.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) { Text("Paste / Auto-Find", color = Color.White, fontSize = 11.sp) }
                                        }

                                        HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("WEBDAV ACCESS & PRIVACY (PORT 18080)", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                                listOf(
                                                    Pair(false, "Same Device (Private)"),
                                                    Pair(true, "LAN (Wi-Fi / Hotspot)")
                                                ).forEach { (lanFlag, label) ->
                                                    val isSel = rcloneServeWebDav == lanFlag
                                                    Surface(
                                                        color = if (isSel) AccentViolet else BorderPurple,
                                                        shape = RoundedCornerShape(8.dp),
                                                        modifier = Modifier.weight(1f).clickable {
                                                            if (rcloneServeWebDav != lanFlag) {
                                                                rcloneServeWebDav = lanFlag
                                                                RcloneEngine.setRcloneServeWebDavEnabled(context, lanFlag)
                                                                RcloneEngine.restartServeIfNeeded(context)
                                                            }
                                                        }
                                                    ) {
                                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                                            Text(label, color = if (isSel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                                        }
                                                    }
                                                }
                                            }
                                            Text(
                                                text = if (rcloneServeWebDav) "LAN mode: 0.0.0.0:18080 active with background service notification." else "Same Device mode: 127.0.0.1:18080 on-demand only, hidden from LAN & no notification.",
                                                color = Color(0xFF94A3B8),
                                                fontSize = 11.sp
                                            )
                                        }
                                        if (rcloneServeWebDav) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                OutlinedTextField(
                                                    value = rcloneServeUser,
                                                    onValueChange = { newVal: String -> rcloneServeUser = newVal; RcloneEngine.setRcloneServeCredentials(context, newVal, rcloneServePass) },
                                                    label = { Text("Username (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) },
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                                    modifier = Modifier.weight(1f)
                                                )
                                                OutlinedTextField(
                                                    value = rcloneServePass,
                                                    onValueChange = { newVal: String -> rcloneServePass = newVal; RcloneEngine.setRcloneServeCredentials(context, rcloneServeUser, newVal) },
                                                    label = { Text("Password (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) },
                                                    singleLine = true,
                                                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { RcloneEngine.restartServeIfNeeded(context); Toast.makeText(context, "Rclone WebDAV server restarted", Toast.LENGTH_SHORT).show() },
                                                border = BorderStroke(1.dp, BorderPurple),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth().height(34.dp)
                                            ) { Text("Apply Auth & Restart Server", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                                        }
                                        if (rcloneRemotes.isNotEmpty()) {
                                            HorizontalDivider(color = BorderPurple, thickness = 1.dp)
                                            Row(
                                                modifier = Modifier.fillMaxWidth().clickable { showRcloneRemotes = !showRcloneRemotes; drawerPrefs.edit().putBoolean("show_rclone_remotes", showRcloneRemotes).apply() }.padding(vertical = 2.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Remotes (${rcloneRemotes.count { it.enabled }}/${rcloneRemotes.size} active)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                Surface(color = BorderPurple, shape = RoundedCornerShape(6.dp)) {
                                                    Text(if (showRcloneRemotes) "Hide" else "Show", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                                                }
                                            }
                                            if (showRcloneRemotes) {
                                                rcloneRemotes.forEachIndexed { idx, remote ->
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                                            .background(if (remote.enabled) BorderPurple.copy(alpha = 0.55f) else Color.Transparent)
                                                            .clickable {
                                                                val updated = rcloneRemotes.toMutableList(); updated[idx] = remote.copy(enabled = !remote.enabled)
                                                                rcloneRemotes = updated; RcloneEngine.saveConfiguredRemotes(context, updated); RcloneEngine.restartServeIfNeeded(context)
                                                            }.padding(horizontal = 8.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Text("${remote.name}:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                                            Text("type = ${remote.type}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                        }
                                                        Checkbox(
                                                            checked = remote.enabled,
                                                            onCheckedChange = { checked ->
                                                                val updated = rcloneRemotes.toMutableList(); updated[idx] = remote.copy(enabled = checked)
                                                                rcloneRemotes = updated; RcloneEngine.saveConfiguredRemotes(context, updated); RcloneEngine.restartServeIfNeeded(context)
                                                            },
                                                            colors = CheckboxDefaults.colors(checkedColor = AccentViolet, checkmarkColor = Color.White, uncheckedColor = Color(0xFF94A3B8))
                                                        )
                                                    }
                                                }
                                                var showRemoveConfDialog by remember { mutableStateOf(false) }
                                                Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                                                    Text(
                                                        text = "Remove Config",
                                                        color = Color(0xFF94A3B8),
                                                        fontSize = 11.sp,
                                                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showRemoveConfDialog = true }.padding(horizontal = 8.dp, vertical = 4.dp)
                                                    )
                                                }
                                                if (showRemoveConfDialog) {
                                                    AlertDialog(
                                                        onDismissRequest = { showRemoveConfDialog = false },
                                                        containerColor = SurfaceDark,
                                                        title = { Text("Remove Rclone Config?", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                                                        text = { Text("This deletes the imported rclone.conf and clears saved remotes from VidLab.", color = Color(0xFFCBD5E1), fontSize = 12.sp) },
                                                        confirmButton = {
                                                            TextButton(onClick = {
                                                                showRemoveConfDialog = false
                                                                RcloneEngine.removeConfig(context)
                                                                rcloneRemotes = emptyList()
                                                                useRclone = false
                                                                sourceMode = "local"
                                                                RcloneEngine.setSourceMode(context, "local")
                                                                onRescanClick()
                                                            }) { Text("Remove", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold) }
                                                        },
                                                        dismissButton = { TextButton(onClick = { showRemoveConfDialog = false }) { Text("Cancel", color = Color(0xFF94A3B8)) } }
                                                    )
                                                }
                                            }
                                        }
                                    } else if (sourceMode == "webdav") {
                                        OutlinedTextField(
                                            value = webDavUrl, onValueChange = { webDavUrl = it },
                                            label = { Text("WebDAV Server URL", color = Color(0xFF94A3B8), fontSize = 11.sp) },
                                            singleLine = true,
                                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = webDavUser,
                                                onValueChange = { newVal: String -> webDavUser = newVal },
                                                label = { Text("Username (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                                modifier = Modifier.weight(1f)
                                            )
                                            OutlinedTextField(
                                                value = webDavPass,
                                                onValueChange = { newVal: String -> webDavPass = newVal },
                                                label = { Text("Password (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) },
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = AccentViolet, unfocusedBorderColor = BorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                                modifier = Modifier.weight(1f)
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                            Button(
                                                onClick = { RcloneEngine.setWebDavBaseUrl(context, webDavUrl); RcloneEngine.setWebDavCredentials(context, webDavUser, webDavPass); RcloneEngine.setRcloneModeEnabled(context, false); RcloneEngine.stopServer(); onRescanClick() },
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentViolet), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(38.dp)
                                            ) { Text("Save & Scan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                            OutlinedButton(onClick = onBrowseStorageClick, border = BorderStroke(1.dp, BorderPurple), shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp)) { Text("Presets", color = Color.White, fontSize = 12.sp) }
                                        }
                                    }
                                    HorizontalDivider(color = BorderPurple, thickness = 1.dp)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        var isRescanSpinning by remember { mutableStateOf(false) }
                                        val spinAngle by androidx.compose.animation.core.animateFloatAsState(
                                            targetValue = if (isRescanSpinning) 360f else 0f,
                                            animationSpec = androidx.compose.animation.core.tween(650),
                                            finishedListener = { isRescanSpinning = false },
                                            label = "rescanSpin"
                                        )
                                        Button(
                                            onClick = {
                                                isRescanSpinning = true
                                                if (useRclone) RcloneEngine.restartServeIfNeeded(context)
                                                onRescanClick()
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1f).height(40.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Refresh,
                                                contentDescription = null,
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = spinAngle }
                                            )
                                            Spacer(modifier = Modifier.width(5.dp))
                                            Text("Rescan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                if (isOrganizerRunning || organizerStatusLabel.isNotBlank()) {
                                                    onRunRenamerClick()
                                                } else {
                                                    showRenamerConfirmDialog = true
                                                }
                                            },
                                            border = BorderStroke(1.dp, if (isOrganizerRunning) Color(0xFF4CAF50) else AccentViolet),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                            modifier = Modifier.weight(1f).height(40.dp)
                                        ) {
                                            Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = if (isOrganizerRunning) Color(0xFF4CAF50) else AccentViolet, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(5.dp))
                                            val btnText = when {
                                                organizerStatusLabel.isNotBlank() -> organizerStatusLabel
                                                sourceMode == "rclone" -> "Rclone Renamer"
                                                sourceMode == "local" -> "Local Renamer"
                                                else -> "WebDAV Renamer"
                                            }
                                            Text(btnText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }

                    2 -> {
                        val rev = com.vidlab.app.data.prefs.WatchProgressStore.revision.intValue
                        val historyList = remember(sections, rev) {
                            progressStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("RECENT WATCH HISTORY (${historyList.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            if (historyList.isNotEmpty()) {
                                Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { progressStore.clearHistory() })
                            }
                        }
                        if (historyList.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No watch history yet. Play any movie or episode and it will appear here.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (entry in historyList) {
                                    Surface(onClick = { onSelectHistoryItem(entry.item) }, color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                            coil.compose.AsyncImage(
                                                model = entry.item.posterUrl ?: entry.item.backdropUrl,
                                                contentDescription = entry.item.title,
                                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                                modifier = Modifier.size(width = 44.dp, height = 64.dp).clip(RoundedCornerShape(8.dp))
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(entry.item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                val sub = buildString {
                                                    if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                                    if (entry.progress.watched) append("Watched") else append("${(entry.progress.fraction * 100).toInt()}% watched")
                                                }
                                                Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                                Spacer(modifier = Modifier.height(6.dp))
                                                LinearProgressIndicator(
                                                    progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                                    color = AccentViolet,
                                                    trackColor = BorderPurple,
                                                    modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp))
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    0 -> {
                        Text("NETWORK & DNS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                DrawerToggleRow("Cloudflare DNS (1.1.1.1)", "Route DNS queries through 1.1.1.1 to bypass ISP blocking.", cloudflareDns) {
                                    cloudflareDns = it; playerPrefs.cloudflareDnsEnabled = it
                                }
                            }
                        }

                        Text("PLAYER SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Default Playback Speed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                        val selected = kotlin.math.abs(defaultSpeed - spd) < 0.01f
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { defaultSpeed = spd; playerPrefs.defaultSpeed = spd }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${spd}x", color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Double-Tap Seek Step", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(5, 10, 15, 30).forEach { sec ->
                                        val selected = seekStep == sec
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { seekStep = sec; playerPrefs.seekStepSeconds = sec }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${sec}s", color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Default Aspect Ratio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(0 to "Fit", 4 to "Zoom", 3 to "Fill").forEach { (mode, label) ->
                                        val selected = resizeMode == mode
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { resizeMode = mode; playerPrefs.defaultResizeMode = mode }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text(label, color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                DrawerToggleRow("Auto English Audio & Subs", "Prefer EN tracks automatically", autoEnglish) { autoEnglish = it; playerPrefs.autoEnglishTracks = it }
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Video & Audio Decoder", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        when (decoderMode) {
                                            1 -> "HW Only: Lowest battery & heat; may fail on 10-bit Hi10P or DTS."
                                            2 -> "SW / FFmpeg: Fixes green artifacts & unsupported audio; higher CPU use."
                                            else -> "Auto: Hardware video + FFmpeg audio fallback (recommended)."
                                        },
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                        listOf("Auto", "HW Only", "SW / FFmpeg").forEachIndexed { idx, label ->
                                            val sel = decoderMode == idx
                                            Surface(
                                                color = if (sel) AccentViolet else BorderPurple.copy(alpha = 0.6f),
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f).clickable {
                                                    decoderMode = idx
                                                    playerPrefs.decoderMode = idx
                                                    preferFfmpeg = (idx == 2)
                                                    playerPrefs.preferFfmpegAudio = (idx == 2)
                                                }
                                            ) {
                                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) {
                                                    Text(label, color = if (sel) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
                                DrawerToggleRow("Long-Press 2x Speed Boost", "Hold screen during playback for temporary 2.0x speed", longPressBoost) {
                                    longPressBoost = it; playerPrefs.longPressBoostEnabled = it
                                }
                                DrawerToggleRow("Volume Swipe Gesture", "Swipe vertically on right side", volGesture) { volGesture = it; playerPrefs.volumeGestureEnabled = it }
                                DrawerToggleRow("Brightness Swipe Gesture", "Swipe vertically on left side", brightGesture) { brightGesture = it; playerPrefs.brightnessGestureEnabled = it }
                                DrawerToggleRow("Auto-Delete Offline After Watch", "Remove local offline copy after finishing ≥ 90%", autoDeleteWatched) { autoDeleteWatched = it; playerPrefs.autoDeleteAfterWatch = it }
                            }
                        }

                        Text("SUBTITLE & SKIP SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("Subtitle Text Size", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(22f, 28f, 36f, 46f).forEach { sz ->
                                        val selected = kotlin.math.abs(subSize - sz) < 0.5f
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subSize = sz; playerPrefs.subtitleFontSizeSp = sz }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("${sz.toInt()}sp", color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Subtitle Color", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf("White", "Yellow", "Cyan", "Green").forEachIndexed { idx, label ->
                                        val selected = subColorIdx == idx
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subColorIdx = idx; playerPrefs.subtitleColorIndex = idx }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text(label, color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                Text("Bottom Margin", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                    listOf(2, 5, 8, 12).forEach { pct ->
                                        val selected = subBottomPct == pct
                                        Surface(color = if (selected) AccentViolet else BorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subBottomPct = pct; playerPrefs.subtitleBottomPaddingPct = pct }) {
                                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                                Text("$pct%", color = if (selected) Color.White else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                                DrawerToggleRow("Dark Background Box", "Improve readability on bright scenes", subBgBox) { subBgBox = it; playerPrefs.subtitleBgBox = it }
                                DrawerToggleRow("Show Skip Intro/Outro", "Fetch segments & show skip button", skipEnabled) { skipEnabled = it; playerPrefs.skipSegmentsEnabled = it }
                                DrawerToggleRow("Auto-Skip Intro & Outro", "Jump past segments automatically", autoSkip) { autoSkip = it; playerPrefs.autoSkipSegments = it }
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                defaultSpeed = 1.0f; playerPrefs.defaultSpeed = 1.0f
                                seekStep = 10; playerPrefs.seekStepSeconds = 10
                                resizeMode = 0; playerPrefs.defaultResizeMode = 0
                                autoEnglish = true; playerPrefs.autoEnglishTracks = true
                                preferFfmpeg = false; playerPrefs.preferFfmpegAudio = false; decoderMode = 0; playerPrefs.decoderMode = 0; longPressBoost = true; playerPrefs.longPressBoostEnabled = true
                                volGesture = true; playerPrefs.volumeGestureEnabled = true
                                brightGesture = true; playerPrefs.brightnessGestureEnabled = true
                                subSize = 28f; playerPrefs.subtitleFontSizeSp = 28f
                                subColorIdx = 0; playerPrefs.subtitleColorIndex = 0
                                subBottomPct = 5; playerPrefs.subtitleBottomPaddingPct = 5
                                subBgBox = false; playerPrefs.subtitleBgBox = false
                                skipEnabled = true; playerPrefs.skipSegmentsEnabled = true
                                autoSkip = false; playerPrefs.autoSkipSegments = false
                                autoDeleteWatched = false; playerPrefs.autoDeleteAfterWatch = false
                                cloudflareDns = false; playerPrefs.cloudflareDnsEnabled = false
                                Toast.makeText(context, "Settings reset to default", Toast.LENGTH_SHORT).show()
                            },
                            border = BorderStroke(1.dp, BorderPurple),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth().height(42.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset Settings to Default", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }

                        Text("TRACKING & SYNC", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        UnifiedTrackingCard(onSimklToggle = onSimklToggle)
                        Text("APP UPDATES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Card(colors = CardDefaults.cardColors(containerColor = SurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("VidLab Android", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Installed: v$installedVersion", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = {
                                            if (!isCheckingUpdate && !isDownloadingUpdate) {
                                                isCheckingUpdate = true
                                                updateStatusMessage = null
                                                scope.launch {
                                                    AppUpdater.checkForUpdate(context)
                                                        .onSuccess { info ->
                                                            availableRelease = info
                                                            updateStatusMessage = if (info.isNewer) {
                                                                "New version ${info.tagName} is available!"
                                                            } else {
                                                                "You're on the latest version (${info.tagName})."
                                                            }
                                                        }
                                                        .onFailure { err ->
                                                            updateStatusMessage = "Update check failed: ${err.message}"
                                                        }
                                                    isCheckingUpdate = false
                                                }
                                            }
                                        },
                                        enabled = !isCheckingUpdate && !isDownloadingUpdate,
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet, contentColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            if (isCheckingUpdate) "Checking..." else "Check Updates",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (updateStatusMessage != null) {
                                    Text(
                                        text = updateStatusMessage!!,
                                        color = if (availableRelease?.isNewer == true) AccentViolet else Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                val rel = availableRelease
                                if (rel != null && rel.isNewer) {
                                    if (rel.body.isNotBlank()) {
                                        Text(
                                            text = rel.body,
                                            color = Color(0xFFCBD5E1),
                                            fontSize = 11.sp,
                                            maxLines = 5,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isDownloadingUpdate) {
                                        LinearProgressIndicator(
                                            progress = { updateDownloadProgress },
                                            color = AccentViolet,
                                            trackColor = BorderPurple,
                                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            if (!isDownloadingUpdate) {
                                                isDownloadingUpdate = true
                                                updateDownloadProgress = 0f
                                                scope.launch {
                                                    AppUpdater.downloadAndInstallApk(
                                                        context = context,
                                                        downloadUrl = rel.apkDownloadUrl,
                                                        onProgress = { pct -> updateDownloadProgress = pct }
                                                    ).onFailure { err ->
                                                        updateStatusMessage = "Download failed: ${err.message}"
                                                    }
                                                    isDownloadingUpdate = false
                                                }
                                            }
                                        },
                                        enabled = !isDownloadingUpdate,
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet, contentColor = Color.White),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(38.dp)
                                    ) {
                                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            if (isDownloadingUpdate) "Downloading ${(updateDownloadProgress * 100).toInt()}%..." else "Download & Install ${rel.tagName}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("LEGAL & LICENSES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(0.8.dp, BorderPurple),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    "VidLab is a bring-your-own-media player and cloud client. It does not host, index, or distribute any media content.",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                                HorizontalDivider(color = BorderPurple.copy(alpha = 0.6f))
                                Text(
                                    "Metadata & Sync: This product uses the TMDB API and Trakt, but is not endorsed or certified by TMDB or Trakt. Additional metadata and sync powered by Simkl, AniList, AniSkip, and TheIntroDB.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                                Text(
                                    "Open-Source Licenses: VidLab is licensed under GPL-3.0. Uses FFmpeg (LGPLv2.1+), Rclone (MIT), and AndroidX Media3 (Apache 2.0).",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                    3 -> {
                        val failedCount = downloadTasks.count { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("DOWNLOADS (${downloadTasks.size})", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (failedCount > 0) {
                                    Text("Retry Failed", color = Color(0xFF60A5FA), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { DownloadTaskManager.retryAllFailed(context) })
                                }
                                if (downloadTasks.isNotEmpty()) {
                                    Text("Clear", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { DownloadTaskManager.clearFinished() })
                                }
                            }
                        }
                        if (downloadTasks.isEmpty()) {
                            Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text("No active downloads. Long-press an episode or use the 3-dot menu to save to /storage/emulated/0/VidLab.", color = Color(0xFF94A3B8), fontSize = 12.sp, modifier = Modifier.padding(14.dp))
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (task in downloadTasks) {
                                    Surface(color = SurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, BorderPurple), modifier = Modifier.fillMaxWidth()) {
                                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(task.fileName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text("VidLab/${task.folderName}", color = Color(0xFF94A3B8), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) {
                                                    IconButton(onClick = { DownloadTaskManager.cancelTask(context, task.id) }, modifier = Modifier.size(26.dp)) {
                                                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color(0xFF94A3B8), modifier = Modifier.size(16.dp))
                                                    }
                                                } else if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                                                    IconButton(onClick = { DownloadTaskManager.retryTask(context, task.id) }, modifier = Modifier.size(26.dp)) {
                                                        Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFF60A5FA), modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                            LinearProgressIndicator(
                                                progress = { task.progressFraction },
                                                color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet },
                                                trackColor = BorderPurple,
                                                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                                            )
                                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                val dlStr = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                                                val totStr = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                                                Text("$dlStr$totStr", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                                val statusLabel = when (task.status) {
                                                    DownloadStatus.QUEUED -> "Queued"
                                                    DownloadStatus.DOWNLOADING -> "${(task.progressFraction * 100).toInt()}%"
                                                    DownloadStatus.COMPLETED -> "Completed"
                                                    DownloadStatus.FAILED -> "Failed"
                                                    DownloadStatus.CANCELLED -> "Cancelled"
                                                }
                                                Text(statusLabel, color = when (task.status) { DownloadStatus.COMPLETED -> Color(0xFF4CAF50); DownloadStatus.FAILED -> Color(0xFFEF5350); else -> AccentViolet }, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun DrawerToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Color(0xFF3B6CB7), uncheckedThumbColor = Color(0xFF8E9199), uncheckedTrackColor = Color(0xFF1F2737), uncheckedBorderColor = Color(0xFF2D3A52)))
    }
}
