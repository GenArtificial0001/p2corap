package com.radar.relay

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Enlace por internet: un WebSocket al relé. Cada mensaje binario es un MeshPacket completo
 * (ya cifrado), sin fragmentar. El relé solo los reenvía a los demás miembros de la sala.
 *
 * Todos los callbacks se entregan en el hilo principal. Reintenta con espera creciente
 * mientras el usuario no lo desactive.
 */
class RelayClient(
    private val onPacket: (ByteArray) -> Unit,
    /** Cambió el estado de la conexión o la cantidad de miembros. */
    private val onChange: () -> Unit = {}
) {

    enum class State { OFF, CONNECTING, CONNECTED, RETRYING }

    private val main = Handler(Looper.getMainLooper())

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow(State.OFF)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Personas en la sala del relé, contándome a mí (lo informa el servidor). */
    private val _members = MutableStateFlow(0)
    val members: StateFlow<Int> = _members.asStateFlow()

    private var socket: WebSocket? = null
    private var url: String? = null
    private var roomId: String? = null
    private var token: String? = null
    private var attempt = 0
    private var generation = 0

    private val retry = Runnable { openSocket() }

    val isActive: Boolean get() = url != null

    /** Backlog aproximado en bytes del WebSocket, para la contrapresión del emisor de medios. */
    fun queuedBytes(): Long = socket?.queueSize() ?: 0L

    fun connect(relayUrl: String, roomCode: String, accessToken: String? = null) {
        disconnect()
        url = relayUrl
        roomId = RelayRoom.idFor(roomCode)
        token = accessToken?.takeIf { it.isNotBlank() }
        attempt = 0
        openSocket()
    }

    /** Cambia de sala (clave nueva) manteniendo el relé. */
    fun switchRoom(roomCode: String) {
        val current = url ?: return
        connect(current, roomCode, token)
    }

    fun disconnect() {
        generation++
        main.removeCallbacks(retry)
        socket?.let { runCatching { it.close(1000, "bye") } }
        socket = null
        url = null
        roomId = null
        _members.value = 0
        _state.value = State.OFF
        onChange()
    }

    fun send(packet: ByteArray): Boolean {
        val s = socket ?: return false
        if (_state.value != State.CONNECTED) return false
        return s.send(ByteString.of(*packet))
    }

    private fun openSocket() {
        val base = url ?: return
        val room = roomId ?: return
        val gen = generation

        val separator = if ('?' in base) "&" else "?"
        var target = "$base${separator}room=$room"
        token?.let { target += "&token=" + java.net.URLEncoder.encode(it, "UTF-8") }

        _state.value = if (attempt == 0) State.CONNECTING else State.RETRYING
        val request = try {
            Request.Builder().url(target).build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "URL de relé inválida", e)
            disconnect()
            return
        }

        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    if (gen != generation) return@post
                    attempt = 0
                    _state.value = State.CONNECTED
                    onChange()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                main.post { if (gen == generation) onPacket(data) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Texto = control del servidor (presencia).
                val peers = runCatching {
                    val json = JSONObject(text)
                    if (json.optString("t") == "presence") json.optInt("peers", 0) else -1
                }.getOrDefault(-1)
                if (peers >= 0) {
                    main.post {
                        if (gen == generation) {
                            _members.value = peers
                            onChange()
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                main.post { if (gen == generation) scheduleRetry() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relé caído: ${t.message} (${response?.code})")
                main.post { if (gen == generation) scheduleRetry() }
            }
        })
    }

    private fun scheduleRetry() {
        socket = null
        _members.value = 0
        _state.value = State.RETRYING
        onChange()
        attempt++
        val delay = minOf(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl minOf(attempt - 1, 4))
        main.removeCallbacks(retry)
        main.postDelayed(retry, delay)
    }

    private companion object {
        const val TAG = "RadarRelay"
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
    }
}
