package com.vidlab.app.ui.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

data class OnlineSubtitleResult(
    val id: String,
    val language: String,
    val releaseName: String,
    val url: String,
    val source: String
)

object OnlineSubtitleHelper {

    private fun fetchString(urlStr: String, headers: Map<String, String> = emptyMap()): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4500
            readTimeout = 4500
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun searchSubtitles(
        query: String,
        season: Int?,
        episode: Int?,
        languageFilter: String
    ): List<OnlineSubtitleResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<OnlineSubtitleResult>()
        val seMatch = Regex("""(?i)S(\d{1,2})\s*E(\d{1,3})""").find(query)
        val effSeason = season ?: seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val effEpisode = episode ?: seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
        val onlyEn = languageFilter.equals("en", ignoreCase = true)

        val cleanTitle = query
            .replace(Regex("""(?i)\s*[-–:|•]\s*S\d{1,2}\s*E\d{1,3}.*"""), "")
            .replace(Regex("""(?i)S\d{1,2}\s*E\d{1,3}.*"""), "")
            .replace(Regex("""(?i)\(\d{4}\)"""), "")
            .replace(Regex("""\[[^\]]*]"""), "")
            .replace(Regex("""\s*[-–:|•]+\s*$"""), "")
            .trim()
        if (cleanTitle.isBlank()) return@withContext emptyList()

