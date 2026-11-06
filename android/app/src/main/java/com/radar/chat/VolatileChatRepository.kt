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
        _messages.update {
            it + Message(id, text, isMine, System.currentTimeMillis(), hops, delivered = false)
        }
    }

    /** Marca como entregado el mensaje propio cuyo ACK llegó. */
    fun markDelivered(id: Long) {
        _messages.update { list ->
            list.map { if (it.isMine && it.id == id) it.copy(delivered = true) else it }
        }
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
}
