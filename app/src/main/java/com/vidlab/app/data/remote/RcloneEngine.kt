package com.vidlab.app.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.content.Context
import android.content.Intent
import android.os.Build
import com.vidlab.app.data.model.MediaFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class RcloneRemoteConfig(
    val name: String,
    val type: String,
    val rootSubpath: String = "",
    val enabled: Boolean = true
)

@Serializable
data class RcloneLsJsonEntry(
    val Path: String = "",
    val Name: String = "",
    val Size: Long = 0L,
    val IsDir: Boolean = false
)

object RcloneEngine {
    const val STREAM_PORT = 18080
    const val STREAM_BASE_URL = "http://127.0.0.1:$STREAM_PORT"

    private const val PREFS_NAME = "vidlab_rclone_prefs"
    private const val KEY_REMOTES_JSON = "configured_remotes_json"
    private const val KEY_USE_RCLONE = "use_embedded_rclone"
    private const val KEY_SOURCE_MODE = "vidlab_source_mode" // "rclone", "webdav", "local"

    fun getSourceMode(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SOURCE_MODE, null)
            ?: when {
                prefs.contains(KEY_USE_RCLONE) -> if (prefs.getBoolean(KEY_USE_RCLONE, false)) "rclone" else "webdav"
                getConfigFile(context).exists() -> "rclone"
                else -> "local"
            }
    }

    fun setSourceMode(context: Context, mode: String) {
        val normalized = mode.lowercase().trim()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_SOURCE_MODE, normalized)
            .putBoolean(KEY_USE_RCLONE, normalized == "rclone")
            .apply()
    }

    fun isLocalStorageModeEnabled(context: Context): Boolean = getSourceMode(context) == "local"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    @Volatile
    private var serverProcess: Process? = null

    @Volatile
    private var activeServedConfigSig: String = ""

    fun getBinaryFile(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, "librclone.so")
    }

    fun getConfigFile(context: Context): File {
        return File(context.filesDir, "rclone.conf")
    }

    fun getRuntimeServeConfigFile(context: Context): File {
        return File(context.filesDir, "rclone_serve.conf")
    }

    fun hasAnyConfiguredSource(context: Context): Boolean {
        if (isLocalStorageModeEnabled(context)) return true
        val hasCustomWebDav = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .contains("webdav_base_url")
        return hasValidConfig(context) || hasCustomWebDav
    }

    fun getWebDavBaseUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("webdav_base_url", "http://127.0.0.1:8080")
            ?.trimEnd('/') ?: "http://127.0.0.1:8080"
    }

    fun isRcloneServeWebDavEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean("rclone_serve_webdav_enabled", false)
    fun getRcloneServeUsername(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("rclone_serve_username", "") ?: ""
    fun getRcloneServePassword(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("rclone_serve_password", "") ?: ""
    fun getWebDavUsername(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("webdav_username", "") ?: ""
    fun getWebDavPassword(context: Context): String = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString("webdav_password", "") ?: ""
    fun setWebDavBaseUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("webdav_base_url", url.trim().trimEnd('/')).apply()
    }



    fun setWebDavCredentials(context: Context, user: String, pass: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("webdav_username", user.trim())
            .putString("webdav_password", pass)
            .apply()
    }


    fun setRcloneServeWebDavEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("rclone_serve_webdav_enabled", enabled)
            .apply()
    }


    fun setRcloneServeCredentials(context: Context, user: String, pass: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("rclone_serve_username", user.trim())
            .putString("rclone_serve_password", pass)
            .apply()
    }

    fun isRcloneModeEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_USE_RCLONE, getConfigFile(context).exists())
    }

    fun setRcloneModeEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_USE_RCLONE, enabled).apply()
    }

    fun removeConfig(context: Context) {
        stopServer()
        runCatching { getConfigFile(context).delete() }
        runCatching { getRuntimeServeConfigFile(context).delete() }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("configured_remotes_json")
            .putBoolean(KEY_USE_RCLONE, false)
            .apply()
    }

    suspend fun moveLocalFile(context: Context, sourcePath: String, destPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val extRoot = android.os.Environment.getExternalStorageDirectory()
            val vidLabRoot = File(extRoot, "VidLab")
            val srcNorm = "/" + sourcePath.trim('/')
            val dstNorm = "/" + destPath.trim('/')
            val srcFile = when {
                sourcePath.startsWith(extRoot.absolutePath) -> File(sourcePath)
                srcNorm.equals("/VidLab", ignoreCase = true) -> vidLabRoot
                srcNorm.startsWith("/VidLab/", ignoreCase = true) -> File(vidLabRoot, srcNorm.substring("/VidLab/".length))
                else -> File(extRoot, srcNorm.trimStart('/'))
            }
            val dstFile = when {
                dstNorm.startsWith("/VidLab/", ignoreCase = true) -> File(vidLabRoot, dstNorm.substring("/VidLab/".length))
                else -> File(extRoot, dstNorm.trimStart('/'))
            }
            if (!srcFile.exists()) throw java.io.FileNotFoundException("Source file not found: ${srcFile.absolutePath}")
            if (srcFile.canonicalPath == dstFile.canonicalPath) return@runCatching Unit
            dstFile.parentFile?.mkdirs()
            val moved = srcFile.renameTo(dstFile) || run {
                srcFile.copyTo(dstFile, overwrite = true)
                if (dstFile.exists() && dstFile.length() == srcFile.length()) {
                    srcFile.delete()
                    true
                } else false
            }
            if (!moved) throw java.io.IOException("Failed to move ${srcFile.name} to ${dstFile.absolutePath}")
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(srcFile.absolutePath, dstFile.absolutePath),
                null,
                null
            )
        }
    }

    suspend fun pruneEmptyDirectories(context: Context, dirPaths: Collection<String>) = withContext(Dispatchers.IO) {
        if (dirPaths.isEmpty()) return@withContext
        val protectedNames = setOf("vidlab", "movies", "shows", "tv shows", "anime", "kdrama", "documentaries", "animation")
        val sortedDirs = dirPaths
            .map { "/" + it.trim('/') }
            .filter { it.length > 1 && it.substringAfterLast('/').lowercase() !in protectedNames }
            .distinct()
            .sortedByDescending { it.length }

        if (isLocalStorageModeEnabled(context)) {
            val extRoot = android.os.Environment.getExternalStorageDirectory()
            for (d in sortedDirs) {
                var cur: File? = File(extRoot, d.trimStart('/'))
                while (cur != null && cur.absolutePath.startsWith(extRoot.absolutePath) && cur != extRoot) {
                    if (cur.name.lowercase() in protectedNames) break
                    val children = cur.listFiles()
                    if (children != null && children.isEmpty()) {
                        cur.delete()
                        cur = cur.parentFile
                    } else break
                }
            }
            return@withContext
        }

        if (isRcloneModeEnabled(context) && hasValidConfig(context)) {
            val bin = getBinaryFile(context)
            if (!bin.exists()) return@withContext
            val runtimeConf = getRuntimeServeConfigFile(context)
            for (d in sortedDirs) {
                var cur = d
                while (cur.length > 1 && cur.substringAfterLast('/').lowercase() !in protectedNames) {
                    val spec = resolveRemoteSpec(context, cur)
                    val pb = ProcessBuilder(listOf(bin.absolutePath, "rmdir", spec, "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
                    pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                    pb.environment()["HOME"] = context.filesDir.absolutePath
                    val exit = runCatching { pb.start().waitFor() }.getOrDefault(1)
                    if (exit != 0) break
                    val parent = "/" + cur.trim('/').substringBeforeLast('/', "")
                    if (parent == cur || parent.length <= 1) break
                    cur = parent
                }
            }
        }
    }

    fun hasValidConfig(context: Context): Boolean {
        val f = getConfigFile(context)
        return f.exists() && parseRemotesFromConf(f.readText()).isNotEmpty()
    }

    fun parseRemotesFromConf(confText: String): List<Pair<String, String>> {
        val remotes = mutableListOf<Pair<String, String>>()
        var currentSection: String? = null
        var currentType = "remote"
        for (rawLine in confText.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]") && line.length > 2) {
                if (currentSection != null) {
                    remotes.add(currentSection to currentType)
                }
                currentSection = line.substring(1, line.length - 1).trim()
                currentType = "remote"
            } else if (currentSection != null && line.startsWith("type")) {
                val eqIdx = line.indexOf('=')
                if (eqIdx > 0) {
                    currentType = line.substring(eqIdx + 1).trim()
                }
            }
        }
        if (currentSection != null) {
            remotes.add(currentSection to currentType)
        }
        return remotes.filter { !it.first.startsWith("_") }
    }

    fun importConfigText(context: Context, confText: String): Result<List<RcloneRemoteConfig>> {
        return runCatching {
            val parsed = parseRemotesFromConf(confText)
            require(parsed.isNotEmpty()) { "No [remote] sections found in rclone.conf" }
            getConfigFile(context).writeText(confText.trim() + "\n")

            val existing = getConfiguredRemotes(context).associateBy { it.name }
            // Prefer 'vault' or 'decrypted' enabled by default if present, otherwise enable first remote
            val hasPreferred = parsed.any { it.first.equals("vault", true) || it.first.equals("decrypted", true) }
            val updated = parsed.mapIndexed { idx, (name, type) ->
                val prev = existing[name]
                if (prev != null) {
                    prev.copy(type = type)
                } else {
                    val autoEnable = if (hasPreferred) {
                        name.equals("vault", true) || name.equals("decrypted", true)
                    } else {
                        idx == 0
                    }
                    RcloneRemoteConfig(name = name, type = type, rootSubpath = "", enabled = autoEnable)
                }
            }
            saveConfiguredRemotes(context, updated)
            setRcloneModeEnabled(context, true)
            restartServeIfNeeded(context)
            updated
        }
    }

    fun getConfiguredRemotes(context: Context): List<RcloneRemoteConfig> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_REMOTES_JSON, null)
        if (!raw.isNullOrBlank()) {
            val saved = runCatching { json.decodeFromString<List<RcloneRemoteConfig>>(raw) }.getOrNull()
            if (!saved.isNullOrEmpty()) return saved
        }
        val confFile = getConfigFile(context)
        if (!confFile.exists()) return emptyList()
        return parseRemotesFromConf(confFile.readText()).mapIndexed { idx, (name, type) ->
            RcloneRemoteConfig(name, type, "", idx == 0)
        }
    }

    fun saveConfiguredRemotes(context: Context, remotes: List<RcloneRemoteConfig>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_REMOTES_JSON, json.encodeToString(remotes)).apply()
    }

    /**
     * Builds a runtime rclone config containing a unified [vidlab_mount] combine remote
     * mapping every enabled remote so `/RemoteName/...` or single-remote root paths resolve seamlessly.
     */
    private fun prepareRuntimeServeTarget(context: Context): String? {
        val baseConf = getConfigFile(context)
        if (!baseConf.exists()) return null
        val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
        if (enabledRemotes.isEmpty()) return null

        val runtimeConf = getRuntimeServeConfigFile(context)
        val sb = StringBuilder(baseConf.readText().trim()).append("\n\n")

        if (enabledRemotes.size == 1) {
            val r = enabledRemotes.first()
            val sub = r.rootSubpath.trim('/')
            runtimeConf.writeText(sb.toString())
            return if (sub.isEmpty()) "${r.name}:" else "${r.name}:$sub"
        } else {
            val upstreams = enabledRemotes.joinToString(" ") { r ->
                val sub = r.rootSubpath.trim('/')
                val target = if (sub.isEmpty()) "${r.name}:" else "${r.name}:$sub"
                "${r.name}=$target"
            }
            sb.append("[vidlab_combined]\n")
            sb.append("type = combine\n")
            sb.append("upstreams = $upstreams\n")
            runtimeConf.writeText(sb.toString())
            return "vidlab_combined:"
        }
    }

    suspend fun streamEnabledRemotes(
        context: Context,
        onBatchDiscovered: suspend (List<MediaFile>) -> Unit
    ): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            require(bin.exists()) { "Embedded rclone binary missing" }
            val baseConf = getConfigFile(context)
            require(baseConf.exists()) { "Import rclone.conf first in the menu" }
            val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
            require(enabledRemotes.isNotEmpty()) { "Select at least one rclone remote in the menu" }

            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")
            val multiRemote = enabledRemotes.size > 1
            val allFound = java.util.Collections.synchronizedList(mutableListOf<MediaFile>())

            coroutineScope {
                enabledRemotes.map { remote ->
                    async(Dispatchers.IO) {
                        val sub = remote.rootSubpath.trim('/')
                        val target = if (sub.isEmpty()) "${remote.name}:" else "${remote.name}:$sub"
                        val cmd = mutableListOf(
                            bin.absolutePath, "lsf",
                            "--config", baseConf.absolutePath,
                            "--recursive", "--max-depth", "6", "--files-only",
                            "--format", "sp", "--separator", "|",
                            "--include", "*.{mp4,mkv,avi,mov,webm,ts,m4v,flv,MP4,MKV,AVI,MOV,WEBM,TS,M4V,FLV}",
                            target
                        )
                        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
                        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                        pb.environment()["HOME"] = context.filesDir.absolutePath
                        val proc = pb.start()
                        val batch = mutableListOf<MediaFile>()
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                val sep = line.indexOf('|')
                                if (sep <= 0) continue
                                val sizeVal = line.substring(0, sep).trim().toLongOrNull() ?: 0L
                                val relPath = line.substring(sep + 1).trim().trim('/')
                                val name = relPath.substringAfterLast('/')
                                val ext = name.substringAfterLast('.', "").lowercase()
                                if (ext !in videoExtensions) continue
                                val logicalPath = if (multiRemote) "/${remote.name}/$relPath" else "/$relPath"
                                val mf = MediaFile(name = name, path = logicalPath, size = sizeVal, isDirectory = false)
                                allFound.add(mf)
                                batch.add(mf)
                                if (batch.size >= 20) {
                                    batch.clear()
                                    onBatchDiscovered(allFound.toList())
                                }
                            }
                        }
                        val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                        val exitCode = proc.waitFor()
                        if (exitCode != 0 && allFound.isEmpty()) {
                            throw Exception("rclone (${remote.name}): ${stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit $exitCode"}")
                        }
                    }
                }.awaitAll()
            }
            if (allFound.isNotEmpty()) onBatchDiscovered(allFound.toList())
            allFound.toList()
        }
    }

    suspend fun scanEnabledRemotes(context: Context): Result<List<MediaFile>> = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            require(bin.exists()) { "Embedded rclone binary missing" }
            val baseConf = getConfigFile(context)
            require(baseConf.exists()) { "Import rclone.conf first in the menu" }

            val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
            require(enabledRemotes.isNotEmpty()) { "Select at least one rclone remote in the menu" }

            val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv")
            val multiRemote = enabledRemotes.size > 1

            coroutineScope {
                enabledRemotes.map { remote ->
                    async(Dispatchers.IO) {
                        val sub = remote.rootSubpath.trim('/')
                        val target = if (sub.isEmpty()) "${remote.name}:" else "${remote.name}:$sub"

                        val cmd = mutableListOf(
                            bin.absolutePath,
                            "lsjson",
                            "--config", baseConf.absolutePath,
                            "--recursive",
                            "--max-depth", "6",
                            "--files-only",
                            "--no-modtime",
                            "--no-mimetype",
                            "--include", "*.{mp4,mkv,avi,mov,webm,ts,m4v,flv,MP4,MKV,AVI,MOV,WEBM,TS,M4V,FLV}",
                            target
                        )
                        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
                        pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                        pb.environment()["HOME"] = context.filesDir.absolutePath
                        val proc = pb.start()

                        val stdout = proc.inputStream.bufferedReader().use { it.readText() }
                        val stderr = proc.errorStream.bufferedReader().use { it.readText() }
                        val exitCode = proc.waitFor()

                        if (exitCode != 0 && stdout.isBlank()) {
                            throw Exception("rclone (${remote.name}): ${stderr.lines().lastOrNull { it.isNotBlank() } ?: "exit $exitCode"}")
                        }

                        val entries = runCatching { json.decodeFromString<List<RcloneLsJsonEntry>>(stdout) }.getOrDefault(emptyList())
                        entries.mapNotNull { entry ->
                            if (entry.IsDir) return@mapNotNull null
                            val ext = entry.Name.substringAfterLast('.', "").lowercase()
                            if (ext !in videoExtensions) return@mapNotNull null

                            val relPath = entry.Path.trim('/')
                            val logicalPath = if (multiRemote) "/${remote.name}/$relPath" else "/$relPath"
                            MediaFile(
                                name = entry.Name,
                                path = logicalPath,
                                isDirectory = false,
                                size = entry.Size,
                                isVideo = true
                            )
                        }
                    }
                }.awaitAll().flatten()
            }
        }
    }

    fun stopForegroundServeService(context: Context) {
        runCatching {
            context.stopService(Intent(context, RcloneForegroundService::class.java))
        }
        stopServer()
    }

    fun startForegroundServeService(context: Context) {
        if (!isRcloneModeEnabled(context) || !hasValidConfig(context)) {
            stopForegroundServeService(context)
            return
        }
        if (!isRcloneServeWebDavEnabled(context)) {
            stopForegroundServeService(context)
            return
        }
        val intent = Intent(context, RcloneForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun restartServeIfNeeded(context: Context) {
        if (!isRcloneModeEnabled(context) || !hasValidConfig(context)) {
            stopForegroundServeService(context)
            return
        }
        if (isRcloneServeWebDavEnabled(context)) {
            stopServer()
            startForegroundServeService(context)
        } else {
            // Same Device mode: stop persistent foreground notification & run on-demand over 127.0.0.1
            stopForegroundServeService(context)
        }
    }

    suspend fun ensureServerRunning(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!isRcloneModeEnabled(context) || !hasValidConfig(context)) {
            stopServer()
            return@withContext false
        }
        val bin = getBinaryFile(context)
        if (!bin.exists()) return@withContext false
        val target = prepareRuntimeServeTarget(context) ?: return@withContext false
        val runtimeConf = getRuntimeServeConfigFile(context)
        val srvUser = getRcloneServeUsername(context)
        val srvPass = getRcloneServePassword(context)
        val isLan = isRcloneServeWebDavEnabled(context)
        val sig = "$target|${runtimeConf.lastModified()}|$srvUser|$srvPass|$isLan"

        if (serverProcess?.isAlive == true && activeServedConfigSig == sig && isPortReachable()) {
            return@withContext true
        }

        stopServer()

        val cmd = mutableListOf(
            bin.absolutePath,
            "serve", "webdav",
            target,
            "--config", runtimeConf.absolutePath,
            "--addr", if (isRcloneServeWebDavEnabled(context)) "0.0.0.0:$STREAM_PORT" else "127.0.0.1:$STREAM_PORT",
            "--vfs-cache-mode", "minimal",
            "--vfs-read-chunk-size", "4M",
            "--vfs-read-chunk-size-limit", "16M",
            "--vfs-read-ahead", "8M",
            "--buffer-size", "8M",
            "--dir-cache-time", "10m",
            "--transfers", "4",
            "--no-modtime",
            "--no-checksum"
        )
        if (srvUser.isNotBlank()) {
            cmd.addAll(listOf("--user", srvUser))
            if (srvPass.isNotBlank()) cmd.addAll(listOf("--pass", srvPass))
        }

        val servePb = ProcessBuilder(cmd).redirectErrorStream(true)
        servePb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
        servePb.environment()["HOME"] = context.filesDir.absolutePath
        val proc = servePb.start()
        serverProcess = proc
        activeServedConfigSig = sig

        // Drain output asynchronously so pipe buffer never blocks
        Thread {
            runCatching { proc.inputStream.bufferedReader().forEachLine { } }
        }.start()

        repeat(150) {
            if (isPortReachable()) return@withContext true
            delay(40)
        }
        isPortReachable()
    }

    fun stopServer() {
        runCatching { serverProcess?.destroy() }
        serverProcess = null
        activeServedConfigSig = ""
    }

    private fun isPortReachable(): Boolean {
        return runCatching {
            val conn = (URL("$STREAM_BASE_URL/").openConnection() as HttpURLConnection).apply {
                requestMethod = "OPTIONS"
                connectTimeout = 400
                readTimeout = 400
            }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..499
        }.getOrDefault(false)
    }

    suspend fun ensureVidLabVaultFolders(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            if (!bin.exists()) return@runCatching
            val runtimeConf = getRuntimeServeConfigFile(context)
            val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
            val defaultRemote = enabledRemotes.firstOrNull()?.name ?: "vidlab_mount"
            val sub = enabledRemotes.firstOrNull()?.rootSubpath?.trim('/')?.takeIf { it.isNotEmpty() }
            val basePrefix = if (sub != null) "$defaultRemote:$sub/VidLab" else "$defaultRemote:VidLab"
            val categories = listOf("", "/Anime", "/Movies", "/Shows", "/Documentaries", "/KDrama", "/Animation")
            for (cat in categories) {
                val pb = ProcessBuilder(
                    listOf(bin.absolutePath, "mkdir", "$basePrefix$cat", "--config", runtimeConf.absolutePath)
                ).redirectErrorStream(true)
                pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                pb.environment()["HOME"] = context.filesDir.absolutePath
                pb.start().waitFor()
            }
        }
    }

    private val rcloneCreatedDirs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    fun clearDirectoryCache() { rcloneCreatedDirs.clear() }

    private fun encodeUrlPath(rawPath: String): String =
        "/" + rawPath.trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { seg ->
            java.net.URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }

    private fun ensureRcloneWebDavDir(baseUrl: String, dirLogicalPath: String) {
        val norm = "/" + dirLogicalPath.trim('/')
        if (norm == "/" || rcloneCreatedDirs.containsKey(norm)) return
        val segments = norm.split('/').filter { it.isNotBlank() }
        var current = ""
        for (seg in segments) {
            current += "/$seg"
            if (rcloneCreatedDirs.containsKey(current)) continue
            val mkUrl = baseUrl.trimEnd('/') + encodeUrlPath(current) + "/"
            val code = runCatching {
                val mk = (java.net.URL(mkUrl).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "MKCOL"; connectTimeout = 5000; readTimeout = 8000
                }
                val c = mk.responseCode; mk.disconnect(); c
            }.getOrDefault(0)
            if (code in 200..299 || code == 405 || code == 301 || code == 302) rcloneCreatedDirs[current] = true
        }
    }

    private fun rcloneWebDavExists(baseUrl: String, logicalPath: String): Boolean {
        val urlStr = baseUrl.trimEnd('/') + encodeUrlPath("/" + logicalPath.trim('/'))
        return runCatching {
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "HEAD"; connectTimeout = 4000; readTimeout = 6000
            }
            val code = conn.responseCode; conn.disconnect(); code in 200..299
        }.getOrDefault(false)
    }

    suspend fun moveFile(context: Context, oldLogicalPath: String, newLogicalPath: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            prepareRuntimeServeTarget(context) ?: error("No active Rclone remote configured")
            val srcNorm = "/" + oldLogicalPath.trim('/')
            val dstNorm = "/" + newLogicalPath.trim('/')
            if (srcNorm == dstNorm) return@runCatching Unit

            if (ensureServerRunning(context)) {
                val baseUrl = buildStreamUrl(context, "/").trimEnd('/')
                val destFolder = dstNorm.substringBeforeLast('/')
                val srcFolder = srcNorm.substringBeforeLast('/')
                if (destFolder.isNotBlank() && destFolder != "/" && destFolder != srcFolder) {
                    ensureRcloneWebDavDir(baseUrl, destFolder)
                }
                val srcEncoded = encodeUrlPath(srcNorm)
                val dstEncoded = encodeUrlPath(dstNorm)
                val srcUrl = baseUrl + srcEncoded
                val dstAbsoluteUrl = baseUrl + dstEncoded
                val backoffMs = longArrayOf(0L, 500L, 1500L, 3500L)
                var lastCode = 0
                for (attempt in backoffMs.indices) {
                    if (backoffMs[attempt] > 0L) kotlinx.coroutines.delay(backoffMs[attempt])
                    for (dstHeader in listOf(dstAbsoluteUrl, dstEncoded)) {
                        val code = runCatching {
                            val conn = (java.net.URL(srcUrl).openConnection() as java.net.HttpURLConnection).apply {
                                requestMethod = "MOVE"
                                setRequestProperty("Destination", dstHeader)
                                setRequestProperty("Overwrite", "T")
                                connectTimeout = 10000; readTimeout = 25000
                            }
                            val c = conn.responseCode; conn.disconnect(); c
                        }.getOrDefault(0)
                        lastCode = code
                        if (code in 200..299 || code == 412) return@runCatching Unit
                        if (code == 404 && rcloneWebDavExists(baseUrl, dstNorm)) return@runCatching Unit
                        if (code == 409 && destFolder.isNotBlank() && destFolder != "/") {
                            rcloneCreatedDirs.remove(destFolder)
                            ensureRcloneWebDavDir(baseUrl, destFolder)
                        }
                    }
                    if (lastCode !in setOf(0, 403, 409, 423, 429, 500, 502, 503, 504)) break
                }
                if (rcloneWebDavExists(baseUrl, dstNorm)) return@runCatching Unit
            }

            val bin = getBinaryFile(context)
            require(bin.exists()) { "Embedded rclone binary not found" }
            val runtimeConf = getRuntimeServeConfigFile(context)
            val knownRemotes = parseRemotesFromConf(getConfigFile(context).readText()).map { it.first }.toSet()
            val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
            val defaultRemote = enabledRemotes.firstOrNull()?.name ?: "vidlab_mount"

            fun toRemoteSpec(logicalPath: String): String {
                val clean = logicalPath.trimStart('/')
                val slashIdx = clean.indexOf('/')
                if (slashIdx > 0) {
                    val firstSeg = clean.substring(0, slashIdx)
                    if (firstSeg in knownRemotes) return "$firstSeg:${clean.substring(slashIdx + 1)}"
                }
                val sub = enabledRemotes.firstOrNull()?.rootSubpath?.trim('/')?.takeIf { it.isNotEmpty() }
                return if (sub != null) "$defaultRemote:$sub/$clean" else "$defaultRemote:$clean"
            }

            val src = toRemoteSpec(oldLogicalPath)
            val dst = toRemoteSpec(newLogicalPath)
            val dstParentLogical = "/" + newLogicalPath.trim('/').substringBeforeLast('/', "")
            if (dstParentLogical.length > 1) {
                ensureDirectoryExists(context, dstParentLogical)
            }
            val pb = ProcessBuilder(listOf(bin.absolutePath, "moveto", "--ignore-existing", src, dst, "--no-traverse", "--drive-server-side-across-configs", "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
            pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            pb.environment()["HOME"] = context.filesDir.absolutePath
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                val checkDst = ProcessBuilder(listOf(bin.absolutePath, "lsf", dst, "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
                checkDst.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                checkDst.environment()["HOME"] = context.filesDir.absolutePath
                val checkProc = checkDst.start()
                val dstOut = checkProc.inputStream.bufferedReader().readText().trim()
                if (checkProc.waitFor() == 0 && dstOut.isNotBlank()) return@runCatching Unit
                error(out.ifBlank { "rclone moveto failed (exit $exitCode)" })
            }
        }
    }

    private val rcloneDirMutex = Mutex()
    private val createdRcloneDirs = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun clearRcloneDirectoryCache() {
        createdRcloneDirs.clear()
    }

    private fun resolveRemoteSpec(context: Context, logicalPath: String): String {
        val knownRemotes = parseRemotesFromConf(getConfigFile(context).readText()).map { it.first }.toSet()
        val enabledRemotes = getConfiguredRemotes(context).filter { it.enabled }
        val defaultRemote = enabledRemotes.firstOrNull()?.name ?: "vidlab_mount"
        val clean = logicalPath.trimStart('/')
        val slashIdx = clean.indexOf('/')
        if (slashIdx > 0) {
            val firstSeg = clean.substring(0, slashIdx)
            if (firstSeg in knownRemotes) return "$firstSeg:${clean.substring(slashIdx + 1)}"
        }
        val sub = enabledRemotes.firstOrNull()?.rootSubpath?.trim('/')?.takeIf { it.isNotEmpty() }
        return if (sub != null) "$defaultRemote:$sub/$clean" else "$defaultRemote:$clean"
    }

    suspend fun ensureDirectoryExists(context: Context, logicalDirPath: String) = withContext(Dispatchers.IO) {
        val norm = "/" + logicalDirPath.trim('/')
        if (norm == "/" || createdRcloneDirs.containsKey(norm)) return@withContext
        rcloneDirMutex.withLock {
            if (createdRcloneDirs.containsKey(norm)) return@withLock
            runCatching {
                val bin = getBinaryFile(context)
                if (!bin.exists()) return@runCatching
                val runtimeConf = getRuntimeServeConfigFile(context)
                val spec = resolveRemoteSpec(context, norm)
                if (spec.endsWith(":")) return@runCatching
                val pb = ProcessBuilder(listOf(bin.absolutePath, "mkdir", spec, "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
                pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
                pb.environment()["HOME"] = context.filesDir.absolutePath
                val code = pb.start().waitFor()
                if (code == 0) {
                    createdRcloneDirs[norm] = true
                    delay(150L)
                }
            }
        }
    }

    suspend fun dedupeFolder(context: Context, logicalDirPath: String) = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            if (!bin.exists()) return@runCatching
            val runtimeConf = getRuntimeServeConfigFile(context)
            val spec = resolveRemoteSpec(context, logicalDirPath)
            val pb = ProcessBuilder(listOf(bin.absolutePath, "dedupe", spec, "--dedupe-mode", "newest", "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
            pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.start().waitFor()
        }
    }

    suspend fun removeEmptyDirs(context: Context, logicalDirPath: String) = withContext(Dispatchers.IO) {
        runCatching {
            val bin = getBinaryFile(context)
            if (!bin.exists()) return@runCatching
            val runtimeConf = getRuntimeServeConfigFile(context)
            val spec = resolveRemoteSpec(context, logicalDirPath)
            val pb = ProcessBuilder(listOf(bin.absolutePath, "rmdirs", spec, "--leave-root", "--config", runtimeConf.absolutePath)).redirectErrorStream(true)
            pb.environment()["SSL_CERT_DIR"] = "/system/etc/security/cacerts"
            pb.environment()["HOME"] = context.filesDir.absolutePath
            pb.start().waitFor()
        }
    }

    fun buildStreamUrl(context: Context, logicalPath: String): String {
        if (logicalPath.startsWith("http://") || logicalPath.startsWith("https://") || logicalPath.startsWith("file://")) {
            return logicalPath
        }
        DownloadTaskManager.findDownloadedFile(context, logicalPath)?.let { localFile ->
            return android.net.Uri.fromFile(localFile).toString()
        }
        if (DownloadTaskManager.isOfflineOnlyMode(context)) {
            return "file:///offline_not_downloaded"
        }
        val base = if (isRcloneModeEnabled(context) && hasValidConfig(context)) {
            STREAM_BASE_URL
        } else {
            getWebDavBaseUrl(context)
        }
        val encodedPath = logicalPath.split('/').joinToString("/") { seg ->
            if (seg.isEmpty()) "" else URLEncoder.encode(seg, "UTF-8").replace("+", "%20")
        }
        return "$base$encodedPath"
    }
}
