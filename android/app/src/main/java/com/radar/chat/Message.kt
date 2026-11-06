package com.radar.chat

/** Un mensaje de la sesión. Vive únicamente en RAM. */
data class Message(
    /** messageId de la malla, para poder marcarlo como entregado. */
    val id: Long,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    /** Saltos de malla que recorrió. 0 = escrito en este dispositivo. */
    val hops: Int = 0,
    /** ACK recibido del otro extremo. */
    val delivered: Boolean = false
)
