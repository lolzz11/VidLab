package com.vidlab.app.ui.catalog

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Refresh
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
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig
import java.io.File

@Composable
internal fun DrawerSourceTab(
    sourceMode: String,
    onSourceModeChange: (String) -> Unit,
    rcloneRemotes: List<RcloneRemoteConfig>,
    onRemotesChange: (List<RcloneRemoteConfig>) -> Unit,
    onPendingSelectRemotes: (List<RcloneRemoteConfig>) -> Unit,
    onShowPasteConf: (String) -> Unit,
    isOrganizerRunning: Boolean,
    organizerStatusLabel: String,
    onShowRenamerConfirm: () -> Unit,
    onShowFixVaultConfirm: () -> Unit,
    onRescanClick: () -> Unit,
    onRunRenamerClick: () -> Unit,
    onBrowseStorageClick: () -> Unit
) {
    val context = LocalContext.current
    val useRclone = sourceMode == "rclone"

    var webDavUrl by remember { mutableStateOf(RcloneEngine.getWebDavBaseUrl(context)) }
    var webDavUser by remember { mutableStateOf(RcloneEngine.getWebDavUsername(context)) }
    var webDavPass by remember { mutableStateOf(RcloneEngine.getWebDavPassword(context)) }
    var rcloneServeWebDav by remember { mutableStateOf(RcloneEngine.isRcloneServeWebDavEnabled(context)) }
    var rcloneServeUser by remember { mutableStateOf(RcloneEngine.getRcloneServeUsername(context)) }
    var rcloneServePass by remember { mutableStateOf(RcloneEngine.getRcloneServePassword(context)) }

    LaunchedEffect(sourceMode) { webDavUrl = RcloneEngine.getWebDavBaseUrl(context) }

    val drawerPrefs = remember { context.getSharedPreferences("vidlab_drawer_prefs", Context.MODE_PRIVATE) }
    var showRcloneRemotes by remember { mutableStateOf(drawerPrefs.getBoolean("show_rclone_remotes", false)) }
    var showRemoveConfDialog by remember { mutableStateOf(false) }

    val pickConfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }
                .getOrNull()?.let { content ->
                    RcloneEngine.importConfigText(context, content).onSuccess { list ->
                        onRemotesChange(list); onSourceModeChange("rclone"); onPendingSelectRemotes(list)
                    }.onFailure { err ->
                        Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }

    if (showRemoveConfDialog) {
        RemoveRcloneConfigDialog(
            onDismiss = { showRemoveConfDialog = false },
            onConfirm = {
                showRemoveConfDialog = false
                RcloneEngine.removeConfig(context)
                onRemotesChange(emptyList())
                onSourceModeChange("local")
                RcloneEngine.setSourceMode(context, "local")
                onRescanClick()
            }
        )
    }

    Text("MEDIA SOURCE", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Card(colors = CardDefaults.cardColors(containerColor = DrawerSurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF0A0E17)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(color = if (sourceMode == "rclone") DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { onSourceModeChange("rclone"); RcloneEngine.setSourceMode(context, "rclone"); RcloneEngine.restartServeIfNeeded(context); onRescanClick() }) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("Rclone", color = if (sourceMode == "rclone") Color.White else Color(0xFF94A3B8), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                Surface(color = if (sourceMode == "webdav") DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { onSourceModeChange("webdav"); RcloneEngine.setSourceMode(context, "webdav"); RcloneEngine.stopServer(); onRescanClick() }) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("WebDAV", color = if (sourceMode == "webdav") Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
                Surface(color = if (sourceMode == "local") DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { onSourceModeChange("local"); RcloneEngine.setSourceMode(context, "local"); RcloneEngine.stopServer(); DownloadTaskManager.ensureVidLabCategoryFolders(context); onRescanClick() }) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) { Text("Local", color = if (sourceMode == "local") Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                }
            }

            if (sourceMode == "local") {
                var localScope by remember { mutableStateOf(context.getSharedPreferences("vidlab_offline_prefs", Context.MODE_PRIVATE).getString("local_scan_mode", "full") ?: "full") }
                Text("LOCAL SCAN SCOPE", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(Pair("vidlab_only", "VidLab Folder Only"), Pair("full", "Full Internal Storage")).forEach { (sKey, sLabel) ->
                        val isScopeSel = localScope == sKey
                        Surface(color = if (isScopeSel) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { localScope = sKey; context.getSharedPreferences("vidlab_offline_prefs", Context.MODE_PRIVATE).edit().putString("local_scan_mode", sKey).apply(); onRescanClick() }) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) { Text(sLabel, color = if (isScopeSel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
                }
            }
            if (sourceMode == "rclone") {
                Text("Embedded Rclone scans, streams & organizes your selected remotes directly.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { pickConfLauncher.launch(arrayOf("*/*")) }, colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), modifier = Modifier.weight(1f)) {
                        Text("Import rclone.conf", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = {
                            val found = listOf(File("/storage/emulated/0/Download/rclone.conf"), File("/storage/emulated/0/rclone.conf")).firstOrNull { it.exists() && it.canRead() }
                            if (found != null) {
                                RcloneEngine.importConfigText(context, found.readText()).onSuccess { list -> onRemotesChange(list); onSourceModeChange("rclone"); onPendingSelectRemotes(list) }
                            } else {
                                onShowPasteConf(if (RcloneEngine.getConfigFile(context).exists()) RcloneEngine.getConfigFile(context).readText() else "")
                            }
                        },
                        border = BorderStroke(1.dp, DrawerBorderPurple), shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp), modifier = Modifier.weight(1f)
                    ) { Text("Paste / Auto-Find", color = Color.White, fontSize = 11.sp) }
                }
                HorizontalDivider(color = DrawerBorderPurple, thickness = 0.8.dp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("WEBDAV ACCESS & PRIVACY (PORT 18080)", color = Color(0xFF94A3B8), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(Pair(false, "Same Device (Private)"), Pair(true, "LAN (Wi-Fi / Hotspot)")).forEach { (lanFlag, label) ->
                            val isSel = rcloneServeWebDav == lanFlag
                            Surface(color = if (isSel) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable {
                                if (rcloneServeWebDav != lanFlag) { rcloneServeWebDav = lanFlag; RcloneEngine.setRcloneServeWebDavEnabled(context, lanFlag); RcloneEngine.restartServeIfNeeded(context) }
                            }) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) { Text(label, color = if (isSel) Color.White else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                            }
                        }
                    }
                    Text(if (rcloneServeWebDav) "LAN mode: 0.0.0.0:18080 active with background service notification." else "Same Device mode: 127.0.0.1:18080 on-demand only, hidden from LAN & no notification.", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                if (rcloneServeWebDav) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(value = rcloneServeUser, onValueChange = { rcloneServeUser = it; RcloneEngine.setRcloneServeCredentials(context, it, rcloneServePass) }, label = { Text("Username (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DrawerAccentViolet, unfocusedBorderColor = DrawerBorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.weight(1f))
                        OutlinedTextField(value = rcloneServePass, onValueChange = { rcloneServePass = it; RcloneEngine.setRcloneServeCredentials(context, rcloneServeUser, it) }, label = { Text("Password (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DrawerAccentViolet, unfocusedBorderColor = DrawerBorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.weight(1f))
                    }
                    OutlinedButton(onClick = { RcloneEngine.restartServeIfNeeded(context); Toast.makeText(context, "Rclone WebDAV server restarted", Toast.LENGTH_SHORT).show() }, border = BorderStroke(1.dp, DrawerBorderPurple), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().height(34.dp)) {
                        Text("Apply Auth & Restart Server", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (rcloneRemotes.isNotEmpty()) {
                    HorizontalDivider(color = DrawerBorderPurple, thickness = 1.dp)
                    Row(modifier = Modifier.fillMaxWidth().clickable { showRcloneRemotes = !showRcloneRemotes; drawerPrefs.edit().putBoolean("show_rclone_remotes", showRcloneRemotes).apply() }.padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Remotes (${rcloneRemotes.count { it.enabled }}/${rcloneRemotes.size} active)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Surface(color = DrawerBorderPurple, shape = RoundedCornerShape(6.dp)) { Text(if (showRcloneRemotes) "Hide" else "Show", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) }
                    }
                    if (showRcloneRemotes) {
                        rcloneRemotes.forEachIndexed { idx, remote ->
                            Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (remote.enabled) DrawerBorderPurple.copy(alpha = 0.55f) else Color.Transparent).clickable {
                                val updated = rcloneRemotes.toMutableList(); updated[idx] = remote.copy(enabled = !remote.enabled)
                                onRemotesChange(updated); RcloneEngine.saveConfiguredRemotes(context, updated); RcloneEngine.restartServeIfNeeded(context)
                            }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${remote.name}:", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("type = ${remote.type}", color = Color(0xFF94A3B8), fontSize = 10.sp)
                                }
                                Checkbox(checked = remote.enabled, onCheckedChange = { checked ->
                                    val updated = rcloneRemotes.toMutableList(); updated[idx] = remote.copy(enabled = checked)
                                    onRemotesChange(updated); RcloneEngine.saveConfiguredRemotes(context, updated); RcloneEngine.restartServeIfNeeded(context)
                                }, colors = CheckboxDefaults.colors(checkedColor = DrawerAccentViolet, checkmarkColor = Color.White, uncheckedColor = Color(0xFF94A3B8)))
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                            Text("Remove Config", color = Color(0xFF94A3B8), fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { showRemoveConfDialog = true }.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            } else if (sourceMode == "webdav") {
                OutlinedTextField(value = webDavUrl, onValueChange = { webDavUrl = it }, label = { Text("WebDAV Server URL", color = Color(0xFF94A3B8), fontSize = 11.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DrawerAccentViolet, unfocusedBorderColor = DrawerBorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(value = webDavUser, onValueChange = { webDavUser = it }, label = { Text("Username (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DrawerAccentViolet, unfocusedBorderColor = DrawerBorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.weight(1f))
                    OutlinedTextField(value = webDavPass, onValueChange = { webDavPass = it }, label = { Text("Password (opt)", color = Color(0xFF94A3B8), fontSize = 10.sp) }, singleLine = true, colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = DrawerAccentViolet, unfocusedBorderColor = DrawerBorderPurple, focusedTextColor = Color.White, unfocusedTextColor = Color.White), modifier = Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { RcloneEngine.setWebDavBaseUrl(context, webDavUrl); RcloneEngine.setWebDavCredentials(context, webDavUser, webDavPass); RcloneEngine.setRcloneModeEnabled(context, false); RcloneEngine.stopServer(); onRescanClick() }, colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet), shape = RoundedCornerShape(10.dp), modifier = Modifier.weight(1f).height(38.dp)) {
                        Text("Save & Scan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onBrowseStorageClick, border = BorderStroke(1.dp, DrawerBorderPurple), shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp)) { Text("Presets", color = Color.White, fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = DrawerBorderPurple, thickness = 1.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                var isRescanSpinning by remember { mutableStateOf(false) }
                val spinAngle by animateFloatAsState(targetValue = if (isRescanSpinning) 360f else 0f, animationSpec = tween(650), finishedListener = { isRescanSpinning = false }, label = "rescanSpin")
                Button(onClick = { isRescanSpinning = true; if (useRclone) RcloneEngine.restartServeIfNeeded(context); onRescanClick() }, colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.weight(1f).height(40.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = spinAngle })
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Rescan", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                OutlinedButton(onClick = { if (isOrganizerRunning || organizerStatusLabel.isNotBlank()) onRunRenamerClick() else onShowRenamerConfirm() }, border = BorderStroke(1.dp, if (isOrganizerRunning) Color(0xFF4CAF50) else DrawerAccentViolet), shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp), modifier = Modifier.weight(1f).height(40.dp)) {
                    Icon(Icons.Default.DriveFileRenameOutline, contentDescription = null, tint = if (isOrganizerRunning) Color(0xFF4CAF50) else DrawerAccentViolet, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(5.dp))
                    val btnText = when { organizerStatusLabel.isNotBlank() -> organizerStatusLabel; sourceMode == "rclone" -> "Rclone Renamer"; sourceMode == "local" -> "Local Renamer"; else -> "WebDAV Renamer" }
                    Text(btnText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedButton(
                onClick = { if (isOrganizerRunning || organizerStatusLabel.isNotBlank()) onRunRenamerClick() else onShowFixVaultConfirm() },
                border = BorderStroke(1.dp, DrawerBorderPurple),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth().height(38.dp)
            ) {
                Icon(Icons.Default.Build, contentDescription = null, tint = Color(0xFF60A5FA), modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Fix / Clean Up VidLab Folder", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
