package com.vidlab.app.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.data.remote.DownloadStatus
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig

@Suppress("UNUSED_PARAMETER")
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
    sections: List<MediaFeedSection> = emptyList(),
    onSelectHistoryItem: (ScrapedMediaItem) -> Unit = {},
    onOpenSetupGuide: () -> Unit = {}
) {
    val context = LocalContext.current
    var sourceMode by remember { mutableStateOf(RcloneEngine.getSourceMode(context)) }
    var rcloneRemotes by remember { mutableStateOf(RcloneEngine.getConfiguredRemotes(context)) }
    LaunchedEffect(sections) {
        sourceMode = RcloneEngine.getSourceMode(context)
        rcloneRemotes = RcloneEngine.getConfiguredRemotes(context)
    }

    var selectedTab by remember { mutableIntStateOf(0) }
    var showPasteConfDialog by remember { mutableStateOf(false) }
    var pasteConfText by remember { mutableStateOf("") }
    var drawerPendingSelectRemotes by remember { mutableStateOf<List<RcloneRemoteConfig>?>(null) }
    var showRenamerConfirmDialog by remember { mutableStateOf(false) }

    val downloadTasks = DownloadTaskManager.tasks
    val activeDownloadCount = downloadTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED }

    if (showPasteConfDialog) {
        PasteRcloneConfDialog(
            initialText = pasteConfText,
            onDismiss = { showPasteConfDialog = false },
            onSaved = { list -> rcloneRemotes = list; sourceMode = "rclone"; showPasteConfDialog = false; drawerPendingSelectRemotes = list }
        )
    }
    drawerPendingSelectRemotes?.let { list ->
        SelectRemotesDialog(
            initialRemotes = list,
            onDismiss = { drawerPendingSelectRemotes = null },
            onConfirm = { updated -> rcloneRemotes = updated; drawerPendingSelectRemotes = null; onRescanClick() }
        )
    }
    if (showRenamerConfirmDialog) {
        RenamerConfirmDialog(
            onDismiss = { showRenamerConfirmDialog = false },
            onConfirm = { showRenamerConfirmDialog = false; onRunRenamerClick() }
        )
    }

    ModalDrawerSheet(drawerContainerColor = DrawerDeepBg, drawerContentColor = Color.White, modifier = Modifier.width(330.dp)) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("VidLab", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(when (sourceMode) { "local" -> "Local Storage • Media Center"; "rclone" -> "Rclone Engine • Media Center"; else -> "WebDAV • Media Center" }, color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Surface(color = DrawerSurfaceDark, shape = RoundedCornerShape(8.dp), border = BorderStroke(0.8.dp, DrawerBorderPurple)) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Box(modifier = Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF4CAF50)))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(when (sourceMode) { "local" -> "Local"; "rclone" -> "Rclone"; else -> "WebDAV" }, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(DrawerSurfaceDark).border(0.8.dp, DrawerBorderPurple, RoundedCornerShape(12.dp)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tabs = listOf("Settings", "Source", "Recent", if (activeDownloadCount > 0) "Downloads ($activeDownloadCount)" else "Downloads")
                tabs.forEachIndexed { index, label ->
                    val isSelected = selectedTab == index
                    Surface(color = if (isSelected) DrawerAccentViolet else Color.Transparent, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(if (index == 3 && activeDownloadCount > 0) 1.35f else 1f).clickable { selectedTab = index }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
                            Text(label, color = if (isSelected) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (selectedTab) {
                    0 -> DrawerSettingsTab(onSimklToggle = onSimklToggle, onOpenSetupGuide = onOpenSetupGuide)
                    1 -> DrawerSourceTab(
                        sourceMode = sourceMode,
                        onSourceModeChange = { sourceMode = it },
                        rcloneRemotes = rcloneRemotes,
                        onRemotesChange = { rcloneRemotes = it },
                        onPendingSelectRemotes = { drawerPendingSelectRemotes = it },
                        onShowPasteConf = { pasteConfText = it; showPasteConfDialog = true },
                        isOrganizerRunning = isOrganizerRunning,
                        organizerStatusLabel = organizerStatusLabel,
                        onShowRenamerConfirm = { showRenamerConfirmDialog = true },
                        onRescanClick = onRescanClick,
                        onRunRenamerClick = onRunRenamerClick,
                        onBrowseStorageClick = onBrowseStorageClick
                    )
                    2 -> DrawerRecentTab(sections = sections, onSelectHistoryItem = onSelectHistoryItem)
                    3 -> DrawerDownloadsTab()
                }
            }
        }
    }
}
