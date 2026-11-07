package com.radar.media

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Graba una nota de voz en PCM 16-bit / 8 kHz / mono **directamente en RAM** (sin archivos).
 * El llamador codifica el resultado con [AdpcmCodec]. Requiere el permiso RECORD_AUDIO.
 */
class VoiceRecorder {

    private val _elapsedMs = MutableStateFlow(0)
    val elapsedMs: StateFlow<Int> = _elapsedMs.asStateFlow()

    /** Pico reciente 0..1, para animar el indicador de nivel. */
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    /** true cuando se alcanzó [MAX_MS]: la UI debe detener y enviar. */
    private val _limitReached = MutableStateFlow(false)
    val limitReached: StateFlow<Boolean> = _limitReached.asStateFlow()

    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var samples = ShortArray(0)

    @Volatile
    private var count = 0

    val isRecording: Boolean get() = running

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return false
        val minBuffer = AudioRecord.getMinBufferSize(
            AdpcmCodec.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AdpcmCodec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 2
            )
        } catch (_: Exception) {
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return false
        }

        samples = ShortArray(MAX_SAMPLES)
        count = 0
        _elapsedMs.value = 0
        _level.value = 0f
        _limitReached.value = false
        record = rec
        running = true

        try {
            rec.startRecording()
        } catch (_: Exception) {
            running = false
            rec.release()
            record = null
            return false
        }

        thread = Thread({ captureLoop(rec, minBuffer) }, "radar-voice").also { it.start() }
        return true
    }

    private fun captureLoop(rec: AudioRecord, bufferSize: Int) {
        val chunk = ShortArray(maxOf(256, bufferSize / 2))
        while (running) {
            val read = rec.read(chunk, 0, chunk.size)
            if (read <= 0) break

            val room = MAX_SAMPLES - count
            val take = minOf(read, room)
            chunk.copyInto(samples, count, 0, take)
            count += take

            var peak = 0
            for (i in 0 until take) {
                val v = kotlin.math.abs(chunk[i].toInt())
                if (v > peak) peak = v
            }
            _level.value = peak / 32768f
            _elapsedMs.value = AdpcmCodec.duration(count)

            if (count >= MAX_SAMPLES) {
                _limitReached.value = true
                break
            }
        }
    }

    /** Detiene y devuelve el audio, o null si fue demasiado corto. */
    fun stop(): ShortArray? {
        val pcm = finish()
        return if (pcm != null && pcm.size >= MIN_SAMPLES) pcm else null
    }

    /** Detiene y descarta. */
    fun cancel() {
        finish()
        _elapsedMs.value = 0
        _level.value = 0f
    }

    private fun finish(): ShortArray? {
        running = false
        thread?.let { runCatching { it.join(700) } }
        thread = null
        record?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        record = null
        val pcm = if (count > 0) samples.copyOf(count) else null
        samples = ShortArray(0)
        count = 0
        return pcm
    }

    companion object {
        const val MAX_MS = 20_000
        const val MIN_MS = 500
        private const val MAX_SAMPLES = AdpcmCodec.SAMPLE_RATE * MAX_MS / 1000
        private const val MIN_SAMPLES = AdpcmCodec.SAMPLE_RATE * MIN_MS / 1000
    }
}
