package com.radar.session

import android.content.Context
import com.radar.RadarForegroundService
import com.radar.ble.ControlOpcode
import com.radar.ble.Fragmenter
import com.radar.ble.MessageAssembler
import com.radar.ble.RadarBleManager
import com.radar.ble.RadarGattClient
import com.radar.ble.RadarGattServer
import com.radar.chat.VolatileChatRepository
import com.radar.crypto.MeshCrypto
import com.radar.crypto.RadarCrypto
import com.radar.mesh.MeshPacket
import com.radar.mesh.MeshRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.random.Random

/** Estado observable de la malla local. */
data class MeshStats(
    val links: Int = 0,
    val delivered: Int = 0,
    val relayed: Int = 0,
    val maxHop: Int = 0,
    val pending: Int = 0,
    /** Paquetes reenviados que no pudimos descifrar (otra sala o manipulación). */
    val undecryptable: Int = 0,
    /** ACKs recibidos de mensajes propios. */
    val acked: Int = 0,
    /** Conexiones esperando un slot libre. */
    val queued: Int = 0
)

/**
 * Orquesta la malla: vínculos BLE simultáneos, cifrado, ACKs y reenvío.
 *
 * Cada dispositivo puede ser Central (cliente GATT) de hasta [MAX_OUTGOING]
 * peers y Peripheral (servidor GATT) de varios más. El rol no es excluyente.
 */
class RadarSessionManager(context: Context) {

    private val appContext = context.applicationContext

    val ble = RadarBleManager(appContext)
    val chat = VolatileChatRepository()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    /** Radar encendido y con el servicio en primer plano activo. */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Dirección a la que se está intentando conectar, para feedback en la UI. */
    private val _connectingTo = MutableStateFlow<String?>(null)
    val connectingTo: StateFlow<String?> = _connectingTo.asStateFlow()

    /** Último error de conexión, visible para el usuario. */
    private val _linkError = MutableStateFlow<String?>(null)
    val linkError: StateFlow<String?> = _linkError.asStateFlow()

    private val crypto = MeshCrypto()

    /** Sala pública de fábrica: todos comparten la misma clave al iniciar. */
    private val _roomCode = MutableStateFlow(RadarCrypto.PUBLIC_ROOM_CODE)
    val roomCode: StateFlow<String> = _roomCode.asStateFlow()

    private val _alias = MutableStateFlow("Radar")
    val alias: StateFlow<String> = _alias.asStateFlow()

    /** Identidad efímera del proceso: no se persiste (Zero-Storage). */
    private val selfId: Int = Random.nextInt(1, Int.MAX_VALUE)

    private val router = MeshRouter(selfId = selfId)

    /** Direcciones a las que ya se está intentando conectar (evita doble intento simultáneo). */
    private val connecting = mutableSetOf<String>()

    init {
        // Todo el mundo empieza en la misma sala pública para poder hablar de inmediato.
        crypto.join(RadarCrypto.PUBLIC_ROOM_CODE)
    }

    private var server: RadarGattServer? = null

    /** Vínculos salientes: somos Central de ese peer. */
    private val clients = mutableMapOf<String, RadarGattClient>()

    /** Todos los peers con vínculo activo, en cualquier dirección. */
    private val peers = mutableSetOf<String>()

    /** Direcciones descubiertas esperando que se libere un slot. */
    private val waiting = ArrayDeque<String>()

    /** Un ensamblador por link: los fragmentos de distintos peers no se mezclan. */
    private val assemblers = mutableMapOf<String, MessageAssembler>()

    // ------------------------------------------------------------------ ciclo

    fun start() {
        _running.value = true
        _linkError.value = null
        ble.clearError()
        ensureServer()
        RadarForegroundService.start(appContext)
        ble.startAdvertising()
        ble.startScanning()
        updateStats()
    }

    fun stop() {
        _running.value = false
        _connectingTo.value = null
        _linkError.value = null
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        connecting.clear()
        waiting.clear()
        runCatching { server?.close() }
        server = null
        peers.clear()
        assemblers.clear()
        chat.purgeSession()
        ble.close()
        RadarForegroundService.stop(appContext)
        updateStats()
    }

