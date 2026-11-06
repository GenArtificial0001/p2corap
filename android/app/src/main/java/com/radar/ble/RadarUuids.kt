package com.radar.ble

import java.util.UUID

/**
 * Fuente única de verdad de los UUIDs del perfil Radar.
 *
 * TODO(R-01): el rango 0xFA00-0xFAFF está reservado por el Bluetooth SIG.
 * Antes de publicar hay que migrar a UUIDs aleatorios de 128 bits. Al estar
 * centralizados acá, el cambio no toca el resto del código.
 */
object RadarUuids {
    val SERVICE: UUID = UUID.fromString("0000FA01-0000-1000-8000-00805F9B34FB")
    val PROFILE_CHAR: UUID = UUID.fromString("0000FA02-0000-1000-8000-00805F9B34FB")
    val CONTROL_CHAR: UUID = UUID.fromString("0000FA03-0000-1000-8000-00805F9B34FB")
    val MESSAGE_CHAR: UUID = UUID.fromString("0000FA04-0000-1000-8000-00805F9B34FB")
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
