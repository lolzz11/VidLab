package com.vidlab.app.ui.player

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import com.vidlab.app.data.prefs.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
internal fun PlayerSubtitleDialog(
    title: String,
    player: ExoPlayer,
    playerPrefs: PlayerPreferences,
    subtitleTracks: List<TrackOption>,
    activeExternalSubUri: Uri?,
    activeExternalSubRawUrl: String?,
    externalSubLabel: String?,
    subtitleOffsetMs: Long,
    onExternalSubApplied: (uri: Uri, rawUrl: String?, label: String, offsetMs: Long, toastMsg: String?) -> Unit,
    onPickLocalSub: () -> Unit,
    onStyleChanged: () -> Unit,
    onRefreshTracks: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var subTab by remember { mutableIntStateOf(0) }
    var onlineQuery by remember {
        mutableStateOf(title.replace(Regex("""(?i)\s*[-–:|•]\s*S\d{1,2}\s*E\d{1,3}\b.*"""), "").trim())
    }
    var onlineLang by remember { mutableStateOf("en") }
    var onlineResults by remember { mutableStateOf<List<OnlineSubtitleResult>>(emptyList()) }
    var isSearchingOnline by remember { mutableStateOf(false) }
    var onlineStatusMsg by remember { mutableStateOf<String?>(null) }
    val dialogScope = rememberCoroutineScope()

    fun applyExternalSubWithOffset(targetUri: Uri, label: String) {
        val resumePos = player.currentPosition
        val wasPlaying = player.isPlaying
        val currentItem = player.currentMediaItem ?: return
        val extSub = MediaItem.SubtitleConfiguration.Builder(targetUri)
            .setMimeType(
                when {
                    targetUri.toString().endsWith(".vtt", true) -> MimeTypes.TEXT_VTT
                    targetUri.toString().endsWith(".ass", true) || targetUri.toString().endsWith(".ssa", true) -> MimeTypes.TEXT_SSA
                    else -> MimeTypes.APPLICATION_SUBRIP
                }
            )
            .setLanguage(onlineLang)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val existingSubs = (currentItem.localConfiguration?.subtitleConfigurations ?: emptyList())
            .filterNot { it.label == label }
        val updatedItem = currentItem.buildUpon()
            .setSubtitleConfigurations(existingSubs + extSub)
            .build()
        player.setMediaItem(updatedItem, resumePos)
        player.prepare()
        player.playWhenReady = wasPlaying
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1015),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Subtitles", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Tracks", "Search Online", "Sync & Style").forEachIndexed { idx, tabLabel ->
                        val active = subTab == idx
                        Surface(
                            onClick = { subTab = idx },
                            color = if (active) Color(0xFF244985) else Color(0xFF171D29),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = tabLabel,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        text = {
            when (subTab) {
                0 -> Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        onClick = { onDismiss(); onPickLocalSub() },
                        color = Color(0xFF244985),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Load Local File (.srt / .ass / .vtt)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                        items(subtitleTracks) { track ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        if (track.group != null) {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                                                .build()
                                        } else {
                                            player.trackSelectionParameters = player.trackSelectionParameters
                                                .buildUpon()
                                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                .build()
                                        }
                                        onRefreshTracks()
                                        onDismiss()
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = track.isSelected,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF244985), unselectedColor = Color(0xFF71717A))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(track.label, color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                }
                1 -> Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextField(
                        value = onlineQuery,
                        onValueChange = { onlineQuery = it },
                        singleLine = true,
                        placeholder = { Text("Movie or Series Title", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF171D29),
                            unfocusedContainerColor = Color(0xFF171D29),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color(0xFF244985),
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf("en" to "English (EN)", "" to "All Languages").forEach { (code, label) ->
                            val active = onlineLang == code
                            Surface(
                                onClick = { onlineLang = code },
                                color = if (active) Color(0xFF244985) else Color(0xFF1F2737),
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, if (active) Color(0xFF244985) else Color(0xFF2D3A52))
                            ) {
                                Text(
                                    label,
                                    color = if (active) Color.White else Color(0xFFD4D4D8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            isSearchingOnline = true
                            onlineStatusMsg = null
                            dialogScope.launch {
                                val seMatch = Regex("""(?i)S(\d{1,2})\s*E(\d{1,3})""").find(title)
                                val found = OnlineSubtitleHelper.searchSubtitles(
                                    query = onlineQuery,
                                    season = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull(),
                                    episode = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull(),
                                    languageFilter = onlineLang
                                )
                                onlineResults = found
                                isSearchingOnline = false
                                if (found.isEmpty()) onlineStatusMsg = "No subtitles found. Try shortening the title or switching to All Languages."
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (isSearchingOnline) "Searching..." else "Search Online Subtitles", color = Color.White, fontWeight = FontWeight.Bold) }
                    if (onlineStatusMsg != null) {
                        Text(onlineStatusMsg!!, color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                    LazyColumn(modifier = Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(onlineResults) { res ->
                            Surface(
                                onClick = {
                                    isSearchingOnline = true
                                    dialogScope.launch {
                                        val uri = OnlineSubtitleHelper.downloadAndShiftSubtitle(context, res.url, res.releaseName, 0L)
                                        isSearchingOnline = false
                                        if (uri != null) {
                                            val lbl = "${res.language} • ${res.releaseName.take(28)}"
                                            applyExternalSubWithOffset(uri, lbl)
                                            onExternalSubApplied(uri, res.url, lbl, 0L, "Loaded ${res.language} subtitle")
                                            onDismiss()
                                        } else {
                                            onlineStatusMsg = "Failed to download subtitle file."
                                        }
                                    }
                                },
                                color = Color(0xFF171D29),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(res.releaseName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${res.language} • ${res.source}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
                else -> Column(
                    modifier = Modifier.heightIn(max = 215.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("SUBTITLE TIMING OFFSET", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(-500L to "-0.5s", -100L to "-0.1s", 100L to "+0.1s", 500L to "+0.5s").forEach { (step, lbl) ->
                            Surface(
                                onClick = {
                                    val newOff = subtitleOffsetMs + step
                                    val lblName = externalSubLabel ?: "Synced Subtitle"
                                    if (activeExternalSubRawUrl != null) {
                                        dialogScope.launch {
                                            val shiftedUri = OnlineSubtitleHelper.downloadAndShiftSubtitle(context, activeExternalSubRawUrl, externalSubLabel ?: "Online Subtitle", newOff)
                                            if (shiftedUri != null) {
                                                applyExternalSubWithOffset(shiftedUri, lblName)
                                                onExternalSubApplied(shiftedUri, activeExternalSubRawUrl, lblName, newOff, null)
                                            }
                                        }
                                    } else if (activeExternalSubUri != null) {
                                        dialogScope.launch {
                                            val shiftedUri = withContext(Dispatchers.IO) {
                                                OnlineSubtitleHelper.shiftLocalUriSubtitle(context, activeExternalSubUri, newOff)
                                            }
                                            if (shiftedUri != null) {
                                                applyExternalSubWithOffset(shiftedUri, lblName)
                                                onExternalSubApplied(shiftedUri, null, lblName, newOff, null)
                                            }
                                        }
                                    } else {
                                        onExternalSubApplied(Uri.EMPTY, null, lblName, newOff, null)
                                    }
                                },
                                color = Color(0xFF171D29),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(lbl, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                            }
                        }
                    }
                    Text(
                        text = "Current Sync Offset: ${if (subtitleOffsetMs >= 0) "+${subtitleOffsetMs}ms" else "${subtitleOffsetMs}ms"}" +
                            if (activeExternalSubUri == null && activeExternalSubRawUrl == null) " (Load external/online .srt to shift timestamps)" else "",
                        color = Color(0xFF244985),
                        fontSize = 12.sp
                    )
                    HorizontalDivider(color = Color(0xFF282E38))
                    Text("TEXT SIZE (${playerPrefs.subtitleFontSizeSp.toInt()}sp)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(22f to "Small", 28f to "Medium", 36f to "Large", 46f to "XL").forEach { (spVal, lbl) ->
                            val sel = abs(playerPrefs.subtitleFontSizeSp - spVal) < 0.5f
                            Surface(
                                onClick = { playerPrefs.subtitleFontSizeSp = spVal; onStyleChanged() },
                                color = if (sel) Color(0xFF244985) else Color(0xFF171D29),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(lbl, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Text("BOTTOM POSITION (${playerPrefs.subtitleBottomPaddingPct}%)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5 to "Low", 10 to "Normal", 16 to "High", 24 to "Top+").forEach { (pctVal, lbl) ->
                            val sel = playerPrefs.subtitleBottomPaddingPct == pctVal
                            Surface(
                                onClick = { playerPrefs.subtitleBottomPaddingPct = pctVal; onStyleChanged() },
                                color = if (sel) Color(0xFF244985) else Color(0xFF171D29),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(lbl, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            playerPrefs.subtitleBgBox = !playerPrefs.subtitleBgBox
                            onStyleChanged()
                        },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dark Background Box", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = playerPrefs.subtitleBgBox,
                            onCheckedChange = { playerPrefs.subtitleBgBox = it; onStyleChanged() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF3B6CB7),
                                uncheckedThumbColor = Color(0xFF8E9199),
                                uncheckedTrackColor = Color(0xFF1F2737),
                                uncheckedBorderColor = Color(0xFF2D3A52)
                            )
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF94A3B8)) } }
    )
}
