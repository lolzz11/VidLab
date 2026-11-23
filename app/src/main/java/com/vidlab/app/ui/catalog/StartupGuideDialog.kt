package com.vidlab.app.ui.catalog

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.remote.DownloadTaskManager
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig

private fun hasAllFilesPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

private fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

@Composable
fun StartupGuideDialog(
    onModeApplied: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val playerPrefs = remember { PlayerPreferences(context) }

    var storageGranted by remember { mutableStateOf(hasAllFilesPermission(context)) }
    var notifGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var cloudflareDns by remember { mutableStateOf(playerPrefs.cloudflareDnsEnabled) }
    var selectedMode by remember { mutableStateOf(RcloneEngine.getSourceMode(context)) }
    var showMissingConfChoice by remember { mutableStateOf(false) }
    var showPasteConfDialog by remember { mutableStateOf(false) }
    var pendingSelectRemotes by remember { mutableStateOf<List<RcloneRemoteConfig>?>(null) }

    fun finishRcloneSetup() {
        RcloneEngine.setSourceMode(context, "rclone")
        RcloneEngine.startForegroundServeService(context)
        RcloneEngine.restartServeIfNeeded(context)
        onModeApplied("rclone")
    }

    val pickConfLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            if (content != null) {
                RcloneEngine.importConfigText(context, content).onSuccess { list ->
                    showMissingConfChoice = false
                    pendingSelectRemotes = list
                }.onFailure { err ->
                    Toast.makeText(context, "Import failed: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                storageGranted = hasAllFilesPermission(context)
                notifGranted = hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        storageGranted = hasAllFilesPermission(context)
        notifGranted = hasNotificationPermission(context)
    }

    if (showMissingConfChoice) {
        AlertDialog(
            onDismissRequest = { showMissingConfChoice = false },
            containerColor = DrawerSurfaceDark,
            title = {
                Text(
                    "Rclone Config Required",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    "No rclone.conf found yet. Import your rclone.conf file or paste its contents to connect your cloud remotes.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showMissingConfChoice = false
                        pickConfLauncher.launch(arrayOf("*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Import File", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = { showMissingConfChoice = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                    OutlinedButton(
                        onClick = {
                            showMissingConfChoice = false
                            showPasteConfDialog = true
                        },
                        border = BorderStroke(1.dp, DrawerBorderPurple),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Paste Text", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        )
    }

    if (showPasteConfDialog) {
        PasteRcloneConfDialog(
            initialText = "",
            onDismiss = { showPasteConfDialog = false },
            onSaved = { list ->
                showPasteConfDialog = false
                pendingSelectRemotes = list
            }
        )
    }

    pendingSelectRemotes?.let { list ->
        SelectRemotesDialog(
            initialRemotes = list,
            onDismiss = {
                pendingSelectRemotes = null
                finishRcloneSetup()
            },
            onConfirm = {
                pendingSelectRemotes = null
                finishRcloneSetup()
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            color = Color(0xFF131926),
            shape = RoundedCornerShape(18.dp),
            border = BorderStroke(1.dp, DrawerBorderPurple),
            modifier = Modifier
                .fillMaxWidth(0.93f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp)
            ) {
                Text(
                    "Welcome to VidLab",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Quick setup & network tips before scanning your media",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "1. REQUIRED PERMISSIONS",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = DrawerSurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, DrawerBorderPurple)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.FolderOpen,
                                    contentDescription = null,
                                    tint = if (storageGranted) Color(0xFF4CAF50) else DrawerAccentViolet,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "All Files Access",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Needed to scan local videos, read rclone.conf, and save offline downloads in /VidLab.",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                if (storageGranted) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Granted",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                                runCatching {
                                                    context.startActivity(
                                                        Intent(
                                                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                                            Uri.parse("package:${context.packageName}")
                                                        )
                                                    )
                                                }.onFailure {
                                                    runCatching {
                                                        context.startActivity(
                                                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                                        )
                                                    }
                                                }
                                            } else {
                                                permLauncher.launch(
                                                    arrayOf(
                                                        Manifest.permission.READ_EXTERNAL_STORAGE,
                                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                                    )
                                                )
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Grant", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            HorizontalDivider(color = DrawerBorderPurple.copy(alpha = 0.6f))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = if (notifGranted) Color(0xFF4CAF50) else DrawerAccentViolet,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Notifications",
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "Shows live download progress, batch renamer status, and background Rclone server alerts.",
                                        color = Color(0xFF94A3B8),
                                        fontSize = 11.sp,
                                        lineHeight = 14.sp
                                    )
                                }
                                if (notifGranted) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Granted",
                                        tint = Color(0xFF4CAF50),
                                        modifier = Modifier.size(20.dp)
                                    )
                                } else {
                                    Button(
                                        onClick = {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("Allow", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    Text(
                        "2. ISP BLOCKING FIX (TMDB / ANILIST)",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Surface(
                        color = DrawerSurfaceDark,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(0.8.dp, if (cloudflareDns) DrawerAccentViolet else DrawerBorderPurple)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = Color(0xFF60A5FA),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    "Cloudflare DNS (1.1.1.1) — Necessary on Blocked ISPs",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                "Many mobile & broadband ISPs block TMDB, AniList, and Simkl domains—causing missing posters, empty search results, or failed sync. Turn on built-in Cloudflare DNS (1.1.1.1) to bypass ISP DNS blocks without a VPN.",
                                color = Color(0xFFCBD5E1),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                            DrawerToggleRow(
                                title = "Enable Built-in Cloudflare DNS (1.1.1.1)",
                                subtitle = "Recommended if posters or metadata fail to load",
                                checked = cloudflareDns
                            ) {
                                cloudflareDns = it
                                playerPrefs.cloudflareDnsEnabled = it
                            }
                        }
                    }

                    Text(
                        "3. CHOOSE LIBRARY SOURCE & TIPS",
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    listOf(
                        Triple("local", "Local Storage Mode", "Scan offline movies & shows on your phone or inside /storage/emulated/0/VidLab."),
                        Triple("rclone", "Rclone Mode (Cloud Vault)", "Import your rclone.conf in the left drawer (Source tab) to stream & auto-organize Google Drive, OneDrive, or WebDAV remotes."),
                        Triple("webdav", "WebDAV Mode", "Connect directly to any external HTTP/HTTPS WebDAV server URL with optional auth.")
                    ).forEach { (modeKey, title, desc) ->
                        val isSel = selectedMode == modeKey
                        Surface(
                            onClick = { selectedMode = modeKey },
                            color = if (isSel) Color(0xFF1D355E) else DrawerSurfaceDark,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, if (isSel) DrawerAccentViolet else DrawerBorderPurple),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(11.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text(desc, color = Color(0xFFCBD5E1), fontSize = 11.sp, lineHeight = 14.sp)
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0B0F17))
                            .padding(10.dp)
                    ) {
                        Text(
                            "Tip: Open the left menu anytime to run the Vault Renamer (sorts files into /VidLab/Movies, Shows, Anime) or toggle Cloudflare DNS.",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (selectedMode == "rclone" && !RcloneEngine.hasValidConfig(context)) {
                            showMissingConfChoice = true
                            return@Button
                        }
                        RcloneEngine.setSourceMode(context, selectedMode)
                        if (selectedMode == "rclone") {
                            RcloneEngine.startForegroundServeService(context)
                            RcloneEngine.restartServeIfNeeded(context)
                        } else {
                            RcloneEngine.stopServer()
                        }
                        if (selectedMode == "local") {
                            DownloadTaskManager.ensureVidLabCategoryFolders(context)
                        }
                        onModeApplied(selectedMode)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                ) {
                    Text(
                        "Save & Start VidLab",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
