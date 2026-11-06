package com.radar.crypto

/**
 * Estado de la sala actual: código y clave AES derivada.
 *
 * La clave vive solo en RAM. Si el proceso muere, se pierde (Zero-Storage):
 * para volver a la misma sala hay que reingresar el código.
 */
class MeshCrypto {

    @Volatile
    private var key: ByteArray? = null

    @Volatile
    private var code: String = ""

    @Synchronized
    fun startNewRoom(): String {
        val generated = RadarCrypto.generateCode()
        adopt(generated)
        return code
    }

    /** @return false si el código no tiene el formato esperado. */
    @Synchronized
    fun join(rawCode: String): Boolean {
        if (!RadarCrypto.isValidCode(rawCode)) return false
        adopt(RadarCrypto.normalize(rawCode))
        return true
    }

    fun currentCode(): String = code

    fun hasKey(): Boolean = key != null

    fun encrypt(plaintext: ByteArray): ByteArray? {
        val current = key ?: return null
        return runCatching { RadarCrypto.encrypt(current, plaintext) }.getOrNull()
    }

    fun decrypt(payload: ByteArray): ByteArray? {
        val current = key ?: return null
        return RadarCrypto.decrypt(current, payload)
    }

    @Synchronized
    fun purge() {
        RadarCrypto.wipe(key)
        key = null
        code = ""
    }

    private fun adopt(rawCode: String) {
        RadarCrypto.wipe(key)
        key = RadarCrypto.deriveKey(rawCode)
        code = RadarCrypto.format(rawCode)
    }
}
