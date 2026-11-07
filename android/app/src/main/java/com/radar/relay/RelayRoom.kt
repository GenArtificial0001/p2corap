package com.radar.relay

import com.radar.crypto.RadarCrypto
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Identidad de sala en el relé e invitaciones. Puro (sin Android): se prueba en JVM.
 *
 * El relé **nunca** recibe el código de sala: solo `roomId`, un hash. La clave AES se deriva
 * con otra sal (PBKDF2 «radar-v1»), así que `roomId` no permite descifrar nada.
 */
object RelayRoom {

    private const val SALT = "radar-relay-v1|"

    /** `SHA-256("radar-relay-v1|" + código normalizado)`, primeros 32 hex. Igual que el `room` del servidor. */
    fun idFor(code: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((SALT + RadarCrypto.normalize(code)).toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Datos de una invitación: a qué relé conectar y qué sala abrir. */
/** [relayUrl] es null en las invitaciones para Wi-Fi local (no usan servidor). */
data class Invite(val relayUrl: String?, val code: String)

object InviteLink {

    /** wss://host/ws → https://host/join#CODIGO (el código va en el fragmento: no llega al servidor). */
    fun build(relayUrl: String, code: String): String? {
        val uri = parseRelay(relayUrl) ?: return null
        val port = if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
        return "https://${uri.host}$port/join#${RadarCrypto.format(code)}"
    }

    /**
     * Acepta:
     * - `https://host/join#CODIGO` (el enlace que se comparte),
     * - `radar://join?code=CODIGO&relay=wss://host/ws` (lo abre la página de invitación).
     */
    fun parse(text: String): Invite? {
        val raw = text.trim()
        return runCatching {
            val uri = URI(raw)
            when (uri.scheme?.lowercase()) {
                "https" -> {
                    val code = uri.rawFragment?.let { decode(it) } ?: return null
                    val host = uri.host ?: return null
                    val port = if (uri.port == -1) "" else ":${uri.port}"
                    valid("wss://$host$port/ws", code)
                }

                "radar" -> {
                    val params = (uri.rawQuery ?: return null).split("&").mapNotNull {
                        val i = it.indexOf('=')
                        if (i <= 0) null else it.substring(0, i) to decode(it.substring(i + 1))
                    }.toMap()
                    valid(params["relay"], params["code"] ?: return null)
                }

                else -> null
            }
        }.getOrNull()
    }

    private fun valid(relayUrl: String?, code: String): Invite? {
        if (relayUrl != null && parseRelay(relayUrl) == null) return null
        if (!RadarCrypto.isValidCode(code)) return null
        return Invite(relayUrl, RadarCrypto.format(code))
    }

    /** Solo `wss://` con host: nada de texto plano por internet. */
    fun parseRelay(text: String): URI? = runCatching {
        val uri = URI(text.trim())
        if (uri.scheme?.lowercase() == "wss" && !uri.host.isNullOrBlank()) uri else null
    }.getOrNull()

    /**
     * `radar://join?...`. Con [relayUrl] lo usa el botón de la página de invitación; sin él es la
     * invitación para Wi-Fi local, que no necesita ningún servidor.
     */
    fun appLink(relayUrl: String?, code: String): String {
        val base = "radar://join?code=${enc(RadarCrypto.format(code))}"
        return if (relayUrl == null) base else "$base&relay=${enc(relayUrl)}"
    }

    private fun decode(s: String): String = URLDecoder.decode(s, "UTF-8")
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
