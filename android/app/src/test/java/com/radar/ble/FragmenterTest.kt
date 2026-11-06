package com.radar.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FragmenterTest {

    private fun reassemble(frames: List<ByteArray>): ByteArray? {
        val assembler = MessageAssembler()
        var result: ByteArray? = null
        for (frame in frames) {
            assembler.accept(frame)?.let { result = it }
        }
        return result
    }

    @Test
    fun `un mensaje corto entra en un solo fragmento`() {
        val bytes = "hola".toByteArray(Charsets.UTF_8)
        val frames = Fragmenter.fragment(bytes)

        assertArrayEquals(bytes, reassemble(frames))
    }

    @Test
    fun `un mensaje largo se parte y se reensambla`() {
        val bytes = "radar ".repeat(200).toByteArray(Charsets.UTF_8)
        val frames = Fragmenter.fragment(bytes)

        assertArrayEquals(bytes, reassemble(frames))
    }

    @Test
    fun `cada fragmento respeta el limite de 17 bytes de payload`() {
        val frames = Fragmenter.fragment(ByteArray(100) { it.toByte() })

        frames.forEach { frame ->
            val payload = frame.size - Fragmenter.HEADER_SIZE
            if (payload > Fragmenter.MAX_FRAGMENT_PAYLOAD) {
                error("Fragmento demasiado grande: $payload")
            }
        }
    }

    @Test
    fun `un fragmento suelto no completa el mensaje`() {
        val assembler = MessageAssembler()
        val frames = Fragmenter.fragment(ByteArray(100) { 1 })

        assertNull(assembler.accept(frames.first()))
    }

    @Test
    fun `si falta un fragmento no se entrega un mensaje truncado`() {
        val assembler = MessageAssembler()
        val bytes = ByteArray(100) { it.toByte() }
        val frames = Fragmenter.fragment(bytes)

        // Llega el último fragmento y todos menos el del medio.
        var result: ByteArray? = null
        frames.forEachIndexed { index, frame ->
            if (index != 2) assembler.accept(frame)?.let { result = it }
        }

        assertNull(result)
    }

    @Test
    fun `fragmentos duplicados no corrompen el mensaje`() {
        val assembler = MessageAssembler()
        val bytes = "radar ".repeat(50).toByteArray(Charsets.UTF_8)
        val frames = Fragmenter.fragment(bytes)

        var result: ByteArray? = null
        frames.forEach { frame ->
            repeat(2) {
                assembler.accept(frame)?.let { result = it }
            }
        }

        assertArrayEquals(bytes, result)
    }

    @Test
    fun `un paquete basura no rompe el ensamblador`() {
        val assembler = MessageAssembler()

        assertNull(assembler.accept(byteArrayOf(0, 0, 0)))
        assertNull(assembler.accept(ByteArray(2)))
    }
}
