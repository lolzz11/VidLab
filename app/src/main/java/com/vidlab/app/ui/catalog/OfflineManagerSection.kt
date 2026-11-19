package com.vidlab.app.ui.catalog

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.prefs.WatchProgressStore
import com.vidlab.app.ui.player.PlayerActivity
import java.io.File

private val BorderPurple = Color(0xFF222E46)
private val AccentViolet = Color(0xFF244985)

@Composable
fun OfflineManagerSection(
    context: Context,
    offlineFiles: List<File>,
    progressStore: WatchProgressStore,
    onRefresh: () -> Unit
) {
    if (offlineFiles.isEmpty()) return

    var offlineSearch by remember { mutableStateOf("") }
    var offlineFilter by remember { mutableIntStateOf(0) }
    var sortBySize by remember { mutableStateOf(false) }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    val totalBytes = offlineFiles.sumOf { it.length() }
    val totalSizeLabel = if (totalBytes >= 1024L * 1024L * 1024L) {
        String.format("%.2f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))
    } else {
        String.format("%.1f MB", totalBytes / (1024.0 * 1024.0))
    }

    val watchedFiles = offlineFiles.filter { f ->
        (progressStore.getProgress(Uri.fromFile(f).toString())?.watched == true) ||
            (progressStore.getProgress(f.absolutePath)?.watched == true)
    }

    HorizontalDivider(color = BorderPurple, thickness = 0.8.dp)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "SAVED OFFLINE (${offlineFiles.size} • $totalSizeLabel)",
            color = AccentViolet,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = "Refresh offline files",
                tint = AccentViolet,
                modifier = Modifier.size(15.dp).clickable {
                    onRefresh()
                    Toast.makeText(context, "Offline library refreshed", Toast.LENGTH_SHORT).show()
                }
            )
            if (watchedFiles.isNotEmpty()) {
            Surface(
                color = Color(0xFF3A1C24),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable {
                    watchedFiles.forEach { f ->
                        val p = f.parentFile
                        f.delete()
                        if (p != null && p.listFiles()?.isEmpty() == true) p.delete()
                    }
                    onRefresh()
                    Toast.makeText(context, "Cleaned ${watchedFiles.size} watched file(s)", Toast.LENGTH_SHORT).show()
                }
            ) {
                Text(
                    "Clean Watched (${watchedFiles.size})",
                    color = Color(0xFFEF5350),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                )
            }
        }
    }
    }

    OutlinedTextField(
        value = offlineSearch,
        onValueChange = { offlineSearch = it },
        placeholder = { Text("Filter saved shows or movies...", color = Color(0xFF71717A), fontSize = 11.sp) },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = AccentViolet,
            unfocusedBorderColor = BorderPurple
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("All", "Series", "Movies").forEachIndexed { idx, label ->
            val sel = offlineFilter == idx
            Surface(
                color = if (sel) AccentViolet else BorderPurple.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.weight(1f).clickable { offlineFilter = idx }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 4.dp)) {
                    Text(label, color = if (sel) Color(0xFF050608) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Surface(
            color = if (sortBySize) AccentViolet.copy(alpha = 0.25f) else BorderPurple.copy(alpha = 0.4f),
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(0.6.dp, if (sortBySize) AccentViolet else BorderPurple),
            modifier = Modifier.clickable { sortBySize = !sortBySize }
        ) {
            Text(
                if (sortBySize) "Size ↓" else "Name",
                color = if (sortBySize) AccentViolet else Color(0xFF244985),
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }

    val grouped = offlineFiles
        .filter { f ->
            offlineSearch.isBlank() ||
                f.name.contains(offlineSearch, ignoreCase = true) ||
                (f.parentFile?.name?.contains(offlineSearch, ignoreCase = true) == true)
        }
        .groupBy { it.parentFile?.name?.takeIf { n -> n != "offline_downloads" && n != "VidLab" } ?: "Single Videos" }
        .filter { (_, files) ->
            when (offlineFilter) {
                1 -> files.size > 1 || files.any { Regex("""(?i)S\d{1,2}E\d{1,3}""").containsMatchIn(it.name) }
                2 -> files.size == 1 && !Regex("""(?i)S\d{1,2}E\d{1,3}""").containsMatchIn(files.first().name)
                else -> true
            }
        }
        .toList()
        .let { list ->
            if (sortBySize) list.sortedByDescending { it.second.sumOf { f -> f.length() } }
            else list.sortedBy { it.first.lowercase() }
        }

    for ((rawGroupName, groupFiles) in grouped) {
        val displayGroupName = rawGroupName.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
        val isSingleMovie = groupFiles.size == 1 && !Regex("""(?i)S\d{1,2}E\d{1,3}""").containsMatchIn(groupFiles.first().name)
        val isExpanded = expandedGroups[rawGroupName] ?: false
        val groupBytes = groupFiles.sumOf { it.length() }
        val groupSizeStr = if (groupBytes >= 1024L * 1024L * 1024L) {
            String.format("%.2f GB", groupBytes / (1024.0 * 1024.0 * 1024.0))
        } else {
            String.format("%.1f MB", groupBytes / (1024.0 * 1024.0))
        }
        val isInApp = groupFiles.first().absolutePath.contains("/offline_downloads/")

        Surface(
            color = Color(0xFF050608),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(0.7.dp, BorderPurple),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().clickable {
                        if (isSingleMovie) {
                            val movieFile = groupFiles.first()
                            val intent = Intent(context, PlayerActivity::class.java).apply {
                                putExtra("FILE_PATH", Uri.fromFile(movieFile).toString())
                                putExtra("FILE_NAME", movieFile.name)
                            }
                            context.startActivity(intent)
                        } else {
                            expandedGroups[rawGroupName] = !isExpanded
                        }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(AccentViolet.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSingleMovie) Icons.Default.PlayArrow else Icons.Default.Folder,
                            contentDescription = null,
                            tint = AccentViolet,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayGroupName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (isSingleMovie) {
                                "Movie • $groupSizeStr • ${if (isInApp) "App Vault" else "Downloads"}"
                            } else {
                                "${groupFiles.size} episode(s) • $groupSizeStr • ${if (isInApp) "App Vault" else "Downloads"}"
                            },
                            color = Color(0xFF244985),
                            fontSize = 9.sp
                        )
                    }
                    if (!isSingleMovie) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = Color(0xFF244985),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(
                        onClick = {
                            groupFiles.forEach { f ->
                                val p = f.parentFile
                                f.delete()
                                if (p != null && p.listFiles()?.isEmpty() == true) p.delete()
                            }
                            onRefresh()
                            Toast.makeText(context, "Deleted $displayGroupName", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(26.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color(0xFFEF5350), modifier = Modifier.size(15.dp))
                    }
                }

                if (!isSingleMovie && isExpanded) {
                    HorizontalDivider(color = BorderPurple.copy(alpha = 0.6f), thickness = 0.6.dp)
                    for (offFile in groupFiles.sortedBy { it.name.lowercase() }) {
                        val epCode = Regex("""(?i)(S\d{1,2}E\d{1,3})""").find(offFile.name)?.groupValues?.getOrNull(1)?.uppercase()
                        val stripped = offFile.nameWithoutExtension
                            .replace('_', ' ')
                            .replace(displayGroupName, "", ignoreCase = true)
                            .replace(Regex("""(?i)S\d{1,2}E\d{1,3}"""), "")
                            .replace(Regex("""(?i)^[\s._:-]+"""), "")
                            .trim()
                        val epDisplayTitle = when {
                            stripped.isBlank() || stripped.matches(Regex("""^\(\d{4}\)$""")) -> epCode ?: offFile.nameWithoutExtension.replace('_', ' ')
                            epCode != null -> "$epCode • $stripped"
                            else -> stripped
                        }
                        val isEpWatched = (progressStore.getProgress(Uri.fromFile(offFile).toString())?.watched == true) ||
                            (progressStore.getProgress(offFile.absolutePath)?.watched == true)

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(AccentViolet.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = AccentViolet, modifier = Modifier.size(13.dp))
                            }
                            Column(modifier = Modifier.weight(1f).clickable {
                                val intent = Intent(context, PlayerActivity::class.java).apply {
                                    putExtra("FILE_PATH", Uri.fromFile(offFile).toString())
                                    putExtra("FILE_NAME", offFile.name)
                                }
                                context.startActivity(intent)
                            }) {
                                Text(
                                    epDisplayTitle,
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append(String.format("%.1f MB", offFile.length() / (1024.0 * 1024.0)))
                                        if (isEpWatched) append(" • Watched")
                                    },
                                    color = if (isEpWatched) AccentViolet else Color(0xFF244985),
                                    fontSize = 9.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    val p = offFile.parentFile
                                    offFile.delete()
                                    if (p != null && p.listFiles()?.isEmpty() == true) p.delete()
                                    onRefresh()
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Delete episode", tint = Color(0xFFEF5350), modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}
