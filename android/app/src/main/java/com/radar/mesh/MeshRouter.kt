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
    fun origin(
        payload: ByteArray,
        type: Int = MeshPacket.TYPE_TEXT,
        ttl: Int = defaultTtl
    ): MeshPacket {
        val packet = MeshPacket(
            type = type,
            messageId = kotlin.random.Random.nextLong(),
            originId = selfId,
            ttl = ttl,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            payload = payload
        )
        seen[packet.messageId] = now() + SEEN_TTL_MS
        return packet
    }

    /** ACK de entrega: 8 bytes con el messageId confirmado. */
    fun originAck(ackFor: Long): MeshPacket {
        val payload = java.nio.ByteBuffer.allocate(8).putLong(ackFor).array()
        return origin(payload, MeshPacket.TYPE_ACK)
    }

    fun onReceive(packet: MeshPacket): Decision {
        pruneSeen()

        if (seen.containsKey(packet.messageId)) return Decision.Drop
        seen[packet.messageId] = now() + SEEN_TTL_MS

        // Eco de un mensaje propio: ya se entregó localmente al enviarlo.
        if (packet.originId == selfId) return Decision.Drop

        val deliver = packet.type in DELIVERABLE_TYPES

        // ttl = saltos que todavía puede dar. Con ttl 1 llega y no reenvía.
        val forward = if (packet.ttl > 1) {
            packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
        } else {
            null
        }

        // Solo el texto se guarda para un vecino futuro: los ACK caducan con
        // el vínculo y no tiene sentido reenviarlos minutos después.
        if (forward != null && packet.type in STORED_TYPES) enqueue(forward)

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
        /**
         * 5 saltos ≈ 6 dispositivos de extremo a extremo. Pensado para escenarios sin
         * infraestructura (apagón, desastre), donde alcanzar más lejos importa más que el
         * tráfico extra; la deduplicación acota el costo: cada nodo reenvía un mensaje una vez.
         */
        const val DEFAULT_TTL = 5

        /** Tipos que se entregan a la app. Los desconocidos solo se reenvían. */
        private val DELIVERABLE_TYPES = setOf(
            MeshPacket.TYPE_TEXT,
            MeshPacket.TYPE_ACK,
            MeshPacket.TYPE_STICKER,
            MeshPacket.TYPE_MEDIA_HEADER,
            MeshPacket.TYPE_MEDIA_CHUNK
        )

        /**
         * Solo lo pequeño se guarda para vecinos futuros. Los medios (cientos de KB) y los ACK
         * no: reenviarlos minutos después no tendría sentido y agotaría la memoria.
         */
        private val STORED_TYPES = setOf(MeshPacket.TYPE_TEXT, MeshPacket.TYPE_STICKER)

        private const val MAX_SEEN = 1024
        private const val MAX_PENDING = 128
        // Debe superar a PENDING_TTL_MS: si no, un reenvío tardío volvería a entrar como nuevo.
        private const val SEEN_TTL_MS = 30 * 60_000L
        
        /** Retención store-and-forward: quien llegue a la zona en este lapso recibe lo reciente. */
        private const val PENDING_TTL_MS = 15 * 60_000L
    }
}
