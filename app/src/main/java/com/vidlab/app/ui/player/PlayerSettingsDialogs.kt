package com.vidlab.app.ui.player

import android.app.Activity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.PlayerPreferences

@Composable
internal fun PlayerDecoderDialog(
    playerPrefs: PlayerPreferences,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val decoderOptions = listOf(
        0 to ("Auto (HW + FFmpeg)" to "Uses hardware acceleration with FFmpeg fallback"),
        1 to ("Hardware (HW)" to "Forces device hardware video/audio decoders"),
        2 to ("Software (SW / FFmpeg)" to "Best for 10-bit Hi10P anime & unsupported codecs")
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1015),
        title = { Text("Video & Audio Decoder", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                decoderOptions.forEach { (mode, descPair) ->
                    val selected = playerPrefs.decoderMode == mode
                    Surface(
                        onClick = {
                            playerPrefs.decoderMode = mode
                            onDismiss()
                            (context as? Activity)?.recreate()
                        },
                        color = if (selected) Color(0xFF244985).copy(alpha = 0.25f) else Color(0xFF171D29),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (selected) Color(0xFF244985) else Color(0xFF282E38))
                    ) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Text(descPair.first, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(descPair.second, color = Color(0xFF94A3B8), fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF94A3B8)) } }
    )
}

@Composable
internal fun PlayerSpeedDialog(
    currentSpeed: Float,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF0D1015),
        title = { Text("Playback Speed", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (spd in speeds) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectSpeed(spd); onDismiss() }
                            .padding(vertical = 8.dp, horizontal = 6.dp)
                    ) {
                        RadioButton(
                            selected = currentSpeed == spd,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF244985))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("${spd}x", color = Color.White, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close", color = Color(0xFF94A3B8)) } }
    )
}
