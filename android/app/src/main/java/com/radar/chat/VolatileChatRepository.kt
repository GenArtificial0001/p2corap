package com.radar.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Única fuente de verdad del chat: RAM y nada más.
 *
 * No hay DAO, ni SharedPreferences, ni SavedStateHandle. Si el proceso muere
 * o se cae el último vínculo, la conversación desaparece. Eso es el producto.
 *
 * Nota (R-05): en ART no se puede "sobrescribir" un String ni forzar el GC.
 * La purga consiste en soltar las referencias; el GC hace el resto.
 */
class VolatileChatRepository {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _peerAlias = MutableStateFlow<String?>(null)
    val peerAlias: StateFlow<String?> = _peerAlias.asStateFlow()

    fun append(id: Long, text: String, isMine: Boolean, hops: Int = 0) {
        add(Message(id, text, isMine, System.currentTimeMillis(), hops, delivered = false))
    }

    fun appendSticker(id: Long, stickerId: Int, isMine: Boolean, hops: Int = 0) {
        add(
            Message(
                id = id,
                text = "",
                isMine = isMine,
                timestamp = System.currentTimeMillis(),
                hops = hops,
                kind = MessageKind.STICKER,
                stickerId = stickerId
            )
        )
    }

    /**
     * Mensaje de medio en curso. El emisor ya tiene los bytes ([media] != null);
     * el receptor los recibirá con [completeMedia].
     */
    fun appendMedia(
        id: Long,
        kind: MessageKind,
        isMine: Boolean,
        media: ByteArray?,
        durationMs: Int,
        width: Int,
        height: Int,
        progress: Float
    ) {
        add(
            Message(
                id = id,
                text = "",
                isMine = isMine,
                timestamp = System.currentTimeMillis(),
                kind = kind,
                media = media,
                durationMs = durationMs,
                width = width,
                height = height,
                progress = progress
            )
        )
    }

    fun updateProgress(id: Long, progress: Float) {
        update(id) { it.copy(progress = progress.coerceIn(0f, 1f)) }
    }

    /** El receptor terminó de recibir: se guardan los bytes. */
    fun completeMedia(id: Long, bytes: ByteArray) {
        update(id) { it.copy(media = bytes, progress = 1f, failed = false) }
        trimMedia()
    }

    fun failMedia(id: Long) {
        update(id) { if (it.progress < 1f) it.copy(failed = true) else it }
    }

    fun remove(id: Long) {
        _messages.update { list -> list.filterNot { it.id == id } }
    }

    /** Marca como entregado el mensaje propio cuyo ACK llegó. */
    fun markDelivered(id: Long) {
        update(id) { if (it.isMine) it.copy(delivered = true) else it }
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
    }

    fun setPeerAlias(alias: String?) {
        _peerAlias.value = alias
    }

    /**
     * Borra los mensajes pero conserva el estado de conexión. Se usa al cambiar
     * de sala: los vínculos siguen vivos, solo cambia la clave. (Antes se
     * llamaba [purgeSession] y la UI creía que ya no había nadie conectado.)
     */
    fun clearMessages() {
        _messages.value = emptyList()
    }

    /** Purga total. Se llama cuando no queda ningún vínculo. */
    fun purgeSession() {
        _messages.value = emptyList()
        _connected.value = false
        _peerAlias.value = null
    }

    private fun add(message: Message) {
        _messages.update { it + message }
        trimMedia()
    }

    private fun update(id: Long, transform: (Message) -> Message) {
        _messages.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }

    /**
     * Tope de RAM para medios retenidos: al superarlo se liberan los más antiguos (el mensaje
     * queda como «contenido liberado»). Sin esto, una sala activa llenaría la memoria de a poco.
     */
    private fun trimMedia() {
        _messages.update { list ->
            var total = list.sumOf { it.media?.size ?: 0 }
            if (total <= MAX_MEDIA_BYTES) return@update list
            list.map { message ->
                val size = message.media?.size ?: 0
                if (total > MAX_MEDIA_BYTES && size > 0 && message.progress >= 1f) {
                    total -= size
                    message.copy(media = null, released = true)
                } else {
                    message
                }
            }
        }
    }

    companion object {
        /** ≈ 6 MB: 60 fotos, o ~10 videos cortos. */
        const val MAX_MEDIA_BYTES = 6 * 1024 * 1024
    }
}
