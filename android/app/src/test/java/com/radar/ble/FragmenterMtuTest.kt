package com.radar.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FragmenterMtuTest {

    private fun reassemble(frames: List<ByteArray>): ByteArray? {
        val assembler = MessageAssembler()
        var result: ByteArray? = null
        frames.forEach { frame -> assembler.accept(frame)?.let { result = it } }
        return result
    }

    @Test
    fun `payloadForMtu respeta ATT y la cabecera propia`() {
        assertEquals(17, Fragmenter.payloadForMtu(23)) // mínimo de BLE
        assertEquals(241, Fragmenter.payloadForMtu(247)) // lo habitual con DLE
        assertEquals(509, Fragmenter.payloadForMtu(517))
        assertEquals(17, Fragmenter.payloadForMtu(5)) // nunca por debajo del mínimo
        assertEquals(509, Fragmenter.payloadForMtu(1_000))
    }

    @Test
    fun `con MTU grande hay muchos menos fragmentos y se reensambla igual`() {
        val bytes = ByteArray(3_964) { (it % 253).toByte() }

        val small = Fragmenter.fragment(bytes, Fragmenter.payloadForMtu(23))
        val large = Fragmenter.fragment(bytes, Fragmenter.payloadForMtu(247))

        assertEquals(234, small.size) // 3964 / 17
        assertEquals(17, large.size)
        assertArrayEquals(bytes, reassemble(small))
        assertArrayEquals(bytes, reassemble(large))
    }

    @Test
    fun `ningun fragmento supera MTU menos 3`() {
        val frames = Fragmenter.fragment(ByteArray(2_000), Fragmenter.payloadForMtu(247))

        assertTrue(frames.all { it.size <= 247 - 3 })
    }

    @Test
    fun `el receptor no necesita conocer el tamano de fragmento`() {
        // Dos vínculos con MTU distinto reensamblan el mismo paquete sin configuración.
        val bytes = ByteArray(1_234) { it.toByte() }
        listOf(17, 100, 241, 509).forEach { size ->
            assertArrayEquals("payload $size", bytes, reassemble(Fragmenter.fragment(bytes, size)))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `un paquete que no cabe en 255 fragmentos se rechaza`() {
        Fragmenter.fragment(ByteArray(255 * 17 + 1), 17)
    }
}
