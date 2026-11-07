package com.radar.chat

/** Qué contiene un mensaje. */
enum class MessageKind { TEXT, STICKER, IMAGE, AUDIO, VIDEO }

/** Un mensaje de la sesión. Vive únicamente en RAM. */
data class Message(
    /** messageId de la malla (o transferId en medios), para poder marcarlo como entregado. */
    val id: Long,
    val text: String,
    val isMine: Boolean,
    val timestamp: Long,
    /** Saltos de malla que recorrió. 0 = escrito en este dispositivo. */
    val hops: Int = 0,
    /** ACK recibido del otro extremo. */
    val delivered: Boolean = false,
    val kind: MessageKind = MessageKind.TEXT,
    /** Id del sticker en [com.radar.media.StickerCatalog] (solo si [kind] es STICKER). */
    val stickerId: Int = 0,
    /** Foto (JPEG), audio (ADPCM) o video (MP4) ya completo; null mientras llega o si se liberó. */
    val media: ByteArray? = null,
    val durationMs: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    /** 0..1 mientras se transfiere; 1 cuando terminó. */
    val progress: Float = 1f,
    /** La transferencia no terminó (cortada, cancelada por tiempo o rechazada). */
    val failed: Boolean = false,
    /** Los bytes se liberaron para no agotar la RAM. */
    val released: Boolean = false
) {
    val inTransfer: Boolean get() = kind.isMedia && progress < 1f && !failed

    // El contenido de `media` puede ser grande: la igualdad y el hash usan solo identidad y estado.
    override fun equals(other: Any?): Boolean =
        other is Message && other.id == id && other.isMine == isMine && other.delivered == delivered &&
            other.progress == progress && other.failed == failed && other.released == released &&
            other.hops == hops && (other.media?.size ?: -1) == (media?.size ?: -1)

    override fun hashCode(): Int = id.hashCode()
}

val MessageKind.isMedia: Boolean
    get() = this == MessageKind.IMAGE || this == MessageKind.AUDIO || this == MessageKind.VIDEO
