package com.radar.mesh

import java.nio.ByteBuffer

/**
 * Paquete de la red mesh. Es lo que viaja dentro del characteristic de
 * mensajes, ya fragmentado por BLE.
 *
 * Formato (26 bytes de header + payload):
 *
 * ```
 *  0      version      1
 *  1      type         1
 *  2-9    messageId    8   (aleatorio, identifica el mensaje en toda la malla)
 *  10-13  originId     4   (quién lo creó; sirve para cortar loops)
 *  14     ttl          1   (saltos restantes)
 *  15     hopCount     1   (saltos recorridos)
 *  16-23  timestamp    8
 *  24-25  payloadLen   2
 *  26..   payload      n   (UTF-8, texto plano por ahora)
 * ```
 *
 * El payload viaja cifrado con AES-256-GCM (clave derivada del código de
 * sala): los nodos intermedios reenvían ciphertext que no pueden leer.
 * Ver docs/05-mesh.md.
 */
data class MeshPacket(
    val type: Int,
    val messageId: Long,
    val originId: Int,
    val ttl: Int,
    val hopCount: Int,
    val timestamp: Long,
    val payload: ByteArray
) {

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.put(VERSION.toByte())
        buffer.put(type.toByte())
        buffer.putLong(messageId)
        buffer.putInt(originId)
        buffer.put(ttl.toByte())
        buffer.put(hopCount.toByte())
        buffer.putLong(timestamp)
        buffer.putShort(payload.size.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    fun text(): String = String(payload, Charsets.UTF_8)

    companion object {
        const val VERSION = 1
        const val TYPE_TEXT = 1
        const val TYPE_ACK = 2

        /** Sticker del catálogo: el payload cifrado lleva solo su id (2 bytes). */
        const val TYPE_STICKER = 3

        /** Cabecera de una transferencia de medios (foto, audio, video). */
        const val TYPE_MEDIA_HEADER = 4

        /** Trozo de una transferencia de medios. */
        const val TYPE_MEDIA_CHUNK = 5

        const val HEADER_SIZE = 26

        /** Texto, ACK y stickers. */
        const val MAX_PAYLOAD = 512

        /**
         * Los trozos de medios son mayores (≈4 KB). El límite sigue acotado para que
         * un paquete quepa en 255 fragmentos incluso con el MTU mínimo (23 → 17 B).
         */
        const val MAX_MEDIA_PAYLOAD = 4_000

        fun maxPayloadFor(type: Int): Int =
            if (type == TYPE_MEDIA_CHUNK) MAX_MEDIA_PAYLOAD else MAX_PAYLOAD

        fun text(
            messageId: Long,
            originId: Int,
            ttl: Int,
            payload: String
        ): MeshPacket {
            val bytes = payload.toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_PAYLOAD) { "Mensaje demasiado largo para la malla" }
            return MeshPacket(
                type = TYPE_TEXT,
                messageId = messageId,
                originId = originId,
                ttl = ttl,
                hopCount = 0,
                timestamp = System.currentTimeMillis(),
                payload = bytes
            )
        }

        fun decode(bytes: ByteArray): MeshPacket? {
            if (bytes.size < HEADER_SIZE) return null
            val buffer = ByteBuffer.wrap(bytes)
            val version = buffer.get().toInt() and 0xFF
            if (version != VERSION) return null

            val type = buffer.get().toInt() and 0xFF
            val messageId = buffer.long
            val originId = buffer.int
            val ttl = buffer.get().toInt() and 0xFF
            val hopCount = buffer.get().toInt() and 0xFF
            val timestamp = buffer.long
            val length = buffer.short.toInt() and 0xFFFF

            if (length <= 0 || length > maxPayloadFor(type)) return null
            if (buffer.remaining() < length) return null

            val payload = ByteArray(length)
            buffer.get(payload)

            return MeshPacket(type, messageId, originId, ttl, hopCount, timestamp, payload)
        }
    }
}
