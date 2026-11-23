package com.vidlab.app.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class WebDavClient(
    private val baseUrl: String = "http://127.0.0.1:8080",
    private val username: String = "",
    private val password: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(35, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) {

    private fun cleanBaseUrl(): String = baseUrl.trimEnd('/')

    // RFC 3986 path segment escaping: spaces -> %20, literal '+' -> %2B
    internal fun escapeUrlPath(path: String): String {
        return path.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) "" else {
                URLEncoder.encode(seg, "UTF-8")
                    .replace("+", "%20")
            }
        }
    }

    // Safe decoding that NEVER turns literal '+' into spaces
    internal fun decodeUrlPath(encodedPath: String): String {
        return runCatching {
            URLDecoder.decode(encodedPath.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(encodedPath)
    }

    suspend fun listDirectory(path: String): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedPath = "/" + path.trim('/')
            val encodedPath = escapeUrlPath(normalizedPath)
            val fullUrl = cleanBaseUrl() + (if (encodedPath.isEmpty() || encodedPath == "/") "/" else "$encodedPath/")

            val propfindXml = """
                <?xml version="1.0" encoding="utf-8" ?>
                <D:propfind xmlns:D="DAV:">
                    <D:prop>
                        <D:resourcetype/>
                        <D:getcontentlength/>
                    </D:prop>
                </D:propfind>
            """.trimIndent()

            val reqBuilder = Request.Builder()
                .url(fullUrl)
                .method("PROPFIND", propfindXml.toRequestBody("application/xml".toMediaType()))
                .header("Depth", "1")
            if (username.isNotBlank() || password.isNotBlank()) {
                reqBuilder.header("Authorization", Credentials.basic(username, password))
            }
            val request = reqBuilder.build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful && response.code != 207) {
                response.close()
                return@runCatching emptyList<MediaFile>()
            }

            val bodyString = response.body?.string() ?: ""
            response.close()
            if (bodyString.isBlank()) return@runCatching emptyList<MediaFile>()

            parseMultiStatus(bodyString, normalizedPath)
        }
    }

    private val createdDirsCache = ConcurrentHashMap<String, Boolean>()
    private val dirCreationMutex = Mutex()
    fun clearDirectoryCache() { createdDirsCache.clear() }

    suspend fun ensureDirectoryExists(dirPath: String) = withContext(Dispatchers.IO) {
        val normalized = "/" + dirPath.trim('/')
        if (normalized == "/" || createdDirsCache.containsKey(normalized)) return@withContext
        dirCreationMutex.withLock {
            if (createdDirsCache.containsKey(normalized)) return@withLock
            val segments = normalized.split('/').filter { it.isNotBlank() }
            var current = ""
            for (seg in segments) {
                current += "/$seg"
                if (createdDirsCache.containsKey(current)) continue
                val url = cleanBaseUrl() + escapeUrlPath(current) + "/"
                val request = Request.Builder().url(url).method("MKCOL", null).build()
                val resp = runCatching { client.newCall(request).execute() }.getOrNull()
                val code = resp?.code ?: 0
                resp?.close()
                if (code in 200..299 || code == 405 || code == 301 || code == 302) {
                    createdDirsCache[current] = true
                    if (code in 200..299) delay(150L)
                }
            }
        }
    }

    suspend fun pathExists(targetPath: String): Boolean = withContext(Dispatchers.IO) {
        val norm = "/" + targetPath.trim('/')
        val parent = norm.substringBeforeLast('/', "/")
        val name = norm.substringAfterLast('/')
        listDirectory(parent).getOrDefault(emptyList()).any { it.name.equals(name, ignoreCase = false) }
    }

    suspend fun ensureVidLabVaultFolders() {
        val categories = listOf("Anime", "Movies", "Shows", "Documentaries", "KDrama", "Animation")
        ensureDirectoryExists("/VidLab")
        for (cat in categories) {
            ensureDirectoryExists("/VidLab/$cat")
        }
    }

    suspend fun moveFile(sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val srcNormalized = "/" + sourcePath.trim('/')
            val dstNormalized = "/" + destPath.trim('/')
            if (srcNormalized == dstNormalized) return@runCatching Unit
            val destFolder = dstNormalized.substringBeforeLast('/')
            val srcFolder = srcNormalized.substringBeforeLast('/')
            if (destFolder.isNotBlank() && destFolder != "/" && destFolder != srcFolder) {
                ensureDirectoryExists(destFolder)
            }
            val srcEncoded = escapeUrlPath(srcNormalized)
            val dstEncoded = escapeUrlPath(dstNormalized)
            val srcUrl = cleanBaseUrl() + srcEncoded
            val dstAbsoluteUrl = cleanBaseUrl() + dstEncoded
            val backoffMs = longArrayOf(0L, 500L, 1500L, 3500L)
            var lastCode = 0
            var lastErr = ""
            for (attempt in backoffMs.indices) {
                if (backoffMs[attempt] > 0L) delay(backoffMs[attempt])
                for (dst in listOf(dstAbsoluteUrl, dstEncoded)) {
                    val request = Request.Builder()
                        .url(srcUrl)
                        .header("Destination", dst)
                        .header("Overwrite", "T")
                        .method("MOVE", null)
                        .build()
                    val resp = runCatching { client.newCall(request).execute() }.getOrNull()
                    if (resp == null) { lastErr = "Connection reset"; continue }
                    lastCode = resp.code
                    lastErr = resp.body?.string() ?: ""
                    resp.close()
                    if (lastCode in 200..299 || lastCode == 412) return@runCatching Unit
                    if (lastCode == 404 && pathExists(dstNormalized)) return@runCatching Unit
                    if (lastCode == 409 && destFolder.isNotBlank() && destFolder != "/") {
                        createdDirsCache.remove(destFolder)
                        ensureDirectoryExists(destFolder)
                    }
                }
                if (lastCode !in setOf(0, 403, 409, 423, 429, 500, 502, 503, 504)) break
            }
            if (pathExists(dstNormalized)) return@runCatching Unit
            throw Exception("HTTP $lastCode:$lastErr")
        }
    }

    internal fun parseMultiStatus(xml: String, requestedPath: String): List<MediaFile> {
        val files = mutableListOf<MediaFile>()
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray()))
        val responses = doc.getElementsByTagNameNS("*", "response")

        val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")

        for (i in 0 until responses.length) {
            val respElem = responses.item(i) as? Element ?: continue
            val hrefNode = respElem.getElementsByTagNameNS("*", "href").item(0) ?: continue
            val rawHref = hrefNode.textContent.trim()

            var pathOnly = rawHref
            if (pathOnly.startsWith("http://", ignoreCase = true) || pathOnly.startsWith("https://", ignoreCase = true)) {
                pathOnly = "/" + pathOnly.substringAfter("://").substringAfter('/', "")
            }

            // Decode safely without turning '+' into space
            val decodedHref = decodeUrlPath(pathOnly)
            val propstat = respElem.getElementsByTagNameNS("*", "propstat").item(0) as? Element
            val prop = propstat?.getElementsByTagNameNS("*", "prop")?.item(0) as? Element

            val isDirectory = prop?.getElementsByTagNameNS("*", "resourcetype")?.item(0)?.let {
                (it as Element).getElementsByTagNameNS("*", "collection").length > 0
            } ?: false

            val contentLength = prop?.getElementsByTagNameNS("*", "getcontentlength")?.item(0)?.textContent?.toLongOrNull() ?: 0L

            val pathTrimmed = "/" + decodedHref.trim('/')
            val reqTrimmed = "/" + requestedPath.trim('/')

            if (pathTrimmed.equals(reqTrimmed, ignoreCase = true) || pathTrimmed == "/") {
                continue
            }

            val name = pathTrimmed.substringAfterLast('/')
            val ext = name.substringAfterLast('.', "").lowercase()
            val isVideo = ext in videoExtensions

            files.add(
                MediaFile(
                    name = name,
                    path = pathTrimmed,
                    isDirectory = isDirectory,
                    size = contentLength,
                    isVideo = isVideo
                )
            )
        }
        return files
    }
}
