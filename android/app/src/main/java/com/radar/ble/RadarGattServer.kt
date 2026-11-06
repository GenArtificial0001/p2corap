package com.radar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Lado Peripheral: expone el servicio Radar y responde a los peers.
 *
 * Mantiene una tabla de dispositivos conectados y sus suscripciones a
 * notificaciones (CCCD). El estado de sesión lo decide [RadarSessionManager].
 */
class RadarGattServer(
    private val context: Context,
    private val profileProvider: () -> ByteArray,
    private val onControl: (address: String, payload: ByteArray) -> Unit,
    private val onMessage: (address: String, payload: ByteArray) -> Unit,
    private val onSubscribed: (address: String) -> Unit,
    private val onDisconnected: (address: String) -> Unit
) {

    private val manager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private var server: BluetoothGattServer? = null

    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val subscribed = mutableSetOf<String>()

    private val profileChar = BluetoothGattCharacteristic(
        RadarUuids.PROFILE_CHAR,
        BluetoothGattCharacteristic.PROPERTY_READ,
        BluetoothGattCharacteristic.PERMISSION_READ
    )

    private val controlChar = BluetoothGattCharacteristic(
        RadarUuids.CONTROL_CHAR,
        BluetoothGattCharacteristic.PROPERTY_WRITE,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    )

    private val messageChar = BluetoothGattCharacteristic(
        RadarUuids.MESSAGE_CHAR,
        BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
        BluetoothGattCharacteristic.PERMISSION_WRITE
    ).apply {
        addDescriptor(
            BluetoothGattDescriptor(
                RadarUuids.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or
                    BluetoothGattDescriptor.PERMISSION_WRITE
            )
        )
    }

    private val service = BluetoothGattService(
        RadarUuids.SERVICE,
        BluetoothGattService.SERVICE_TYPE_PRIMARY
    ).apply {
        addCharacteristic(profileChar)
        addCharacteristic(controlChar)
        addCharacteristic(messageChar)
    }

    @SuppressLint("MissingPermission")
    fun open(): Boolean {
        if (server != null) return true
        val gattServer = manager?.openGattServer(context, callback) ?: run {
            Log.e(TAG, "openGattServer devolvió null")
            return false
        }
        if (!gattServer.addService(service)) {
            Log.e(TAG, "No se pudo agregar el servicio")
            gattServer.close()
            return false
        }
        server = gattServer
        return true
    }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        devices.clear()
        subscribed.clear()
    }

    @SuppressLint("MissingPermission")
    fun notifyMessage(address: String, payload: ByteArray): Boolean {
        val gattServer = server ?: return false
        if (address !in subscribed) return false
        val device = devices[address] ?: return false

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gattServer.notifyCharacteristicChanged(device, messageChar, false, payload) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                messageChar.value = payload
                @Suppress("DEPRECATION")
                gattServer.notifyCharacteristicChanged(device, messageChar, false)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Sin permiso para notificar", e)
            false
        }
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val address = device.address ?: return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    devices[address] = device
                    Log.d(TAG, "Peer conectado: $address")
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    devices.remove(address)
                    subscribed.remove(address)
                    onDisconnected(address)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid != RadarUuids.PROFILE_CHAR) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, profileProvider())
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val address = device.address ?: return
            val payload = value ?: ByteArray(0)

            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            when (characteristic.uuid) {
                RadarUuids.CONTROL_CHAR -> onControl(address, payload)
                RadarUuids.MESSAGE_CHAR -> onMessage(address, payload)
                else -> Unit
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val address = device.address ?: return
            if (descriptor.uuid != RadarUuids.CCCD) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }

            val enabled = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            if (enabled) {
                subscribed.add(address)
                onSubscribed(address)
            } else {
                subscribed.remove(address)
            }

            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    @SuppressLint("MissingPermission")
    private fun respond(
        device: BluetoothDevice,
        requestId: Int,
        status: Int,
        offset: Int,
        value: ByteArray?
    ) {
        runCatching { server?.sendResponse(device, requestId, status, offset, value) }
    }

    private companion object {
        const val TAG = "RadarGattServer"
    }
}
