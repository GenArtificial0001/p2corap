package com.radar.ble

/**
 * Framing de paquetes sobre el characteristic Message Tx/Rx.
 *
 * Cada fragmento: [seq:1][total:1][flags:1][payload:<=17]
 * El receptor reensambla hasta `total`. Sin ACK: si falta un fragmento,
 * el mensaje se descarta por timeout (ver R-03).
 *
 * El framing transporta bytes opacos: el contenido real es un MeshPacket.
 */
object Fragmenter {

    const val HEADER_SIZE = 3
    const val MAX_FRAGMENT_PAYLOAD = RadarUuids.MIN_FRAGMENT_PAYLOAD
    const val FLAG_LAST: Byte = 0x01

    fun fragment(text: String): List<ByteArray> = fragment(text.toByteArray(Charsets.UTF_8))

    fun fragment(bytes: ByteArray): List<ByteArray> {
        require(bytes.isNotEmpty()) { "No se envía un paquete vacío" }
        val total = (bytes.size + MAX_FRAGMENT_PAYLOAD - 1) / MAX_FRAGMENT_PAYLOAD
        require(total <= 255) { "Paquete demasiado grande: ${bytes.size} bytes" }

        return (0 until total).map { index ->
            val from = index * MAX_FRAGMENT_PAYLOAD
            val to = minOf(from + MAX_FRAGMENT_PAYLOAD, bytes.size)
            val chunk = bytes.copyOfRange(from, to)
            val flags: Byte = if (index == total - 1) FLAG_LAST else 0
            ByteArray(HEADER_SIZE + chunk.size).apply {
                this[0] = index.toByte()
                this[1] = total.toByte()
                this[2] = flags
                chunk.copyInto(this, HEADER_SIZE)
            }
        }
    }
}

/**
 * Reensambla fragmentos en bytes. Un ensamblador por link.
 *
 * Exige que estén todos los fragmentos (0..total-1): un `FLAG_LAST` adelantado
 * o un hueco no pueden producir un mensaje truncado que luego se descifre a
 * medias. GATT entrega en orden por vínculo, así que `seq == 0` inicia un
 * mensaje nuevo.
 */
class MessageAssembler {

    private var buffer: ByteArray? = null
    private var seen: BooleanArray? = null
    private var expected: Int = -1
    private var received: Int = 0
    private var lastChunkSize: Int = 0
    private var lastUpdateMs: Long = 0L

    /** Devuelve el paquete completo cuando llegó el último fragmento, o null. */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.size <= Fragmenter.HEADER_SIZE) return null

        val now = System.currentTimeMillis()
        if (lastUpdateMs != 0L && now - lastUpdateMs > TIMEOUT_MS) reset()
        lastUpdateMs = now

        val seq = packet[0].toInt() and 0xFF
        val total = packet[1].toInt() and 0xFF
        val chunk = packet.copyOfRange(Fragmenter.HEADER_SIZE, packet.size)

        if (total <= 0 || seq >= total || chunk.isEmpty()) return null

        if (buffer == null || expected != total || seq == 0) {
            buffer = ByteArray(total * Fragmenter.MAX_FRAGMENT_PAYLOAD)
            seen = BooleanArray(total)
            expected = total
            received = 0
            lastChunkSize = 0
        }

        val target = buffer ?: return null
        val flags = seen ?: return null
        val offset = seq * Fragmenter.MAX_FRAGMENT_PAYLOAD
        if (offset + chunk.size > target.size) {
            reset()
            return null
        }

        if (!flags[seq]) {
            chunk.copyInto(target, offset)
            flags[seq] = true
            received++
            if (seq == total - 1) lastChunkSize = chunk.size
        }

        if (received < expected || lastChunkSize <= 0) return null

        val length = (expected - 1) * Fragmenter.MAX_FRAGMENT_PAYLOAD + lastChunkSize
        if (length <= 0 || length > target.size) {
            reset()
            return null
        }

        val out = target.copyOf(length)
        reset()
        return if (out.isEmpty()) null else out
    }

    fun reset() {
        buffer = null
        seen = null
        expected = -1
        received = 0
        lastChunkSize = 0
        lastUpdateMs = 0L
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
