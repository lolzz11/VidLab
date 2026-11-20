package com.vidlab.app.ui.catalog

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidlab.app.data.model.TmdbSearchResult

private val BgDark = Color(0xFF0D1015)
private val SurfaceDark = Color(0xFF1A1629)
private val BorderPurple = Color(0xFF2E2647)
private val AccentViolet = Color(0xFF244985)
private val TextSecondary = Color(0xFF244985)

@Composable
fun AutoRenameScreen(
    state: RenameProgressState,
    searchResults: List<TmdbSearchResult>,
    onSearch: (String) -> Unit,
    onManualMatchApply: (ManualQuarantineItem, String, String, String?, Int, Int, String?) -> Unit,
    onSkipItem: (ManualQuarantineItem) -> Unit = {},
    onRestart: () -> Unit,
    onMinimize: () -> Unit,
    initialTab: Int = 0,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab) }
    var activeQuarantineItem by remember { mutableStateOf<ManualQuarantineItem?>(null) }

    LaunchedEffect(state.unmatchedItems.size) {
        if (!state.isRunning && state.unmatchedItems.isNotEmpty() && selectedTab == 0 && state.isCompleted) {
            selectedTab = 1
        }
    }

    val progress = if (state.totalCount > 0) {
        state.processedCount.toFloat() / state.totalCount.toFloat()
    } else 0f
    val animatedProgress by animateFloatAsState(targetValue = progress, label = "progress")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BgDark)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "VidLab Vault Organizer",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                val ctx = androidx.compose.ui.platform.LocalContext.current
                val isLocal = com.vidlab.app.data.remote.RcloneEngine.isLocalStorageModeEnabled(ctx)
                val isRclone = com.vidlab.app.data.remote.RcloneEngine.isRcloneModeEnabled(ctx)
                Text(
                    text = when {
                        isLocal -> "Local Storage -> /VidLab Vault"
                        isRclone -> "Rclone -> /VidLab Vault"
                        else -> "WebDAV -> /VidLab Vault"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = onRestart,
                    enabled = !state.isRunning,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Rescan & Organize",
                        tint = if (!state.isRunning) AccentViolet else Color.Gray
                    )
                }
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = "Minimize to Background",
                        tint = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        Surface(
            color = SurfaceDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, BorderPurple, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.currentStep,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.totalCount > 0) {
                        Text(
                            text = "${state.processedCount}/${state.totalCount}",
                            color = AccentViolet,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (state.isRunning && state.totalCount == 0) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentViolet,
                        trackColor = BorderPurple
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { animatedProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = AccentViolet,
                        trackColor = BorderPurple
                    )
                }

                if (state.currentFile.isNotBlank() && state.isRunning) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = state.currentFile,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (state.isCompleted) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Background organizer finished",
                            color = Color(0xFF4CAF50),
                            fontSize = 12.sp
                        )
                    }
                }

                state.error?.let { err ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = "Error: $err", color = Color(0xFFEF5350), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "Activity Logs (${state.logs.size})",
                "Needs Input (${state.unmatchedItems.size})"
            ).forEachIndexed { idx, label ->
                val active = selectedTab == idx
                Surface(
                    color = if (active) AccentViolet else SurfaceDark,
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(0.6.dp, if (active) AccentViolet else BorderPurple),
                    modifier = Modifier
                        .weight(1f)
                        .clickable { selectedTab = idx }
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 9.dp)) {
                        Text(
                            text = label,
                            color = if (active) Color(0xFF050608) else Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceDark)
                    .border(0.5.dp, BorderPurple, RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(state.logs) { log ->
                    Text(
                        text = log,
                        color = when {
                            log.startsWith("✓") -> Color(0xFF81C784)
                            log.startsWith("✗") -> Color(0xFFE57373)
                            log.startsWith("⚠") -> Color(0xFFFFB74D)
                            else -> TextSecondary
                        },
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 16.sp
                    )
                }
            }
        } else {
            if (state.unmatchedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceDark)
                        .border(0.5.dp, BorderPurple, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No files waiting for manual input", color = TextSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.unmatchedItems, key = { it.file.path }) { qItem ->
                        Surface(
                            color = SurfaceDark,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(0.7.dp, Color(0xFFFFB74D).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D), modifier = Modifier.size(20.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(qItem.file.name, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text(qItem.reason, color = Color(0xFFFFB74D), fontSize = 10.sp)
                                    Text("Source: ${qItem.file.path}", color = TextSecondary, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        onClick = { onSkipItem(qItem) },
                                        border = BorderStroke(1.dp, BorderPurple),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Skip", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                    Button(
                                        onClick = {
                                            activeQuarantineItem = qItem
                                            onSearch(qItem.detectedTitle)
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = AccentViolet),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Match", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (activeQuarantineItem != null) {
        val target = activeQuarantineItem!!
        var query by remember(target.file.path) { mutableStateOf(target.detectedTitle) }
        var yearInput by remember(target.file.path) { mutableStateOf(target.detectedYear.orEmpty()) }
        var selectedCat by remember(target.file.path) { mutableStateOf(target.suggestedCategory) }
        var seasonInput by remember(target.file.path) { mutableStateOf(target.season.toString()) }
        var episodeInput by remember(target.file.path) { mutableStateOf(target.episode.toString()) }
        var epTitleInput by remember(target.file.path) { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { activeQuarantineItem = null },
            containerColor = SurfaceDark,
            title = { Text("Manual Match -> /VidLab", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(target.file.name, color = TextSecondary, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Title", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onSearch(query) }) {
                            Icon(Icons.Default.Search, contentDescription = "Search", tint = AccentViolet)
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Anime", "Movies", "Shows").forEach { cat ->
                            val sel = selectedCat == cat
                            Surface(
                                color = if (sel) AccentViolet else BorderPurple,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).clickable { selectedCat = cat }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                    Text(cat, color = if (sel) Color(0xFF050608) else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("KDrama", "Documentaries", "Animation").forEach { cat ->
                            val sel = selectedCat == cat
                            Surface(
                                color = if (sel) AccentViolet else BorderPurple,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.weight(1f).clickable { selectedCat = cat }
                            ) {
                                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 6.dp)) {
                                    Text(cat, color = if (sel) Color(0xFF050608) else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = yearInput,
                            onValueChange = { yearInput = it },
                            label = { Text("Year", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.weight(1f)
                        )
                        if (selectedCat != "Movies") {
                            OutlinedTextField(
                                value = seasonInput,
                                onValueChange = { seasonInput = it },
                                label = { Text("Season", fontSize = 11.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = episodeInput,
                                onValueChange = { episodeInput = it },
                                label = { Text("Ep", fontSize = 11.sp) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    if (selectedCat != "Movies") {
                        OutlinedTextField(
                            value = epTitleInput,
                            onValueChange = { epTitleInput = it },
                            label = { Text("Episode Name (optional)", fontSize = 11.sp) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    if (searchResults.isNotEmpty()) {
                        Text("Database Matches (tap to fill):", color = AccentViolet, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        LazyColumn(modifier = Modifier.heightIn(max = 130.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(searchResults.take(5)) { cand ->
                                Surface(
                                    color = BgDark,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        query = cand.title
                                        yearInput = cand.year.orEmpty()
                                        if (cand.mediaType == "movie") selectedCat = "Movies"
                                        else if (selectedCat == "Movies") selectedCat = "Shows"
                                    }
                                ) {
                                    Text(
                                        "${cand.title} ${cand.year?.let { "($it)" } ?: ""} • ${cand.mediaType.uppercase()}",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onManualMatchApply(
                            target,
                            query,
                            selectedCat,
                            yearInput.takeIf { it.isNotBlank() },
                            seasonInput.toIntOrNull() ?: 1,
                            episodeInput.toIntOrNull() ?: 1,
                            epTitleInput.takeIf { it.isNotBlank() }
                        )
                        activeQuarantineItem = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)
                ) {
                    Text("Move to /VidLab", color = Color(0xFF050608), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { activeQuarantineItem = null }) {
                    Text("Cancel", color = TextSecondary)
                }
            }
        )
    }
}
