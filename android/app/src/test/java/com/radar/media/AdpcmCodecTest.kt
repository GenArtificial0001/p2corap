package com.radar.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AdpcmCodecTest {

    /** Tono de 440 Hz a 8 kHz con amplitud de voz. */
    private fun tone(samples: Int, amplitude: Double = 9_000.0): ShortArray =
        ShortArray(samples) {
            (amplitude * sin(2 * PI * 440 * it / AdpcmCodec.SAMPLE_RATE)).toInt().toShort()
        }

    @Test
    fun `ida y vuelta conserva la forma de onda`() {
        val pcm = tone(8_000)

        val decoded = AdpcmCodec.decode(AdpcmCodec.encode(pcm))

        assertNotNull(decoded)
        assertEquals(pcm.size, decoded!!.size)
        // Error medio acotado (< 7 % de la amplitud) una vez estabilizado el predictor.
        // Un fallo real (p. ej. nibbles invertidos) da errores de miles.
        val meanError = (200 until pcm.size).map { abs(pcm[it] - decoded[it]) }.average()
        assertTrue("error medio $meanError", meanError < 600.0)
    }

    @Test
    fun `comprime aproximadamente 4 a 1`() {
        val pcm = tone(16_000) // 2 s
        val encoded = AdpcmCodec.encode(pcm)

        // 16 bits → 4 bits, más 7 bytes de cabecera.
        assertEquals(7 + 8_000, encoded.size)
        assertTrue(encoded.size * 3.9 < pcm.size * 2)
    }

    @Test
    fun `20 segundos caben en el tope de audio`() {
        val encoded = AdpcmCodec.encode(tone(AdpcmCodec.SAMPLE_RATE * 20))

        assertTrue(encoded.size < MediaKind.AUDIO.maxBytes)
    }

    @Test
    fun `numero impar de muestras`() {
        val decoded = AdpcmCodec.decode(AdpcmCodec.encode(tone(1_001)))

        assertEquals(1_001, decoded?.size)
    }

    @Test
    fun `datos corruptos o hostiles se rechazan sin romper`() {
        assertNull(AdpcmCodec.decode(ByteArray(0)))
        assertNull(AdpcmCodec.decode(ByteArray(7)))
        val encoded = AdpcmCodec.encode(tone(100))
        assertNull(AdpcmCodec.decode(encoded.copyOf(encoded.size - 3))) // truncado
        // Declara más muestras que el tope: no debe reservar memoria.
        val huge = encoded.copyOf().also {
            it[0] = 0x7F
            it[1] = 0xFF.toByte()
        }
        assertNull(AdpcmCodec.decode(huge))
        // Índice de paso fuera de rango.
        val badIndex = encoded.copyOf().also { it[6] = 120 }
        assertNull(AdpcmCodec.decode(badIndex))
    }

    @Test
    fun `el silencio no explota`() {
        val decoded = AdpcmCodec.decode(AdpcmCodec.encode(ShortArray(500)))

        assertNotNull(decoded)
        assertTrue(decoded!!.all { abs(it.toInt()) < 20 })
    }

    @Test
    fun `waveform normaliza a 0 y 1`() {
        val bars = AdpcmCodec.waveform(tone(8_000), 30)

        assertEquals(30, bars.size)
        assertTrue(bars.all { it in 0f..1f })
        assertEquals(1f, bars.max(), 0.0001f)
        assertEquals(0, AdpcmCodec.waveform(ShortArray(0), 30).size)
    }

    @Test
    fun `duracion en milisegundos`() {
        assertEquals(1_000, AdpcmCodec.duration(8_000))
        assertEquals(500, AdpcmCodec.duration(4_000))
    }
}
