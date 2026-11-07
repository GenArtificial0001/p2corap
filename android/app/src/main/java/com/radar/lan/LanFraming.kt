package com.radar.lan

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Enmarcado sobre un socket TCP: `[longitud:4 big-endian][bytes]`. Puro: se prueba en JVM.
 *
 * TCP es un flujo sin límites de mensaje; cada paquete de malla va precedido de su largo.
 * El lector rechaza largos absurdos **antes** de reservar memoria, porque en una red local
 * cualquiera puede abrirnos un socket.
 */
object LanFraming {

    /** El paquete más grande es un trozo de medios (≈4 KB); se deja margen. */
    const val MAX_FRAME = 8 * 1024

    @Throws(IOException::class)
    fun write(out: OutputStream, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= MAX_FRAME) { "Tamaño de frame inválido: ${bytes.size}" }
        val data = DataOutputStream(out)
        data.writeInt(bytes.size)
        data.write(bytes)
        data.flush()
    }

    /**
     * Lee un frame. Devuelve null si el flujo terminó limpiamente; lanza [IOException]
     * si el largo declarado es inválido o el flujo se cortó a mitad de frame.
     */
    @Throws(IOException::class)
    fun read(input: InputStream): ByteArray? {
        val data = DataInputStream(input)
        val length = try {
            data.readInt()
        } catch (_: EOFException) {
            return null
        }
        if (length <= 0 || length > MAX_FRAME) throw IOException("Largo de frame inválido: $length")
        val bytes = ByteArray(length)
        data.readFully(bytes)
        return bytes
    }
}
