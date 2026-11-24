package com.vidlab.app.ui.catalog

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.remote.RcloneEngine
import com.vidlab.app.data.remote.RcloneRemoteConfig

@Composable
internal fun PasteRcloneConfDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onSaved: (List<RcloneRemoteConfig>) -> Unit
) {
    val context = LocalContext.current
    var pasteConfText by remember(initialText) { mutableStateOf(initialText) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DrawerSurfaceDark,
        title = {
            Text(
                "Paste rclone.conf",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = pasteConfText,
                onValueChange = { pasteConfText = it },
                placeholder = {
                    Text("[gdrive]\ntype = drive\n...", color = Color(0xFF64748B), fontSize = 12.sp)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = DrawerAccentViolet,
                    unfocusedBorderColor = DrawerBorderPurple,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
            )
        },
        confirmButton = {
            TextButton(onClick = {
                RcloneEngine.importConfigText(context, pasteConfText).onSuccess { list ->
                    onSaved(list)
                }.onFailure { err ->
                    Toast.makeText(context, err.message ?: "Invalid config", Toast.LENGTH_LONG).show()
                }
            }) {
                Text("Save", color = DrawerAccentViolet, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
internal fun SelectRemotesDialog(
    initialRemotes: List<RcloneRemoteConfig>,
    onDismiss: () -> Unit,
    onConfirm: (List<RcloneRemoteConfig>) -> Unit
) {
    val context = LocalContext.current
    var remotes by remember(initialRemotes) { mutableStateOf(initialRemotes) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DrawerSurfaceDark,
        title = {
            Text(
                "Select Remotes to Use",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Choose which remotes VidLab should scan and stream from:",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp
                )
                remotes.forEachIndexed { idx, rem ->
                    Surface(
                        color = DrawerDeepBg,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(
                            0.8.dp,
                            if (rem.enabled) DrawerAccentViolet else DrawerBorderPurple
                        ),
                        onClick = {
                            remotes = remotes.mapIndexed { i, r ->
                                if (i == idx) r.copy(enabled = !r.enabled) else r
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    rem.name,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Type: ${rem.type}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                            }
                            Checkbox(
                                checked = rem.enabled,
                                onCheckedChange = { checked ->
                                    remotes = remotes.mapIndexed { i, r ->
                                        if (i == idx) r.copy(enabled = checked) else r
                                    }
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = DrawerAccentViolet,
                                    uncheckedColor = Color(0xFF94A3B8)
                                )
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RcloneEngine.saveConfiguredRemotes(context, remotes)
                RcloneEngine.setRcloneModeEnabled(context, true)
                RcloneEngine.restartServeIfNeeded(context)
                onConfirm(remotes)
            }) {
                Text("Save & Scan", color = DrawerAccentViolet, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
internal fun RenamerConfirmDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DrawerSurfaceDark,
        title = {
            Text(
                "Run Vault Organizer & Renamer?",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
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
            TextButton(onClick = onConfirm) {
                Text("Proceed & Organize", color = Color(0xFFFBBF24), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
internal fun FixVidLabFolderConfirmDialog(
    sourceMode: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val modeLabel = when (sourceMode) {
        "rclone" -> "Rclone Remotes"
        "webdav" -> "WebDAV Server"
        else -> "Local Storage"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DrawerSurfaceDark,
        title = {
            Text(
                "Fix / Clean Up VidLab Folder?",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "Target: /VidLab folder on $modeLabel\n\n" +
                    "• Audits every movie, show, and anime inside /VidLab/ using the updated parser & TMDB metadata.\n" +
                    "• Fixes misnamed titles/years, loose files, and messy release tags into canonical folders.\n" +
                    "• Removes empty leftover folders inside /VidLab/ after moving.\n" +
                    "• Preserves your manual matches.",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp,
                lineHeight = 17.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Fix VidLab Folder", color = Color(0xFF60A5FA), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}

@Composable
internal fun RemoveRcloneConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DrawerSurfaceDark,
        title = {
            Text(
                "Remove Rclone Config?",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Text(
                "This deletes the imported rclone.conf and clears saved remotes from VidLab.",
                color = Color(0xFFCBD5E1),
                fontSize = 12.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Remove", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color(0xFF94A3B8))
            }
        }
    )
}
