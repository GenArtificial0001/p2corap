package com.radar.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClearMessagesTest {

    @Test
    fun `clearMessages borra el chat pero conserva la conexion`() {
        val repo = VolatileChatRepository()
        repo.setConnected(true)
        repo.setPeerAlias("Ana")
        repo.append(1L, "hola", isMine = true)

        repo.clearMessages()

        assertEquals(0, repo.messages.value.size)
        // Cambiar de sala no desconecta a nadie: la UI no debe volver a la lista de pares.
        assertTrue(repo.connected.value)
        assertEquals("Ana", repo.peerAlias.value)
    }

    @Test
    fun `purgeSession sigue borrandolo todo`() {
        val repo = VolatileChatRepository()
        repo.setConnected(true)
        repo.append(1L, "hola", isMine = true)

        repo.purgeSession()

        assertEquals(0, repo.messages.value.size)
        assertFalse(repo.connected.value)
    }
}
