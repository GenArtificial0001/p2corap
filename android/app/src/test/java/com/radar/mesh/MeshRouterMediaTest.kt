package com.radar.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterMediaTest {

    private fun incoming(type: Int, ttl: Int, id: Long = 77L, size: Int = 8) = MeshPacket(
        type = type,
        messageId = id,
        originId = 2,
        ttl = ttl,
        hopCount = 0,
        timestamp = 0L,
        payload = ByteArray(size) { 1 }
    )

    @Test
    fun `los medios se entregan pero no se reenvian con ttl 1`() {
        val router = MeshRouter(selfId = 1)

        val chunk = router.onReceive(incoming(MeshPacket.TYPE_MEDIA_CHUNK, ttl = 1, id = 1)) as MeshRouter.Decision.Relay
        val header = router.onReceive(incoming(MeshPacket.TYPE_MEDIA_HEADER, ttl = 1, id = 2)) as MeshRouter.Decision.Relay

        assertTrue(chunk.deliver)
        assertNull(chunk.forward)
        assertTrue(header.deliver)
        assertNull(header.forward)
    }

    @Test
    fun `los medios nunca quedan guardados para vecinos futuros`() {
        val router = MeshRouter(selfId = 1)

        // Aunque llegaran con ttl alto, un trozo de cientos de KB no se retiene.
        router.onReceive(incoming(MeshPacket.TYPE_MEDIA_CHUNK, ttl = 5, id = 1))
        router.onReceive(incoming(MeshPacket.TYPE_MEDIA_HEADER, ttl = 5, id = 2))

        assertEquals(0, router.pendingCount())
    }

    @Test
    fun `el sticker se entrega, se reenvia y se guarda como el texto`() {
        val router = MeshRouter(selfId = 1)

        val decision = router.onReceive(incoming(MeshPacket.TYPE_STICKER, ttl = 3)) as MeshRouter.Decision.Relay

        assertTrue(decision.deliver)
        assertNotNull(decision.forward)
        assertEquals(1, router.pendingCount())
    }

    @Test
    fun `un tipo desconocido se reenvia pero no se entrega`() {
        val router = MeshRouter(selfId = 1)

        val decision = router.onReceive(incoming(type = 99, ttl = 3)) as MeshRouter.Decision.Relay

        assertEquals(false, decision.deliver)
        assertNotNull(decision.forward)
    }

    @Test
    fun `origin con ttl propio crea paquetes que no se reenvian`() {
        val router = MeshRouter(selfId = 1)

        val packet = router.origin(ByteArray(4), MeshPacket.TYPE_MEDIA_CHUNK, ttl = 1)

        assertEquals(1, packet.ttl)
        assertEquals(MeshPacket.TYPE_MEDIA_CHUNK, packet.type)
        // Y su eco no se procesa: ya está en `seen`.
        assertTrue(router.onReceive(packet) is MeshRouter.Decision.Drop)
    }

    @Test
    fun `cada trozo tiene su propio id y por eso no se descartan entre si`() {
        val router = MeshRouter(selfId = 1)

        val ids = (1..50).map { router.origin(ByteArray(4), MeshPacket.TYPE_MEDIA_CHUNK, ttl = 1).messageId }

        assertEquals(50, ids.toSet().size)
    }

    @Test
    fun `el ttl por defecto alcanza mas lejos que antes`() {
        assertTrue(MeshRouter.DEFAULT_TTL >= 5)
    }

    @Test
    fun `un trozo de medios admite hasta 4000 bytes y el texto sigue en 512`() {
        val chunk = incoming(MeshPacket.TYPE_MEDIA_CHUNK, ttl = 1, size = 3_938)
        assertNotNull(MeshPacket.decode(chunk.encode()))

        val bigText = incoming(MeshPacket.TYPE_TEXT, ttl = 3, size = 513)
        assertNull(MeshPacket.decode(bigText.encode()))

        val bigChunk = incoming(MeshPacket.TYPE_MEDIA_CHUNK, ttl = 1, size = 4_001)
        assertNull(MeshPacket.decode(bigChunk.encode()))
    }
}
