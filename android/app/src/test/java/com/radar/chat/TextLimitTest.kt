package com.radar.chat

import com.radar.crypto.RadarCrypto
import com.radar.mesh.MeshPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLimitTest {

    @Test
    fun `byteLength cuenta bytes UTF-8 y no caracteres`() {
        assertEquals(5, TextLimit.byteLength("hola!"))
        assertEquals(2, TextLimit.byteLength("ñ"))
        assertEquals(3, TextLimit.byteLength("€"))
        assertEquals(4, TextLimit.byteLength("😀"))
        assertEquals("😀".toByteArray(Charsets.UTF_8).size, TextLimit.byteLength("😀"))
    }

    @Test
    fun `truncate respeta el limite sin partir caracteres`() {
        val text = "😀".repeat(200) // 800 bytes
        val cut = TextLimit.truncate(text, maxBytes = 10)

        // 10 bytes caben 2 emojis completos (8 bytes); un tercero se pasaría.
        assertEquals("😀😀", cut)
        assertTrue(cut.toByteArray(Charsets.UTF_8).size <= 10)
    }

    @Test
    fun `truncate no toca un texto que ya cabe`() {
        assertEquals("hola mundo", TextLimit.truncate("hola mundo"))
    }

    @Test
    fun `un mensaje al limite cifrado cabe en un paquete de malla`() {
        // Caso que antes fallaba: 400 emojis pasaban el recorte por caracteres,
        // superaban MAX_PAYLOAD tras cifrar y el receptor los descartaba.
        val worst = TextLimit.truncate("😀".repeat(400))
        val key = RadarCrypto.deriveKey(RadarCrypto.PUBLIC_ROOM_CODE)
        val payload = RadarCrypto.encrypt(key, worst.toByteArray(Charsets.UTF_8))

        assertTrue(
            "payload ${payload.size} excede ${MeshPacket.MAX_PAYLOAD}",
            payload.size <= MeshPacket.MAX_PAYLOAD
        )

        val packet = MeshPacket(
            type = MeshPacket.TYPE_TEXT,
            messageId = 1L,
            originId = 2,
            ttl = 3,
            hopCount = 0,
            timestamp = 0L,
            payload = payload
        )
        val decoded = MeshPacket.decode(packet.encode())
        assertFalse("el receptor descartó el paquete", decoded == null)
    }
}
