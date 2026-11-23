package com.vidlab.app.data.prefs

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.mutableIntStateOf

object MediaSpecsCache {
    private const val PREFS_NAME = "vidlab_media_specs_cache"
    val revision = mutableIntStateOf(0)

    private val CRC_HASH_REGEX = Regex("""\[[0-9A-Fa-f]{8}]|_[0-9A-Fa-f]{8}(?=\.[a-zA-Z0-9]{2,4}$)""")
    private val RES_4K_REGEX = Regex("""(?i)(?<![a-z0-9])(2160p?|4k|uhd)(?![a-z0-9])""")
    private val RES_1080_REGEX = Regex("""(?i)(?<![a-z0-9])1080p?(?![a-z0-9])""")
    private val RES_720_REGEX = Regex("""(?i)(?<![a-z0-9])720p?(?![a-z0-9])""")
    private val RES_480_REGEX = Regex("""(?i)(?<![a-z0-9])480p?(?![a-z0-9])""")
    private val CODEC_HEVC_REGEX = Regex("""(?i)(?<![a-z0-9])(hevc|x265|h\.?265)(?![a-z0-9])""")
    private val CODEC_AV1_REGEX = Regex("""(?i)(?<![a-z0-9])av1(?![a-z0-9])""")
    private val CODEC_AVC_REGEX = Regex("""(?i)(?<![a-z0-9])(avc|x264|h\.?264)(?![a-z0-9])""")
    private val SRC_REMUX_REGEX = Regex("""(?i)(?<![a-z0-9])remux(?![a-z0-9])""")
    private val SRC_BLURAY_REGEX = Regex("""(?i)(?<![a-z0-9])(blu[\s._-]?ray|bdrip|brrip)(?![a-z0-9])""")
    private val SRC_WEBDL_REGEX = Regex("""(?i)(?<![a-z0-9])(web[\s._-]?dl|webrip)(?![a-z0-9])""")
    private val HDR_REGEX = Regex("""(?i)(?<![a-z0-9])(hdr10\+?|hdr|dolby[\s._-]?vision|dv)(?![a-z0-9])""")
    private val BIT10_REGEX = Regex("""(?i)(?<![a-z0-9])(10[\s._-]?bit|hi10p)(?![a-z0-9])""")

    private val RESOLUTION_ORDER = listOf("4K", "1080p", "720p", "480p")
    private val CODEC_ORDER = listOf("HEVC", "AV1", "AVC")
    private val SOURCE_ORDER = listOf("REMUX", "BluRay", "WEB-DL")

    fun extractTagsFromText(rawText: String): List<String> {
        if (rawText.isBlank()) return emptyList()
        val cleaned = CRC_HASH_REGEX.replace(rawText, " ")
        return buildList {
            when {
                RES_4K_REGEX.containsMatchIn(cleaned) -> add("4K")
                RES_1080_REGEX.containsMatchIn(cleaned) -> add("1080p")
                RES_720_REGEX.containsMatchIn(cleaned) -> add("720p")
                RES_480_REGEX.containsMatchIn(cleaned) -> add("480p")
            }
            when {
                CODEC_HEVC_REGEX.containsMatchIn(cleaned) -> add("HEVC")
                CODEC_AV1_REGEX.containsMatchIn(cleaned) -> add("AV1")
                CODEC_AVC_REGEX.containsMatchIn(cleaned) -> add("AVC")
            }
            when {
                SRC_REMUX_REGEX.containsMatchIn(cleaned) -> add("REMUX")
                SRC_BLURAY_REGEX.containsMatchIn(cleaned) -> add("BluRay")
                SRC_WEBDL_REGEX.containsMatchIn(cleaned) -> add("WEB-DL")
            }
            if (HDR_REGEX.containsMatchIn(cleaned)) add("HDR")
            if (BIT10_REGEX.containsMatchIn(cleaned)) add("10-bit")
        }
    }

