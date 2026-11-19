package com.vidlab.app.data.prefs

import android.content.Context
import android.net.Uri
import org.json.JSONObject

object VidLabBackupManager {
    private val PREF_FILES = listOf(
        "vidlab_watch_progress",
        "vidlab_manual_tmdb",
        "vidlab_offline_prefs",
        "vidlab_player_prefs",
        "vidlab_simkl_prefs",
        "vidlab_trakt_prefs",
        "vidlab_anilist_prefs"
    )

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("vidlab_backup_version", 1)
        root.put("exported_at", System.currentTimeMillis())
        val storesObj = JSONObject()
        for (prefName in PREF_FILES) {
            val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
            val entriesObj = JSONObject()
            for ((k, v) in prefs.all) {
                if (v == null) continue
                val item = JSONObject()
                when (v) {
                    is Boolean -> { item.put("t", "B"); item.put("v", v) }
                    is Int -> { item.put("t", "I"); item.put("v", v) }
                    is Long -> { item.put("t", "L"); item.put("v", v) }
                    is Float -> { item.put("t", "F"); item.put("v", v.toDouble()) }
                    is String -> { item.put("t", "S"); item.put("v", v) }
                }
                if (item.has("t")) entriesObj.put(k, item)
            }
            storesObj.put(prefName, entriesObj)
        }
        root.put("stores", storesObj)
        return root.toString(2)
    }

    fun exportToUri(context: Context, uri: Uri): Result<Int> = runCatching {
        val jsonStr = exportToJson(context)
        context.contentResolver.openOutputStream(uri)?.use { out ->
            out.write(jsonStr.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Unable to open destination file")
        val stores = JSONObject(jsonStr).getJSONObject("stores")
        var totalKeys = 0
        val keys = stores.keys()
        while (keys.hasNext()) {
            totalKeys += stores.getJSONObject(keys.next()).length()
        }
        totalKeys
    }

    fun importFromUri(context: Context, uri: Uri): Result<Int> = runCatching {
        val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalStateException("Unable to read backup file")
        val root = JSONObject(raw)
        val stores = root.optJSONObject("stores") ?: throw IllegalArgumentException("Invalid VidLab backup format")
        var restoredCount = 0
        val storeNames = stores.keys()
        while (storeNames.hasNext()) {
            val prefName = storeNames.next()
            if (prefName !in PREF_FILES) continue
            val entries = stores.getJSONObject(prefName)
            val editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit()
            val entryKeys = entries.keys()
            while (entryKeys.hasNext()) {
                val k = entryKeys.next()
                val obj = entries.optJSONObject(k) ?: continue
                when (obj.optString("t")) {
                    "B" -> editor.putBoolean(k, obj.getBoolean("v"))
                    "I" -> editor.putInt(k, obj.getInt("v"))
                    "L" -> editor.putLong(k, obj.getLong("v"))
                    "F" -> editor.putFloat(k, obj.getDouble("v").toFloat())
                    "S" -> editor.putString(k, obj.getString("v"))
                }
                restoredCount++
            }
            editor.commit()
        }
        WatchProgressStore.bump()
        restoredCount
    }
}
