package com.radar.media

import com.radar.mesh.MeshPacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaProtocolTest {

    private fun header(
        kind: MediaKind = MediaKind.IMAGE,
        total: Int = 10_000,
        chunks: Int = MediaLimits.chunkCount(total),
        duration: Int = 0,
        w: Int = 480,
        h: Int = 320
    ) = MediaHeader(kind, 0x1122334455667788L, total, chunks, duration, w, h)

    @Test
    fun `la cabecera sobrevive al viaje`() {
        val original = header(MediaKind.VIDEO, total = 200_000, duration = 4_800, w = 426, h = 240)

        val decoded = MediaHeader.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(MediaHeader.SIZE, original.encode().size)
    }

    @Test
    fun `rechaza cabeceras fuera de limites`() {
        // Más grande que el tope del tipo.
        assertNull(MediaHeader.decode(header(total = MediaKind.IMAGE.maxBytes + 1).encode()))
        // Cantidad de trozos inconsistente con el tamaño.
        assertNull(MediaHeader.decode(header(total = 10_000, chunks = 1).encode()))
        // Vacía.
        assertNull(MediaHeader.decode(header(total = 0, chunks = 0).encode()))
        // Dimensiones absurdas (bomba de descompresión).
        assertNull(MediaHeader.decode(header(w = 60_000).encode()))
        // Largo incorrecto o tipo desconocido.
        assertNull(MediaHeader.decode(ByteArray(5)))
        val unknown = header().encode().also { it[0] = 99 }
        assertNull(MediaHeader.decode(unknown))
    }

    @Test
    fun `split parte y los trozos suman el original`() {
        val bytes = ByteArray(10_000) { (it % 251).toByte() }

        val chunks = MediaChunk.split(7L, bytes)

        assertEquals(MediaLimits.chunkCount(bytes.size), chunks.size)
        assertEquals(3, chunks.size) // 3900 + 3900 + 2200
        assertArrayEquals(bytes, chunks.flatMap { it.data.toList() }.toByteArray())
        chunks.forEachIndexed { i, c -> assertEquals(i, c.index) }
    }

    @Test
    fun `un trozo sobrevive al viaje y rechaza tamanos invalidos`() {
        val chunk = MediaChunk(42L, 3, ByteArray(100) { it.toByte() })
        val decoded = MediaChunk.decode(chunk.encode())

        assertNotNull(decoded)
        assertEquals(42L, decoded!!.transferId)
        assertEquals(3, decoded.index)
        assertArrayEquals(chunk.data, decoded.data)

        assertNull(MediaChunk.decode(ByteArray(MediaChunk.HEADER))) // sin datos
        assertNull(MediaChunk.decode(ByteArray(MediaChunk.HEADER + MediaLimits.CHUNK_DATA + 1)))
    }

    @Test
    fun `un trozo cifrado cabe en un paquete de malla y en 255 fragmentos con MTU minimo`() {
        // Peor caso: nonce+tag (28) + cabecera de trozo (10) + datos = payload de malla.
        val payload = 28 + MediaChunk.HEADER + MediaLimits.CHUNK_DATA
        val packetBytes = MeshPacket.HEADER_SIZE + payload

        assertTrue("payload $payload", payload <= MeshPacket.MAX_MEDIA_PAYLOAD)
        val fragments = (packetBytes + 17 - 1) / 17
        assertTrue("$fragments fragmentos", fragments <= 255)
    }
}
