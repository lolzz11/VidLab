package com.vidlab.app.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.remote.DownloadStatus
import com.vidlab.app.data.remote.DownloadTaskManager

@Composable
internal fun DrawerDownloadsTab() {
    val context = LocalContext.current
    val downloadTasks = DownloadTaskManager.tasks
    val failedCount = downloadTasks.count {
        it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "DOWNLOADS (${downloadTasks.size})",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (failedCount > 0) {
                Text(
                    "Retry Failed",
                    color = Color(0xFF60A5FA),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { DownloadTaskManager.retryAllFailed(context) }
                )
            }
            if (downloadTasks.isNotEmpty()) {
                Text(
                    "Clear",
                    color = DrawerAccentViolet,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { DownloadTaskManager.clearFinished() }
                )
            }
        }
    }

    if (downloadTasks.isEmpty()) {
        Surface(
            color = DrawerSurfaceDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "No active downloads. Long-press an episode or use the 3-dot menu to save to /storage/emulated/0/VidLab.",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                modifier = Modifier.padding(14.dp)
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (task in downloadTasks) {
                Surface(
                    color = DrawerSurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.8.dp, DrawerBorderPurple),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    task.fileName,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "VidLab/${task.folderName}",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (task.status == DownloadStatus.QUEUED || task.status == DownloadStatus.DOWNLOADING) {
                                IconButton(
                                    onClick = { DownloadTaskManager.cancelTask(context, task.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Cancel",
                                        tint = Color(0xFF94A3B8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else if (task.status == DownloadStatus.FAILED || task.status == DownloadStatus.CANCELLED) {
                                IconButton(
                                    onClick = { DownloadTaskManager.retryTask(context, task.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = "Retry",
                                        tint = Color(0xFF60A5FA),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                        val statusColor = when (task.status) {
                            DownloadStatus.COMPLETED -> Color(0xFF4CAF50)
                            DownloadStatus.FAILED -> Color(0xFFEF5350)
                            else -> DrawerAccentViolet
                        }
                        LinearProgressIndicator(
                            progress = { task.progressFraction },
                            color = statusColor,
                            trackColor = DrawerBorderPurple,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val dlStr = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                            val totStr = if (task.totalBytes > 0L) {
                                String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0))
                            } else ""
                            Text("$dlStr$totStr", color = Color(0xFF94A3B8), fontSize = 10.sp)
                            val statusLabel = when (task.status) {
                                DownloadStatus.QUEUED -> "Queued"
                                DownloadStatus.DOWNLOADING -> "${(task.progressFraction * 100).toInt()}%"
                                DownloadStatus.COMPLETED -> "Completed"
                                DownloadStatus.FAILED -> "Failed"
                                DownloadStatus.CANCELLED -> "Cancelled"
                            }
                            Text(
                                statusLabel,
                                color = statusColor,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
