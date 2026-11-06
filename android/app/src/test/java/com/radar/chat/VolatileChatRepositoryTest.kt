package com.radar.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolatileChatRepositoryTest {

    @Test
    fun `append conserva orden y marca como no entregado`() {
        val repo = VolatileChatRepository()

        repo.append(1L, "hola", isMine = true)
        repo.append(2L, "qué tal", isMine = false, hops = 2)

        val messages = repo.messages.value
        assertEquals(2, messages.size)
        assertEquals("hola", messages[0].text)
        assertTrue(messages[0].isMine)
        assertFalse(messages[0].delivered)
        assertEquals(2, messages[1].hops)
    }

    @Test
    fun `markDelivered solo afecta al mensaje propio con ese id`() {
        val repo = VolatileChatRepository()

        repo.append(10L, "propio", isMine = true)
        repo.append(11L, "ajeno", isMine = false)
        repo.append(12L, "otro propio", isMine = true)

        repo.markDelivered(10L)

        val messages = repo.messages.value
        assertTrue(messages.first { it.id == 10L }.delivered)
        assertFalse(messages.first { it.id == 11L }.delivered)
        assertFalse(messages.first { it.id == 12L }.delivered)
    }

    @Test
    fun `markDelivered ignora ids desconocidos`() {
        val repo = VolatileChatRepository()
        repo.append(1L, "hola", isMine = true)

        repo.markDelivered(999L)

        assertFalse(repo.messages.value.single().delivered)
    }

    @Test
    fun `purgeSession borra mensajes, vínculo y alias`() {
        val repo = VolatileChatRepository()
        repo.append(1L, "hola", isMine = true)
        repo.setConnected(true)
        repo.setPeerAlias("Vecino")

        repo.purgeSession()

        assertTrue(repo.messages.value.isEmpty())
        assertFalse(repo.connected.value)
        assertNull(repo.peerAlias.value)
    }
}
