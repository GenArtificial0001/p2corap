package com.radar.session

import android.content.Context
import com.radar.ble.ControlOpcode
import com.radar.ble.Fragmenter
import com.radar.ble.MessageAssembler
import com.radar.ble.RadarBleManager
import com.radar.ble.RadarGattClient
import com.radar.ble.RadarGattServer
import com.radar.chat.VolatileChatRepository
import com.radar.mesh.MeshPacket
import com.radar.mesh.MeshRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.random.Random

/** Estado observable de la malla local. */
data class MeshStats(
    val links: Int = 0,
    val delivered: Int = 0,
    val relayed: Int = 0,
    val maxHop: Int = 0,
    val pending: Int = 0
)

/**
 * Orquesta la malla: mantiene vínculos BLE simultáneos, enruta paquetes y
 * decide cuándo entregar y cuándo reenviar.
 *
 * Cada dispositivo puede ser Central (cliente GATT) de hasta [MAX_OUTGOING]
 * peers y Peripheral (servidor GATT) de varios más. El rol no es excluyente:
 * la malla se forma con vínculos punto a punto y el reenvío los encadena.
 */
class RadarSessionManager(context: Context) {

    private val appContext = context.applicationContext

    val ble = RadarBleManager(appContext)
    val chat = VolatileChatRepository()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    /** Identidad efímera del proceso: no se persiste (Zero-Storage). */
    private val selfId: Int = Random.nextInt(1, Int.MAX_VALUE)

    private val router = MeshRouter(selfId = selfId)

    private var server: RadarGattServer? = null

    /** Vínculos salientes: somos Central de ese peer. */
    private val clients = mutableMapOf<String, RadarGattClient>()

    /** Todos los peers con vínculo activo, en cualquier dirección. */
    private val peers = mutableSetOf<String>()

    /** Un ensamblador por link: los fragmentos de distintos peers no se mezclan. */
    private val assemblers = mutableMapOf<String, MessageAssembler>()

    var alias: String = "Radar"
        set(value) {
            field = value.ifBlank { "Radar" }.take(31)
        }

    // ------------------------------------------------------------------ ciclo

    fun start() {
        ensureServer()
        ble.startAdvertising()
        ble.startScanning()
        updateStats()
    }

    fun stop() {
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { server?.close() }
        server = null
        peers.clear()
        assemblers.clear()
        chat.purgeSession()
        ble.stopAll()
        updateStats()
    }

    // --------------------------------------------------------------- vínculos

    fun connect(address: String) {
        if (address in clients) return
        if (address in peers) return
        if (clients.size >= MAX_OUTGOING) return

        val gattClient = RadarGattClient(
            context = appContext,
            onReady = {
                peers.add(address)
                chat.setConnected(true)
                updateStats()
                flushPendingTo(address)
                clients[address]?.sendControl(byteArrayOf(ControlOpcode.CONNECT_REQUEST))
            },
            onDisconnected = { onLinkLost(address) },
            onProfile = { payload -> chat.setPeerAlias(parseAlias(payload)) },
            onControl = { payload -> onClientControl(address, payload) },
            onMessage = { payload -> onFrame(address, payload) }
        )
        clients[address] = gattClient
        gattClient.connect(address)
        updateStats()
    }

    private fun onPeerUp(address: String) {
        peers.add(address)
        chat.setConnected(true)
        updateStats()
        flushPendingTo(address)
    }

    private fun onLinkLost(address: String) {
        clients.remove(address)?.let { runCatching { it.close() } }
        peers.remove(address)
        assemblers.remove(address)
        if (peers.isEmpty()) {
            chat.purgeSession()
        }
        updateStats()
    }

    // -------------------------------------------------------------- mensajes

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (peers.isEmpty()) return

        val packet = router.origin(trimmed)
        chat.append(trimmed, isMine = true, hops = 0)
        broadcast(packet.encode(), exclude = null)
        updateStats()
    }

    private fun onFrame(address: String, frame: ByteArray) {
        val assembler = assemblers.getOrPut(address) { MessageAssembler() }
        val raw = assembler.accept(frame) ?: return
        val packet = MeshPacket.decode(raw) ?: return

        when (val decision = router.onReceive(packet)) {
            is MeshRouter.Decision.Drop -> Unit

            is MeshRouter.Decision.Relay -> {
                if (decision.deliver) {
                    chat.append(packet.text(), isMine = false, hops = packet.hopCount)
                    _stats.update {
                        it.copy(
                            delivered = it.delivered + 1,
                            maxHop = maxOf(it.maxHop, packet.hopCount)
                        )
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
            it.copy(links = peers.size, pending = router.pendingCount())
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
        if (gattServer.open()) server = gattServer
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
        val name = alias.toByteArray(Charsets.UTF_8).take(31).toByteArray()
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

        /**
         * Conexiones salientes simultáneas. Android soporta varias, pero cada
         * una consume stack y batería; más de 3 degrada el throughput.
         */
        const val MAX_OUTGOING = 3
    }
}
