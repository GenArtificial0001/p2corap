package com.radar.media

import java.nio.ByteBuffer

/**
 * IMA-ADPCM mono de 4 bits: 16 bits PCM → 4 bits (relación 4:1).
 *
 * A 8 kHz son 4 KB/s: 20 s de nota de voz ≈ 80 KB, transferibles en ≈10 s por BLE.
 * Es un códec pequeño, sin dependencias y sin archivos temporales (Zero-Storage), y corre
 * igual en la JVM, así que se prueba sin dispositivo.
 *
 * Formato: `[sampleCount:4][predictor:2][stepIndex:1][nibbles…]` (alto nibble primero).
 */
object AdpcmCodec {

    const val SAMPLE_RATE = 8_000

    /** Tope de muestras aceptadas al decodificar (30 s). Evita reservar memoria por un dato hostil. */
    const val MAX_SAMPLES = SAMPLE_RATE * 30

    private const val HEADER = 7

    private val STEPS = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60,
        66, 73, 80, 88, 97, 107, 118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371,
        408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878,
        2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132, 7845,
        8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086,
        29794, 32767
    )

    private val INDEX_ADJUST = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)

    fun duration(samples: Int): Int = (samples.toLong() * 1000 / SAMPLE_RATE).toInt()

    fun encode(pcm: ShortArray): ByteArray {
        require(pcm.isNotEmpty()) { "Sin audio" }
        val out = ByteBuffer.allocate(HEADER + (pcm.size + 1) / 2)
        out.putInt(pcm.size)

        var predictor = pcm[0].toInt()
        var index = 0
        out.putShort(predictor.toShort())
        out.put(index.toByte())

        var high = true
        var pending = 0
        for (sample in pcm) {
            var diff = sample - predictor
            var code = 0
            if (diff < 0) {
                code = 8
                diff = -diff
            }
            var step = STEPS[index]
            var delta = step shr 3
            if (diff >= step) {
                code = code or 4
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                code = code or 2
                diff -= step
                delta += step
            }
            step = step shr 1
            if (diff >= step) {
                code = code or 1
                delta += step
            }

            predictor += if (code and 8 != 0) -delta else delta
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            index = (index + INDEX_ADJUST[code and 7]).coerceIn(0, STEPS.size - 1)

            if (high) {
                pending = code shl 4
            } else {
                out.put((pending or code).toByte())
            }
            high = !high
        }
        if (!high) out.put(pending.toByte())

        return out.array()
    }

    /** @return null si los datos están corruptos o exceden los límites. */
    fun decode(data: ByteArray): ShortArray? {
        if (data.size <= HEADER) return null
        val buf = ByteBuffer.wrap(data)
        val samples = buf.int
        if (samples < 1 || samples > MAX_SAMPLES) return null
        if (data.size != HEADER + (samples + 1) / 2) return null

        var predictor = buf.short.toInt()
        var index = buf.get().toInt()
        if (index !in 0 until STEPS.size) return null

        val pcm = ShortArray(samples)
        var offset = HEADER
        var high = true
        for (i in 0 until samples) {
            val code = if (high) {
                (data[offset].toInt() shr 4) and 0x0F
            } else {
                (data[offset++].toInt()) and 0x0F
            }
            high = !high

            val step = STEPS[index]
            var delta = step shr 3
            if (code and 4 != 0) delta += step
            if (code and 2 != 0) delta += step shr 1
            if (code and 1 != 0) delta += step shr 2

            predictor += if (code and 8 != 0) -delta else delta
            predictor = predictor.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            index = (index + INDEX_ADJUST[code and 7]).coerceIn(0, STEPS.size - 1)

            pcm[i] = predictor.toShort()
        }
        return pcm
    }

    /**
     * Resume el audio en [bars] valores 0..1 (pico por tramo) para dibujar la forma de onda.
     */
    fun waveform(pcm: ShortArray, bars: Int): FloatArray {
        if (pcm.isEmpty() || bars <= 0) return FloatArray(0)
        val out = FloatArray(bars)
        val per = maxOf(1, pcm.size / bars)
        for (b in 0 until bars) {
            var peak = 0
            val from = b * per
            val to = minOf(pcm.size, from + per)
            for (i in from until to) {
                val v = kotlin.math.abs(pcm[i].toInt())
                if (v > peak) peak = v
            }
            out[b] = peak / 32768f
        }
        val top = out.max()
        if (top > 0f) for (i in out.indices) out[i] = (out[i] / top).coerceIn(0f, 1f)
        return out
    }
}
