package com.vidlab.app.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VidLabAudioSettingsSheet(
    audioTracks: List<TrackOption>,
    selectedChannelMode: Int,
    volumeNormalization: Boolean,
    drcEnabled: Boolean,
    onSelectTrack: (TrackOption) -> Unit,
    onChannelModeChange: (Int) -> Unit,
    onVolumeNormChange: (Boolean) -> Unit,
    onDrcChange: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetBg = Color(0xFF0E1420)
    val cardBg = Color(0xFF283142)
    val pillSelectedBg = Color(0xFF3E4A5E)
    val accentBlue = Color(0xFF4F86E8)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetBg,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFF6B778D)) },
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Text("Audio", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)

            if (audioTracks.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accentBlue))
                    Text("Audio track", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    audioTracks.forEach { track ->
                        Surface(
                            onClick = { onSelectTrack(track) },
                            color = if (track.isSelected) pillSelectedBg else Color.Transparent,
                            shape = RoundedCornerShape(10.dp),
                            border = if (!track.isSelected) BorderStroke(1.dp, Color(0xFF4B5568)) else null
                        ) {
                            Text(
                                text = track.label,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accentBlue))
                Text("Audio channels", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            val channelOptions = listOf(
                0 to "Auto",
                1 to "Auto Safe",
                2 to "Mono",
                3 to "Stereo",
                4 to "Reversed Stereo"
            )
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                channelOptions.forEach { (modeIdx, label) ->
                    val active = selectedChannelMode == modeIdx
                    Surface(
                        onClick = { onChannelModeChange(modeIdx) },
                        color = if (active) pillSelectedBg else Color.Transparent,
                        shape = RoundedCornerShape(12.dp),
                        border = if (!active) BorderStroke(1.dp, Color(0xFF6B778D)) else null
                    ) {
                        Text(
                            text = label,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(accentBlue))
                Text("Audio processing", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = cardBg,
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp, bottomStart = 6.dp, bottomEnd = 6.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onVolumeNormChange(!volumeNormalization) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Volume normalization", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = volumeNormalization,
                            onCheckedChange = onVolumeNormChange,
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

                Surface(
                    color = cardBg,
                    shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 14.dp, bottomEnd = 14.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onDrcChange(!drcEnabled) }
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Dynamic range compression (DRC)", color = Color(0xFFE2E8F0), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Switch(
                            checked = drcEnabled,
                            onCheckedChange = onDrcChange,
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
        }
    }
}

@Composable
fun StatsForNerdsHud(
    resolution: String,
    videoCodec: String,
    audioCodec: String,
    bitrateKbps: Int,
    bufferedMs: Long,
    currentPosMs: Long,
    speed: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Color(0xCC0D1015),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color(0xFF244985)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("STATS FOR NERDS", color = Color(0xFF8AB4F8), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text("Resolution: $resolution", color = Color.White, fontSize = 11.sp)
            Text("Video: $videoCodec • ${if (bitrateKbps > 0) "${bitrateKbps} kbps" else "VBR"}", color = Color.White, fontSize = 11.sp)
            Text("Audio: $audioCodec", color = Color.White, fontSize = 11.sp)
            val aheadSec = ((bufferedMs - currentPosMs).coerceAtLeast(0L) / 1000L)
            Text("Buffer Ahead: ${aheadSec}s • Speed: ${speed}x", color = Color(0xFF94A3B8), fontSize = 11.sp)
        }
    }
}
