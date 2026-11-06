package com.radar.mesh

import java.util.ArrayDeque

/**
 * Núcleo de la malla: deduplicación, TTL y store-and-forward.
 *
 * Modelo: flooding epidémico acotado. Cada nodo reenvía una sola vez cada
 * messageId a todos sus vínculos, salvo por donde lo recibió. El TTL limita
 * el alcance y el `originId` corta los ecos de nuestros propios mensajes.
 *
 * No hay rutas ni tablas: no hacen falta para texto efímero. Lo que sí hay
 * es una cola de paquetes recientes que se entrega cuando aparece un vecino
 * nuevo (store-and-forward), que es lo que permite saltar entre grupos que
 * nunca estuvieron conectados al mismo tiempo.
 */
class MeshRouter(
    private val selfId: Int,
    private val defaultTtl: Int = DEFAULT_TTL
) {

    sealed interface Decision {
        /** Entregar localmente y, opcionalmente, reenviar con un salto menos. */
        data class Relay(val deliver: Boolean, val forward: MeshPacket?) : Decision

        /** Ya visto, es propio o no es reenviable. */
        data object Drop : Decision
    }

    /** messageId -> instante de expiración. Access-order para LRU. */
    private val seen = object : LinkedHashMap<Long, Long>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Long>): Boolean =
            size > MAX_SEEN
    }

    private val pending = ArrayDeque<PendingPacket>()

    /** Crea el paquete de un mensaje propio y lo marca como visto. */
    fun origin(text: String): MeshPacket {
        val packet = MeshPacket.text(
            messageId = kotlin.random.Random.nextLong(),
            originId = selfId,
            ttl = defaultTtl,
            payload = text
        )
        seen[packet.messageId] = now() + SEEN_TTL_MS
        return packet
    }

    fun onReceive(packet: MeshPacket): Decision {
        pruneSeen()

        if (seen.containsKey(packet.messageId)) return Decision.Drop
        seen[packet.messageId] = now() + SEEN_TTL_MS

        // Eco de un mensaje propio: ya se entregó localmente al enviarlo.
        if (packet.originId == selfId) return Decision.Drop

        val deliver = packet.type == MeshPacket.TYPE_TEXT

        // ttl = saltos que todavía puede dar. Con ttl 1 llega y no reenvía.
        val forward = if (packet.ttl > 1) {
            packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
        } else {
            null
        }

        forward?.let(::enqueue)

        return Decision.Relay(deliver, forward)
    }

    /** Copia de los paquetes vigentes, para entregar a un vecino nuevo. */
    fun pendingSnapshot(): List<MeshPacket> {
        prunePending()
        return pending.map { it.packet }
    }

    fun pendingCount(): Int {
        prunePending()
        return pending.size
    }

    private fun enqueue(packet: MeshPacket) {
        pending.addLast(PendingPacket(packet, now() + PENDING_TTL_MS))
        while (pending.size > MAX_PENDING) pending.removeFirst()
    }

    private fun prunePending() {
        val t = now()
        while (pending.isNotEmpty() && pending.peekFirst().expiresAtMs <= t) {
            pending.removeFirst()
        }
    }

    private fun pruneSeen() {
        if (seen.size < MAX_SEEN / 2) return
        val t = now()
        val iterator = seen.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().value <= t) iterator.remove()
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    private data class PendingPacket(val packet: MeshPacket, val expiresAtMs: Long)

    companion object {
        /** 3 saltos ≈ 4 dispositivos de extremo a extremo. */
        const val DEFAULT_TTL = 3

        private const val MAX_SEEN = 512
        private const val MAX_PENDING = 64
        private const val SEEN_TTL_MS = 5 * 60_000L
        private const val PENDING_TTL_MS = 2 * 60_000L
    }
}
