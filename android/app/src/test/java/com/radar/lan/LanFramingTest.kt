package com.radar.lan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LanFramingTest {

    private fun frames(vararg packets: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        packets.forEach { LanFraming.write(out, it) }
        return out.toByteArray()
    }

    @Test
    fun `varios paquetes en un mismo flujo se leen por separado`() {
        val a = ByteArray(10) { 1 }
        val b = ByteArray(4_000) { (it % 251).toByte() }
        val c = byteArrayOf(9)
        val input = ByteArrayInputStream(frames(a, b, c))

        assertArrayEquals(a, LanFraming.read(input))
        assertArrayEquals(b, LanFraming.read(input))
        assertArrayEquals(c, LanFraming.read(input))
        assertNull(LanFraming.read(input)) // fin limpio
    }

    @Test
    fun `el largo va en 4 bytes big endian`() {
        val bytes = frames(ByteArray(258))

        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(1, bytes[2].toInt())
        assertEquals(2, bytes[3].toInt())
        assertEquals(4 + 258, bytes.size)
    }

    @Test
    fun `un largo absurdo se rechaza sin reservar memoria`() {
        // Declara 2 GB: el lector debe fallar antes de intentar ByteArray(2_000_000_000).
        val hostile = byteArrayOf(0x77, 0x35, 0x94.toByte(), 0x00, 1, 2, 3)

        try {
            LanFraming.read(ByteArrayInputStream(hostile))
            fail("debía rechazar el largo")
        } catch (_: IOException) {
            // esperado
        }
    }

    @Test
    fun `largo cero o negativo se rechaza`() {
        listOf(
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        ).forEach { bytes ->
            try {
                LanFraming.read(ByteArrayInputStream(bytes))
                fail("debía rechazar el largo")
            } catch (_: IOException) {
                // esperado
            }
        }
    }

    @Test
    fun `un flujo cortado a mitad de frame es un error`() {
        val whole = frames(ByteArray(100))

        try {
            LanFraming.read(ByteArrayInputStream(whole.copyOf(50)))
            fail("debía fallar por frame incompleto")
        } catch (_: IOException) {
            // EOFException es una IOException
        }
    }

    @Test
    fun `no se escriben frames vacios ni mayores al tope`() {
        listOf(ByteArray(0), ByteArray(LanFraming.MAX_FRAME + 1)).forEach { bytes ->
            try {
                LanFraming.write(ByteArrayOutputStream(), bytes)
                fail("debía rechazar el frame")
            } catch (_: IllegalArgumentException) {
                // esperado
            }
        }
    }

    @Test
    fun `el tope cubre un trozo de medios cifrado`() {
        // 26 de cabecera de malla + 28 de nonce/tag + 10 de cabecera de trozo + 3900 de datos.
        val packet = 26 + 28 + 10 + 3_900
        assertEquals(true, packet <= LanFraming.MAX_FRAME)
    }
}
