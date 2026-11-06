package com.radar.session

import android.content.Context
import com.radar.ble.ControlOpcode
import com.radar.ble.Fragmenter
import com.radar.ble.MessageAssembler
import com.radar.ble.RadarBleManager
import com.radar.ble.RadarGattClient
import com.radar.ble.RadarGattServer
import com.radar.chat.VolatileChatRepository

/**
 * Orquesta el doble rol: decide si somos Central (cliente GATT) o Peripheral
 * (servidor GATT) para una sesión dada, y enruta los mensajes.
 *
 * Regla de diseño: una sola sesión activa por dispositivo.
 */
class RadarSessionManager(context: Context) {

    private val appContext = context.applicationContext

    val ble = RadarBleManager(appContext)
    val chat = VolatileChatRepository()

    private var server: RadarGattServer? = null
    private var client: RadarGattClient? = null

    private val inboundAssembler = MessageAssembler()
    private var role: Role = Role.NONE
    private var peerAddress: String? = null

    var alias: String = "Radar"
        set(value) {
            field = value.ifBlank { "Radar" }.take(31)
        }

    fun start() {
        if (server == null) {
            val gattServer = RadarGattServer(
                context = appContext,
                profileProvider = ::profilePayload,
                onControl = ::onServerControl,
                onMessage = { _, payload ->
                    inboundAssembler.accept(payload)?.let { chat.append(it, isMine = false) }
                },
                onSubscribed = { address ->
                    if (role == Role.NONE) {
                        role = Role.PERIPHERAL
                        peerAddress = address
                        chat.setConnected(true)
                    }
                },
                onDisconnected = {
                    if (role == Role.PERIPHERAL) endSession()
                }
            )
            if (gattServer.open()) server = gattServer
        }

        ble.startAdvertising()
        ble.startScanning()
    }

    fun connect(address: String) {
        if (client != null || peerAddress != null) return

        val gattClient = RadarGattClient(
            context = appContext,
            onReady = {
                role = Role.CENTRAL
                peerAddress = address
                chat.setConnected(true)
                client?.sendControl(byteArrayOf(ControlOpcode.CONNECT_REQUEST))
            },
            onDisconnected = { if (role == Role.CENTRAL) endSession() },
            onProfile = { payload -> chat.setPeerAlias(parseAlias(payload)) },
            onControl = ::onClientControl,
            onMessage = { payload ->
                inboundAssembler.accept(payload)?.let { chat.append(it, isMine = false) }
            }
        )
        client = gattClient
        ble.stopScanning()
        gattClient.connect(address)
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !chat.connected.value) return

        val packets = Fragmenter.fragment(trimmed)
        when (role) {
            Role.CENTRAL -> client?.send(packets)
            Role.PERIPHERAL -> {
                val target = peerAddress
                val gattServer = server
                if (target != null && gattServer != null) {
                    packets.forEach { gattServer.notifyMessage(target, it) }
                } else {
                    return
                }
            }
            Role.NONE -> return
        }
        chat.append(trimmed, isMine = true)
    }

    fun stop() {
        endSession()
        server?.close()
        server = null
        ble.stopAll()
    }

    private fun endSession() {
        client?.close()
        client = null
        inboundAssembler.reset()
        role = Role.NONE
        peerAddress = null
        chat.purgeSession()
        // Volver a descubrir después de cerrar.
        if (ble.advertising.value.not()) ble.startAdvertising()
        if (ble.scanning.value.not()) ble.startScanning()
    }

    private fun onServerControl(address: String, payload: ByteArray) {
        when (payload.firstOrNull()) {
            ControlOpcode.CONNECT_REQUEST -> {
                if (role == Role.NONE || peerAddress == address) {
                    role = Role.PERIPHERAL
                    peerAddress = address
                    chat.setConnected(true)
                    server?.notifyMessage(address, byteArrayOf(ControlOpcode.ACCEPT))
                } else {
                    server?.notifyMessage(address, byteArrayOf(ControlOpcode.REJECT))
                }
            }
            ControlOpcode.BYE -> endSession()
            else -> Unit
        }
    }

    private fun onClientControl(payload: ByteArray) {
        when (payload.firstOrNull()) {
            ControlOpcode.REJECT -> endSession()
            ControlOpcode.BYE -> endSession()
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

    private enum class Role { NONE, CENTRAL, PERIPHERAL }

    private companion object {
        const val AVATAR_ID: Byte = 1
    }
}