    // --------------------------------------------------------------- vínculos

    fun connect(address: String) {
        _linkError.value = null
        if (address in clients || address in peers || address in connecting) return

        if (clients.size >= MAX_OUTGOING) {
            _linkError.value = "Ya hay $MAX_OUTGOING conexiones; esperá un momento"
            if (address !in waiting) waiting.addLast(address)
            updateStats()
            return
        }
        openClient(address)
    }

    private fun openClient(address: String) {
        connecting.add(address)
        _connectingTo.value = address

        val gattClient = RadarGattClient(
            context = appContext,
            onReady = {
                connecting.remove(address)
                _connectingTo.value = null
                _linkError.value = null
                peers.add(address)
                chat.setConnected(true)
                updateStats()
                flushPendingTo(address)
                clients[address]?.sendControl(byteArrayOf(ControlOpcode.CONNECT_REQUEST))
            },
            onDisconnected = {
                val wasPeer = address in peers
                connecting.remove(address)
                _connectingTo.value = null
                if (!wasPeer) {
                    _linkError.value = "No se pudo conectar con $address"
                }
                onLinkLost(address)
            },
            onProfile = { payload -> chat.setPeerAlias(parseAlias(payload)) },
            onControl = { payload -> onClientControl(address, payload) },
            onMessage = { payload -> onFrame(address, payload) }
        )
        clients[address] = gattClient
        gattClient.connect(address)
        updateStats()
    }

    private fun onPeerUp(address: String) {
        _linkError.value = null
        peers.add(address)
        chat.setConnected(true)
        updateStats()
        flushPendingTo(address)
    }

    private fun onLinkLost(address: String) {
        clients.remove(address)?.let { runCatching { it.close() } }
        peers.remove(address)
        assemblers.remove(address)
        waiting.remove(address)

        if (peers.isEmpty()) {
            chat.purgeSession()
        }

        // Si había gente esperando slot, entra la siguiente.
        while (clients.size < MAX_OUTGOING && waiting.isNotEmpty()) {
            val next = waiting.removeFirst()
            if (next !in clients && next !in peers) {
                openClient(next)
                break
            }
        }
        updateStats()
    }

    // -------------------------------------------------------------- mensajes

    fun send(text: String) {
        val trimmed = text.trim().take(MAX_PLAINTEXT_CHARS)
        if (trimmed.isEmpty() || peers.isEmpty()) return

        val payload = crypto.encrypt(trimmed.toByteArray(Charsets.UTF_8)) ?: return
        val packet = router.origin(payload)

        chat.append(packet.messageId, trimmed, isMine = true, hops = 0)
        broadcast(packet.encode(), exclude = null)
        updateStats()
    }

    /** Cambia la sala actual. Devuelve false si el código es inválido. */
    fun joinRoom(code: String): Boolean {
        val joined = crypto.join(code)
        if (joined) {
            _roomCode.value = crypto.currentCode()
            chat.purgeSession()
        }
        return joined
    }

    /** Genera una sala nueva con código aleatorio. */
    fun newRoom() {
        _roomCode.value = crypto.startNewRoom()
        chat.purgeSession()
    }

    fun setAlias(value: String) {
        _alias.value = value.ifBlank { "Radar" }.take(31)
    }

    private fun onFrame(address: String, frame: ByteArray) {
        val assembler = assemblers.getOrPut(address) { MessageAssembler() }
        val raw = assembler.accept(frame) ?: return
        val packet = MeshPacket.decode(raw) ?: return

        when (val decision = router.onReceive(packet)) {
            is MeshRouter.Decision.Drop -> Unit

            is MeshRouter.Decision.Relay -> {
                if (decision.deliver) {
                    when (packet.type) {
                        MeshPacket.TYPE_TEXT -> onText(address, packet)
                        MeshPacket.TYPE_ACK -> onAck(packet)
                    }
                }

                decision.forward?.let { next ->
                    broadcast(next.encode(), exclude = address)
                    _stats.update { it.copy(relayed = it.relayed + 1) }
                }
            }
        }

        updateStats()
    }

