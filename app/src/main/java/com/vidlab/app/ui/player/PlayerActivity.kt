package com.vidlab.app.ui.player

import androidx.compose.foundation.BorderStroke
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import com.vidlab.app.data.prefs.PlayerPreferences
import com.vidlab.app.data.prefs.WatchProgressStore
import androidx.media3.extractor.metadata.id3.ChapterFrame
import com.vidlab.app.data.remote.SegmentType
import com.vidlab.app.data.remote.SkipSegment
import com.vidlab.app.data.remote.SimklManager
import com.vidlab.app.data.remote.SkipSegmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import android.graphics.Typeface
import android.util.TypedValue
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import java.util.Locale

data class TrackOption(
    val id: String,
    val label: String,
    val language: String,
    val group: Tracks.Group?,
    val trackIndex: Int,
    val isSelected: Boolean
)

class PlayerActivity : ComponentActivity() {
    private var exoPlayer: ExoPlayer? = null
    internal val channelProcessor = VidLabChannelAudioProcessor()
    internal val effectsController = VidLabAudioEffectsController()
    internal var isInPipState by mutableStateOf(false)

    fun enterVidLabPipMode() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            runCatching { enterPictureInPictureMode(params) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPipState = isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // PiP is disabled by default on Home/exit; user can trigger it manually from the player menu.
    }

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        val incomingPath = intent.getStringExtra("FILE_PATH") ?: ""
        val rawPath = if (
            com.vidlab.app.data.remote.RcloneEngine.isLocalStorageModeEnabled(this) &&
            incomingPath.startsWith("/VidLab/", ignoreCase = true)
        ) {
            val rel = incomingPath.substringAfter("/VidLab/")
            val localFile = java.io.File(com.vidlab.app.data.remote.DownloadTaskManager.getPublicOfflineRoot(this), rel)
            if (localFile.exists()) android.net.Uri.fromFile(localFile).toString() else incomingPath
        } else incomingPath
        val fileName = intent.getStringExtra("FILE_NAME") ?: "Playing"
        val displayTitle = fileName.substringBeforeLast('.')
        val streamUrl = com.vidlab.app.data.remote.RcloneEngine.buildStreamUrl(this, rawPath)
        if (com.vidlab.app.data.remote.RcloneEngine.isRcloneModeEnabled(this)) {
            com.vidlab.app.data.remote.RcloneEngine.startForegroundServeService(this)
        }

