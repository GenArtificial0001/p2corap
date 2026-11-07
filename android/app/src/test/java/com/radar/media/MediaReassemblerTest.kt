package com.radar.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaReassemblerTest {

    private var now = 1_000L
    private val reassembler = MediaReassembler { now }

    private fun transfer(
        id: Long,
        size: Int,
        kind: MediaKind = MediaKind.IMAGE
    ): Pair<MediaHeader, List<MediaChunk>> {
        val bytes = ByteArray(size) { ((it * 31 + id) % 256).toByte() }
        val chunks = MediaChunk.split(id, bytes)
        return MediaHeader(kind, id, size, chunks.size) to chunks
    }

    @Test
    fun `reensambla en orden y entrega los bytes exactos`() {
        val (header, chunks) = transfer(1, 10_000)
        val original = chunks.flatMap { it.data.toList() }.toByteArray()

        assertTrue(reassembler.onHeader(header) is MediaReassembler.Event.Started)
        val events = chunks.map { reassembler.onChunk(it) }

        assertTrue(events[0] is MediaReassembler.Event.Progress)
        val done = events.last() as MediaReassembler.Event.Complete
        assertArrayEquals(original, done.bytes)
        assertEquals(0, reassembler.activeCount)
    }

    @Test
    fun `acepta los trozos en cualquier orden y descarta duplicados`() {
        val (header, chunks) = transfer(2, 10_000)
        reassembler.onHeader(header)

        assertTrue(reassembler.onChunk(chunks[2]) is MediaReassembler.Event.Progress)
        assertNull(reassembler.onChunk(chunks[2])) // duplicado
        assertTrue(reassembler.onChunk(chunks[0]) is MediaReassembler.Event.Progress)
        assertTrue(reassembler.onChunk(chunks[1]) is MediaReassembler.Event.Complete)
    }

    @Test
    fun `ignora trozos de transferencias que no anunciaron cabecera`() {
        val (_, chunks) = transfer(3, 5_000)

        assertNull(reassembler.onChunk(chunks[0]))
    }

    @Test
    fun `rechaza un trozo con tamano distinto al que le corresponde`() {
        val (header, chunks) = transfer(4, 10_000)
        reassembler.onHeader(header)

        val truncated = MediaChunk(4, 0, chunks[0].data.copyOf(100))
        val oversized = MediaChunk(4, 1, ByteArray(MediaLimits.CHUNK_DATA + 1))
        val outOfRange = MediaChunk(4, 9, chunks[0].data)

        assertNull(reassembler.onChunk(truncated))
        assertNull(reassembler.onChunk(oversized))
        assertNull(reassembler.onChunk(outOfRange))
    }

    @Test
    fun `limita transferencias simultaneas`() {
        repeat(MediaReassembler.MAX_ACTIVE) { i ->
            val event = reassembler.onHeader(transfer(10L + i, 1_000).first)
            assertTrue(event is MediaReassembler.Event.Started)
        }
        // Una más que el máximo.
        assertNull(reassembler.onHeader(transfer(99, 1_000).first))
    }

    @Test
    fun `limita la memoria retenida`() {
        val big = MediaKind.VIDEO.maxBytes // 640 KB
        // Con un tope de 1.2 MB caben 1 (640 KB) pero no 2 (1.28 MB).
        assertTrue(reassembler.onHeader(transfer(1, big, MediaKind.VIDEO).first) is MediaReassembler.Event.Started)
        assertNull(reassembler.onHeader(transfer(2, big, MediaKind.VIDEO).first))
        // Lo pequeño sigue entrando.
        assertTrue(reassembler.onHeader(transfer(3, 5_000).first) is MediaReassembler.Event.Started)
    }

    @Test
    fun `una cabecera repetida no pisa la transferencia en curso`() {
        val (header, _) = transfer(5, 5_000)
        assertTrue(reassembler.onHeader(header) is MediaReassembler.Event.Started)
        assertNull(reassembler.onHeader(header))
    }

    @Test
    fun `descarta por inactividad y libera la memoria`() {
        val (header, chunks) = transfer(6, 10_000)
        reassembler.onHeader(header)
        reassembler.onChunk(chunks[0])

        now += MediaReassembler.IDLE_TIMEOUT_MS - 1
        assertEquals(emptyList<Long>(), reassembler.expire())

        now += 2
        assertEquals(listOf(6L), reassembler.expire())
        assertEquals(0, reassembler.activeCount)
        // Tras expirar, un trozo tardío ya no tiene dónde caer.
        assertNull(reassembler.onChunk(chunks[1]))
    }

    @Test
    fun `clear y drop sueltan lo retenido`() {
        reassembler.onHeader(transfer(7, 5_000).first)
        reassembler.onHeader(transfer(8, 5_000).first)

        reassembler.drop(7)
        assertEquals(1, reassembler.activeCount)
        reassembler.clear()
        assertEquals(0, reassembler.activeCount)
    }
}