        val candidateTitles = buildList {
            add(cleanTitle)
            if (":" in cleanTitle) {
                val beforeColon = cleanTitle.substringBefore(":").trim()
                if (beforeColon.length >= 4) add(beforeColon)
            }
            val words = cleanTitle.split(Regex("""\s+"""))
            if (words.size > 4) add(words.take(4).joinToString(" "))
        }.distinct()

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 VidLab/1.2.1",
            "Accept" to "application/json, text/plain, */*"
        )
        val type = if (effSeason != null && effEpisode != null) "series" else "movie"

        for (candidate in candidateTitles.take(2)) {
            if (results.size >= 15) break
            val encoded = URLEncoder.encode(candidate, "UTF-8")

            // 1. Stremio Cinemeta + OpenSubtitles v3
            runCatching {
                val metaUrl = "https://v3-cinemeta.strem.io/catalog/$type/top/search=$encoded.json"
                val metas = JSONObject(fetchString(metaUrl, headers)).optJSONArray("metas")
                if (metas != null) {
                    for (mIdx in 0 until minOf(metas.length(), 3)) {
                        val imdbId = metas.getJSONObject(mIdx).optString("imdb_id").ifBlank {
                            metas.getJSONObject(mIdx).optString("id")
                        }
                        if (!imdbId.startsWith("tt")) continue
                        val subTarget = if (type == "series") "$imdbId:$effSeason:$effEpisode" else imdbId
                        val subUrl = "https://opensubtitles-v3.strem.io/subtitles/$type/$subTarget.json"
                        val subs = JSONObject(fetchString(subUrl, headers)).optJSONArray("subtitles") ?: continue
                        for (i in 0 until subs.length()) {
                            val obj = subs.getJSONObject(i)
                            val lang = obj.optString("lang", "en").lowercase()
                            if (onlyEn && !lang.startsWith("en")) continue
                            val url = obj.optString("url")
                            if (url.isBlank()) continue
                            val rel = obj.optString("release").ifBlank { "$candidate (${lang.uppercase()}) #${i + 1}" }
                            results.add(OnlineSubtitleResult(obj.optString("id", "os3_$i"), lang.uppercase(), rel, url, "OpenSubtitles"))
                        }
                        if (results.isNotEmpty()) break
                    }
                }
            }

            // 2. SubDL Public API
            if (results.size < 10) {
                runCatching {
                    val seQuery = if (effSeason != null && effEpisode != null) "$candidate S%02dE%02d".format(effSeason, effEpisode) else candidate
                    val qEnc = URLEncoder.encode(seQuery, "UTF-8")
                    val subdlUrl = "https://api.subdl.com/api/v1/subtitles?api_key=subdl_free&film_name=$qEnc" +
                        (if (effSeason != null) "&season_number=$effSeason" else "") +
                        (if (effEpisode != null) "&episode_number=$effEpisode" else "") +
                        (if (onlyEn) "&languages=en" else "")
                    val subs = JSONObject(fetchString(subdlUrl, headers)).optJSONArray("subtitles")
                    if (subs != null) {
                        for (i in 0 until minOf(subs.length(), 20)) {
                            val obj = subs.getJSONObject(i)
                            val lang = obj.optString("lang", "en").lowercase()
                            if (onlyEn && !lang.startsWith("en")) continue
                            val rawUrl = obj.optString("url")
                            if (rawUrl.isBlank()) continue
                            val fullUrl = if (rawUrl.startsWith("http")) rawUrl else "https://dl.subdl.com$rawUrl"
                            val rel = obj.optString("release_name", obj.optString("name", "$seQuery.srt"))
                            results.add(OnlineSubtitleResult("subdl_$i", lang.uppercase(), rel, fullUrl, "SubDL"))
                        }
                    }
                }
            }

            // 3. OpenSubtitles Legacy REST Search
            if (results.size < 10) {
                runCatching {
                    val sePart = if (effSeason != null && effEpisode != null) "/season-$effSeason/episode-$effEpisode" else ""
                    val langPart = if (onlyEn) "/sublanguageid-eng" else ""
                    val url = "https://rest.opensubtitles.org/search/query-$encoded$sePart$langPart"
                    val arr = JSONArray(fetchString(url, mapOf("User-Agent" to "TemporaryUserAgent")))
                    for (i in 0 until minOf(arr.length(), 20)) {
                        val obj = arr.getJSONObject(i)
                        val dlLink = obj.optString("SubDownloadLink").replace(".gz", "")
                        if (dlLink.isBlank()) continue
                        results.add(
                            OnlineSubtitleResult(
                                id = obj.optString("IDSubtitleFile", "osr_$i"),
                                language = obj.optString("ISO639", "en").uppercase(),
                                releaseName = obj.optString("SubFileName", "$candidate.srt"),
                                url = dlLink,
                                source = "OpenSubtitles REST"
                            )
                        )
                    }
                }
            }

            // 4. Podnapisi Public Search
            if (results.size < 10) {
                runCatching {
                    val langParam = if (onlyEn) "&language=en" else ""
                    val seParam = if (effSeason != null && effEpisode != null) "&seasons=$effSeason&episodes=$effEpisode" else ""
                    val podUrl = "https://www.podnapisi.net/subtitles/search/old?sXML=1&sK=$encoded$seParam$langParam"
                    val blocks = fetchString(podUrl, headers).split("<subtitle>").drop(1)
                    for ((idx, b) in blocks.take(15).withIndex()) {
                        val pid = Regex("<pid>(.*?)</pid>").find(b)?.groupValues?.getOrNull(1)?.trim() ?: continue
                        val rel = Regex("<release>(.*?)</release>").find(b)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { "$candidate #$idx" } ?: "$candidate #$idx"
                        val lang = Regex("<language>(.*?)</language>").find(b)?.groupValues?.getOrNull(1)?.trim()?.uppercase() ?: "EN"
                        results.add(OnlineSubtitleResult("pod_$pid", lang, rel, "https://www.podnapisi.net/subtitles/$pid/download", "Podnapisi"))
                    }
                }
            }
        }
        results.distinctBy { it.url }.take(35)
    }

    suspend fun downloadAndShiftSubtitle(context: Context, subUrl: String, fileNameHint: String, offsetMs: Long = 0L): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            val rawText = httpGet(subUrl) ?: return@runCatching null
            val shifted = if (offsetMs != 0L) shiftSrtOrVttTimestamps(rawText, offsetMs) else rawText
            val dir = File(context.cacheDir, "online_subtitles").apply { mkdirs() }
            val safeName = fileNameHint.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50).ifBlank { "sub" }
            val ext = if (shifted.trimStart().startsWith("WEBVTT")) "vtt" else "srt"
            val outFile = File(dir, "${safeName}_${System.currentTimeMillis()}.$ext")
            outFile.writeText(shifted, Charsets.UTF_8)
            Uri.fromFile(outFile)
        }.getOrNull()
    }

    fun shiftLocalUriSubtitle(context: Context, sourceUri: Uri, offsetMs: Long): Uri? {
        return runCatching {
            val text = context.contentResolver.openInputStream(sourceUri)?.bufferedReader()?.use { it.readText() } ?: return null
            val shifted = shiftSrtOrVttTimestamps(text, offsetMs)
            val dir = File(context.cacheDir, "online_subtitles").apply { mkdirs() }
            val ext = if (shifted.trimStart().startsWith("WEBVTT")) "vtt" else "srt"
            val outFile = File(dir, "synced_${System.currentTimeMillis()}.$ext")
            outFile.writeText(shifted, Charsets.UTF_8)
            Uri.fromFile(outFile)
        }.getOrNull()
    }

    private fun shiftSrtOrVttTimestamps(content: String, offsetMs: Long): String {
        val regex = Regex("""(\d{2}):(\d{2}):(\d{2})[,.](\d{3})""")
        return regex.replace(content) { m ->
            val total = (m.groupValues[1].toLong() * 3600_000L + m.groupValues[2].toLong() * 60_000L + m.groupValues[3].toLong() * 1000L + m.groupValues[4].toLong() + offsetMs).coerceAtLeast(0L)
            val sep = if (m.value.contains(',')) ',' else '.'
            String.format(Locale.US, "%02d:%02d:%02d%c%03d", total / 3600_000L, (total % 3600_000L) / 60_000L, (total % 60_000L) / 1000L, sep, total % 1000L)
        }
    }

    private fun httpGet(urlStr: String, headers: Map<String, String> = emptyMap()): String? {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000; readTimeout = 10000; instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) VidLab/1.2.1")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try { if (conn.responseCode in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null } finally { conn.disconnect() }
    }
}
