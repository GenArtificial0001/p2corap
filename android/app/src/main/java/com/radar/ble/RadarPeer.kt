package com.radar.ble

/** Un dispositivo Radar detectado por el scanner. La identidad real llega por GATT. */
data class RadarPeer(
    val address: String,
    val rssi: Int,
    val lastSeenMs: Long
) {
    /**
     * RSSI no es distancia: varía con el cuerpo, la mano y la orientación.
     * Se expone como categoría cualitativa, no en metros.
     */
    val proximity: Proximity
        get() = when {
            rssi >= -60 -> Proximity.CERCA
            rssi >= -80 -> Proximity.MEDIO
            else -> Proximity.LEJOS
        }
}

enum class Proximity { CERCA, MEDIO, LEJOS }
