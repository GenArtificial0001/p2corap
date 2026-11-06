package com.radar.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Única fuente de verdad del chat: RAM y nada más.
 *
 * No hay DAO, ni SharedPreferences, ni SavedStateHandle. Si el proceso muere
 * o el vínculo GATT cae, la conversación desaparece. Eso es el producto.
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

    fun append(text: String, isMine: Boolean) {
        _messages.update { it + Message(text, isMine, System.currentTimeMillis()) }
    }

    fun setConnected(value: Boolean) {
        _connected.value = value
        if (!value) _peerAlias.value = null
    }

    fun setPeerAlias(alias: String?) {
        _peerAlias.value = alias
    }

    /** Purga total. Se llama al perder la conexión. */
    fun purgeSession() {
        _messages.value = emptyList()
        _connected.value = false
        _peerAlias.value = null
    }
}
