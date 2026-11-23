package com.vidlab.app.data.prefs

import android.content.Context

class PlayerPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("vidlab_player_prefs", Context.MODE_PRIVATE)

    var defaultSpeed: Float
        get() = prefs.getFloat("default_speed", 1.0f)
        set(value) = prefs.edit().putFloat("default_speed", value).apply()

    var seekStepSeconds: Int
        get() = prefs.getInt("seek_step_seconds", 10)
        set(value) = prefs.edit().putInt("seek_step_seconds", value).apply()

    var preferFfmpegAudio: Boolean
        get() = prefs.getBoolean("prefer_ffmpeg_audio", false)
        set(value) = prefs.edit().putBoolean("prefer_ffmpeg_audio", value).apply()

    var autoEnglishTracks: Boolean
        get() = prefs.getBoolean("auto_english_tracks", true)
        set(value) = prefs.edit().putBoolean("auto_english_tracks", value).apply()

    var defaultResizeMode: Int
        get() = prefs.getInt("default_resize_mode", 0)
        set(value) = prefs.edit().putInt("default_resize_mode", value).apply()

    var subtitleFontSizeSp: Float
        get() = prefs.getFloat("subtitle_font_size_sp", 28f)
        set(value) = prefs.edit().putFloat("subtitle_font_size_sp", value).apply()

    var subtitleColorIndex: Int
        get() = prefs.getInt("subtitle_color_index", 0)
        set(value) = prefs.edit().putInt("subtitle_color_index", value).apply()

    var subtitleBgBox: Boolean
        get() = prefs.getBoolean("subtitle_bg_box", false)
        set(value) = prefs.edit().putBoolean("subtitle_bg_box", value).apply()

    var subtitleBottomPaddingPct: Int
        get() = prefs.getInt("subtitle_bottom_padding_pct", 4)
        set(value) = prefs.edit().putInt("subtitle_bottom_padding_pct", value).apply()

    var volumeGestureEnabled: Boolean
        get() = prefs.getBoolean("volume_gesture_enabled", true)
        set(value) = prefs.edit().putBoolean("volume_gesture_enabled", value).apply()

    var brightnessGestureEnabled: Boolean
        get() = prefs.getBoolean("brightness_gesture_enabled", false)
        set(value) = prefs.edit().putBoolean("brightness_gesture_enabled", value).apply()

    var skipSegmentsEnabled: Boolean
        get() = prefs.getBoolean("skip_segments_enabled", true)
        set(value) = prefs.edit().putBoolean("skip_segments_enabled", value).apply()

    var autoSkipSegments: Boolean
        get() = prefs.getBoolean("auto_skip_segments", false)
        set(value) = prefs.edit().putBoolean("auto_skip_segments", value).apply()

    var decoderMode: Int
        get() = prefs.getInt("decoder_mode", 0)
        set(value) = prefs.edit().putInt("decoder_mode", value).apply()

    var subtitleDelayMs: Long
        get() = prefs.getLong("subtitle_delay_ms", 0L)
        set(value) = prefs.edit().putLong("subtitle_delay_ms", value).apply()

    var longPressBoostEnabled: Boolean
        get() = prefs.getBoolean("long_press_boost_enabled", true)
        set(value) = prefs.edit().putBoolean("long_press_boost_enabled", value).apply()

    var autoDeleteAfterWatch: Boolean
        get() = prefs.getBoolean("auto_delete_after_watch", false)
        set(value) = prefs.edit().putBoolean("auto_delete_after_watch", value).apply()

    var autoProbeMediaSpecs: Boolean
        get() = prefs.getBoolean("auto_probe_media_specs", false)
        set(value) = prefs.edit().putBoolean("auto_probe_media_specs", value).apply()

    init {
        if (!prefs.getBoolean("cloudflare_dns_migrated_v121", false)) {
            prefs.edit()
                .putBoolean("cloudflare_dns_enabled", false)
                .putBoolean("cloudflare_dns_migrated_v121", true)
                .apply()
            com.vidlab.app.data.remote.CloudflareDns.enabled = false
        }
    }

    var cloudflareDnsEnabled: Boolean
        get() = prefs.getBoolean("cloudflare_dns_enabled", false)
        set(value) {
            prefs.edit().putBoolean("cloudflare_dns_enabled", value).apply()
            com.vidlab.app.data.remote.CloudflareDns.enabled = value
        }

    var audioChannelMode: Int
        get() = prefs.getInt("audio_channel_mode", 1)
        set(value) = prefs.edit().putInt("audio_channel_mode", value).apply()

    var volumeNormalization: Boolean
        get() = prefs.getBoolean("volume_normalization", true)
        set(value) = prefs.edit().putBoolean("volume_normalization", value).apply()

    var dynamicRangeCompression: Boolean
        get() = prefs.getBoolean("dynamic_range_compression", true)
        set(value) = prefs.edit().putBoolean("dynamic_range_compression", value).apply()

    fun resetToDefaults() {
        prefs.edit().clear().apply()
    }
}
