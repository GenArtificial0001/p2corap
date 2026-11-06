package com.radar.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MeshRouterTest {

    private fun packet(ttl: Int = 3, origin: Int = 2, hop: Int = 0) = MeshPacket(
        type = MeshPacket.TYPE_TEXT,
        messageId = 1234L,
        originId = origin,
        ttl = ttl,
        hopCount = hop,
        timestamp = 0L,
        payload = "x".toByteArray(Charsets.UTF_8)
    )

    @Test
    fun `el primer paquete se entrega y se reenvia con un salto menos`() {
        val router = MeshRouter(selfId = 1, defaultTtl = 3)

        val decision = router.onReceive(packet()) as MeshRouter.Decision.Relay

        assertTrue(decision.deliver)
        assertEquals(2, decision.forward!!.ttl)
        assertEquals(1, decision.forward!!.hopCount)
    }

    @Test
    fun `el duplicado se descarta`() {
        val router = MeshRouter(selfId = 1)

        router.onReceive(packet())

        assertTrue(router.onReceive(packet()) is MeshRouter.Decision.Drop)
    }

    @Test
    fun `ttl 1 se entrega pero no se reenvia`() {
        val router = MeshRouter(selfId = 1)

        val decision = router.onReceive(packet(ttl = 1)) as MeshRouter.Decision.Relay

        assertTrue(decision.deliver)
        assertNull(decision.forward)
    }

    @Test
    fun `el eco de un mensaje propio se descarta`() {
        val router = MeshRouter(selfId = 1)
        val own = router.origin("hola".toByteArray(Charsets.UTF_8))

        assertTrue(router.onReceive(own) is MeshRouter.Decision.Drop)
    }

    @Test
    fun `guarda el reenvio para un vecino que aparece despues`() {
        val router = MeshRouter(selfId = 1)

        router.onReceive(packet())

        assertEquals(1, router.pendingCount())
        assertEquals(1, router.pendingSnapshot().size)
    }

    @Test
    fun `un mensaje propio no queda en la cola de reenvio`() {
        val router = MeshRouter(selfId = 1)

        router.origin("hola".toByteArray(Charsets.UTF_8))

        assertEquals(0, router.pendingCount())
    }
}