    private fun onText(address: String, packet: MeshPacket) {
        val decrypted = crypto.decrypt(packet.payload)
        if (decrypted == null) {
            _stats.update { it.copy(undecryptable = it.undecryptable + 1) }
            return
        }

        chat.append(
            packet.messageId,
            String(decrypted, Charsets.UTF_8),
            isMine = false,
            hops = packet.hopCount
        )
        _stats.update {
            it.copy(
                delivered = it.delivered + 1,
                maxHop = maxOf(it.maxHop, packet.hopCount)
            )
        }

        // Confirmar al vecino del que vino; los relays lo propagan de vuelta.
        val ack = router.originAck(packet.messageId)
        sendFrames(address, Fragmenter.fragment(ack.encode()))
    }

    private fun onAck(packet: MeshPacket) {
        if (packet.payload.size < 8) return
        val acked = ByteBuffer.wrap(packet.payload).long
        chat.markDelivered(acked)
        _stats.update { it.copy(acked = it.acked + 1) }
    }

    // ------------------------------------------------------------ transporte

    private fun broadcast(payload: ByteArray, exclude: String?) {
        if (peers.isEmpty()) return
        val frames = Fragmenter.fragment(payload)
        peers.filter { it != exclude }.forEach { address ->
            sendFrames(address, frames)
        }
    }

    private fun sendFrames(address: String, frames: List<ByteArray>) {
        val client = clients[address]
        if (client != null) {
            client.send(frames)
            return
        }
        val gattServer = server ?: return
        frames.forEach { gattServer.notifyMessage(address, it) }
    }

    /** Store-and-forward: entrega el historial reciente a un vecino nuevo. */
    private fun flushPendingTo(address: String) {
        val pending = router.pendingSnapshot()
        if (pending.isEmpty()) return
        pending.forEach { packet ->
            sendFrames(address, Fragmenter.fragment(packet.encode()))
        }
        updateStats()
    }

    private fun updateStats() {
        _stats.update {
            it.copy(
                links = peers.size,
                pending = router.pendingCount(),
                queued = waiting.size
            )
        }
    }

    // ------------------------------------------------------------------ GATT

    private fun ensureServer() {
        if (server != null) return
        val gattServer = RadarGattServer(
            context = appContext,
            profileProvider = ::profilePayload,
            onControl = ::onServerControl,
            onMessage = { address, payload -> onFrame(address, payload) },
            onSubscribed = ::onPeerUp,
            onDisconnected = ::onLinkLost
        )
        if (gattServer.open()) {
            server = gattServer
        } else {
            _linkError.value = "Este dispositivo no pudo abrir el servidor BLE"
        }
    }

    private fun onServerControl(address: String, payload: ByteArray) {
        when (payload.firstOrNull()) {
            ControlOpcode.CONNECT_REQUEST -> {
                if (address !in peers) peers.add(address)
                chat.setConnected(true)
                server?.notifyMessage(address, byteArrayOf(ControlOpcode.ACCEPT))
                updateStats()
            }

            ControlOpcode.BYE -> onLinkLost(address)
            else -> Unit
        }
    }

    private fun onClientControl(address: String, payload: ByteArray) {
        when (payload.firstOrNull()) {
            ControlOpcode.REJECT, ControlOpcode.BYE -> onLinkLost(address)
            else -> Unit
        }
    }

    /** [len][alias UTF-8][avatarId] */
    private fun profilePayload(): ByteArray {
        val name = _alias.value.toByteArray(Charsets.UTF_8).take(31).toByteArray()
        return byteArrayOf(name.size.toByte()) + name + byteArrayOf(AVATAR_ID)
    }

    private fun parseAlias(payload: ByteArray): String? {
        if (payload.isEmpty()) return null
        val length = payload[0].toInt() and 0xFF
        if (length == 0 || payload.size < length + 1) return null
        return String(payload, 1, length, Charsets.UTF_8)
    }

    private companion object {
        const val AVATAR_ID: Byte = 1

        /** Deja margen para nonce + tag GCM dentro del payload de 512 bytes. */
        const val MAX_PLAINTEXT_CHARS = 400

        /**
         * Conexiones salientes simultáneas. Android soporta varias, pero cada
         * una consume stack y batería; más de 3 degrada el throughput.
         */
        const val MAX_OUTGOING = 3
    }
}
