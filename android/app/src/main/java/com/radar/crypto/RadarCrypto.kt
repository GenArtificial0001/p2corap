package com.radar.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografía de la sala.
 *
 * Modelo: clave simétrica compartida (grupo). Se deriva de un código que los
 * miembros intercambian fuera de banda (de viva voz, QR, mensaje). Nunca viaja
 * por la malla.
 *
 * - AES-256-GCM: confidencialidad + integridad.
 * - Nonce aleatorio de 12 bytes por mensaje, prefijado al ciphertext.
 * - PBKDF2-HMAC-SHA256 para derivar la clave del código.
 *
 * Solo usa `javax.crypto` y `java.security`: corre igual en Android y en la JVM,
 * así que se puede testear sin dispositivo.
 */
object RadarCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val TAG_BITS = 128
    const val NONCE_BYTES = 12
    const val KEY_BYTES = 32
    private const val ITERATIONS = 120_000
    private const val SALT = "radar-v1"

    /**
     * Código de sala pública de fábrica.
     *
     * Todos los dispositivos que instalen Radar empiezan con este mismo código,
     * así que dos teléfonos cualquiera pueden chatear sin tener que pasarse
     * un código a mano. Es 20 caracteres válidos del alfabeto Crockford.
     */
    const val PUBLIC_ROOM_CODE = "RADAR000000000000000"

    /** Alfabeto Crockford: sin I, L, O ni U para evitar confusiones al dictar. */
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val CODE_GROUPS = 4
    private const val CODE_GROUP_SIZE = 5

    private val AAD = "radar-msg-v1".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    // ------------------------------------------------------------------ código

    fun generateCode(): String {
        val chars = CharArray(CODE_GROUPS * CODE_GROUP_SIZE) {
            ALPHABET[random.nextInt(ALPHABET.length)]
        }
        return format(String(chars))
    }

    fun format(raw: String): String {
        val clean = normalize(raw)
        return clean.chunked(CODE_GROUP_SIZE).joinToString("-")
    }

    /** Mayúsculas, sin separadores y con las confusiones típicas corregidas. */
    fun normalize(code: String): String = buildString {
        for (ch in code.uppercase()) {
            when (ch) {
                '-', ' ', '\t', '\n' -> Unit
                'I', 'L' -> append('1')
                'O' -> append('0')
                'U' -> append('V')
                else -> append(ch)
            }
        }
    }

    fun isValidCode(code: String): Boolean {
        val clean = normalize(code)
        if (clean.length != CODE_GROUPS * CODE_GROUP_SIZE) return false
        return clean.all { it in ALPHABET }
    }

    // -------------------------------------------------------------------- clave

    fun deriveKey(code: String): ByteArray {
        val normalized = normalize(code)
        val spec = PBEKeySpec(
            normalized.toCharArray(),
            SALT.toByteArray(Charsets.UTF_8),
            ITERATIONS,
            KEY_BYTES * 8
        )
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec)
                .encoded
        } finally {
            spec.clearPassword()
        }
    }

    // ---------------------------------------------------------------- cifrado

    /** Devuelve `nonce || ciphertext`. */
    fun encrypt(key: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES)
        random.nextBytes(nonce)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, KEY_ALGORITHM),
            GCMParameterSpec(TAG_BITS, nonce)
        )
        cipher.updateAAD(AAD)

        return nonce + cipher.doFinal(plaintext)
    }

    /** Devuelve null si el paquete no pertenece a la sala o fue alterado. */
    fun decrypt(key: ByteArray, payload: ByteArray): ByteArray? {
        if (payload.size <= NONCE_BYTES) return null

        val nonce = payload.copyOfRange(0, NONCE_BYTES)
        val ciphertext = payload.copyOfRange(NONCE_BYTES, payload.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, KEY_ALGORITHM),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            cipher.updateAAD(AAD)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            null
        }
    }

    fun wipe(bytes: ByteArray?) {
        bytes?.fill(0)
    }
}
