package com.vidlab.app.ui.player

import android.content.Context
import android.net.Uri
import com.vidlab.app.data.remote.RcloneEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

internal suspend fun fetchSiblingEpisodes(
    context: Context,
    currentFilePath: String
): List<Pair<String, String>> = withContext(Dispatchers.IO) {
    if (currentFilePath.isBlank() || currentFilePath.startsWith("http")) {
        return@withContext emptyList()
    }
    val parentDir = currentFilePath.substringBeforeLast("/", "")
    if (parentDir.isBlank()) return@withContext emptyList()
    runCatching {
        val encodedDir = parentDir.split("/").joinToString("/") { seg ->
            if (seg.isEmpty()) "" else Uri.encode(seg)
        }
        val baseUrl = if (RcloneEngine.isRcloneModeEnabled(context)) {
            RcloneEngine.STREAM_BASE_URL
        } else {
            RcloneEngine.getWebDavBaseUrl(context)
        }
        val url = URL("$baseUrl$encodedDir/")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PROPFIND"
            setRequestProperty("Depth", "1")
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        if (conn.responseCode !in 200..299) return@runCatching emptyList()
        val xml = conn.inputStream.bufferedReader().use { it.readText() }
        val hrefRegex = Regex("<[^>]*href[^>]*>([^<]+)</[^>]*href>", RegexOption.IGNORE_CASE)
        val videoExts = setOf("mkv", "mp4", "avi", "webm", "mov", "m4v")
        hrefRegex.findAll(xml)
            .map { URLDecoder.decode(it.groupValues[1], "UTF-8").trimEnd('/') }
            .filter { path ->
                val ext = path.substringAfterLast('.', "").lowercase()
                ext in videoExts && path != parentDir
            }
            .distinct()
            .sorted()
            .map { path -> path to path.substringAfterLast('/') }
            .toList()
    }.getOrDefault(emptyList())
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
