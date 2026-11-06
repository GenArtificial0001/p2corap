package com.radar.ble

import java.util.UUID

/**
 * Fuente única de verdad de los UUIDs del perfil Radar.
 *
 * UUIDs aleatorios de 128 bits (RFC 4122 v4), fuera de cualquier rango
 * reservado por el Bluetooth SIG. Resuelve R-01 de la revisión técnica.
 */
object RadarUuids {
    val SERVICE: UUID = UUID.fromString("7D022782-8CD9-4FD0-8648-780D092EF19B")
    val PROFILE_CHAR: UUID = UUID.fromString("23ECFC0A-133C-4E13-9151-C8250E514414")
    val CONTROL_CHAR: UUID = UUID.fromString("DBD2BE6F-0278-47CE-AA7E-137BF8F40F6F")
    val MESSAGE_CHAR: UUID = UUID.fromString("77188928-C80D-4CB5-8C3B-B73C0D1B4585")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** Payload útil con MTU 23 (23 - 3 de overhead ATT). */
    const val MIN_FRAGMENT_PAYLOAD = 17
}

/** Opcodes del characteristic Connection Control. */
object ControlOpcode {
    const val CONNECT_REQUEST: Byte = 0x01
    const val ACCEPT: Byte = 0x02
    const val REJECT: Byte = 0x03
    const val BYE: Byte = 0x04
}
