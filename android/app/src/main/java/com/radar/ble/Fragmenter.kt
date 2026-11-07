package com.radar.ble

/**
 * Framing de paquetes sobre el characteristic Message Tx/Rx.
 *
 * Cada fragmento: [seq:1][total:1][flags:1][payload:<=N]
 *
 * `N` depende del MTU negociado en cada vínculo: con el MTU mínimo (23) son 17 bytes
 * ([MIN_PAYLOAD]); con 247 son 241. El receptor **no necesita conocer N**: concatena los
 * fragmentos por `seq`, así que cada vínculo puede usar su propio tamaño.
 *
 * El receptor reensambla solo cuando están todos los fragmentos; si falta alguno, el
 * mensaje se descarta por timeout. La confirmación de entrega es de aplicación
 * (MeshPacket.TYPE_ACK), no por fragmento.
 *
 * El framing transporta bytes opacos: el contenido real es un MeshPacket.
 */
object Fragmenter {

    const val HEADER_SIZE = 3

    /** Payload por fragmento con MTU 23 (la garantía mínima de BLE). */
    const val MIN_PAYLOAD = RadarUuids.MIN_FRAGMENT_PAYLOAD

    /** Alias histórico de [MIN_PAYLOAD]. */
    const val MAX_FRAGMENT_PAYLOAD = MIN_PAYLOAD

    const val FLAG_LAST: Byte = 0x01

    /** `total` cabe en un byte. */
    const val MAX_FRAGMENTS = 255

    /** Payload de fragmento que corresponde a un MTU ATT: MTU - 3 (ATT) - 3 (cabecera). */
    fun payloadForMtu(mtu: Int): Int = (mtu - 3 - HEADER_SIZE).coerceIn(MIN_PAYLOAD, 509)

    fun fragment(text: String): List<ByteArray> = fragment(text.toByteArray(Charsets.UTF_8))

    fun fragment(bytes: ByteArray, payloadSize: Int = MIN_PAYLOAD): List<ByteArray> {
        require(bytes.isNotEmpty()) { "No se envía un paquete vacío" }
        require(payloadSize >= 1) { "Tamaño de fragmento inválido: $payloadSize" }
        val total = (bytes.size + payloadSize - 1) / payloadSize
        require(total <= MAX_FRAGMENTS) { "Paquete demasiado grande: ${bytes.size} bytes" }

        return (0 until total).map { index ->
            val from = index * payloadSize
            val to = minOf(from + payloadSize, bytes.size)
            val flags: Byte = if (index == total - 1) FLAG_LAST else 0
            ByteArray(HEADER_SIZE + (to - from)).apply {
                this[0] = index.toByte()
                this[1] = total.toByte()
                this[2] = flags
                bytes.copyInto(this, HEADER_SIZE, from, to)
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
 *
 * No asume un tamaño de fragmento fijo: guarda cada trozo por su `seq` y los une al
 * final, por lo que acepta cualquier MTU.
 */
class MessageAssembler {

    private var parts: Array<ByteArray?>? = null
    private var expected: Int = -1
    private var received: Int = 0
    private var bytes: Int = 0
    private var lastUpdateMs: Long = 0L

    /** Devuelve el paquete completo cuando llegaron todos los fragmentos, o null. */
    fun accept(packet: ByteArray): ByteArray? {
        if (packet.size <= Fragmenter.HEADER_SIZE) return null

        val now = System.currentTimeMillis()
        if (lastUpdateMs != 0L && now - lastUpdateMs > TIMEOUT_MS) reset()
        lastUpdateMs = now

        val seq = packet[0].toInt() and 0xFF
        val total = packet[1].toInt() and 0xFF
        if (total <= 0 || seq >= total) return null

        if (parts == null || expected != total || seq == 0) {
            parts = arrayOfNulls(total)
            expected = total
            received = 0
            bytes = 0
        }

        val slots = parts ?: return null
        if (slots[seq] == null) {
            val chunk = packet.copyOfRange(Fragmenter.HEADER_SIZE, packet.size)
            slots[seq] = chunk
            received++
            bytes += chunk.size
            if (bytes > MAX_ASSEMBLED_BYTES) {
                reset()
                return null
            }
        }

        if (received < expected) return null

        val out = ByteArray(bytes)
        var offset = 0
        for (slot in slots) {
            val chunk = slot ?: run {
                reset()
                return null
            }
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        reset()
        return if (out.isEmpty()) null else out
    }

    fun reset() {
        parts = null
        expected = -1
        received = 0
        bytes = 0
        lastUpdateMs = 0L
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        /** 255 fragmentos × 509 B. Evita que un cliente hostil nos haga reservar de más. */
        const val MAX_ASSEMBLED_BYTES = 255 * 509
    }
}
