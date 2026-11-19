package com.vidlab.app.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL

class AniListManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vidlab_anilist_prefs", Context.MODE_PRIVATE)
    private val backupFile = File(appContext.filesDir, "anilist_session_backup.json")

    companion object {
        private const val GRAPHQL_URL = "https://graphql.anilist.co"
        private const val USER_AGENT = "VidLab/2.5 (Android)"
    }

    init { restoreFromBackupIfNeeded() }

    var clientId: String
        get() = prefs.getString("anilist_client_id", "")?.trim().orEmpty()
        set(value) { prefs.edit().putString("anilist_client_id", value.trim()).commit(); syncBackup() }

    var accessToken: String?
        get() { restoreFromBackupIfNeeded(); return prefs.getString("anilist_access_token", null) }
        set(value) { prefs.edit().putString("anilist_access_token", value?.trim()).commit(); syncBackup() }

    var username: String?
        get() = prefs.getString("anilist_username", null)
        set(value) { prefs.edit().putString("anilist_username", value).commit(); syncBackup() }

    var scrobbleEnabled: Boolean
        get() = prefs.getBoolean("anilist_scrobble_enabled", true)
        set(value) { prefs.edit().putBoolean("anilist_scrobble_enabled", value).commit(); syncBackup() }

    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank()

    fun buildAuthorizeUrl(): String {
        val cid = clientId
        return if (cid.isNotBlank()) "https://anilist.co/api/v2/oauth/authorize?client_id=$cid&response_type=token"
        else "https://anilist.co/settings/developer"
    }

    private fun restoreFromBackupIfNeeded() {
        val current = prefs.getString("anilist_access_token", null)
        if (current.isNullOrBlank() && backupFile.exists()) {
            runCatching {
                val json = JSONObject(backupFile.readText())
                val tok = json.optString("access_token", "")
                if (tok.isNotBlank()) {
                    prefs.edit()
                        .putString("anilist_client_id", json.optString("client_id", ""))
                        .putString("anilist_access_token", tok)
                        .putString("anilist_username", json.optString("username", "").takeIf { it.isNotBlank() })
                        .putBoolean("anilist_scrobble_enabled", json.optBoolean("scrobble_enabled", true))
                        .commit()
                }
            }
        }
    }

    private fun syncBackup() {
        runCatching {
            val token = prefs.getString("anilist_access_token", null)
            if (token.isNullOrBlank() && clientId.isBlank()) {
                if (backupFile.exists()) backupFile.delete()
            } else {
                val json = JSONObject().apply {
                    put("client_id", clientId)
                    put("access_token", token ?: "")
                    put("username", prefs.getString("anilist_username", "") ?: "")
                    put("scrobble_enabled", prefs.getBoolean("anilist_scrobble_enabled", true))
                }
                backupFile.writeText(json.toString())
            }
        }
    }

    fun signOut() {
        val savedCid = clientId
        prefs.edit().clear().commit()
        if (savedCid.isNotBlank()) clientId = savedCid
        runCatching { if (backupFile.exists()) backupFile.delete() }
    }

    private fun postGraphQl(token: String, query: String, variables: JSONObject): Pair<Int, String> {
        val conn = CloudflareDns.openConnection(URL(GRAPHQL_URL)).apply {
            requestMethod = "POST"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 12_000; readTimeout = 12_000; doOutput = true
        }
        val body = JSONObject().apply { put("query", query); put("variables", variables) }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to resp
    }

    suspend fun verifyAndSaveToken(rawToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = rawToken.trim().removePrefix("Bearer ").trim()
            if (clean.isBlank()) throw IllegalArgumentException("Paste a valid AniList access token.")
            val (code, resp) = postGraphQl(clean, "query { Viewer { id name } }", JSONObject())
            if (code in 200..299) {
                val viewer = JSONObject(resp).optJSONObject("data")?.optJSONObject("Viewer")
                val name = viewer?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Could not verify AniList account.")
                accessToken = clean
                username = name
                name
            } else throw IllegalStateException("HTTP $code: Invalid or expired AniList token.")
        }
    }

    suspend fun scrobblePlayback(rawTitleOrFilename: String, isAnimeHint: Boolean = false) = withContext(Dispatchers.IO) {
        val token = accessToken ?: return@withContext
        if (!scrobbleEnabled || token.isBlank()) return@withContext
        val pathUpper = rawTitleOrFilename.uppercase()
        if (!isAnimeHint && !pathUpper.contains("/ANIME/") && !pathUpper.contains("/ANIMATION/")) return@withContext

        runCatching {
            val cleanName = rawTitleOrFilename.substringAfterLast("/").substringBeforeLast(".")
                .replace('.', ' ').replace('_', ' ').trim()
            val epMatch = Regex("""(?i)(.*?)\s*[-–]?\s*S(\d{1,2})E(\d{1,3})""").find(cleanName)
            val searchTitle = (epMatch?.groupValues?.getOrNull(1) ?: cleanName)
                .replace(Regex("""\(\d{4}\).*"""), "").trim()
            val seasonNum = epMatch?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 1
            val episodeNum = epMatch?.groupValues?.getOrNull(3)?.toIntOrNull() ?: 1
            if (searchTitle.isBlank()) return@runCatching

            val searchQueryTitle = if (seasonNum > 1) "$searchTitle Season $seasonNum" else searchTitle
            val findQuery = "query (\$search: String) { Media(search: \$search, type: ANIME) { id episodes } }"
            val (sCode, sBody) = postGraphQl(token, findQuery, JSONObject().put("search", searchQueryTitle))
            if (sCode !in 200..299) return@runCatching
            val media = JSONObject(sBody).optJSONObject("data")?.optJSONObject("Media") ?: return@runCatching
            val mediaId = media.optInt("id", 0)
            val totalEps = media.optInt("episodes", 0)
            if (mediaId <= 0) return@runCatching

            val status = if (totalEps > 0 && episodeNum >= totalEps) "COMPLETED" else "CURRENT"
            val mutateQuery = "mutation (\$mediaId: Int, \$progress: Int, \$status: MediaListStatus) { SaveMediaListEntry(mediaId: \$mediaId, progress: \$progress, status: \$status) { id progress } }"
            val vars = JSONObject().apply { put("mediaId", mediaId); put("progress", episodeNum); put("status", status) }
            postGraphQl(token, mutateQuery, vars)
        }
    }
}
