package com.vidlab.app.data.remote

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.vidlab.app.data.model.MediaFile
import com.vidlab.app.data.model.ScrapedMediaItem
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

enum class DownloadStatus {
    QUEUED, DOWNLOADING, COMPLETED, FAILED, CANCELLED
}

data class DownloadTaskItem(
    val id: String,
    val fileName: String,
    val remotePath: String,
    val folderName: String,
    val saveInApp: Boolean = false,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val errorMessage: String? = null
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

object DownloadTaskManager {
    private const val CHANNEL_ID = "vidlab_downloads"
    private const val CHANNEL_NAME = "VidLab Downloads"

    val tasks = mutableStateListOf<DownloadTaskItem>()
    val downloadRevision = mutableIntStateOf(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private const val MAX_CONCURRENT_DOWNLOADS = 3

    private const val PREFS_OFFLINE = "vidlab_offline_prefs"
    private const val KEY_OFFLINE_ONLY = "offline_only_mode"

    fun bumpRevision() {
        downloadRevision.intValue += 1
    }

    fun hasFullStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun buildManageAllFilesIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
        }
    }

    fun isOfflineOnlyMode(context: Context): Boolean =
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).getBoolean(KEY_OFFLINE_ONLY, false)

    fun setOfflineOnlyMode(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_OFFLINE, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFFLINE_ONLY, enabled).apply()
        bumpRevision()
    }

    fun getLegacyVaultRoot(context: Context): File = File(context.filesDir, "offline_downloads")

    fun getAppOfflineRoot(context: Context): File = getLegacyVaultRoot(context)

    fun ensureVidLabCategoryFolders(context: Context): File {
        val root = File(Environment.getExternalStorageDirectory(), "VidLab")
        listOf("Movies", "Anime", "TV Shows", "Documentary").forEach { runCatching { File(root, it).mkdirs() } }
        return root
    }

    fun getPublicOfflineRoot(): File =
        File(Environment.getExternalStorageDirectory(), "VidLab")

    fun getPublicOfflineRoot(context: Context): File = getPublicOfflineRoot()

    fun getDownloadedFileNames(context: Context): Set<String> =
        listAllDownloadedFiles(context).map { it.name }.toSet()

    fun listLegacyVaultFiles(context: Context): List<File> {
        val vault = getLegacyVaultRoot(context)
        if (!vault.exists()) return emptyList()
        return vault.walkTopDown().filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0L }.toList()
    }

    fun migrateLegacyVaultToPublic(context: Context): Int {
        val vaultFiles = listLegacyVaultFiles(context)
        if (vaultFiles.isEmpty()) return 0
        val pubRoot = getPublicOfflineRoot().apply { if (!exists()) mkdirs() }
        var moved = 0
        for (src in vaultFiles) {
            val folder = src.parentFile?.name?.takeIf { it != "offline_downloads" } ?: "Movies"
            val destDir = File(pubRoot, folder).apply { if (!exists()) mkdirs() }
            val dest = File(destDir, src.name)
            val ok = runCatching {
                if (dest.exists() && dest.length() == src.length()) {
                    src.delete()
                    true
                } else {
                    src.copyTo(dest, overwrite = true)
                    if (dest.exists() && dest.length() == src.length()) {
                        src.delete()
                        true
                    } else false
                }
            }.getOrDefault(false)
            if (ok) {
                moved++
                val p = src.parentFile
                if (p != null && p != getLegacyVaultRoot(context) && p.listFiles()?.isEmpty() == true) {
                    runCatching { p.delete() }
                }
            }
        }
        bumpRevision()
        return moved
    }

    private fun queryMediaStoreDownloads(context: Context): List<File> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val found = mutableListOf<File>()
        val projection = arrayOf(MediaStore.Downloads.DATA)
        val selection = "${MediaStore.Downloads.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("%Download/VidLab%")
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                null
            )?.use { cursor ->
                val dataCol = cursor.getColumnIndex(MediaStore.Downloads.DATA)
                while (cursor.moveToNext()) {
                    val path = if (dataCol >= 0) cursor.getString(dataCol) else null
                    if (!path.isNullOrBlank()) {
                        val f = File(path)
                        if (f.exists() && f.isFile && !f.name.endsWith(".part") && f.length() > 0L) {
                            found.add(f)
                        }
                    }
                }
            }
        }
        return found
    }

    fun deleteDownloadedFile(context: Context, logicalPathOrUri: String): Boolean {
        val targetFile = if (logicalPathOrUri.startsWith("file://")) {
            runCatching { File(Uri.parse(logicalPathOrUri).path ?: "") }.getOrNull()?.takeIf { it.exists() }
        } else {
            findDownloadedFile(context, logicalPathOrUri)
        } ?: return false
        val parent = targetFile.parentFile
        val deleted = runCatching { targetFile.delete() }.getOrDefault(false)
        if (deleted && parent != null && parent != getLegacyVaultRoot(context) && parent != getPublicOfflineRoot()) {
            if (parent.listFiles()?.isEmpty() == true) {
                runCatching { parent.delete() }
            }
        }
        if (deleted) bumpRevision()
        return deleted
    }

    fun findDownloadedFile(context: Context, logicalPath: String): File? {
        val fileName = logicalPath.substringAfterLast('/').trim()
        if (fileName.isEmpty()) return null
        for (root in listOf(getPublicOfflineRoot(), getLegacyVaultRoot(context))) {
            if (!root.exists()) continue
            root.walkTopDown().firstOrNull { it.isFile && it.name == fileName && !it.name.endsWith(".part") && it.length() > 0L }?.let {
                return it
            }
        }
        return queryMediaStoreDownloads(context).firstOrNull { it.name == fileName }
    }

    fun hasAnyDownloadedFile(context: Context, item: ScrapedMediaItem): Boolean {
        val files = if (item.isSeries && item.seasons.isNotEmpty()) {
            item.seasons.flatMap { it.episodes }.map { it.file }
        } else {
            listOf(item.primaryFile)
        }
        return files.any { findDownloadedFile(context, it.path) != null }
    }

    fun listAllDownloadedFiles(context: Context): List<File> {
        val seenPaths = HashSet<String>()
        val out = mutableListOf<File>()
        for (root in listOf(getPublicOfflineRoot(), getLegacyVaultRoot(context))) {
            if (root.exists()) {
                root.walkTopDown()
                    .filter { it.isFile && !it.name.endsWith(".part") && it.length() > 0L }
                    .forEach { f -> if (seenPaths.add(f.absolutePath)) out.add(f) }
            }
        }
        for (f in queryMediaStoreDownloads(context)) {
            if (seenPaths.add(f.absolutePath)) out.add(f)
        }
        return out.sortedByDescending { it.lastModified() }
    }

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shows progress for downloaded episodes and movies"
                }
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun postNotification(context: Context, task: DownloadTaskItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val notifId = task.id.hashCode()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(task.fileName)
            .setOnlyAlertOnce(true)

        when (task.status) {
            DownloadStatus.QUEUED -> builder.setContentText("Queued • VidLab/${task.folderName}").setProgress(100, 0, true).setOngoing(true)
            DownloadStatus.DOWNLOADING -> {
                val pct = (task.progressFraction * 100).toInt()
                val dlMb = String.format("%.1f MB", task.bytesDownloaded / (1024.0 * 1024.0))
                val totMb = if (task.totalBytes > 0L) String.format(" / %.1f MB", task.totalBytes / (1024.0 * 1024.0)) else ""
                builder.setContentText("$pct% • $dlMb$totMb").setProgress(100, pct, task.totalBytes <= 0L).setOngoing(true)
            }
            DownloadStatus.COMPLETED -> builder.setSmallIcon(android.R.drawable.stat_sys_download_done).setContentText("Saved to VidLab/${task.folderName}").setProgress(0, 0, false).setOngoing(false)
            DownloadStatus.FAILED -> builder.setSmallIcon(android.R.drawable.stat_notify_error).setContentText("Failed: ${task.errorMessage ?: "Network error"}").setProgress(0, 0, false).setOngoing(false)
            DownloadStatus.CANCELLED -> {
                NotificationManagerCompat.from(context).cancel(notifId)
                return
            }
        }
        runCatching { NotificationManagerCompat.from(context).notify(notifId, builder.build()) }
    }

    private fun updateTask(id: String, transform: (DownloadTaskItem) -> DownloadTaskItem): DownloadTaskItem? {
        val idx = tasks.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val updated = transform(tasks[idx])
        tasks[idx] = updated
        return updated
    }

    fun enqueueFile(context: Context, file: MediaFile, folderName: String, saveInApp: Boolean = false) {
        val appCtx = context.applicationContext
        val safeFolder = folderName.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "General" }
        val taskId = file.path
        if (findDownloadedFile(appCtx, file.path) != null) return

        val existingIdx = tasks.indexOfFirst { it.id == taskId }
        if (existingIdx >= 0) {
            val st = tasks[existingIdx].status
            if (st == DownloadStatus.QUEUED || st == DownloadStatus.DOWNLOADING) return
            tasks.removeAt(existingIdx)
        }

        val initial = DownloadTaskItem(
            id = taskId,
            fileName = file.name,
            remotePath = file.path,
            folderName = safeFolder,
            saveInApp = false,
            bytesDownloaded = 0L,
            totalBytes = file.size.coerceAtLeast(0L),
            status = DownloadStatus.QUEUED
        )
        tasks.add(0, initial)
        postNotification(appCtx, initial)
        activeJobs[taskId] = scope.launch { downloadSemaphore.withPermit { if (isActive) runDownload(appCtx, initial) } }
    }

    fun enqueueBatch(context: Context, files: List<MediaFile>, folderName: String, saveInApp: Boolean = false) {
        for (f in files) enqueueFile(context, f, folderName, false)
    }

    fun cancelTask(context: Context, taskId: String) {
        activeJobs.remove(taskId)?.cancel()
        updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }?.let { postNotification(context.applicationContext, it) }
    }

    fun retryTask(context: Context, taskId: String) {
        val appCtx = context.applicationContext
        val existing = tasks.firstOrNull { it.id == taskId } ?: return
        activeJobs.remove(taskId)?.cancel()
        val reset = existing.copy(bytesDownloaded = 0L, status = DownloadStatus.QUEUED)
        updateTask(taskId) { reset }
        postNotification(appCtx, reset)
        activeJobs[taskId] = scope.launch { downloadSemaphore.withPermit { if (isActive) runDownload(appCtx, reset) } }
    }

    fun retryAllFailed(context: Context) {
        val failedIds = tasks.filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }.map { it.id }
        for (id in failedIds) retryTask(context, id)
    }

    fun clearFinished() {
        tasks.removeAll { it.status == DownloadStatus.COMPLETED || it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELLED }
    }

    private suspend fun runDownload(context: Context, seed: DownloadTaskItem) {
        try {
            if (RcloneEngine.isRcloneModeEnabled(context)) {
                RcloneEngine.startForegroundServeService(context)
                if (!RcloneEngine.ensureServerRunning(context)) delay(1500L)
            }
            val streamUrl = RcloneEngine.buildStreamUrl(context, seed.remotePath)
            val targetDir = File(getPublicOfflineRoot(), seed.folderName).apply { if (!exists()) mkdirs() }
            val outFile = File(targetDir, seed.fileName)
            val partFile = File(targetDir, "${seed.fileName}.part")
            val canDirectFileWrite = hasFullStorageAccess() || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

            if (canDirectFileWrite) {
                var existingBytes = if (partFile.exists()) partFile.length() else 0L
                val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20000; readTimeout = 30000; requestMethod = "GET"
                    if (existingBytes > 0L) setRequestProperty("Range", "bytes=$existingBytes-")
                }
                conn.connect()
                val code = conn.responseCode
                if (code !in 200..299 && code != 206) throw IllegalStateException("HTTP $code")
                if (code == 200 && existingBytes > 0L) existingBytes = 0L
                val totalLen = if (conn.contentLengthLong > 0L) existingBytes + conn.contentLengthLong else seed.totalBytes
                updateTask(seed.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = existingBytes, totalBytes = totalLen) }?.let { postNotification(context, it) }
                var downloaded = existingBytes; var lastNotifyMs = 0L; val buf = ByteArray(128 * 1024)
                conn.inputStream.use { input ->
                    FileOutputStream(partFile, existingBytes > 0L).use { output ->
                        while (currentCoroutineContext().isActive) {
                            val read = input.read(buf); if (read == -1) break
                            output.write(buf, 0, read); downloaded += read
                            val now = System.currentTimeMillis()
                            if (now - lastNotifyMs >= 1500L) {
                                lastNotifyMs = now
                                updateTask(seed.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = totalLen) }?.let { postNotification(context, it) }
                            }
                        }
                        output.flush()
                    }
                }
                conn.disconnect()
                if (!currentCoroutineContext().isActive) return
                if (outFile.exists()) outFile.delete()
                partFile.renameTo(outFile)
                updateTask(seed.id) { it.copy(status = DownloadStatus.COMPLETED, bytesDownloaded = downloaded, totalBytes = if (totalLen > 0L) totalLen else downloaded) }?.let { postNotification(context, it) }
                bumpRevision()
            } else {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, seed.fileName)
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidLab/${seed.folderName}")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val itemUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("MediaStore insert failed")
                try {
                    val conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply { connectTimeout = 20000; readTimeout = 30000; requestMethod = "GET" }
                    conn.connect()
                    if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                    val totalLen = if (conn.contentLengthLong > 0L) conn.contentLengthLong else seed.totalBytes
                    updateTask(seed.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = 0L, totalBytes = totalLen) }?.let { postNotification(context, it) }
                    var downloaded = 0L; var lastNotifyMs = 0L; val buf = ByteArray(128 * 1024)
                    conn.inputStream.use { input ->
                        (resolver.openOutputStream(itemUri) ?: throw IllegalStateException("Stream open failed")).use { output: OutputStream ->
                            while (currentCoroutineContext().isActive) {
                                val read = input.read(buf); if (read == -1) break
                                output.write(buf, 0, read); downloaded += read
                                val now = System.currentTimeMillis()
                                if (now - lastNotifyMs >= 1500L) {
                                    lastNotifyMs = now
                                    updateTask(seed.id) { it.copy(status = DownloadStatus.DOWNLOADING, bytesDownloaded = downloaded, totalBytes = totalLen) }?.let { postNotification(context, it) }
                                }
                            }
                            output.flush()
                        }
                    }
                    conn.disconnect()
                    if (!currentCoroutineContext().isActive) {
                        resolver.delete(itemUri, null, null)
                        return
                    }
                    resolver.update(itemUri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                    updateTask(seed.id) { it.copy(status = DownloadStatus.COMPLETED, bytesDownloaded = downloaded, totalBytes = if (totalLen > 0L) totalLen else downloaded) }?.let { postNotification(context, it) }
                    bumpRevision()
                } catch (inner: Exception) {
                    runCatching { resolver.delete(itemUri, null, null) }
                    throw inner
                }
            }
        } catch (e: CancellationException) {
            updateTask(seed.id) { it.copy(status = DownloadStatus.CANCELLED) }?.let { postNotification(context, it) }
        } catch (e: Exception) {
            updateTask(seed.id) { it.copy(status = DownloadStatus.FAILED, errorMessage = e.message ?: "Download error") }?.let { postNotification(context, it) }
        } finally {
            activeJobs.remove(seed.id)
        }
    }
}
