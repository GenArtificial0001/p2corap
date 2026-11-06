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
import android.util.Log
import java.util.ArrayDeque

/**
 * Lado Central: se conecta a un peer, lee su perfil, habilita notificaciones
 * y envía mensajes fragmentados.
 *
 * Las escrituras se encolan: el stack BLE no soporta dos operaciones GATT
 * concurrentes sobre el mismo vínculo.
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

    private var gatt: BluetoothGatt? = null
    private var messageChar: BluetoothGattCharacteristic? = null
    private var controlChar: BluetoothGattCharacteristic? = null

    private val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = manager?.adapter?.getRemoteDevice(address) ?: run {
            Log.e(TAG, "Dirección inválida: $address")
            onDisconnected()
            return
        }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
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
        queue.clear()
        writing = false
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        messageChar = null
        controlChar = null
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
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt = g
                    g.discoverServices()
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    runCatching { g.close() }
                    gatt = null
                    messageChar = null
                    controlChar = null
                    queue.clear()
                    writing = false
                    onDisconnected()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "discoverServices falló: $status")
                onDisconnected()
                return
            }
            val service = g.getService(RadarUuids.SERVICE) ?: run {
                Log.e(TAG, "El peer no expone el servicio Radar")
                onDisconnected()
                return
            }

            controlChar = service.getCharacteristic(RadarUuids.CONTROL_CHAR)
            messageChar = service.getCharacteristic(RadarUuids.MESSAGE_CHAR)

            g.requestMtu(MTU_REQUESTED)

            val profile = service.getCharacteristic(RadarUuids.PROFILE_CHAR)
            if (profile != null) g.readCharacteristic(profile)

            val message = messageChar
            if (message == null) {
                Log.e(TAG, "Falta el characteristic de mensajes")
                onDisconnected()
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
        const val MTU_REQUESTED = 517
    }
}
