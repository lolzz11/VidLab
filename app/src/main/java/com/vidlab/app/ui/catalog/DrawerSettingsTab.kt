package com.vidlab.app.ui.catalog

import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
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
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.remote.AppReleaseInfo
import com.vidlab.app.data.remote.AppUpdater
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun DrawerSettingsTab(onSimklToggle: (Boolean) -> Unit, onOpenSetupGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val playerPrefs = remember { PlayerPreferences(context) }

    var defaultSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var seekStep by remember { mutableIntStateOf(playerPrefs.seekStepSeconds) }
    var resizeMode by remember { mutableIntStateOf(playerPrefs.defaultResizeMode) }
    var autoEnglish by remember { mutableStateOf(playerPrefs.autoEnglishTracks) }
    var decoderMode by remember { mutableIntStateOf(playerPrefs.decoderMode) }
    var longPressBoost by remember { mutableStateOf(playerPrefs.longPressBoostEnabled) }
    var volGesture by remember { mutableStateOf(playerPrefs.volumeGestureEnabled) }
    var brightGesture by remember { mutableStateOf(playerPrefs.brightnessGestureEnabled) }
    var skipEnabled by remember { mutableStateOf(playerPrefs.skipSegmentsEnabled) }
    var autoSkip by remember { mutableStateOf(playerPrefs.autoSkipSegments) }
    var autoDeleteWatched by remember { mutableStateOf(playerPrefs.autoDeleteAfterWatch) }
    var cloudflareDns by remember { mutableStateOf(playerPrefs.cloudflareDnsEnabled) }
    var autoProbeSpecs by remember { mutableStateOf(playerPrefs.autoProbeMediaSpecs) }
    var subSize by remember { mutableFloatStateOf(playerPrefs.subtitleFontSizeSp) }
    var subColorIdx by remember { mutableIntStateOf(playerPrefs.subtitleColorIndex) }
    var subBottomPct by remember { mutableIntStateOf(playerPrefs.subtitleBottomPaddingPct) }
    var subBgBox by remember { mutableStateOf(playerPrefs.subtitleBgBox) }

    DisposableEffect(context) {
        val prefs = context.getSharedPreferences("vidlab_player_prefs", Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "cloudflare_dns_enabled" -> cloudflareDns = playerPrefs.cloudflareDnsEnabled
                "auto_probe_media_specs" -> autoProbeSpecs = playerPrefs.autoProbeMediaSpecs
                "decoder_mode" -> decoderMode = playerPrefs.decoderMode
                "subtitle_font_size_sp" -> subSize = playerPrefs.subtitleFontSizeSp
                "subtitle_bottom_padding_pct" -> subBottomPct = playerPrefs.subtitleBottomPaddingPct
                "subtitle_bg_box" -> subBgBox = playerPrefs.subtitleBgBox
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    val installedVersion = remember { AppUpdater.getCurrentVersionName(context) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    var updateDownloadProgress by remember { mutableFloatStateOf(0f) }
    var availableRelease by remember { mutableStateOf<AppReleaseInfo?>(null) }
    var updateStatusMessage by remember { mutableStateOf<String?>(null) }

    Text("NETWORK & DNS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Card(colors = CardDefaults.cardColors(containerColor = DrawerSurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DrawerToggleRow("Cloudflare DNS (1.1.1.1)", "Route DNS queries through 1.1.1.1 to bypass ISP blocking.", cloudflareDns) {
                cloudflareDns = it; playerPrefs.cloudflareDnsEnabled = it
            }
            HorizontalDivider(color = DrawerBorderPurple.copy(alpha = 0.6f))
            DrawerToggleRow("Auto-Probe Video Headers", "Read remote headers on scan for 4K/1080p/HEVC badges (Warning: consumes extra mobile data).", autoProbeSpecs) {
                autoProbeSpecs = it; playerPrefs.autoProbeMediaSpecs = it
            }
            OutlinedButton(onClick = onOpenSetupGuide, border = BorderStroke(1.dp, DrawerBorderPurple), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(36.dp)) { Text("Setup & ISP Unblock Guide", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
        }
    }

    Text("PLAYER SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Card(colors = CardDefaults.cardColors(containerColor = DrawerSurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Default Playback Speed", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                    val selected = abs(defaultSpeed - spd) < 0.01f
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { defaultSpeed = spd; playerPrefs.defaultSpeed = spd }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text("${spd}x", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Double-Tap Seek Step", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(5, 10, 15, 30).forEach { sec ->
                    val selected = seekStep == sec
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { seekStep = sec; playerPrefs.seekStepSeconds = sec }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text("${sec}s", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Default Aspect Ratio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(0 to "Fit", 4 to "Zoom", 3 to "Fill").forEach { (mode, label) ->
                    val selected = resizeMode == mode
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { resizeMode = mode; playerPrefs.defaultResizeMode = mode }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
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
                    color = Color(0xFF94A3B8), fontSize = 11.sp, lineHeight = 14.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf("Auto", "HW Only", "SW / FFmpeg").forEachIndexed { idx, label ->
                        val sel = decoderMode == idx
                        Surface(color = if (sel) DrawerAccentViolet else DrawerBorderPurple.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { decoderMode = idx; playerPrefs.decoderMode = idx; playerPrefs.preferFfmpegAudio = (idx == 2) }) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 7.dp)) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }
            HorizontalDivider(color = DrawerBorderPurple, thickness = 0.8.dp)
            DrawerToggleRow("Long-Press 2x Speed Boost", "Hold screen during playback for temporary 2.0x speed", longPressBoost) { longPressBoost = it; playerPrefs.longPressBoostEnabled = it }
            DrawerToggleRow("Volume Swipe Gesture", "Swipe vertically on right side", volGesture) { volGesture = it; playerPrefs.volumeGestureEnabled = it }
            DrawerToggleRow("Brightness Swipe Gesture", "Swipe vertically on left side", brightGesture) { brightGesture = it; playerPrefs.brightnessGestureEnabled = it }
            DrawerToggleRow("Auto-Delete Offline After Watch", "Remove local offline copy after finishing ≥ 90%", autoDeleteWatched) { autoDeleteWatched = it; playerPrefs.autoDeleteAfterWatch = it }
        }
    }

    Text("SUBTITLE & SKIP SETTINGS", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Card(colors = CardDefaults.cardColors(containerColor = DrawerSurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Subtitle Text Size", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(22f, 28f, 36f, 46f).forEach { sz ->
                    val selected = abs(subSize - sz) < 0.5f
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subSize = sz; playerPrefs.subtitleFontSizeSp = sz }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text("${sz.toInt()}sp", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Subtitle Color", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("White", "Yellow", "Cyan", "Green").forEachIndexed { idx, label ->
                    val selected = subColorIdx == idx
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subColorIdx = idx; playerPrefs.subtitleColorIndex = idx }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Text("Bottom Margin", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(2, 5, 8, 12).forEach { pct ->
                    val selected = subBottomPct == pct
                    Surface(color = if (selected) DrawerAccentViolet else DrawerBorderPurple, shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f).clickable { subBottomPct = pct; playerPrefs.subtitleBottomPaddingPct = pct }) {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) { Text("$pct%", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
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
            defaultSpeed = 1.0f; playerPrefs.defaultSpeed = 1.0f; seekStep = 10; playerPrefs.seekStepSeconds = 10; resizeMode = 0; playerPrefs.defaultResizeMode = 0
            autoEnglish = true; playerPrefs.autoEnglishTracks = true; decoderMode = 0; playerPrefs.decoderMode = 0; playerPrefs.preferFfmpegAudio = false
            longPressBoost = true; playerPrefs.longPressBoostEnabled = true; volGesture = true; playerPrefs.volumeGestureEnabled = true
            brightGesture = true; playerPrefs.brightnessGestureEnabled = true; subSize = 28f; playerPrefs.subtitleFontSizeSp = 28f
            subColorIdx = 0; playerPrefs.subtitleColorIndex = 0; subBottomPct = 5; playerPrefs.subtitleBottomPaddingPct = 5
            subBgBox = false; playerPrefs.subtitleBgBox = false; skipEnabled = true; playerPrefs.skipSegmentsEnabled = true
            autoSkip = false; playerPrefs.autoSkipSegments = false; autoDeleteWatched = false; playerPrefs.autoDeleteAfterWatch = false
            cloudflareDns = false; playerPrefs.cloudflareDnsEnabled = false; autoProbeSpecs = false; playerPrefs.autoProbeMediaSpecs = false
            Toast.makeText(context, "Settings reset to default", Toast.LENGTH_SHORT).show()
        },
        border = BorderStroke(1.dp, DrawerBorderPurple), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().height(42.dp)
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Reset Settings to Default", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }

    Text("TRACKING & SYNC", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    UnifiedTrackingCard(onSimklToggle = onSimklToggle)

    Text("APP UPDATES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Card(colors = CardDefaults.cardColors(containerColor = DrawerSurfaceDark), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("VidLab Android", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("Installed: v$installedVersion", color = Color(0xFF94A3B8), fontSize = 11.sp)
                }
                Button(
                    onClick = {
                        if (!isCheckingUpdate && !isDownloadingUpdate) {
                            isCheckingUpdate = true; updateStatusMessage = null
                            scope.launch {
                                AppUpdater.checkForUpdate(context).onSuccess { info ->
                                    availableRelease = info
                                    updateStatusMessage = if (info.isNewer) "New version ${info.tagName} is available!" else "You're on the latest version (${info.tagName})."
                                }.onFailure { err -> updateStatusMessage = "Update check failed: ${err.message}" }
                                isCheckingUpdate = false
                            }
                        }
                    },
                    enabled = !isCheckingUpdate && !isDownloadingUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(if (isCheckingUpdate) "Checking..." else "Check Updates", fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            }
            if (updateStatusMessage != null) {
                Text(text = updateStatusMessage!!, color = if (availableRelease?.isNewer == true) DrawerAccentViolet else Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            val rel = availableRelease
            if (rel != null && rel.isNewer) {
                if (rel.body.isNotBlank()) Text(text = rel.body, color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                if (isDownloadingUpdate) LinearProgressIndicator(progress = { updateDownloadProgress }, color = DrawerAccentViolet, trackColor = DrawerBorderPurple, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)))
                Button(
                    onClick = {
                        if (!isDownloadingUpdate) {
                            isDownloadingUpdate = true; updateDownloadProgress = 0f
                            scope.launch {
                                AppUpdater.downloadAndInstallApk(context, rel.apkDownloadUrl) { pct -> updateDownloadProgress = pct }
                                    .onFailure { err -> updateStatusMessage = "Download failed: ${err.message}" }
                                isDownloadingUpdate = false
                            }
                        }
                    },
                    enabled = !isDownloadingUpdate,
                    colors = ButtonDefaults.buttonColors(containerColor = DrawerAccentViolet, contentColor = Color.White),
                    shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().height(38.dp)
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isDownloadingUpdate) "Downloading ${(updateDownloadProgress * 100).toInt()}%..." else "Download & Install ${rel.tagName}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    Text("LEGAL & LICENSES", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    Surface(color = DrawerSurfaceDark, shape = RoundedCornerShape(12.dp), border = BorderStroke(0.8.dp, DrawerBorderPurple), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("VidLab is a bring-your-own-media player and cloud client. It does not host, index, or distribute any media content.", color = Color(0xFFCBD5E1), fontSize = 11.sp)
            HorizontalDivider(color = DrawerBorderPurple.copy(alpha = 0.6f))
            Text("Metadata & Sync: This product uses the TMDB API and Trakt, but is not endorsed or certified by TMDB or Trakt. Additional metadata and sync powered by Simkl, AniList, AniSkip, and TheIntroDB.", color = Color(0xFF94A3B8), fontSize = 10.sp)
            Text("Open-Source Licenses: VidLab is licensed under GPL-3.0. Uses FFmpeg (LGPLv2.1+), Rclone (MIT), and AndroidX Media3 (Apache 2.0).", color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
    }
}

@Composable
internal fun DrawerToggleRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color(0xFF94A3B8), fontSize = 10.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DrawerAccentViolet, uncheckedThumbColor = Color(0xFF8E9199), uncheckedTrackColor = Color(0xFF1F2737), uncheckedBorderColor = Color(0xFF2D3A52)))
    }
}
