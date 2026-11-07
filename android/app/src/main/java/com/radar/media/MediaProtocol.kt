package com.radar.media

import java.nio.ByteBuffer

/**
 * Tipos de medio que viajan por la malla y su tope de tamaño **ya comprimido**.
 *
 * Los topes existen por dos motivos: BLE es lento (≈8-16 KB/s con MTU 247) y todo vive en RAM
 * (Zero-Storage), así que un par hostil no debe poder hacernos reservar memoria sin límite.
 */
enum class MediaKind(val code: Int, val maxBytes: Int) {
    /** JPEG reducido (≈480 px). */
    IMAGE(1, 96 * 1024),

    /** Voz: IMA-ADPCM 8 kHz mono (4 KB/s). 20 s ≈ 80 KB. */
    AUDIO(2, 128 * 1024),

    /** H.264/AAC pequeño (≈240p, ≤5 s). */
    VIDEO(3, 640 * 1024);

    companion object {
        fun of(code: Int): MediaKind? = entries.firstOrNull { it.code == code }
    }
}

object MediaLimits {
    /**
     * Bytes de medio por trozo. Con la cabecera de malla (26), nonce+tag (28) y la del trozo (10)
     * el paquete mide 3 964 B: cabe en 255 fragmentos incluso con el MTU mínimo (17 B → 234).
     */
    const val CHUNK_DATA = 3_900

    const val MAX_DURATION_MS = 10 * 60_000
    const val MAX_DIMENSION = 4_096

    fun chunkCount(totalBytes: Int): Int = (totalBytes + CHUNK_DATA - 1) / CHUNK_DATA
}

/**
 * Anuncia una transferencia. Va cifrado dentro de un `TYPE_MEDIA_HEADER`.
 *
 * ```
 *  0      kind         1
 *  1-8    transferId   8   (también es el id del mensaje y el id del ACK de entrega)
 *  9-12   totalBytes   4
 *  13-14  chunkCount   2
 *  15-18  durationMs   4   (audio/video; 0 en fotos)
 *  19-20  width        2
 *  21-22  height       2
 * ```
 */
data class MediaHeader(
    val kind: MediaKind,
    val transferId: Long,
    val totalBytes: Int,
    val chunkCount: Int,
    val durationMs: Int = 0,
    val width: Int = 0,
    val height: Int = 0
) {
    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(SIZE)
        b.put(kind.code.toByte())
        b.putLong(transferId)
        b.putInt(totalBytes)
        b.putShort(chunkCount.toShort())
        b.putInt(durationMs)
        b.putShort(width.toShort())
        b.putShort(height.toShort())
        return b.array()
    }

    companion object {
        const val SIZE = 23

        /** Devuelve null si el formato es inválido o los valores exceden los límites. */
        fun decode(bytes: ByteArray): MediaHeader? {
            if (bytes.size != SIZE) return null
            val b = ByteBuffer.wrap(bytes)
            val kind = MediaKind.of(b.get().toInt() and 0xFF) ?: return null
            val id = b.long
            val total = b.int
            val chunks = b.short.toInt() and 0xFFFF
            val duration = b.int
            val width = b.short.toInt() and 0xFFFF
            val height = b.short.toInt() and 0xFFFF

            if (total < 1 || total > kind.maxBytes) return null
            if (chunks != MediaLimits.chunkCount(total)) return null
            if (duration < 0 || duration > MediaLimits.MAX_DURATION_MS) return null
            if (width > MediaLimits.MAX_DIMENSION || height > MediaLimits.MAX_DIMENSION) return null

            return MediaHeader(kind, id, total, chunks, duration, width, height)
        }
    }
}

/** Un trozo de la transferencia. Va cifrado dentro de un `TYPE_MEDIA_CHUNK`. */
class MediaChunk(val transferId: Long, val index: Int, val data: ByteArray) {

    fun encode(): ByteArray {
        val b = ByteBuffer.allocate(HEADER + data.size)
        b.putLong(transferId)
        b.putShort(index.toShort())
        b.put(data)
        return b.array()
    }

    companion object {
        const val HEADER = 10

        fun decode(bytes: ByteArray): MediaChunk? {
            if (bytes.size <= HEADER || bytes.size > HEADER + MediaLimits.CHUNK_DATA) return null
            val b = ByteBuffer.wrap(bytes)
            val id = b.long
            val index = b.short.toInt() and 0xFFFF
            val data = ByteArray(bytes.size - HEADER)
            b.get(data)
            return MediaChunk(id, index, data)
        }

        /** Parte [bytes] en trozos de [MediaLimits.CHUNK_DATA]. */
        fun split(transferId: Long, bytes: ByteArray): List<MediaChunk> {
            val count = MediaLimits.chunkCount(bytes.size)
            return (0 until count).map { i ->
                val from = i * MediaLimits.CHUNK_DATA
                val to = minOf(from + MediaLimits.CHUNK_DATA, bytes.size)
                MediaChunk(transferId, i, bytes.copyOfRange(from, to))
            }
        }
    }
}
