package com.radar.chat

/**
 * Límite del texto de un mensaje, medido en **bytes UTF-8**.
 *
 * El paquete de malla admite 512 bytes de payload y el cifrado añade 28
 * (nonce de 12 + tag GCM de 16). Limitar por *caracteres* no alcanza: 400
 * emojis ocupan 1600 bytes, el receptor descartaba el paquete por exceder
 * [com.radar.mesh.MeshPacket.MAX_PAYLOAD] y el emisor veía «enviado» para siempre.
 */
object TextLimit {

    /** 512 (payload máximo) - 28 (nonce + tag) = 484; se deja un margen. */
    const val MAX_BYTES = 480

    /** Tamaño UTF-8 del texto (estimación conservadora para sustitutos sueltos). */
    fun byteLength(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            bytes += sizeOf(cp)
            index += Character.charCount(cp)
        }
        return bytes
    }

    /** Recorta sin partir un carácter por la mitad. */
    fun truncate(text: String, maxBytes: Int = MAX_BYTES): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            val size = sizeOf(cp)
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    private fun sizeOf(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
