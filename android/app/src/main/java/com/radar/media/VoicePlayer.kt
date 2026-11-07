package com.radar.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reproduce una nota de voz (ADPCM) desde la RAM con [AudioTrack]. Una a la vez.
 * Usar desde el hilo principal.
 */
class VoicePlayer {

    private val main = Handler(Looper.getMainLooper())

    private val _playingId = MutableStateFlow<Long?>(null)
    val playingId: StateFlow<Long?> = _playingId.asStateFlow()

    private val _positionMs = MutableStateFlow(0)
    val positionMs: StateFlow<Int> = _positionMs.asStateFlow()

    private var track: AudioTrack? = null
    private var totalSamples = 0

    private val tick = object : Runnable {
        override fun run() {
            val t = track ?: return
            val head = runCatching { t.playbackHeadPosition }.getOrDefault(0)
            _positionMs.value = AdpcmCodec.duration(head)
            if (head >= totalSamples) {
                stop()
            } else {
                main.postDelayed(this, TICK_MS)
            }
        }
    }

    /** Si [id] ya suena, lo detiene; si no, reproduce [adpcm]. */
    fun toggle(id: Long, adpcm: ByteArray) {
        if (_playingId.value == id) {
            stop()
            return
        }
        stop()
        val pcm = AdpcmCodec.decode(adpcm) ?: return

        val audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AdpcmCodec.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
        } catch (_: Exception) {
            return
        }

        if (audioTrack.write(pcm, 0, pcm.size) <= 0) {
            audioTrack.release()
            return
        }

        track = audioTrack
        totalSamples = pcm.size
        _playingId.value = id
        _positionMs.value = 0
        runCatching { audioTrack.play() }.onFailure {
            stop()
            return
        }
        main.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        main.removeCallbacks(tick)
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
        totalSamples = 0
        _playingId.value = null
        _positionMs.value = 0
    }

    private companion object {
        const val TICK_MS = 80L
    }
}
