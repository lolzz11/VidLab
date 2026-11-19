package com.vidlab.app.data.remote

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class TraktDeviceSession(
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresIn: Int,
    val interval: Int
)

sealed class TraktPollState {
    data class Pending(val slowDown: Boolean = false) : TraktPollState()
    data object Authorized : TraktPollState()
    data class Expired(val message: String) : TraktPollState()
    data class Error(val message: String) : TraktPollState()
}

class TraktManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vidlab_trakt_prefs", Context.MODE_PRIVATE)
    private val backupFile = File(appContext.filesDir, "trakt_session_backup.json")

    companion object {
        private const val BASE_URL = "https://api.trakt.tv"
        private const val USER_AGENT = "VidLab/2.5 (Android)"
        const val REDIRECT_URI = "vidlab://auth/trakt"
    }

    fun buildAuthorizeUrl(): String = if (clientId.isNotBlank()) "https://trakt.tv/oauth/authorize?response_type=code&client_id=$clientId&redirect_uri=$REDIRECT_URI" else "https://trakt.tv/oauth/applications"

    suspend fun exchangeAuthorizationCode(code: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cid = clientId; val sec = clientSecret
            if (cid.isBlank()) throw IllegalStateException("Trakt Client ID not configured.")
            val conn = CloudflareDns.openConnection(URL("$BASE_URL/oauth/token")).apply { requestMethod = "POST"; setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json"); connectTimeout = 12_000; readTimeout = 12_000; doOutput = true }
            val payload = JSONObject().put("code", code.trim()).put("client_id", cid).put("client_secret", sec).put("redirect_uri", REDIRECT_URI).put("grant_type", "authorization_code")
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (st, body) = readStream(conn)
            if (st !in 200..299) throw IllegalStateException("HTTP $st: $body")
            val json = JSONObject(body)
            val tok = json.getString("access_token")
            accessToken = tok; refreshToken = json.optString("refresh_token", "").takeIf { it.isNotBlank() }
            val uConn = CloudflareDns.openConnection(URL("$BASE_URL/users/me")).apply { requestMethod = "GET"; setRequestProperty("User-Agent", USER_AGENT); setRequestProperty("Content-Type", "application/json"); setRequestProperty("trakt-api-version", "2"); setRequestProperty("trakt-api-key", cid); setRequestProperty("Authorization", "Bearer $tok") }
            val (uSt, uBody) = readStream(uConn)
            val uname = if (uSt in 200..299) JSONObject(uBody).optString("username", "Trakt Member") else "Trakt Member"
            username = uname; uname
        }
    }

    init { restoreFromBackupIfNeeded() }

    var clientId: String
        get() = prefs.getString("trakt_client_id", "")?.trim().orEmpty()
        set(value) { prefs.edit().putString("trakt_client_id", value.trim()).commit(); syncBackup() }

    var clientSecret: String
        get() = prefs.getString("trakt_client_secret", "")?.trim().orEmpty()
        set(value) { prefs.edit().putString("trakt_client_secret", value.trim()).commit(); syncBackup() }

    var accessToken: String?
        get() { restoreFromBackupIfNeeded(); return prefs.getString("trakt_access_token", null) }
        set(value) { prefs.edit().putString("trakt_access_token", value).commit(); syncBackup() }

    var refreshToken: String?
        get() = prefs.getString("trakt_refresh_token", null)
        set(value) { prefs.edit().putString("trakt_refresh_token", value).commit(); syncBackup() }

    var username: String?
        get() = prefs.getString("trakt_username", null)
        set(value) { prefs.edit().putString("trakt_username", value).commit(); syncBackup() }

    var scrobbleEnabled: Boolean
        get() = prefs.getBoolean("trakt_scrobble_enabled", true)
        set(value) { prefs.edit().putBoolean("trakt_scrobble_enabled", value).commit(); syncBackup() }

    val isSignedIn: Boolean get() = !accessToken.isNullOrBlank()

    private fun restoreFromBackupIfNeeded() {
        val currentToken = prefs.getString("trakt_access_token", null)
        if (currentToken.isNullOrBlank() && backupFile.exists()) {
            runCatching {
                val json = JSONObject(backupFile.readText())
                val tok = json.optString("access_token", "")
                if (tok.isNotBlank()) {
                    prefs.edit()
                        .putString("trakt_client_id", json.optString("client_id", ""))
                        .putString("trakt_client_secret", json.optString("client_secret", ""))
                        .putString("trakt_access_token", tok)
                        .putString("trakt_refresh_token", json.optString("refresh_token", "").takeIf { it.isNotBlank() })
                        .putString("trakt_username", json.optString("username", "").takeIf { it.isNotBlank() })
                        .putBoolean("trakt_scrobble_enabled", json.optBoolean("scrobble_enabled", true))
                        .commit()
                }
            }
        }
    }

    private fun syncBackup() {
        runCatching {
            val token = prefs.getString("trakt_access_token", null)
            if (token.isNullOrBlank() && clientId.isBlank()) {
                if (backupFile.exists()) backupFile.delete()
            } else {
                val json = JSONObject().apply {
                    put("client_id", clientId)
                    put("client_secret", clientSecret)
                    put("access_token", token ?: "")
                    put("refresh_token", prefs.getString("trakt_refresh_token", "") ?: "")
                    put("username", prefs.getString("trakt_username", "") ?: "")
                    put("scrobble_enabled", prefs.getBoolean("trakt_scrobble_enabled", true))
                }
                backupFile.writeText(json.toString())
            }
        }
    }

    fun signOut() {
        val savedCid = clientId
        val savedSec = clientSecret
        prefs.edit().clear().commit()
        if (savedCid.isNotBlank()) clientId = savedCid
        if (savedSec.isNotBlank()) clientSecret = savedSec
        runCatching { if (backupFile.exists()) backupFile.delete() }
    }

    private fun readStream(conn: HttpURLConnection): Pair<Int, String> {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    suspend fun requestDeviceCode(): Result<TraktDeviceSession> = withContext(Dispatchers.IO) {
        runCatching {
            val cid = clientId
            if (cid.isBlank()) throw IllegalStateException("Enter your Trakt Client ID first.")
            val conn = CloudflareDns.openConnection(URL("$BASE_URL/oauth/device/code")).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 12_000; readTimeout = 12_000; doOutput = true
            }
            conn.outputStream.use { it.write(JSONObject().put("client_id", cid).toString().toByteArray()) }
            val (code, body) = readStream(conn)
            if (code in 200..299) {
                val json = JSONObject(body)
                TraktDeviceSession(
                    deviceCode = json.getString("device_code"),
                    userCode = json.getString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://trakt.tv/activate"),
                    expiresIn = json.optInt("expires_in", 600),
                    interval = json.optInt("interval", 5)
                )
            } else throw IllegalStateException("HTTP $code: ${body.ifBlank { "Failed to request Trakt code" }}")
        }
    }

    suspend fun pollDeviceToken(session: TraktDeviceSession): TraktPollState = withContext(Dispatchers.IO) {
        runCatching {
            val cid = clientId
            val sec = clientSecret
            val conn = CloudflareDns.openConnection(URL("$BASE_URL/oauth/device/token")).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
            }
            val payload = JSONObject().apply {
                put("code", session.deviceCode); put("client_id", cid)
                if (sec.isNotBlank()) put("client_secret", sec)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (code, body) = readStream(conn)
            when (code) {
                200 -> {
                    val json = JSONObject(body)
                    val tok = json.optString("access_token", "")
                    if (tok.isNotBlank()) {
                        accessToken = tok
                        json.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refreshToken = it }
                        fetchUserProfile(tok, cid)
                        TraktPollState.Authorized
                    } else TraktPollState.Pending()
                }
                400 -> TraktPollState.Pending(slowDown = false)
                429 -> TraktPollState.Pending(slowDown = true)
                404, 410 -> TraktPollState.Expired("Code expired. Generate a new code.")
                418 -> TraktPollState.Expired("Authorization denied on Trakt.")
                else -> TraktPollState.Error("HTTP $code: $body")
            }
        }.getOrElse { TraktPollState.Pending() }
    }

    private fun fetchUserProfile(token: String, cid: String) {
        runCatching {
            val conn = CloudflareDns.openConnection(URL("$BASE_URL/users/me")).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("trakt-api-version", "2")
                setRequestProperty("trakt-api-key", cid)
            }
            val (code, body) = readStream(conn)
            if (code in 200..299) {
                username = JSONObject(body).optString("username").takeIf { it.isNotBlank() } ?: "Trakt Member"
            }
        }
    }

    private fun tryRefreshAccessToken(): String? {
        val ref = refreshToken?.takeIf { it.isNotBlank() } ?: return accessToken
        val cid = clientId; val sec = clientSecret
        if (cid.isBlank() || sec.isBlank()) return accessToken
        return runCatching {
            val conn = CloudflareDns.openConnection(URL("$BASE_URL/oauth/token")).apply {
                requestMethod = "POST"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            val payload = JSONObject().apply {
                put("refresh_token", ref); put("client_id", cid); put("client_secret", sec)
                put("redirect_uri", "urn:ietf:wg:oauth:2.0:oob"); put("grant_type", "refresh_token")
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            val (code, body) = readStream(conn)
            if (code in 200..299) {
                val json = JSONObject(body)
                val newTok = json.optString("access_token", "")
                if (newTok.isNotBlank()) {
                    accessToken = newTok
                    json.optString("refresh_token", "").takeIf { it.isNotBlank() }?.let { refreshToken = it }
                    newTok
                } else accessToken
            } else accessToken
        }.getOrDefault(accessToken)
    }

    suspend fun scrobblePlayback(rawTitleOrFilename: String) = withContext(Dispatchers.IO) {
        var token = accessToken ?: return@withContext
        val cid = clientId
        if (!scrobbleEnabled || token.isBlank() || cid.isBlank()) return@withContext

        runCatching {
            val cleanName = rawTitleOrFilename.substringAfterLast("/").substringBeforeLast(".")
                .replace('.', ' ').replace('_', ' ').trim()
            val epMatch = Regex("""(?i)(.*?)\s*[-–]?\s*S(\d{1,2})E(\d{1,3})""").find(cleanName)
            val payload = JSONObject().apply { put("progress", 95.0); put("app_version", "2.5") }
            if (epMatch != null) {
                val showTitle = epMatch.groupValues[1].replace(Regex("""\(\d{4}\)"""), "").trim()
                val seasonNum = epMatch.groupValues[2].toIntOrNull() ?: 1
                val episodeNum = epMatch.groupValues[3].toIntOrNull() ?: 1
                payload.put("show", JSONObject().put("title", showTitle))
                payload.put("episode", JSONObject().apply { put("season", seasonNum); put("number", episodeNum) })
            } else {
                val yearMatch = Regex("""\((\d{4})\)""").find(cleanName)
                val movieTitle = cleanName.replace(Regex("""\(\d{4}\).*"""), "").trim()
                val movieObj = JSONObject().put("title", movieTitle)
                yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { movieObj.put("year", it) }
                payload.put("movie", movieObj)
            }

            fun postScrobble(bearer: String): Int {
                val conn = CloudflareDns.openConnection(URL("$BASE_URL/scrobble/stop")).apply {
                    requestMethod = "POST"
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $bearer")
                    setRequestProperty("trakt-api-version", "2")
                    setRequestProperty("trakt-api-key", cid)
                    connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
                }
                conn.outputStream.use { it.write(payload.toString().toByteArray()) }
                return conn.responseCode
            }

            if (postScrobble(token) == 401) {
                val refreshed = tryRefreshAccessToken()
                if (!refreshed.isNullOrBlank() && refreshed != token) { token = refreshed; postScrobble(token) }
            }
        }
    }
}
