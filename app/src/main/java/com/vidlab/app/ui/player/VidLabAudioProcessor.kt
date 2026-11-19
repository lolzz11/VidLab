package com.vidlab.app.ui.player

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

@OptIn(UnstableApi::class)
class VidLabChannelAudioProcessor : BaseAudioProcessor() {
    @Volatile var mode: Int = 1 // 0=Auto, 1=Auto Safe, 2=Mono, 3=Stereo, 4=Reversed Stereo

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return if (inputAudioFormat.channelCount == 2) inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        val out = replaceOutputBuffer(size)
        val m = mode
        var i = pos
        while (i + 3 < limit) {
            val left = inputBuffer.getShort(i)
            val right = inputBuffer.getShort(i + 2)
            when (m) {
                2 -> { // Mono
                    val mono = ((left.toInt() + right.toInt()) / 2).toShort()
                    out.putShort(mono); out.putShort(mono)
                }
                4 -> { // Reversed Stereo
                    out.putShort(right); out.putShort(left)
                }
                else -> {
                    out.putShort(left); out.putShort(right)
                }
            }
            i += 4
        }
        inputBuffer.position(limit)
        out.flip()
    }
}

class VidLabAudioEffectsController {
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var boundSessionId: Int = 0

    fun applyEffects(sessionId: Int, volumeNorm: Boolean, drcEnabled: Boolean) {
        if (sessionId <= 0) return
        if (boundSessionId != sessionId) {
            release()
            boundSessionId = sessionId
        }
        runCatching {
            if (volumeNorm) {
                val le = loudnessEnhancer ?: LoudnessEnhancer(sessionId).also { loudnessEnhancer = it }
                le.setTargetGain(650) // +6.5 dB loudness boost
                le.enabled = true
            } else {
                loudnessEnhancer?.enabled = false
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                if (drcEnabled) {
                    val dp = dynamicsProcessing ?: DynamicsProcessing(
                        0, sessionId,
                        DynamicsProcessing.Config.Builder(
                            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                            2, false, 0, true, 4, false, 0, true
                        ).build()
                    ).also { dynamicsProcessing = it }
                    dp.enabled = true
                } else {
                    dynamicsProcessing?.enabled = false
                }
            }
        }
    }

    fun release() {
        runCatching { loudnessEnhancer?.release() }
        runCatching { dynamicsProcessing?.release() }
        loudnessEnhancer = null
        dynamicsProcessing = null
        boundSessionId = 0
    }
}
