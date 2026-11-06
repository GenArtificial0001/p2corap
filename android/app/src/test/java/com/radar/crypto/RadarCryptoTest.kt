package com.radar.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadarCryptoTest {

    private val code = "ABCDE-FGHJK-MNPQR-STVWX"
    private val key = RadarCrypto.deriveKey(code)

    @Test
    fun `deriva una clave de 32 bytes`() {
        assertEquals(32, key.size)
    }

    @Test
    fun `cifra y descifra ida y vuelta`() {
        val plaintext = "hola radar".toByteArray(Charsets.UTF_8)
        val payload = RadarCrypto.encrypt(key, plaintext)

        assertArrayEquals(plaintext, RadarCrypto.decrypt(key, payload))
    }

    @Test
    fun `dos cifrados del mismo texto son distintos (nonce aleatorio)`() {
        val plaintext = "mismo mensaje".toByteArray(Charsets.UTF_8)
        val a = RadarCrypto.encrypt(key, plaintext)
        val b = RadarCrypto.encrypt(key, plaintext)

        assertFalse(a.contentEquals(b))
        assertArrayEquals(plaintext, RadarCrypto.decrypt(key, a))
        assertArrayEquals(plaintext, RadarCrypto.decrypt(key, b))
    }

    @Test
    fun `otra clave no puede descifrar`() {
        val plaintext = "secreto".toByteArray(Charsets.UTF_8)
        val payload = RadarCrypto.encrypt(key, plaintext)
        val other = RadarCrypto.deriveKey("ZZZZZ-ZZZZZ-ZZZZZ-ZZZZZ")

        assertNull(RadarCrypto.decrypt(other, payload))
    }

    @Test
    fun `un byte alterado invalida el mensaje`() {
        val plaintext = "integridad".toByteArray(Charsets.UTF_8)
        val payload = RadarCrypto.encrypt(key, plaintext)
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()

        assertNull(RadarCrypto.decrypt(key, payload))
    }

    @Test
    fun `un payload demasiado corto no revienta`() {
        assertNull(RadarCrypto.decrypt(key, ByteArray(3)))
    }

    @Test
    fun `genera codigos validos y formateados`() {
        repeat(50) {
            val generated = RadarCrypto.generateCode()
            assertTrue(generated.contains("-"))
            assertTrue(RadarCrypto.isValidCode(generated))
            assertEquals(23, generated.length) // 20 chars + 3 guiones
        }
    }

    @Test
    fun `normaliza minusculas separadores y confusiones`() {
        assertEquals("ABCDE12345", RadarCrypto.normalize("abcde 1l2o45"))
        assertEquals("ABCDE12345", RadarCrypto.normalize("ABCDE-12345"))
        assertEquals("VWXYZ", RadarCrypto.normalize("uwxyz"))
    }

    @Test
    fun `rechaza codigos incompletos`() {
        assertFalse(RadarCrypto.isValidCode("ABC"))
        assertFalse(RadarCrypto.isValidCode(""))
        assertFalse(RadarCrypto.isValidCode("ABCDE-FGHJK-MNPQR-STVW!"))
    }

    @Test
    fun `el mismo codigo normalizado da la misma clave`() {
        val a = RadarCrypto.deriveKey("abcde fghjk mnpqr stvwx")
        val b = RadarCrypto.deriveKey("ABCDE-FGHJK-MNPQR-STVWX")

        assertArrayEquals(a, b)
        assertNotNull(RadarCrypto.decrypt(a, RadarCrypto.encrypt(b, byteArrayOf(1, 2, 3))))
    }
}
