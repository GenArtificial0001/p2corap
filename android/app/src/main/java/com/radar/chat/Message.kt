package com.radar.chat

/** Un mensaje de la sesión. Vive únicamente en RAM. */
data class Message(
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    /** Saltos de malla que recorrió. 0 = escrito en este dispositivo. */
    val hops: Int = 0
)