    fun getTags(context: Context, path: String): List<String> {
        if (path.isBlank()) return emptyList()
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(path, null).orEmpty()
        return if (raw.isBlank()) emptyList() else raw.split("|").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun mergeAndSaveTags(context: Context, path: String, newTags: List<String>) {
        if (path.isBlank() || newTags.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = getTags(context, path)
        val combined = normalizeTags(existing + newTags)
        if (combined != existing) {
            prefs.edit().putString(path, combined.joinToString("|")).apply()
            revision.intValue++
        }
    }

    fun seedFromFilename(context: Context, path: String, originalName: String) {
        val extracted = extractTagsFromText("$originalName $path")
        if (extracted.isNotEmpty()) mergeAndSaveTags(context, path, extracted)
    }

    fun migrateOnRename(context: Context, oldPath: String, newPath: String) {
        if (oldPath.isBlank() || newPath.isBlank() || oldPath == newPath) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val merged = normalizeTags(getTags(context, oldPath) + extractTagsFromText(oldPath))
        if (merged.isNotEmpty()) {
            val finalTags = normalizeTags(getTags(context, newPath) + merged)
            prefs.edit().putString(newPath, finalTags.joinToString("|")).remove(oldPath).apply()
            revision.intValue++
        }
    }

    fun recordFromPlaybackTracks(context: Context, path: String, width: Int, height: Int, sampleMimeType: String?, codecs: String?) {
        if (path.isBlank()) return
        val detected = buildList {
            when {
                width >= 3600 || height >= 2000 -> add("4K")
                width >= 1800 || height >= 1000 -> add("1080p")
                width >= 1200 || height >= 700 -> add("720p")
                width > 0 || height > 0 -> add("480p")
            }
            val mimeLow = sampleMimeType.orEmpty().lowercase()
            val codecLow = codecs.orEmpty().lowercase()
            when {
                mimeLow.contains("hevc") || mimeLow.contains("h265") || codecLow.startsWith("hev") || codecLow.startsWith("hvc") -> add("HEVC")
                mimeLow.contains("av01") || mimeLow.contains("av1") || codecLow.startsWith("av01") -> add("AV1")
                mimeLow.contains("avc") || mimeLow.contains("h264") || codecLow.startsWith("avc") -> add("AVC")
            }
            if (codecLow.startsWith("hev1.2") || codecLow.startsWith("hvc1.2") || codecLow.contains(".10.")) add("10-bit")
        }
        if (detected.isNotEmpty()) mergeAndSaveTags(context, path, detected)
    }

    fun probeContainerMetadata(
        urlOrLocalPath: String,
        authUser: String = "",
        authPass: String = ""
    ): List<String> {
        val localFilePath = when {
            urlOrLocalPath.startsWith("file://") -> runCatching { android.net.Uri.parse(urlOrLocalPath).path }.getOrNull()
            !urlOrLocalPath.startsWith("http://") && !urlOrLocalPath.startsWith("https://") -> urlOrLocalPath
            else -> null
        }
        if (!localFilePath.isNullOrBlank()) {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(localFilePath)
                val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty().lowercase()
                buildList {
                    when {
                        w >= 3600 || h >= 2000 -> add("4K")
                        w >= 1800 || h >= 1000 -> add("1080p")
                        w >= 1200 || h >= 700 -> add("720p")
                        w > 0 || h > 0 -> add("480p")
                    }
                    when {
                        mime.contains("hevc") || mime.contains("h265") -> add("HEVC")
                        mime.contains("av01") || mime.contains("av1") -> add("AV1")
                        mime.contains("avc") || mime.contains("h264") -> add("AVC")
                    }
                }
            } catch (_: Throwable) {
                emptyList()
            } finally {
                runCatching { retriever.release() }
            }
        }

        return runCatching {
            val conn = (java.net.URL(urlOrLocalPath).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 3000
                readTimeout = 3000
                setRequestProperty("Range", "bytes=0-524287")
                if (authUser.isNotBlank()) {
                    val token = android.util.Base64.encodeToString(
                        "$authUser:$authPass".toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP
                    )
                    setRequestProperty("Authorization", "Basic $token")
                }
            }
            val bytes = conn.inputStream.use { inp ->
                val buf = java.io.ByteArrayOutputStream()
                val tmp = ByteArray(16384)
                var tot = 0
                while (tot < 524288) {
                    val r = inp.read(tmp)
                    if (r <= 0) break
                    buf.write(tmp, 0, r)
                    tot += r
                    // Stop early once Matroska Cluster (0x1F43B675) is reached past Tracks
                    val arr = buf.toByteArray()
                    var hitCluster = false
                    for (i in (arr.size - r - 4).coerceAtLeast(32) until arr.size - 4) {
                        if (arr[i] == 0x1F.toByte() && arr[i + 1] == 0x43.toByte() &&
                            arr[i + 2] == 0xB6.toByte() && arr[i + 3] == 0x75.toByte()
                        ) {
                            hitCluster = true
                            break
                        }
                    }
                    if (hitCluster) break
                }
                buf.toByteArray()
            }
            runCatching { conn.disconnect() }
            parseHeaderBytes(bytes)
        }.getOrDefault(emptyList())
    }

    private fun parseHeaderBytes(bytes: ByteArray): List<String> {
        if (bytes.size < 64) return emptyList()
        var maxW = 0
        var maxH = 0
        val out = mutableListOf<String>()

        var tracksIdx = -1
        for (i in 0 until bytes.size - 4) {
            if (bytes[i] == 0x16.toByte() && bytes[i + 1] == 0x54.toByte() &&
                bytes[i + 2] == 0xAE.toByte() && bytes[i + 3] == 0x6B.toByte()
            ) {
                tracksIdx = i
                break
            }
        }
        if (tracksIdx >= 0) {
            var endIdx = bytes.size - 4
            for (i in tracksIdx until bytes.size - 4) {
                if (bytes[i] == 0x1F.toByte() && bytes[i + 1] == 0x43.toByte() &&
                    bytes[i + 2] == 0xB6.toByte() && bytes[i + 3] == 0x75.toByte()
                ) {
                    endIdx = i
                    break
                }
            }
            for (i in tracksIdx until endIdx) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                if (b0 == 0xB0 && (b1 == 0x82 || b1 == 0x81)) {
                    val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                    if (v in 320..8192) maxW = maxOf(maxW, v)
                } else if (b0 == 0xBA && (b1 == 0x82 || b1 == 0x81)) {
                    val v = if (b1 == 0x82) ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF) else (bytes[i + 2].toInt() and 0xFF)
                    if (v in 240..4320) maxH = maxOf(maxH, v)
                }
            }
            val headerAscii = String(bytes, tracksIdx, (endIdx - tracksIdx).coerceAtLeast(0), Charsets.ISO_8859_1)
            when {
                headerAscii.contains("V_MPEGH/ISO/HEVC") -> out.add("HEVC")
                headerAscii.contains("V_AV1") -> out.add("AV1")
                headerAscii.contains("V_MPEG4/ISO/AVC") -> out.add("AVC")
            }
        } else {
            val ascii = String(bytes, Charsets.ISO_8859_1)
            when {
                ascii.contains("hvc1") || ascii.contains("hev1") -> out.add("HEVC")
                ascii.contains("av01") -> out.add("AV1")
                ascii.contains("avc1") -> out.add("AVC")
            }
            for (i in 0 until bytes.size - 100) {
                if (bytes[i] == 't'.code.toByte() && bytes[i + 1] == 'k'.code.toByte() &&
                    bytes[i + 2] == 'h'.code.toByte() && bytes[i + 3] == 'd'.code.toByte()
                ) {
                    val ver = bytes[i + 4].toInt() and 0xFF
                    val wOff = if (ver == 1) i + 92 else i + 80
                    if (wOff + 6 < bytes.size) {
                        val w = ((bytes[wOff].toInt() and 0xFF) shl 8) or (bytes[wOff + 1].toInt() and 0xFF)
                        val h = ((bytes[wOff + 4].toInt() and 0xFF) shl 8) or (bytes[wOff + 5].toInt() and 0xFF)
                        if (w in 320..8192 && h in 240..4320) {
                            maxW = maxOf(maxW, w)
                            maxH = maxOf(maxH, h)
                        }
                    }
                }
            }
        }

        val res = when {
            maxW >= 3600 || maxH >= 2000 -> "4K"
            maxW >= 1800 || maxH >= 1000 -> "1080p"
            maxW >= 1200 || maxH >= 700 -> "720p"
            maxW > 0 || maxH > 0 -> "480p"
            else -> null
        }
        return listOfNotNull(res) + out.distinct()
    }

    private fun normalizeTags(raw: List<String>): List<String> {
        val distinct = raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val bestRes = RESOLUTION_ORDER.firstOrNull { r -> distinct.any { it.equals(r, ignoreCase = true) } }
        val bestCodec = CODEC_ORDER.firstOrNull { c -> distinct.any { it.equals(c, ignoreCase = true) } }
        val bestSrc = SOURCE_ORDER.firstOrNull { s -> distinct.any { it.equals(s, ignoreCase = true) } }
        val extras = distinct.filter { tag ->
            RESOLUTION_ORDER.none { it.equals(tag, ignoreCase = true) } &&
                CODEC_ORDER.none { it.equals(tag, ignoreCase = true) } &&
                SOURCE_ORDER.none { it.equals(tag, ignoreCase = true) }
        }
        return listOfNotNull(bestRes, bestCodec, bestSrc) + extras
    }
}
