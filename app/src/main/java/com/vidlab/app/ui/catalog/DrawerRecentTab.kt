package com.vidlab.app.ui.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidlab.app.data.model.MediaFeedSection
import com.vidlab.app.data.model.ScrapedMediaItem
import com.vidlab.app.data.prefs.WatchProgressStore

@Composable
internal fun DrawerRecentTab(
    sections: List<MediaFeedSection>,
    onSelectHistoryItem: (ScrapedMediaItem) -> Unit
) {
    val context = LocalContext.current
    val progressStore = remember { WatchProgressStore(context) }
    val rev = WatchProgressStore.revision.intValue
    val historyList = remember(sections, rev) {
        progressStore.getWatchHistory(sections.flatMap { it.items }.distinctBy { it.id })
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "RECENT WATCH HISTORY (${historyList.size})",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        if (historyList.isNotEmpty()) {
            Text(
                "Clear",
                color = DrawerAccentViolet,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { progressStore.clearHistory() }
            )
        }
    }

    if (historyList.isEmpty()) {
        Surface(
            color = DrawerSurfaceDark,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "No watch history yet. Play any movie or episode and it will appear here.",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                modifier = Modifier.padding(14.dp)
            )
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (entry in historyList) {
                Surface(
                    onClick = { onSelectHistoryItem(entry.item) },
                    color = DrawerSurfaceDark,
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(0.8.dp, DrawerBorderPurple),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        AsyncImage(
                            model = entry.item.posterUrl ?: entry.item.backdropUrl,
                            contentDescription = entry.item.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(width = 44.dp, height = 64.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                entry.item.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val sub = buildString {
                                if (entry.episodeLabel != null) append("${entry.episodeLabel} • ")
                                if (entry.progress.watched) append("Watched")
                                else append("${(entry.progress.fraction * 100).toInt()}% watched")
                            }
                            Text(sub, color = Color(0xFF94A3B8), fontSize = 11.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { if (entry.progress.watched) 1f else entry.progress.fraction },
                                color = DrawerAccentViolet,
                                trackColor = DrawerBorderPurple,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(3.dp))
                            )
                        }
                    }
                }
            }
        }
    }
}
