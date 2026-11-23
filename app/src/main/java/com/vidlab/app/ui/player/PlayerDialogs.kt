package com.vidlab.app.ui.player

import android.app.Activity
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
internal fun PlayerToolsDialog(
    currentPos: Long,
    sleepTimerEndMs: Long,
    sleepAfterCurrentFile: Boolean,
    loopPointAMs: Long,
    loopPointBMs: Long,
    onSetSleepMinutes: (Int) -> Unit,
    onSetSleepEndOfFile: () -> Unit,
    onClearSleep: () -> Unit,
    onSetLoopA: (Long) -> Unit,
    onSetLoopB: (Long) -> Unit,
    onClearLoop: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1015),
        title = { Text("Sleep Timer & A-B Loop", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sleep Timer", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(15, 30, 45, 60).forEach { mins ->
                        Surface(
                            onClick = { onSetSleepMinutes(mins); onDismiss() },
                            color = Color(0xFF1A2232),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                Text("${mins}m", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onSetSleepEndOfFile(); onDismiss() },
                        modifier = Modifier.weight(1f)
                    ) { Text("End of File", color = Color.White, fontSize = 11.sp) }
                    if (sleepTimerEndMs > 0L || sleepAfterCurrentFile) {
                        TextButton(onClick = { onClearSleep(); onDismiss() }) {
                            Text("Off", color = Color(0xFFEF5350), fontSize = 11.sp)
                        }
                    }
                }
                HorizontalDivider(color = Color(0xFF222E46))
                Text("A-B Scene Loop", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { onSetLoopA(currentPos) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (loopPointAMs >= 0L) "A: ${formatDuration(loopPointAMs)}" else "Set A", color = Color.White, fontSize = 11.sp) }
                    Button(
                        onClick = { onSetLoopB(currentPos); onDismiss() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                        modifier = Modifier.weight(1f)
                    ) { Text(if (loopPointBMs > 0L) "B: ${formatDuration(loopPointBMs)}" else "Set B", color = Color.White, fontSize = 11.sp) }
                    if (loopPointAMs >= 0L || loopPointBMs > 0L) {
                        OutlinedButton(onClick = { onClearLoop(); onDismiss() }) {
                            Text("Clear", color = Color(0xFFEF5350), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF8AB4F8)) } }
    )
}
