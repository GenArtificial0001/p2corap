package com.radar.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeshPacketTest {

    @Test
    fun `ida y vuelta conserva todos los campos`() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val packet = MeshPacket(
            type = MeshPacket.TYPE_TEXT,
            messageId = 0x1122334455667788L,
            originId = 42,
            ttl = 3,
            hopCount = 1,
            timestamp = 1_700_000_000_000L,
            payload = payload
        )

        val decoded = MeshPacket.decode(packet.encode())

        requireNotNull(decoded)
        assertEquals(MeshPacket.TYPE_TEXT, decoded.type)
        assertEquals(0x1122334455667788L, decoded.messageId)
        assertEquals(42, decoded.originId)
        assertEquals(3, decoded.ttl)
        assertEquals(1, decoded.hopCount)
        assertEquals(1_700_000_000_000L, decoded.timestamp)
        assertArrayEquals(payload, decoded.payload)
    }

    @Test
    fun `rechaza version desconocida`() {
        val encoded = MeshPacket.text(1L, 1, 3, "hola").encode()
        encoded[0] = 99

        assertNull(MeshPacket.decode(encoded))
    }

    @Test
    fun `rechaza paquete truncado`() {
        val encoded = MeshPacket.text(1L, 1, 3, "hola").encode()

        assertNull(MeshPacket.decode(encoded.copyOfRange(0, 10)))
    }

    @Test
    fun `rechaza payload vacio o inconsistente`() {
        val encoded = MeshPacket.text(1L, 1, 3, "hola").encode()
        // Declara 200 bytes de payload pero no los trae.
        encoded[24] = 0
        encoded[25] = 200.toByte()

        assertNull(MeshPacket.decode(encoded))
    }

    @Test
    fun `el texto sobrevive al viaje`() {
        val original = "mensaje con acentos: ñ á é 🛰️"
        val decoded = MeshPacket.decode(MeshPacket.text(7L, 3, 2, original).encode())

        assertEquals(original, decoded?.text())
    }
}
