package com.radar.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.radar.RadarForegroundService
import com.radar.ble.ControlOpcode
import com.radar.ble.Fragmenter
import com.radar.ble.MessageAssembler
import com.radar.ble.RadarBleManager
import com.radar.ble.RadarGattClient
import com.radar.ble.RadarGattServer
import com.radar.chat.MessageKind
import com.radar.chat.TextLimit
import com.radar.chat.VolatileChatRepository
import com.radar.crypto.MeshCrypto
import com.radar.crypto.RadarCrypto
import com.radar.lan.LanLink
import com.radar.media.MediaChunk
import com.radar.media.MediaHeader
import com.radar.media.MediaKind
import com.radar.media.MediaReassembler
import com.radar.media.StickerCatalog
import com.radar.mesh.MeshPacket
import com.radar.mesh.MeshRouter
import com.radar.relay.InviteLink
import com.radar.relay.RelayClient
import com.radar.relay.RelayRoom
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
    val queued: Int = 0,
    /** Direcciones con vínculo activo (para marcar «Vinculado» en la lista de pares). */
    val linkedAddresses: Set<String> = emptySet()
)

/** Resultado de intentar enviar un medio. */
enum class SendResult { OK, NO_PEERS, TOO_BIG, BUSY, ERROR }

/**
 * Orquesta la malla: vínculos BLE simultáneos, enlace por internet (relé), cifrado, ACKs y reenvío.
 *
 * Cada dispositivo puede ser Central (cliente GATT) de hasta [MAX_OUTGOING]
 * peers y Peripheral (servidor GATT) de varios más. El rol no es excluyente.
 *
 * El relé de internet se trata como un vecino más (dirección [RELAY]): los paquetes que llegan por
 * él se reenvían a los vecinos BLE y viceversa, así que Bluetooth e internet forman una sola malla.
 *
 * Hilos: todo corre en el hilo principal (los callbacks BLE y de red se reenvían a él).
 */
