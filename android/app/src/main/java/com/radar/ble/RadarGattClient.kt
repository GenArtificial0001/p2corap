package com.radar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

/**
 * Lado Central: se conecta a un peer, lee su perfil, habilita notificaciones
 * y envía paquetes fragmentados.
 *
 * Reintenta una vez si el primer intento falla (el error 133 es habitual tras
 * un scan agresivo). Las escrituras se encolan porque el stack no soporta dos
 * operaciones GATT concurrentes sobre el mismo vínculo.
 */
class RadarGattClient(
    private val context: Context,
    private val onReady: () -> Unit,
    private val onDisconnected: () -> Unit,
    private val onProfile: (ByteArray) -> Unit,
    private val onControl: (ByteArray) -> Unit,
    private val onMessage: (ByteArray) -> Unit
) {

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var messageChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null

    private var target: String? = null
    private var attempts = 0
    private var closedByUser = false

    private val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        target = address
        closedByUser = false
        attempts = 0
        connectInternal(address)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        val device = manager?.adapter?.getRemoteDevice(address) ?: run {
            Log.e(TAG, "Dirección inválida: $address")
            onDisconnected()
            return
        }
        val newGatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (newGatt == null) {
            Log.e(TAG, "connectGatt devolvió null")
            giveUp()
            return
        }
        gatt = newGatt
    }

    /** Encola un mensaje completo ya fragmentado. */
    fun send(packets: List<ByteArray>) {
        val characteristic = messageChar ?: return
        packets.forEach { queue.addLast(characteristic to it) }
        pump()
    }

    fun sendControl(payload: ByteArray) {
        val characteristic = controlChar ?: return
        queue.addLast(characteristic to payload)
        pump()
    }

    @SuppressLint("MissingPermission")
    fun close() {
        closedByUser = true
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        writing = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        messageChar = null
        controlChar = null
    }

    @SuppressLint("MissingPermission")
    private fun giveUp() {
        runCatching { gatt?.close() }
        gatt = null
        messageChar = null
        controlChar = null
        queue.clear()
        writing = false
        onDisconnected()
    }

    @SuppressLint("MissingPermission")
    private fun retryOrGiveUp() {
        runCatching { gatt?.close() }
        gatt = null
        messageChar = null
        controlChar = null
        writing = false

        val address = target
        if (!closedByUser && address != null && attempts < MAX_ATTEMPTS) {
            attempts++
            val delay = RETRY_BASE_DELAY_MS * attempts
            Log.w(TAG, "Reintentando conexión con $address en ${delay}ms (intento $attempts)")
            handler.postDelayed({
                if (!closedByUser) connectInternal(address)
            }, delay)
        } else {
            queue.clear()
            onDisconnected()
        }
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writing) return
        val next = queue.pollFirst() ?: return
        val gatt = gatt ?: return

        writing = true
        val (characteristic, payload) = next
        @Suppress("DEPRECATION")
        characteristic.value = payload
        @Suppress("DEPRECATION")
        val started = gatt.writeCharacteristic(characteristic)
        if (!started) {
            writing = false
            Log.w(TAG, "writeCharacteristic rechazó el paquete")
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Estado de conexión con error: status=$status newState=$newState")
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        gatt = g
                        attempts = 0
                        g.discoverServices()
                    } else {
                        retryOrGiveUp()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> retryOrGiveUp()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "discoverServices falló: $status")
                retryOrGiveUp()
                return
            }

            val service = g.getService(RadarUuids.SERVICE) ?: run {
                Log.e(TAG, "El peer no expone el servicio Radar")
                giveUp()
                return
            }

            controlChar = service.getCharacteristic(RadarUuids.CONTROL_CHAR)
            messageChar = service.getCharacteristic(RadarUuids.MESSAGE_CHAR)

            val profile = service.getCharacteristic(RadarUuids.PROFILE_CHAR)
            if (profile != null) g.readCharacteristic(profile)

            val message = messageChar
            if (message == null) {
                Log.e(TAG, "Falta el characteristic de mensajes")
                giveUp()
                return
            }

            g.setCharacteristicNotification(message, true)
            val cccd = message.getDescriptor(RadarUuids.CCCD)
            if (cccd != null) {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            } else {
                onReady()
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid == RadarUuids.CCCD && status == BluetoothGatt.GATT_SUCCESS) {
                onReady()
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU negociado: $mtu")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            when (characteristic.uuid) {
                RadarUuids.PROFILE_CHAR -> onProfile(characteristic.value ?: ByteArray(0))
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value ?: return
            when (characteristic.uuid) {
                RadarUuids.CONTROL_CHAR -> onControl(value)
                RadarUuids.MESSAGE_CHAR -> onMessage(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Escritura falló: $status")
            }
            writing = false
            pump()
        }
    }

    private companion object {
        const val TAG = "RadarGattClient"
        const val MAX_ATTEMPTS = 1
        const val RETRY_BASE_DELAY_MS = 600L
    }
}
