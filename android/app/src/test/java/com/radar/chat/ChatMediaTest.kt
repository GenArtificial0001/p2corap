package com.radar.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaTest {

    private fun VolatileChatRepository.incomingPhoto(id: Long) =
        appendMedia(id, MessageKind.IMAGE, isMine = false, media = null, durationMs = 0, width = 480, height = 320, progress = 0f)

    @Test
    fun `una foto recibida pasa de recibiendo a completa`() {
        val repo = VolatileChatRepository()
        repo.incomingPhoto(1L)

        assertTrue(repo.messages.value[0].inTransfer)
        assertNull(repo.messages.value[0].media)

        repo.updateProgress(1L, 0.5f)
        assertEquals(0.5f, repo.messages.value[0].progress, 0.0001f)

        repo.completeMedia(1L, ByteArray(100))
        val done = repo.messages.value[0]
        assertFalse(done.inTransfer)
        assertEquals(1f, done.progress, 0.0001f)
        assertEquals(100, done.media?.size)
    }

    @Test
    fun `una transferencia cortada se marca fallida y deja de contar como en curso`() {
        val repo = VolatileChatRepository()
        repo.incomingPhoto(1L)
        repo.updateProgress(1L, 0.3f)

        repo.failMedia(1L)

        val m = repo.messages.value[0]
        assertTrue(m.failed)
        assertFalse(m.inTransfer)
    }

    @Test
    fun `fallar una transferencia ya completa no la rompe`() {
        val repo = VolatileChatRepository()
        repo.incomingPhoto(1L)
        repo.completeMedia(1L, ByteArray(10))

        repo.failMedia(1L)

        assertFalse(repo.messages.value[0].failed)
    }

    @Test
    fun `el progreso se acota entre 0 y 1`() {
        val repo = VolatileChatRepository()
        repo.incomingPhoto(1L)

        repo.updateProgress(1L, 7f)
        assertEquals(1f, repo.messages.value[0].progress, 0.0001f)
        repo.updateProgress(1L, -3f)
        assertEquals(0f, repo.messages.value[0].progress, 0.0001f)
    }

    @Test
    fun `cancelar quita el mensaje`() {
        val repo = VolatileChatRepository()
        repo.incomingPhoto(1L)
        repo.append(2L, "hola", isMine = false)

        repo.remove(1L)

        assertEquals(listOf(2L), repo.messages.value.map { it.id })
    }

    @Test
    fun `el ACK marca entregado un medio propio y no uno ajeno`() {
        val repo = VolatileChatRepository()
        repo.appendMedia(1L, MessageKind.AUDIO, true, ByteArray(10), 1_000, 0, 0, 1f)
        repo.incomingPhoto(2L)

        repo.markDelivered(1L)
        repo.markDelivered(2L)

        assertTrue(repo.messages.value[0].delivered)
        assertFalse(repo.messages.value[1].delivered)
    }

    @Test
    fun `un sticker guarda su id`() {
        val repo = VolatileChatRepository()
        repo.appendSticker(5L, stickerId = 17, isMine = true)

        val m = repo.messages.value[0]
        assertEquals(MessageKind.STICKER, m.kind)
        assertEquals(17, m.stickerId)
    }

    @Test
    fun `al superar el tope de RAM se liberan los medios mas antiguos`() {
        val repo = VolatileChatRepository()
        val chunk = 2 * 1024 * 1024 // 2 MB cada uno

        repeat(4) { i ->
            repo.appendMedia(i.toLong(), MessageKind.VIDEO, false, ByteArray(chunk), 1_000, 0, 0, 1f)
        }

        val messages = repo.messages.value
        val retained = messages.sumOf { it.media?.size ?: 0 }
        assertTrue("retenido $retained", retained <= VolatileChatRepository.MAX_MEDIA_BYTES)
        // Se pierden los más viejos, no los últimos.
        assertTrue(messages.first().released)
        assertNull(messages.first().media)
        assertNotNull(messages.last().media)
    }

    @Test
    fun `no se liberan medios que aun se estan transfiriendo`() {
        val repo = VolatileChatRepository()
        repo.appendMedia(1L, MessageKind.VIDEO, true, ByteArray(5 * 1024 * 1024), 1_000, 0, 0, 0.4f)
        repo.appendMedia(2L, MessageKind.VIDEO, false, ByteArray(5 * 1024 * 1024), 1_000, 0, 0, 1f)

        assertNotNull(repo.messages.value[0].media) // en curso: se conserva
        assertTrue(repo.messages.value[1].released || repo.messages.value[1].media != null)
    }
}