class RadarSessionManager(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

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

    // ------------------------------------------------------------- internet

    private val relay = RelayClient(
        onPacket = { bytes -> onPacket(RELAY, bytes) },
        onChange = { relayPresenceChanged() }
    )

    val relayState: StateFlow<RelayClient.State> = relay.state

    /** Personas en la sala del relé (yo incluido). */
    val relayMembers: StateFlow<Int> = relay.members

    private val _relayUrl = MutableStateFlow<String?>(null)

    /** URL `wss://…/ws` del relé activo, o null si el enlace por internet está apagado. */
    val relayUrl: StateFlow<String?> = _relayUrl.asStateFlow()

    // ------------------------------------------------------- Wi-Fi local (sin servidor)

    private val lan = LanLink(
        context = appContext,
        onPacket = { address, bytes -> onPacket(address, bytes) },
        onPeer = ::onLanPeer
    )

    /** Teléfonos conectados directo por la red local. */
    val lanPeers: StateFlow<Int> = lan.peers

    /** Wi-Fi local activo (buscando o conectado). */
    val lanRunning: StateFlow<Boolean> = lan.running

    // ---------------------------------------------------------------- medios

    private class Outgoing(val id: Long, val packets: ArrayDeque<ByteArray>, val total: Int)

    private val reassembler = MediaReassembler()
    private val outgoing = LinkedHashMap<Long, Outgoing>()
    private var pumpScheduled = false

    private val pumpTask = object : Runnable {
        override fun run() {
            pumpOnce()
            if (outgoing.isNotEmpty() || reassembler.activeCount > 0) {
                main.postDelayed(this, PUMP_INTERVAL_MS)
            } else {
                pumpScheduled = false
            }
        }
    }

    init {
        // Todo el mundo empieza en la misma sala pública para poder hablar de inmediato.
        crypto.join(RadarCrypto.PUBLIC_ROOM_CODE)
        ble.bluetoothListener = ::onBluetoothChanged
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
        resetTransfers()
        relay.disconnect()
        lan.stop()
        _relayUrl.value = null
        chat.purgeSession()
        ble.close()
        RadarForegroundService.stop(appContext)
        updateStats()
    }

    // --------------------------------------------------------------- vínculos

    /**
     * Bluetooth se apagó o volvió. Al apagarse mueren todos los GATT (cliente y
     * servidor): se sueltan para no quedar con vínculos fantasma y, al volver,
     * se reabre el servidor para poder recibir conexiones otra vez.
     */
    private fun onBluetoothChanged(on: Boolean) {
        if (!_running.value) return
        if (!on) {
            clients.values.forEach { runCatching { it.close() } }
            clients.clear()
            connecting.clear()
            waiting.clear()
            _connectingTo.value = null
            runCatching { server?.close() }
            server = null
            peers.removeAll { it != RELAY && !it.startsWith(LanLink.ADDRESS_PREFIX) }
            assemblers.clear()
            if (peers.isEmpty()) {
                resetTransfers()
                chat.purgeSession()
            }
        } else {
            ensureServer()
        }
        updateStats()
    }

    /** Cierra el vínculo con [address], sea cual sea el rol que tengamos. */
    fun disconnect(address: String) {
        if (address == RELAY) {
            disableInternet()
            return
        }
        val client = clients[address]
        if (client != null) {
            // close() corta el GATT; el otro extremo lo ve como desconexión normal.
            onLinkLost(address)
            return
        }
        server?.disconnect(address)
    }

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
            resetTransfers()
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

    // ------------------------------------------------------- Wi-Fi local

    /**
     * Activa el Wi-Fi local: encuentra a quien esté en la misma red **sin internet ni servidor**.
     * Funciona con un router o con un teléfono compartiendo su hotspot (caso sin electricidad).
     */
    fun enableLan() {
        lan.start(RelayRoom.idFor(crypto.currentCode()))
    }

    fun disableLan() {
        lan.stop()
    }

    private fun onLanPeer(address: String, up: Boolean) {
        if (up) {
            peers.add(address)
            chat.setConnected(true)
            updateStats()
            flushPendingTo(address)
        } else {
            peers.remove(address)
            if (peers.isEmpty()) {
                resetTransfers()
                chat.purgeSession()
            }
            updateStats()
        }
    }

    // ------------------------------------------------------- enlace internet

    /**
     * Activa el enlace por internet con el relé [url] (`wss://…`) en la sala actual.
     * @return false si la URL no es `wss://` válida.
     */
    fun enableInternet(url: String): Boolean {
        val parsed = InviteLink.parseRelay(url) ?: return false
        val normalized = parsed.toString()
        _relayUrl.value = normalized
        relay.connect(normalized, crypto.currentCode())
        return true
    }

    fun disableInternet() {
        relay.disconnect()
        _relayUrl.value = null
        relayPresenceChanged()
    }

    /** Enlace para invitar a alguien a esta sala por internet, o null si no hay relé activo. */
    fun inviteLink(): String? {
        val url = _relayUrl.value ?: return null
        return InviteLink.build(url, crypto.currentCode())
    }

    /**
     * Une [code] de un enlace de invitación. Con relé, activa también internet; sin relé, el
     * enlace es para Wi-Fi local: se activa la búsqueda en la red (sin servidor).
     */
    fun acceptInvite(relayUrl: String?, code: String): Boolean {
        if (relayUrl != null && InviteLink.parseRelay(relayUrl) == null) return false
        if (!joinRoom(code)) return false
        if (relayUrl == null) {
            enableLan()
            return true
        }
        return enableInternet(relayUrl)
    }

    /** Enlace de la sala para Wi-Fi local (no lleva servidor): `radar://join?code=…`. */
    fun roomLink(): String = InviteLink.appLink(null, crypto.currentCode())

    /**
     * El relé informa cuántos hay en la sala: con alguien más, cuenta como un vínculo.
     * Se llama en cada cambio de estado o de miembros.
     */
    private fun relayPresenceChanged() {
        val shouldLink = relay.state.value == RelayClient.State.CONNECTED && relay.members.value >= 2
        val isLinked = RELAY in peers
        if (shouldLink && !isLinked) {
            peers.add(RELAY)
            chat.setConnected(true)
            updateStats()
            flushPendingTo(RELAY)
        } else if (!shouldLink && isLinked) {
            peers.remove(RELAY)
            if (peers.isEmpty()) {
                resetTransfers()
                chat.purgeSession()
            }
            updateStats()
        }
    }

    // -------------------------------------------------------------- mensajes

    fun send(text: String) {
        // Se recorta por bytes UTF-8, no por caracteres: ver TextLimit.
        val trimmed = TextLimit.truncate(text.trim())
        if (trimmed.isEmpty() || peers.isEmpty()) return

        val payload = crypto.encrypt(trimmed.toByteArray(Charsets.UTF_8)) ?: return
        val packet = router.origin(payload)

        chat.append(packet.messageId, trimmed, isMine = true, hops = 0)
        broadcast(packet.encode(), exclude = null)
        updateStats()
    }

    /** Envía un sticker del catálogo: viaja solo su id (2 bytes cifrados). */
    fun sendSticker(stickerId: Int): Boolean {
        if (peers.isEmpty() || StickerCatalog.find(stickerId) == null) return false
        val plain = byteArrayOf((stickerId shr 8).toByte(), stickerId.toByte())
        val payload = crypto.encrypt(plain) ?: return false
        val packet = router.origin(payload, MeshPacket.TYPE_STICKER)

        chat.appendSticker(packet.messageId, stickerId, isMine = true)
        broadcast(packet.encode(), exclude = null)
        updateStats()
        return true
    }

    /**
     * Envía una foto, nota de voz o video ya comprimidos. Va en trozos cifrados, directo a los
     * vecinos (sin reenvío por la malla), con contrapresión: nunca llena la cola del radio.
     */
    fun sendMedia(
        kind: MediaKind,
        bytes: ByteArray,
        durationMs: Int = 0,
        width: Int = 0,
        height: Int = 0
    ): SendResult {
        if (peers.isEmpty()) return SendResult.NO_PEERS
        if (bytes.isEmpty() || bytes.size > kind.maxBytes) return SendResult.TOO_BIG
        if (outgoing.size >= MAX_CONCURRENT_SENDS) return SendResult.BUSY

        val id = Random.nextLong()
        val chunks = MediaChunk.split(id, bytes)
        val header = MediaHeader(kind, id, bytes.size, chunks.size, durationMs, width, height)

        val packets = ArrayDeque<ByteArray>(chunks.size + 1)
        fun enqueue(type: Int, plain: ByteArray): Boolean {
            val encrypted = crypto.encrypt(plain) ?: return false
            // ttl = 1: llega a los vecinos y no se reenvía (los medios no inundan la malla).
            packets.addLast(router.origin(encrypted, type, ttl = 1).encode())
            return true
        }
        if (!enqueue(MeshPacket.TYPE_MEDIA_HEADER, header.encode())) return SendResult.ERROR
        for (chunk in chunks) {
            if (!enqueue(MeshPacket.TYPE_MEDIA_CHUNK, chunk.encode())) return SendResult.ERROR
        }

        chat.appendMedia(
            id = id,
            kind = kind.toMessageKind(),
            isMine = true,
            media = bytes,
            durationMs = durationMs,
            width = width,
            height = height,
            progress = 0f
        )
        outgoing[id] = Outgoing(id, packets, packets.size)
        schedulePump()
        return SendResult.OK
    }

    /** Cancela una transferencia propia o recibida en curso. */
    fun cancelTransfer(id: Long) {
        outgoing.remove(id)
        reassembler.drop(id)
        chat.remove(id)
    }

    /** Cambia la sala actual. Devuelve false si el código es inválido. */
    fun joinRoom(code: String): Boolean {
        val joined = crypto.join(code)
        if (joined) {
            onRoomChanged()
        }
        return joined
    }

    /** Genera una sala nueva con código aleatorio. */
    fun newRoom() {
        _roomCode.value = crypto.startNewRoom()
        onRoomChanged()
    }

    private fun onRoomChanged() {
        _roomCode.value = crypto.currentCode()
        // Los vínculos siguen vivos: solo se borran los mensajes de la sala anterior.
        resetTransfers()
        chat.clearMessages()
        // El relé identifica la sala por hash: hay que reconectar con la nueva.
        if (_relayUrl.value != null) relay.switchRoom(crypto.currentCode())
        // Lo mismo en la red local: el anuncio mDNS lleva el hash de la sala.
        if (lan.running.value) enableLan()
    }

    fun setAlias(value: String) {
        _alias.value = value.ifBlank { "Radar" }.take(31)
    }

    private fun onFrame(address: String, frame: ByteArray) {
        val assembler = assemblers.getOrPut(address) { MessageAssembler() }
        val raw = assembler.accept(frame) ?: return
        onPacket(address, raw)
    }

    /** Un paquete completo, venga de BLE (ya reensamblado) o del relé. */
    private fun onPacket(address: String, raw: ByteArray) {
        val packet = MeshPacket.decode(raw) ?: return

        when (val decision = router.onReceive(packet)) {
            is MeshRouter.Decision.Drop -> Unit

            is MeshRouter.Decision.Relay -> {
                if (decision.deliver) {
                    when (packet.type) {
                        MeshPacket.TYPE_TEXT -> onText(address, packet)
                        MeshPacket.TYPE_ACK -> onAck(packet)
                        MeshPacket.TYPE_STICKER -> onSticker(address, packet)
                        MeshPacket.TYPE_MEDIA_HEADER -> onMediaHeader(packet)
                        MeshPacket.TYPE_MEDIA_CHUNK -> onMediaChunk(address, packet)
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

    private fun decrypt(packet: MeshPacket): ByteArray? {
        val plain = crypto.decrypt(packet.payload)
        if (plain == null) _stats.update { it.copy(undecryptable = it.undecryptable + 1) }
        return plain
    }

    private fun onText(address: String, packet: MeshPacket) {
        val decrypted = decrypt(packet) ?: return

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

        acknowledge(address, packet.messageId)
    }

    private fun onSticker(address: String, packet: MeshPacket) {
        val plain = decrypt(packet) ?: return
        if (plain.size != 2) return
        val id = ((plain[0].toInt() and 0xFF) shl 8) or (plain[1].toInt() and 0xFF)
        if (StickerCatalog.find(id) == null) return

        chat.appendSticker(packet.messageId, id, isMine = false, hops = packet.hopCount)
        _stats.update { it.copy(delivered = it.delivered + 1, maxHop = maxOf(it.maxHop, packet.hopCount)) }
        acknowledge(address, packet.messageId)
    }

    private fun onMediaHeader(packet: MeshPacket) {
        val plain = decrypt(packet) ?: return
        val header = MediaHeader.decode(plain) ?: return
        if (reassembler.onHeader(header) !is MediaReassembler.Event.Started) return

        chat.appendMedia(
            id = header.transferId,
            kind = header.kind.toMessageKind(),
            isMine = false,
            media = null,
            durationMs = header.durationMs,
            width = header.width,
            height = header.height,
            progress = 0f
        )
        schedulePump() // mantiene vivo el reloj que descarta transferencias estancadas
    }

    private fun onMediaChunk(address: String, packet: MeshPacket) {
        val plain = decrypt(packet) ?: return
        val chunk = MediaChunk.decode(plain) ?: return

        when (val event = reassembler.onChunk(chunk)) {
            is MediaReassembler.Event.Progress ->
                chat.updateProgress(event.transferId, event.fraction)

            is MediaReassembler.Event.Complete -> {
                chat.completeMedia(event.header.transferId, event.bytes)
                _stats.update { it.copy(delivered = it.delivered + 1) }
                acknowledge(address, event.header.transferId)
            }

            else -> Unit
        }
    }

    /** Confirma la entrega al vecino del que vino; los relays lo propagan de vuelta. */
    private fun acknowledge(address: String, ackFor: Long) {
        sendPacket(address, router.originAck(ackFor).encode())
    }

    private fun onAck(packet: MeshPacket) {
        if (packet.payload.size < 8) return
        val acked = ByteBuffer.wrap(packet.payload).long
        chat.markDelivered(acked)
        _stats.update { it.copy(acked = it.acked + 1) }
    }

    // ------------------------------------------------------------ transporte

    /** Bytes de payload de fragmento que admite el vínculo BLE con [address]. */
    private fun fragmentPayload(address: String): Int =
        clients[address]?.fragmentPayload
            ?: server?.fragmentPayload(address)
            ?: Fragmenter.MIN_PAYLOAD

    /** Envía un paquete completo por el vínculo que corresponda (BLE fragmentado, o relé tal cual). */
    private fun sendPacket(address: String, packet: ByteArray) {
        if (address == RELAY) {
            relay.send(packet)
            return
        }
        if (address.startsWith(LanLink.ADDRESS_PREFIX)) {
            lan.send(address, packet)
            return
        }
        val frames = try {
            Fragmenter.fragment(packet, fragmentPayload(address))
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Paquete no enviable por BLE: ${e.message}")
            return
        }
        sendFrames(address, frames)
    }

    private fun broadcast(packet: ByteArray, exclude: String?) {
        if (peers.isEmpty()) return
        // Copia: sendPacket puede alterar `peers` indirectamente (vínculo que cae).
        peers.filter { it != exclude }.forEach { address -> sendPacket(address, packet) }
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

    /** Fragmentos (o su equivalente) esperando salir por [address]. */
    private fun queuedFor(address: String): Int = when {
        address == RELAY -> (relay.queuedBytes() / RELAY_BYTES_PER_FRAME).toInt()
        address.startsWith(LanLink.ADDRESS_PREFIX) -> (lan.queuedBytes(address) / RELAY_BYTES_PER_FRAME).toInt()
        else -> clients[address]?.queuedFrames() ?: server?.queuedFrames(address) ?: 0
    }

    /** Store-and-forward: entrega el historial reciente a un vecino nuevo. */
    private fun flushPendingTo(address: String) {
        val pending = router.pendingSnapshot()
        if (pending.isEmpty()) return
        pending.forEach { packet -> sendPacket(address, packet.encode()) }
        updateStats()
    }

    // --------------------------------------------------- transferencias de medios

    private fun schedulePump() {
        if (pumpScheduled) return
        pumpScheduled = true
        main.postDelayed(pumpTask, PUMP_INTERVAL_MS)
    }

    private fun pumpOnce() {
        reassembler.expire().forEach { chat.failMedia(it) }
        if (outgoing.isEmpty()) return

        if (peers.isEmpty()) {
            outgoing.keys.forEach { chat.failMedia(it) }
            outgoing.clear()
            return
        }

        // Contrapresión: no se encola más mientras algún vínculo tenga mucho pendiente.
        if (peers.maxOf { queuedFor(it) } >= HIGH_WATER_FRAMES) return

        val transfer = outgoing.values.first()
        val next = transfer.packets.pollFirst()
        if (next == null) {
            outgoing.remove(transfer.id)
            return
        }

        broadcast(next, exclude = null)
        val sent = transfer.total - transfer.packets.size
        chat.updateProgress(transfer.id, sent.toFloat() / transfer.total)
        if (transfer.packets.isEmpty()) outgoing.remove(transfer.id)
    }

    /** Descarta toda transferencia en curso (se cambió de sala o se cayó el último vínculo). */
    private fun resetTransfers() {
        outgoing.clear()
        reassembler.clear()
    }

    private fun updateStats() {
        _stats.update {
            it.copy(
                links = peers.size,
                pending = router.pendingCount(),
                queued = waiting.size,
                linkedAddresses = peers.toSet()
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

    private fun MediaKind.toMessageKind(): MessageKind = when (this) {
        MediaKind.IMAGE -> MessageKind.IMAGE
        MediaKind.AUDIO -> MessageKind.AUDIO
        MediaKind.VIDEO -> MessageKind.VIDEO
    }

    companion object {
        /** Dirección ficticia del relé de internet en el conjunto de vecinos. */
        const val RELAY = "relay"

        private const val TAG = "RadarSession"
        private const val AVATAR_ID: Byte = 1

        /**
         * Conexiones salientes simultáneas. Android soporta varias, pero cada
         * una consume stack y batería; más de 3 degrada el throughput.
         */
        private const val MAX_OUTGOING = 3

        private const val MAX_CONCURRENT_SENDS = 2
        private const val PUMP_INTERVAL_MS = 30L

        /** Fragmentos en cola a partir de los cuales el emisor de medios espera. */
        private const val HIGH_WATER_FRAMES = 48

        /** Para comparar el backlog del relé (bytes) con el de BLE (fragmentos de ≈240 B). */
        private const val RELAY_BYTES_PER_FRAME = 240L
    }
}