        val playerPrefs = PlayerPreferences(this)
        val watchProgressStore = WatchProgressStore(this)
        val savedProg = watchProgressStore.getProgress(rawPath)
        val dMode = playerPrefs.decoderMode
        val extMode = when {
            dMode == 1 -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            dMode == 2 || playerPrefs.preferFfmpegAudio -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        }
        val codecSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            when (dMode) {
                1 -> all.filter { it.hardwareAccelerated }.ifEmpty { all }
                2 -> all.filter { it.softwareOnly }.ifEmpty { all }
                else -> all
            }
        }
        channelProcessor.mode = playerPrefs.audioChannelMode
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink {
                return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(channelProcessor))
                    .build()
            }
        }
            .setExtensionRendererMode(extMode)
            .setMediaCodecSelector(codecSelector)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 60_000, 1_200, 2_500)
            .setBackBuffer(15_000, true)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val createdPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .build().apply {
            if (playerPrefs.autoEnglishTracks) {
                trackSelectionParameters = trackSelectionParameters
                    .buildUpon()
                    .setPreferredAudioLanguages("en", "eng")
                    .setPreferredTextLanguages("en", "eng")
                    .build()
            }
            setPlaybackSpeed(playerPrefs.defaultSpeed)
            playWhenReady = true
        }
        exoPlayer = createdPlayer

        CoroutineScope(Dispatchers.Main).launch {
            if (com.vidlab.app.data.remote.RcloneEngine.isRcloneModeEnabled(this@PlayerActivity)) {
                com.vidlab.app.data.remote.RcloneEngine.ensureServerRunning(this@PlayerActivity)
            }
            createdPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(streamUrl)))
            createdPlayer.prepare()
            if (savedProg != null && !savedProg.watched && savedProg.positionMs > 3000L) {
                createdPlayer.seekTo(savedProg.positionMs)
            }
        }

        val passedPaths = intent.getStringArrayListExtra("PLAYLIST_PATHS") ?: arrayListOf()
        val passedNames = intent.getStringArrayListExtra("PLAYLIST_NAMES") ?: arrayListOf()
        val initialPlaylist = passedPaths.zip(passedNames)

        setContent {
            exoPlayer?.let { player ->
                ModernPlayerScreen(
                    player = player,
                    title = displayTitle,
                    filePath = rawPath,
                    streamUrl = streamUrl,
                    initialPlaylist = initialPlaylist,
                    onBack = { finish() }
                )
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.let { p ->
            val url = intent.getStringExtra("FILE_PATH") ?: ""
            if (url.isNotBlank() && p.duration > 0L) {
                WatchProgressStore(this).saveProgress(url, p.currentPosition, p.duration)
            }
            if (!isInPictureInPictureMode) p.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) exoPlayer?.pause()
    }

    override fun onDestroy() {
        effectsController.release()
        exoPlayer?.release()
        exoPlayer = null
        super.onDestroy()
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun ModernPlayerScreen(
    player: ExoPlayer,
    title: String,
    filePath: String = "",
    streamUrl: String = "",
    initialPlaylist: List<Pair<String, String>> = emptyList(),
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var isPlaying by remember { mutableStateOf(true) }
    var isBuffering by remember { mutableStateOf(true) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var currentPos by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    var controlsVisible by remember { mutableStateOf(true) }
    var showToolsDialog by remember { mutableStateOf(false) }
    var showStatsHud by remember { mutableStateOf(false) }
    var audioChannelMode by remember { mutableIntStateOf(PlayerPreferences(context).audioChannelMode) }
    var volumeNorm by remember { mutableStateOf(PlayerPreferences(context).volumeNormalization) }
    var drcEnabled by remember { mutableStateOf(PlayerPreferences(context).dynamicRangeCompression) }
    var sleepTimerEndMs by remember { mutableLongStateOf(0L) }
    var sleepAfterCurrentFile by remember { mutableStateOf(false) }
    var loopPointAMs by remember { mutableLongStateOf(-1L) }
    var loopPointBMs by remember { mutableLongStateOf(-1L) }

    val hostActivity = context as? PlayerActivity
    val isInPip = hostActivity?.isInPipState == true

    LaunchedEffect(audioChannelMode, volumeNorm, drcEnabled, player.audioSessionId) {
        hostActivity?.channelProcessor?.mode = audioChannelMode
        hostActivity?.effectsController?.applyEffects(player.audioSessionId, volumeNorm, drcEnabled)
    }

    LaunchedEffect(currentPos, sleepTimerEndMs, sleepAfterCurrentFile, loopPointAMs, loopPointBMs) {
        if (loopPointAMs >= 0L && loopPointBMs > loopPointAMs + 500L && currentPos >= loopPointBMs) {
            player.seekTo(loopPointAMs)
            currentPos = loopPointAMs
        }
        if (sleepTimerEndMs > 0L && System.currentTimeMillis() >= sleepTimerEndMs) {
            sleepTimerEndMs = 0L
            player.pause()
        }
        if (sleepAfterCurrentFile && duration > 0L && currentPos >= duration - 1200L) {
            sleepAfterCurrentFile = false
            player.pause()
        }
    }
    var isLocked by remember { mutableStateOf(false) }
    val playerPrefs = remember { PlayerPreferences(context) }
    val seekStepMs = remember { playerPrefs.seekStepSeconds * 1000L }
    var resizeModeIndex by remember { mutableIntStateOf(playerPrefs.defaultResizeMode.coerceIn(0, 2)) }
    val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Fill"
    )
    var playbackSpeed by remember { mutableFloatStateOf(playerPrefs.defaultSpeed) }
    var simklScrobbled by remember { mutableStateOf(false) }
    var autoDeletedOffline by remember { mutableStateOf(false) }
    var skipSegments by remember { mutableStateOf<List<SkipSegment>>(emptyList()) }
    var autoSkippedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var activeFilePath by remember { mutableStateOf(filePath) }
    var activeTitle by remember { mutableStateOf(title) }
    var activeStreamUrl by remember { mutableStateOf(streamUrl) }
    var siblingEpisodes by remember { mutableStateOf(initialPlaylist) }

    LaunchedEffect(activeFilePath) {
        if (siblingEpisodes.isEmpty() && activeFilePath.isNotBlank()) {
            siblingEpisodes = fetchSiblingEpisodes(context, activeFilePath)
        }
    }

    val currentEpIndex = remember(siblingEpisodes, activeFilePath) {
        siblingEpisodes.indexOfFirst { it.first == activeFilePath }
    }
    val prevEpisode = if (currentEpIndex > 0) siblingEpisodes[currentEpIndex - 1] else null
    val nextEpisode = if (currentEpIndex >= 0 && currentEpIndex + 1 < siblingEpisodes.size) siblingEpisodes[currentEpIndex + 1] else null

    fun switchToEpisode(targetPath: String, targetFileName: String) {
        if (activeFilePath.isNotBlank() && duration > 0L) {
            WatchProgressStore(context).saveProgress(activeFilePath, player.currentPosition, duration)
        }
        val newUrl = com.vidlab.app.data.remote.RcloneEngine.buildStreamUrl(context, targetPath)
        activeFilePath = targetPath
        activeTitle = targetFileName.substringBeforeLast('.')
        activeStreamUrl = newUrl
        skipSegments = emptyList()
        autoSkippedIds = emptySet()
        simklScrobbled = false
        val saved = WatchProgressStore(context).getProgress(targetPath)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(newUrl)))
        player.prepare()
        if (saved != null && !saved.watched && saved.positionMs > 3000L) {
            player.seekTo(saved.positionMs)
        }
        player.playWhenReady = true
    }
    var customScale by remember { mutableFloatStateOf(1f) }
    var customOffset by remember { mutableStateOf(Offset.Zero) }

    var showAudioDialog by remember { mutableStateOf(false) }
    var showSubtitleDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showDecoderDialog by remember { mutableStateOf(false) }
    var gestureOverlayText by remember { mutableStateOf<String?>(null) }
    var externalSubLabel by remember { mutableStateOf<String?>(null) }

    var activeExternalSubUri by remember { mutableStateOf<Uri?>(null) }
    var activeExternalSubRawUrl by remember { mutableStateOf<String?>(null) }
    var subtitleOffsetMs by remember { mutableLongStateOf(0L) }
    val externalSubLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIdx >= 0) cursor.getString(nameIdx) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "External Subtitle"

            val lower = displayName.lowercase()
            val mimeType = when {
                lower.endsWith(".ass") || lower.endsWith(".ssa") -> MimeTypes.TEXT_SSA
                lower.endsWith(".vtt") -> MimeTypes.TEXT_VTT
                else -> MimeTypes.APPLICATION_SUBRIP
            }

            val subConfig = MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mimeType)
                .setLanguage("en")
                .setLabel(displayName)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
                .build()

            val resumePos = player.currentPosition.coerceAtLeast(0L)
            val wasPlaying = player.playWhenReady
            val updatedItem = MediaItem.Builder()
                .setUri(Uri.parse(activeStreamUrl))
                .setSubtitleConfigurations(listOf(subConfig))
                .build()

            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .build()

            player.setMediaItem(updatedItem, resumePos)
            player.prepare()
            player.playWhenReady = wasPlaying
            activeExternalSubUri = uri; activeExternalSubRawUrl = null; subtitleOffsetMs = 0L; externalSubLabel = displayName
            gestureOverlayText = "Loaded: $displayName"
        }
    }

    var audioTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }
    var subtitleTracks by remember { mutableStateOf<List<TrackOption>>(emptyList()) }

    var seekFeedbackText by remember { mutableStateOf<String?>(null) }
    var seekFeedbackIsLeft by remember { mutableStateOf(false) }
    var seekAccumSeconds by remember { mutableIntStateOf(0) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }
    var is2xSpeedActive by remember { mutableStateOf(false) }
    var subStyleTick by remember { mutableIntStateOf(0) }
    var brightnessLevel by remember {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f } ?: 0.5f
        )
    }
    var verticalHudType by remember { mutableStateOf<String?>(null) }
    var verticalHudFraction by remember { mutableFloatStateOf(0.5f) }
    var verticalHudTick by remember { mutableIntStateOf(0) }
    var showMoreMenu by remember { mutableStateOf(false) }

    LaunchedEffect(verticalHudTick) {
        if (verticalHudTick > 0) {
            delay(900L)
            verticalHudType = null
        }
    }

    fun refreshTracks(tracks: Tracks) {
        val audios = mutableListOf<TrackOption>()
        val subs = mutableListOf<TrackOption>()
        var anySubSelected = false

        for ((gIdx, group) in tracks.groups.withIndex()) {
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (tIdx in 0 until group.length) {
                        val format = group.getTrackFormat(tIdx)
                        val langCode = format.language ?: "und"
                        val langName = Locale(langCode).displayLanguage.takeIf { it.isNotBlank() && langCode != "und" } ?: "Track ${audios.size + 1}"
                        val label = listOfNotNull(format.label, langName).distinct().joinToString(" • ")
                        audios.add(
                            TrackOption(
                                id = "aud_${gIdx}_$tIdx",
                                label = label,
                                language = langCode,
                                group = group,
                                trackIndex = tIdx,
                                isSelected = group.isTrackSelected(tIdx)
                            )
                        )
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (tIdx in 0 until group.length) {
                        val format = group.getTrackFormat(tIdx)
                        val langCode = format.language ?: "und"
                        val langName = Locale(langCode).displayLanguage.takeIf { it.isNotBlank() && langCode != "und" } ?: "Subtitle ${subs.size + 1}"
                        val label = listOfNotNull(format.label, langName).distinct().joinToString(" • ")
                        val selected = group.isTrackSelected(tIdx)
                        if (selected) anySubSelected = true
                        subs.add(
                            TrackOption(
                                id = "sub_${gIdx}_$tIdx",
                                label = label,
                                language = langCode,
                                group = group,
                                trackIndex = tIdx,
                                isSelected = selected
                            )
                        )
                    }
                }
            }
        }
        subs.add(0, TrackOption("sub_off", "Off", "off", null, -1, !anySubSelected))
        audioTracks = audios
        subtitleTracks = subs
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isBuffering = playbackState == Player.STATE_BUFFERING
                if (playbackState == Player.STATE_READY) {
                    duration = player.duration.coerceAtLeast(0L)
                }
            }

            override fun onMetadata(metadata: androidx.media3.common.Metadata) {
                if (!playerPrefs.skipSegmentsEnabled) return
                val chapters = mutableListOf<SkipSegment>()
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is ChapterFrame && entry.endTimeMs > entry.startTimeMs) {
                        val chapTitle = entry.chapterId ?: "Chapter"
                        val segType = SkipSegmentManager.classifyChapterTitle(chapTitle)
                        chapters.add(SkipSegment(entry.startTimeMs.toLong(), entry.endTimeMs.toLong(), segType, chapTitle))
                    }
                }
                if (chapters.isNotEmpty()) {
                    val nonChap = skipSegments.filter { it.type != SegmentType.CHAPTER }
                    skipSegments = (nonChap + chapters).sortedBy { it.startMs }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                refreshTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                playerError = "${error.errorCodeName}:${error.message}"
            }
        }
        player.addListener(listener)
        refreshTracks(player.currentTracks)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(activeTitle, activeStreamUrl, duration) {
        if (playerPrefs.skipSegmentsEnabled && activeTitle.isNotBlank()) {
            val fetched = SkipSegmentManager.fetchSegmentsForMedia(activeTitle, duration, activeStreamUrl)
            if (fetched.isNotEmpty()) {
                val existingChapters = skipSegments.filter { it.type == SegmentType.CHAPTER }
                skipSegments = (fetched + existingChapters).sortedBy { it.startMs }
            }
        }
    }

    LaunchedEffect(isPlaying) {
        while (true) {
            currentPos = player.currentPosition.coerceAtLeast(0L)
            if (player.duration > 0L) duration = player.duration
            if (playerPrefs.skipSegmentsEnabled && playerPrefs.autoSkipSegments && duration > 0L) {
                val activeAuto = skipSegments.firstOrNull { seg ->
                    seg.type != SegmentType.CHAPTER &&
                        currentPos in seg.startMs until (seg.endMs - 1500L) &&
                        seg.startMs !in autoSkippedIds
                }
                if (activeAuto != null) {
                    autoSkippedIds = autoSkippedIds + activeAuto.startMs
                    player.seekTo(activeAuto.endMs)
                    currentPos = activeAuto.endMs
                    gestureOverlayText = "Auto-skipped ${activeAuto.type.label.removePrefix("Skip ")}"
                }
            }
            if (duration > 0L && currentPos > 0L && (currentPos / 1000L) % 5L == 0L) {
                if (activeFilePath.isNotBlank()) WatchProgressStore(context).saveProgress(activeFilePath, currentPos, duration)
                if (!simklScrobbled && currentPos.toFloat() / duration.toFloat() >= 0.80f) {
                    simklScrobbled = true
                    CoroutineScope(Dispatchers.IO).launch {
                        val sm = SimklManager(context); val tm = com.vidlab.app.data.remote.TraktManager(context); val am = com.vidlab.app.data.remote.AniListManager(context)
                        sm.scrobblePlayback(activeTitle); tm.scrobblePlayback(activeTitle); am.scrobblePlayback(activeTitle, isAnimeHint = activeFilePath.contains("/Anime/", ignoreCase = true))
                        val synced = listOfNotNull(if (sm.isSignedIn && sm.scrobbleEnabled) "Simkl" else null, if (tm.isSignedIn && tm.scrobbleEnabled) "Trakt" else null, if (am.isSignedIn && am.scrobbleEnabled) "AniList" else null)
                        if (synced.isNotEmpty()) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { android.widget.Toast.makeText(context, "Synced progress to ${synced.joinToString(" & ")}", android.widget.Toast.LENGTH_SHORT).show() }
                    }
                }
                if (!autoDeletedOffline && playerPrefs.autoDeleteAfterWatch && currentPos.toFloat() / duration.toFloat() >= 0.90f && activeFilePath.isNotBlank()) {
                    autoDeletedOffline = true
                    val removed = com.vidlab.app.data.remote.DownloadTaskManager.deleteDownloadedFile(context, activeFilePath)
                    if (removed) {
                        android.widget.Toast.makeText(context, "Deleted offline copy after watching", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
            delay(400L)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, showAudioDialog, showSubtitleDialog, showSpeedDialog, showDecoderDialog) {
        if (controlsVisible && isPlaying && !showAudioDialog && !showSubtitleDialog && !showSpeedDialog && !showDecoderDialog) {
            delay(4000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(seekFeedbackTick) {
        if (seekFeedbackTick > 0) {
            delay(900L)
            seekFeedbackText = null
            seekAccumSeconds = 0
        }
    }

    LaunchedEffect(gestureOverlayText) {
        if (gestureOverlayText != null) {
            delay(900L)
            gestureOverlayText = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    subtitleView?.let { sub ->
                        (sub.parent as? android.view.ViewGroup)?.removeView(sub)
                        addView(
                            sub,
                            android.widget.FrameLayout.LayoutParams(
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                                android.view.ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                }
            },
            update = { view ->
                view.resizeMode = resizeModes[resizeModeIndex].first
                view.clipChildren = false
                view.clipToPadding = false
                val contentFrame = view.videoSurfaceView?.parent as? android.view.ViewGroup
                if (contentFrame != null) {
                    contentFrame.clipChildren = false
                    contentFrame.clipToPadding = false
                    contentFrame.pivotX = contentFrame.width / 2f
                    contentFrame.pivotY = contentFrame.height / 2f
                    contentFrame.scaleX = customScale
                    contentFrame.scaleY = customScale
                    contentFrame.translationX = 0f
                    contentFrame.translationY = 0f
                }
                view.subtitleView?.let { sub ->
                    val fgColor = when (playerPrefs.subtitleColorIndex) {
                        1 -> android.graphics.Color.YELLOW
                        2 -> android.graphics.Color.CYAN
                        3 -> android.graphics.Color.GREEN
                        else -> android.graphics.Color.WHITE
                    }
                    val bgColor = if (playerPrefs.subtitleBgBox) android.graphics.Color.argb(165, 0, 0, 0) else android.graphics.Color.TRANSPARENT
                    val edgeType = if (playerPrefs.subtitleBgBox) CaptionStyleCompat.EDGE_TYPE_NONE else CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    sub.setApplyEmbeddedStyles(false)
                    sub.setApplyEmbeddedFontSizes(false)
                    sub.setStyle(CaptionStyleCompat(fgColor, bgColor, android.graphics.Color.TRANSPARENT, edgeType, android.graphics.Color.BLACK, Typeface.DEFAULT_BOLD))
                    sub.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, playerPrefs.subtitleFontSizeSp)
                    val _tick = subStyleTick
                    val basePct = playerPrefs.subtitleBottomPaddingPct
                    val effectivePct = if (controlsVisible && !isLocked) maxOf(basePct, 11) else basePct
                    sub.setBottomPaddingFraction(effectivePct / 100f)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(isLocked) {
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                        },
                        onDoubleTap = { offset ->
                            if (!isLocked) {
                                val isLeft = offset.x < size.width / 2f
                                val delta = if (isLeft) -seekStepMs else seekStepMs
                                val maxDur = if (duration > 0) duration else Long.MAX_VALUE
                                val target = (player.currentPosition + delta).coerceIn(0L, maxDur)
                                player.seekTo(target)
                                currentPos = target
                                val stepSec = playerPrefs.seekStepSeconds
                                seekAccumSeconds = if (seekFeedbackText != null && seekFeedbackIsLeft == isLeft) {
                                    seekAccumSeconds + stepSec
                                } else {
                                    stepSec
                                }
                                seekFeedbackIsLeft = isLeft
                                seekFeedbackText = if (isLeft) "« ${seekAccumSeconds}s" else "${seekAccumSeconds}s »"
                                seekFeedbackTick += 1
                            }
                        }
                    )
                }
                .pointerInput(isLocked, playbackSpeed) {
                    if (!isLocked) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val startPos = down.position
                            var boosted = false
                            val holdJob = CoroutineScope(Dispatchers.Main).launch {
                                delay(360L)
                                boosted = true
                                is2xSpeedActive = true
                                player.setPlaybackSpeed(2.0f)
                            }
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val active = event.changes.firstOrNull { it.id == down.id }
                                    if (active == null || !active.pressed) break
                                    if (!boosted && (active.position - startPos).getDistance() > viewConfiguration.touchSlop) {
                                        holdJob.cancel()
                                        break
                                    }
                                }
                            } finally {
                                holdJob.cancel()
                                if (boosted) {
                                    is2xSpeedActive = false
                                    player.setPlaybackSpeed(playbackSpeed)
                                }
                            }
                        }
                    }
                }
                .pointerInput(isLocked) {
                    if (!isLocked) {
                        var isLeftSide = true
                        var volumeAccum = 0f
                        var ignoreVerticalDrag = false
                        detectVerticalDragGestures(
                            onDragStart = { startOffset ->
                                val edgeGuardPx = (size.height * 0.15f).coerceIn(80f, 160f)
                                ignoreVerticalDrag = startOffset.y < edgeGuardPx || startOffset.y > (size.height - edgeGuardPx)
                                isLeftSide = startOffset.x < size.width / 2f
                                volumeAccum = 0f
                            },
                            onVerticalDrag = { change, dragAmount ->
                                if (ignoreVerticalDrag) return@detectVerticalDragGestures
                                change.consume()
                                val deltaFraction = -dragAmount / (size.height.toFloat().coerceAtLeast(1f))
                                if (isLeftSide) {
                                    if (playerPrefs.brightnessGestureEnabled) {
                                        brightnessLevel = (brightnessLevel + deltaFraction * 1.4f).coerceIn(0.01f, 1.0f)
                                        activity?.window?.let { win ->
                                            val lp = win.attributes
                                            lp.screenBrightness = brightnessLevel
                                            win.attributes = lp
                                        }
                                        verticalHudType = "brightness"
                                        verticalHudFraction = brightnessLevel
                                        verticalHudTick++
                                    }
                                } else if (playerPrefs.volumeGestureEnabled) {
                                    val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    volumeAccum += deltaFraction * maxVol * 1.5f
                                    val steps = volumeAccum.toInt()
                                    if (steps != 0) {
                                        volumeAccum -= steps
                                        val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                        val newVol = (curVol + steps).coerceIn(0, maxVol)
                                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                        verticalHudType = "volume"
                                        verticalHudFraction = newVol.toFloat() / maxVol.toFloat()
                                        verticalHudTick++
                                    }
                                }
                            }
                        )
                    }
                }
                .pointerInput(isLocked) {
                    if (!isLocked) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var prevDist = 0f
                            var prevCentroid = Offset.Zero
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    val p0 = pressed[0].position
                                    val p1 = pressed[1].position
                                    val dist = (p0 - p1).getDistance()
                                    val centroid = Offset((p0.x + p1.x) / 2f, (p0.y + p1.y) / 2f)
                                    if (prevDist > 0f && dist > 0f) {
                                        val zoom = dist / prevDist
                                        val pan = centroid - prevCentroid
                                        val nextScale = (customScale * zoom).coerceIn(1f, 3.0f)
                                        customScale = nextScale
                                        customOffset = if (nextScale <= 1.01f) {
                                            Offset.Zero
                                        } else {
                                            Offset(
                                                x = (customOffset.x + pan.x).coerceIn(-size.width * 0.6f, size.width * 0.6f),
                                                y = (customOffset.y + pan.y).coerceIn(-size.height * 0.6f, size.height * 0.6f)
                                            )
                                        }
                                        gestureOverlayText = "Zoom: ${(customScale * 100).toInt()}%"
                                    }
                                    prevDist = dist
                                    prevCentroid = centroid
                                    pressed.forEach { it.consume() }
                                } else {
                                    prevDist = 0f
                                }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                }

        )
        if (customScale > 1.02f && controlsVisible && !isLocked) {
            Surface(
                onClick = { customScale = 1f; customOffset = Offset.Zero; gestureOverlayText = "Zoom: 100%" },
                color = Color(0xFF244985),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 24.dp)
            ) {
                Text("Reset Zoom (${(customScale * 100).toInt()}%)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }

        if (customScale > 1.02f && controlsVisible && !isLocked) {
            Surface(
                onClick = {
                    customScale = 1f
                    customOffset = Offset.Zero
                    gestureOverlayText = "Zoom: 100%"
                },
                color = Color(0xFF244985),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 24.dp)
            ) {
                Text(
                    text = "Reset Zoom (${(customScale * 100).toInt()}%)",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }

        if (isBuffering && playerError == null) {
            CircularProgressIndicator(
                color = Color(0xFF244985),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        if (is2xSpeedActive) {
            Surface(
                color = Color(0xDD131A29),
                shape = RoundedCornerShape(20.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244985)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            ) {
                Text(
                    text = "2.0x Speed »",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp)
                )
            }
        }

        if (seekFeedbackText != null) {
            Surface(
                color = Color(0xCC0D1015),
                shape = CircleShape,
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
                shadowElevation = 8.dp,
                modifier = Modifier
                    .align(if (seekFeedbackIsLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 128.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                ) {
                    Icon(
                        imageVector = if (seekFeedbackIsLeft) Icons.Default.FastRewind else Icons.Default.FastForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "${if (seekFeedbackIsLeft) "-" else "+"}${seekAccumSeconds}s",
                        color = Color(0xFFE2E8F0),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (gestureOverlayText != null) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 64.dp)
            ) {
                Text(
                    text = gestureOverlayText!!,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
        }

        verticalHudType?.let { hudKind ->
            val pct = (verticalHudFraction.coerceIn(0f, 1f) * 100).toInt()
            val isBrightnessHud = hudKind == "brightness"
            Surface(
                color = Color(0xFF0D131F).copy(alpha = 0.88f),
                shape = RoundedCornerShape(24.dp),
                border = BorderStroke(1.dp, Color(0xFF222E46)),
                modifier = Modifier
                    .align(if (isBrightnessHud) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 28.dp)
                    .width(50.dp)
                    .height(176.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 10.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "$pct%",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .weight(1f)
                            .padding(vertical = 8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF1A2334)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(verticalHudFraction.coerceIn(0.02f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        colors = listOf(Color(0xFF60A5FA), Color(0xFF3B6CB7))
                                    )
                                )
                        )
                    }
                    Icon(
                        imageVector = if (isBrightnessHud) Icons.Default.BrightnessHigh else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color(0xFF8AB4F8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (isLocked && controlsVisible) {
            IconButton(
                onClick = { isLocked = false },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Unlock Screen",
                    tint = Color.White
                )
            }
        }

        val activeSkipSegment = remember(currentPos, skipSegments, playerPrefs.skipSegmentsEnabled) {
            if (!playerPrefs.skipSegmentsEnabled) null
            else skipSegments.firstOrNull { seg ->
                seg.type != SegmentType.CHAPTER && currentPos in seg.startMs until (seg.endMs - 1000L)
            }
        }

        if ((!controlsVisible || isLocked) && activeSkipSegment != null) {
            Surface(
                onClick = {
                    player.seekTo(activeSkipSegment.endMs)
                    currentPos = activeSkipSegment.endMs
                },
                color = Color(0xE61C2230),
                shape = RoundedCornerShape(22.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .safeDrawingPadding()
                    .padding(end = 24.dp, bottom = 28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = null,
                        tint = Color(0xFF244985),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = activeSkipSegment.type.label,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !isLocked && !isInPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            val hudPillBg = Color(0xCC1B202C)
            val hudCircleBg = Color(0xCC1B202C)

            Box(modifier = Modifier.fillMaxSize()) {
                // TOP BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(hudCircleBg)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            color = hudPillBg,
                            shape = RoundedCornerShape(22.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, Color.White.copy(alpha = 0.12f))
                        ) {
                            Text(
                                text = activeTitle,
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                            )
                        }
                    }

                    var isPortraitMode by remember { mutableStateOf(false) }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = { showAudioDialog = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(hudCircleBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Audiotrack,
                                contentDescription = "Audio Track",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { showSubtitleDialog = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(hudCircleBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Subtitles,
                                contentDescription = "Subtitles",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box {
                            IconButton(
                                onClick = { showMoreMenu = true },
                                modifier = Modifier.size(42.dp).clip(CircleShape).background(hudCircleBg)
                            ) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                            DropdownMenu(
                                expanded = showMoreMenu,
                                onDismissRequest = { showMoreMenu = false },
                                modifier = Modifier.background(Color(0xFF10141D))
                            ) {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Sleep Timer & A-B Loop", color = Color.White, fontSize = 13.sp) },
                                    onClick = { showMoreMenu = false; showToolsDialog = true }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Analytics, contentDescription = null, tint = if (showStatsHud) Color(0xFF3B6CB7) else Color(0xFF8AB4F8), modifier = Modifier.size(18.dp)) },
                                    text = { Text(if (showStatsHud) "Hide Stats for Nerds" else "Stats for Nerds", color = Color.White, fontSize = 13.sp) },
                                    onClick = { showMoreMenu = false; showStatsHud = !showStatsHud }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(18.dp)) },
                                    text = { Text("Picture-in-Picture", color = Color.White, fontSize = 13.sp) },
                                    onClick = { showMoreMenu = false; hostActivity?.enterVidLabPipMode() }
                                )
                            }
                        }
                    }
                }

                // CENTER: Previous Episode, Play/Pause, Next Episode
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(28.dp)
                ) {
                    IconButton(
                        onClick = { prevEpisode?.let { switchToEpisode(it.first, it.second) } },
                        enabled = prevEpisode != null,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(hudCircleBg.copy(alpha = if (prevEpisode != null) 0.8f else 0.35f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous Episode",
                            tint = Color.White.copy(alpha = if (prevEpisode != null) 1f else 0.35f),
                            modifier = Modifier.size(26.dp)
                        )
                    }

                    IconButton(
                        onClick = { if (player.isPlaying) player.pause() else player.play() },
                        modifier = Modifier
                            .size(66.dp)
                            .clip(CircleShape)
                            .background(hudCircleBg)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(34.dp)
                        )
                    }

                    IconButton(
                        onClick = { nextEpisode?.let { switchToEpisode(it.first, it.second) } },
                        enabled = nextEpisode != null,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(hudCircleBg.copy(alpha = if (nextEpisode != null) 0.8f else 0.35f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next Episode",
                            tint = Color.White.copy(alpha = if (nextEpisode != null) 1f else 0.35f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                // BOTTOM CONTROLS + SEGMENTED SEEKBAR
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        IconButton(
                            onClick = { isLocked = true },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(hudCircleBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LockOpen,
                                contentDescription = "Lock Controls",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        Surface(
                            onClick = { showSpeedDialog = true },
                            color = hudCircleBg,
                            shape = CircleShape,
                            modifier = Modifier.height(42.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = "${playbackSpeed}x",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        val decoderLabels = listOf("Auto", "HW", "SW")
                        val currentDecoderIdx = playerPrefs.decoderMode.coerceIn(0, 2)
                        Surface(
                            onClick = { showDecoderDialog = true },
                            color = hudCircleBg,
                            shape = CircleShape,
                            modifier = Modifier.height(42.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Text(
                                    text = decoderLabels[currentDecoderIdx],
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        if (activeSkipSegment != null) {
                            Surface(
                                onClick = {
                                    player.seekTo(activeSkipSegment.endMs)
                                    currentPos = activeSkipSegment.endMs
                                },
                                color = Color(0xE6222838),
                                shape = RoundedCornerShape(21.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF244985).copy(alpha = 0.5f)),
                                modifier = Modifier.height(42.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                ) {
                                    Text(
                                        text = activeSkipSegment.type.label,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        IconButton(
                            onClick = {
                                val isPortraitMode = activity?.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT || activity?.requestedOrientation == android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                                val nextPortrait = !isPortraitMode
                                activity?.requestedOrientation = if (nextPortrait) {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                } else {
                                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(hudCircleBg)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ScreenRotation,
                                contentDescription = "Toggle Orientation",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Surface(
                            onClick = { customScale = 1f; customOffset = Offset.Zero; resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size },
                            color = hudCircleBg,
                            shape = CircleShape,
                            modifier = Modifier.height(42.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 14.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AspectRatio,
                                    contentDescription = "Aspect Ratio",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = resizeModes[resizeModeIndex].second,
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }

                    val chapters = remember(skipSegments) { skipSegments.filter { it.type == SegmentType.CHAPTER } }
                    val activeChapter = remember(currentPos, chapters) { chapters.firstOrNull { currentPos in it.startMs..it.endMs } }
                    Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    if (activeChapter != null) {
                        Text(activeChapter.type.label, color = Color(0xFF8AB4F8), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDuration(currentPos),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    offset = Offset(1f, 1f),
                                    blurRadius = 4f
                                )
                            )
                        )
                        Text(
                            text = formatDuration(duration),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            style = androidx.compose.ui.text.TextStyle(
                                shadow = androidx.compose.ui.graphics.Shadow(
                                    color = Color.Black.copy(alpha = 0.85f),
                                    offset = Offset(1f, 1f),
                                    blurRadius = 4f
                                )
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                                .pointerInput(duration) {
                                    detectTapGestures { offset ->
                                        if (duration > 0L && size.width > 0) {
                                            val frac = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            val target = (frac * duration).toLong()
                                            currentPos = target
                                            player.seekTo(target)
                                        }
                                    }
                                }
                                .pointerInput(duration) {
                                    detectHorizontalDragGestures { change, _ ->
                                        change.consume()
                                        if (duration > 0L && size.width > 0) {
                                            val frac = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                                            val target = (frac * duration).toLong()
                                            currentPos = target
                                            player.seekTo(target)
                                        }
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            val totalDur = duration.coerceAtLeast(1L).toFloat()
                            val progressFraction = (currentPos.toFloat() / totalDur).coerceIn(0f, 1f)

                            androidx.compose.foundation.Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(24.dp)
                            ) {
                                val trackH = 10.dp.toPx()
                                val topY = (size.height - trackH) / 2f
                                val corner = androidx.compose.ui.geometry.CornerRadius(trackH / 2f, trackH / 2f)

                                drawRoundRect(
                                    color = Color.White.copy(alpha = 0.22f),
                                    topLeft = Offset(0f, topY),
                                    size = androidx.compose.ui.geometry.Size(size.width, trackH),
                                    cornerRadius = corner
                                )

                                drawRoundRect(
                                    color = Color(0xFF244985),
                                    topLeft = Offset(0f, topY),
                                    size = androidx.compose.ui.geometry.Size(size.width * progressFraction, trackH),
                                    cornerRadius = corner
                                )

                                if (playerPrefs.skipSegmentsEnabled && duration > 0L) {
                                    for (seg in skipSegments) {
                                        val sFrac = (seg.startMs.toFloat() / totalDur).coerceIn(0f, 1f)
                                        val eFrac = (seg.endMs.toFloat() / totalDur).coerceIn(0f, 1f)
                                        val segW = ((eFrac - sFrac) * size.width).coerceAtLeast(2.dp.toPx())
                                        val segColor = when (seg.type) {
                                            SegmentType.INTRO, SegmentType.RECAP -> Color(0xFFD97724)
                                            SegmentType.OUTRO -> Color(0xFF7E57C2)
                                            SegmentType.CHAPTER -> Color.White.copy(alpha = 0.45f)
                                        }
                                        if (seg.type == SegmentType.CHAPTER) {
                                            drawRect(
                                                color = Color.Black.copy(alpha = 0.7f),
                                                topLeft = Offset(sFrac * size.width, topY),
                                                size = androidx.compose.ui.geometry.Size(2.dp.toPx(), trackH)
                                            )
                                        } else {
                                            drawRect(
                                                color = segColor,
                                                topLeft = Offset(sFrac * size.width, topY),
                                                size = androidx.compose.ui.geometry.Size(segW, trackH)
                                            )
                                        }
                                    }
                                }

                                val thumbW = 6.dp.toPx()
                                val thumbH = 22.dp.toPx()
                                val thumbX = (size.width * progressFraction - thumbW / 2f).coerceIn(0f, size.width - thumbW)
                                drawRoundRect(
                                    color = Color(0xFF244985),
                                    topLeft = Offset(thumbX, (size.height - thumbH) / 2f),
                                    size = androidx.compose.ui.geometry.Size(thumbW, thumbH),
                                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(thumbW / 2f, thumbW / 2f)
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showStatsHud && !isInPip) {
            val vFmt = player.videoFormat
            val aFmt = player.audioFormat
            StatsForNerdsHud(
                resolution = if (vFmt != null && vFmt.width > 0) "${vFmt.width}x${vFmt.height}" else "Auto",
                videoCodec = vFmt?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "Unknown",
                audioCodec = aFmt?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "Unknown",
                bitrateKbps = ((vFmt?.bitrate ?: 0) / 1000).coerceAtLeast(0),
                bufferedMs = player.bufferedPosition,
                currentPosMs = currentPos,
                speed = playbackSpeed,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 66.dp)
            )
        }

        if (showAudioDialog) {
            VidLabAudioSettingsSheet(
                audioTracks = audioTracks,
                selectedChannelMode = audioChannelMode,
                volumeNormalization = volumeNorm,
                drcEnabled = drcEnabled,
                onSelectTrack = { track ->
                    track.group?.let { grp ->
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(TrackSelectionOverride(grp.mediaTrackGroup, track.trackIndex))
                            .build()
                    }
                },
                onChannelModeChange = { audioChannelMode = it; playerPrefs.audioChannelMode = it },
                onVolumeNormChange = { volumeNorm = it; playerPrefs.volumeNormalization = it },
                onDrcChange = { drcEnabled = it; playerPrefs.dynamicRangeCompression = it },
                onDismiss = { showAudioDialog = false }
            )
        }

        if (showToolsDialog) {
            AlertDialog(
                onDismissRequest = { showToolsDialog = false },
                containerColor = Color(0xFF0D1015),
                title = { Text("Sleep Timer & A-B Loop", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Sleep Timer", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf(15, 30, 45, 60).forEach { mins ->
                                Surface(
                                    onClick = {
                                        sleepTimerEndMs = System.currentTimeMillis() + mins * 60_000L
                                        sleepAfterCurrentFile = false
                                        showToolsDialog = false
                                        gestureOverlayText = "Sleep timer: ${mins}m"
                                    },
                                    color = Color(0xFF1A2232),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(vertical = 8.dp)) {
                                        Text("${mins}m", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { sleepAfterCurrentFile = true; sleepTimerEndMs = 0L; showToolsDialog = false; gestureOverlayText = "Will pause at end of file" },
                                modifier = Modifier.weight(1f)
                            ) { Text("End of File", color = Color.White, fontSize = 11.sp) }
                            if (sleepTimerEndMs > 0L || sleepAfterCurrentFile) {
                                TextButton(onClick = { sleepTimerEndMs = 0L; sleepAfterCurrentFile = false; showToolsDialog = false }) { Text("Off", color = Color(0xFFEF5350), fontSize = 11.sp) }
                            }
                        }
                        HorizontalDivider(color = Color(0xFF222E46))
                        Text("A-B Scene Loop", color = Color(0xFF8AB4F8), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { loopPointAMs = currentPos; gestureOverlayText = "Loop A: ${formatDuration(currentPos)}" },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (loopPointAMs >= 0L) "A: ${formatDuration(loopPointAMs)}" else "Set A", color = Color.White, fontSize = 11.sp) }
                            Button(
                                onClick = {
                                    if (loopPointAMs < 0L) loopPointAMs = 0L
                                    loopPointBMs = currentPos
                                    showToolsDialog = false
                                    gestureOverlayText = "Looping A → B"
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                                modifier = Modifier.weight(1f)
                            ) { Text(if (loopPointBMs > 0L) "B: ${formatDuration(loopPointBMs)}" else "Set B", color = Color.White, fontSize = 11.sp) }
                            if (loopPointAMs >= 0L || loopPointBMs > 0L) {
                                OutlinedButton(onClick = { loopPointAMs = -1L; loopPointBMs = -1L; showToolsDialog = false }) { Text("Clear", color = Color(0xFFEF5350), fontSize = 11.sp) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showToolsDialog = false }) { Text("Close", color = Color(0xFF8AB4F8)) } }
            )
        }

        if (showSubtitleDialog) {
            var subTab by remember { mutableIntStateOf(0) }
            var onlineQuery by remember { mutableStateOf(title.replace(Regex("""(?i)\s*[-–:|•]\s*S\d{1,2}\s*E\d{1,3}\b.*"""), "").trim()) }
            var onlineLang by remember { mutableStateOf("en") }
            var onlineResults by remember { mutableStateOf<List<OnlineSubtitleResult>>(emptyList()) }
            var isSearchingOnline by remember { mutableStateOf(false) }
            var onlineStatusMsg by remember { mutableStateOf<String?>(null) }
            val dialogScope = rememberCoroutineScope()

            fun applyExternalSubWithOffset(targetUri: Uri, label: String) {
                val resumePos = player.currentPosition
                val wasPlaying = player.isPlaying
                val currentItem = player.currentMediaItem ?: return
                val extSub = MediaItem.SubtitleConfiguration.Builder(targetUri)
                    .setMimeType(when { targetUri.toString().endsWith(".vtt", true) -> MimeTypes.TEXT_VTT; targetUri.toString().endsWith(".ass", true) || targetUri.toString().endsWith(".ssa", true) -> MimeTypes.TEXT_SSA; else -> MimeTypes.APPLICATION_SUBRIP })
                    .setLanguage(onlineLang)
                    .setLabel(label)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
                val existingSubs = (currentItem.localConfiguration?.subtitleConfigurations ?: emptyList())
                    .filterNot { it.label == label }
                val updatedItem = currentItem.buildUpon()
                    .setSubtitleConfigurations(existingSubs + extSub)
                    .build()
                player.setMediaItem(updatedItem, resumePos)
                player.prepare()
                player.playWhenReady = wasPlaying
            }

            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                containerColor = Color(0xFF0D1015),
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Subtitles", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Tracks", "Search Online", "Sync & Style").forEachIndexed { idx, tabLabel ->
                                val active = subTab == idx
                                Surface(
                                    onClick = { subTab = idx },
                                    color = if (active) Color(0xFF244985) else Color(0xFF171D29),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = tabLabel,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                text = {
                    when (subTab) {
                        0 -> Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = {
                                    showSubtitleDialog = false
                                    externalSubLauncher.launch(arrayOf("*/*"))
                                },
                                color = Color(0xFF244985),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Load Local File (.srt / .ass / .vtt)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                                items(subtitleTracks) { track ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (track.group != null) {
                                                    player.trackSelectionParameters = player.trackSelectionParameters
                                                        .buildUpon()
                                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.trackIndex))
                                                        .build()
                                                } else {
                                                    player.trackSelectionParameters = player.trackSelectionParameters
                                                        .buildUpon()
                                                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                                        .build()
                                                }
                                                refreshTracks(player.currentTracks)
                                                showSubtitleDialog = false
                                            }
                                            .padding(vertical = 8.dp, horizontal = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = track.isSelected,
                                            onClick = null,
                                            colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF244985), unselectedColor = Color(0xFF71717A))
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(track.label, color = Color.White, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                        1 -> Column(
                            modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            var subLangFilter by remember { mutableStateOf("en") }
                            TextField(
                                value = onlineQuery,
                                onValueChange = { onlineQuery = it },
                                singleLine = true,
                                placeholder = { Text("Movie or Series Title", color = Color(0xFF94A3B8), fontSize = 12.sp) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFF171D29),
                                    unfocusedContainerColor = Color(0xFF171D29),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedIndicatorColor = Color(0xFF244985),
                                    unfocusedIndicatorColor = Color.Transparent
                                ),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                listOf("en" to "English (EN)", "" to "All Languages").forEach { (code, label) ->
                                    val active = subLangFilter == code
                                    Surface(
                                        onClick = { subLangFilter = code },
                                        color = if (active) Color(0xFF244985) else Color(0xFF1F2737),
                                        shape = RoundedCornerShape(8.dp),
                                        border = BorderStroke(1.dp, if (active) Color(0xFF244985) else Color(0xFF2D3A52))
                                    ) {
                                        Text(
                                            label,
                                            color = if (active) Color.White else Color(0xFFD4D4D8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                            }
                            Button(
                                onClick = {
                                    isSearchingOnline = true
                                    onlineStatusMsg = null
                                    dialogScope.launch {
                                        val seMatch = Regex("""(?i)S(\d{1,2})\s*E(\d{1,3})""").find(title)
                                        val sNum = seMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
                                        val eNum = seMatch?.groupValues?.getOrNull(2)?.toIntOrNull()
                                        val found = OnlineSubtitleHelper.searchSubtitles(
                                            query = onlineQuery,
                                            season = sNum,
                                            episode = eNum,
                                            languageFilter = subLangFilter
                                        )
                                        onlineResults = found
                                        isSearchingOnline = false
                                        if (found.isEmpty()) onlineStatusMsg = "No subtitles found. Try shortening the title or switching to All Languages."
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(if (isSearchingOnline) "Searching..." else "Search Online Subtitles", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                            if (onlineStatusMsg != null) {
                                Text(onlineStatusMsg!!, color = Color(0xFF94A3B8), fontSize = 12.sp)
                            }
                            LazyColumn(modifier = Modifier.heightIn(max = 190.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(onlineResults) { res ->
                                    Surface(
                                        onClick = {
                                            isSearchingOnline = true
                                            dialogScope.launch {
                                                val uri = OnlineSubtitleHelper.downloadAndShiftSubtitle(context, res.url, res.releaseName, 0L)
                                                isSearchingOnline = false
                                                if (uri != null) {
                                                    activeExternalSubUri = uri
                                                    activeExternalSubRawUrl = res.url
                                                    externalSubLabel = "${res.language} • ${res.releaseName.take(28)}"
                                                    subtitleOffsetMs = 0L
                                                    applyExternalSubWithOffset(uri, externalSubLabel ?: "Online Subtitle")
                                                    showSubtitleDialog = false
                                                    gestureOverlayText = "Loaded ${res.language} subtitle"
                                                } else {
                                                    onlineStatusMsg = "Failed to download subtitle file."
                                                }
                                            }
                                        },
                                        color = Color(0xFF171D29),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(res.releaseName, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            Text("${res.language} • ${res.source}", color = Color(0xFF94A3B8), fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                        else -> Column(modifier = Modifier.heightIn(max = 215.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("SUBTITLE TIMING OFFSET", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOf(-500L to "-0.5s", -100L to "-0.1s", 100L to "+0.1s", 500L to "+0.5s").forEach { (step, lbl) ->
                                    Surface(
                                        onClick = {
                                            val newOff = subtitleOffsetMs + step
                                            subtitleOffsetMs = newOff
                                            val rawUrl = activeExternalSubRawUrl
                                            val localUri = activeExternalSubUri
                                            if (rawUrl != null) {
                                                dialogScope.launch {
                                                    val shiftedUri = OnlineSubtitleHelper.downloadAndShiftSubtitle(context, rawUrl, externalSubLabel ?: "Online Subtitle", newOff)
                                                    if (shiftedUri != null) applyExternalSubWithOffset(shiftedUri, externalSubLabel ?: "Synced Subtitle")
                                                }
                                            } else if (localUri != null) {
                                                dialogScope.launch {
                                                    val shiftedUri = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                                        OnlineSubtitleHelper.shiftLocalUriSubtitle(context, localUri, newOff)
                                                    }
                                                    if (shiftedUri != null) applyExternalSubWithOffset(shiftedUri, externalSubLabel ?: "Synced Subtitle")
                                                }
                                            }
                                        },
                                        color = Color(0xFF171D29),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(lbl, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
                                    }
                                }
                            }
                            Text(
                                text = "Current Sync Offset: ${if (subtitleOffsetMs >= 0) "+${subtitleOffsetMs}ms" else "${subtitleOffsetMs}ms"}" +
                                    if (activeExternalSubUri == null && activeExternalSubRawUrl == null) " (Load external/online .srt to shift timestamps)" else "",
                                color = Color(0xFF244985),
                                fontSize = 12.sp
                            )

                            HorizontalDivider(color = Color(0xFF282E38))
                            Text("TEXT SIZE (${playerPrefs.subtitleFontSizeSp.toInt()}sp)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(22f to "Small", 28f to "Medium", 36f to "Large", 46f to "XL").forEach { (spVal, lbl) ->
                                    val sel = kotlin.math.abs(playerPrefs.subtitleFontSizeSp - spVal) < 0.5f
                                    Surface(
                                        onClick = { playerPrefs.subtitleFontSizeSp = spVal; subStyleTick += 1 },
                                        color = if (sel) Color(0xFF244985) else Color(0xFF171D29),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(lbl, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                            }

                            Text("BOTTOM POSITION (${playerPrefs.subtitleBottomPaddingPct}%)", color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(5 to "Low", 10 to "Normal", 16 to "High", 24 to "Top+").forEach { (pctVal, lbl) ->
                                    val sel = playerPrefs.subtitleBottomPaddingPct == pctVal
                                    Surface(
                                        onClick = { playerPrefs.subtitleBottomPaddingPct = pctVal; subStyleTick += 1 },
                                        color = if (sel) Color(0xFF244985) else Color(0xFF171D29),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text(lbl, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    playerPrefs.subtitleBgBox = !playerPrefs.subtitleBgBox
                                    subStyleTick += 1
                                },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Dark Background Box", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Switch(
                                    checked = playerPrefs.subtitleBgBox,
                                    onCheckedChange = { playerPrefs.subtitleBgBox = it; subStyleTick += 1 },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Color(0xFF3B6CB7),
                                        uncheckedThumbColor = Color(0xFF8E9199),
                                        uncheckedTrackColor = Color(0xFF1F2737),
                                        uncheckedBorderColor = Color(0xFF2D3A52)
                                    )
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) {
                        Text("Close", color = Color(0xFF94A3B8))
                    }
                }
            )
        }

        if (showDecoderDialog) {
            val decoderOptions = listOf(
                0 to ("Auto (HW + FFmpeg)" to "Uses hardware acceleration with FFmpeg fallback"),
                1 to ("Hardware (HW)" to "Forces device hardware video/audio decoders"),
                2 to ("Software (SW / FFmpeg)" to "Best for 10-bit Hi10P anime & unsupported codecs")
            )
            AlertDialog(
                onDismissRequest = { showDecoderDialog = false },
                containerColor = Color(0xFF0D1015),
                title = { Text("Video & Audio Decoder", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        decoderOptions.forEach { (mode, descPair) ->
                            val selected = playerPrefs.decoderMode == mode
                            Surface(
                                onClick = {
                                    playerPrefs.decoderMode = mode
                                    showDecoderDialog = false
                                    (context as? Activity)?.recreate()
                                },
                                color = if (selected) Color(0xFF244985).copy(alpha = 0.25f) else Color(0xFF171D29),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, if (selected) Color(0xFF244985) else Color(0xFF282E38))
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(descPair.first, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                    Text(descPair.second, color = Color(0xFF94A3B8), fontSize = 11.sp)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showDecoderDialog = false }) {
                        Text("Close", color = Color(0xFF94A3B8))
                    }
                }
            )
        }

        if (showSpeedDialog) {
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                containerColor = Color(0xFF0D1015),
                title = { Text("Playback Speed", color = Color.White, fontWeight = FontWeight.Bold) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (spd in speeds) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        playbackSpeed = spd
                                        player.setPlaybackSpeed(spd)
                                        showSpeedDialog = false
                                    }
                                    .padding(vertical = 8.dp, horizontal = 6.dp)
                            ) {
                                RadioButton(
                                    selected = playbackSpeed == spd,
                                    onClick = null,
                                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF244985))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("${spd}x", color = Color.White, fontSize = 14.sp)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) {
                        Text("Close", color = Color(0xFF94A3B8))
                    }
                }
            )
        }

        if (playerError != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .background(Color(0xE6131A29), shape = RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Playback Error", color = Color(0xFFFF5252), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(playerError ?: "", color = Color.White, fontSize = 12.sp)
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF244985))
                ) {
                    Text("Close", color = Color.White)
                }
            }
        }
    }
}


private suspend fun fetchSiblingEpisodes(context: Context, currentFilePath: String): List<Pair<String, String>> =
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (currentFilePath.isBlank() || currentFilePath.startsWith("http")) return@withContext emptyList()
        val parentDir = currentFilePath.substringBeforeLast("/", "")
        if (parentDir.isBlank()) return@withContext emptyList()
        runCatching {
            val encodedDir = parentDir.split("/").joinToString("/") { seg ->
                if (seg.isEmpty()) "" else Uri.encode(seg)
            }
            val url = java.net.URL("${if (com.vidlab.app.data.remote.RcloneEngine.isRcloneModeEnabled(context)) com.vidlab.app.data.remote.RcloneEngine.STREAM_BASE_URL else com.vidlab.app.data.remote.RcloneEngine.getWebDavBaseUrl(context)}$encodedDir/")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
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
                .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8").trimEnd('/') }
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

private fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "00:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
