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
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.ArrayDeque

/**
 * Lado Peripheral: expone el servicio Radar y responde a los peers.
 *
 * Mantiene una tabla de dispositivos conectados y sus suscripciones a
 * notificaciones (CCCD). El estado de sesión lo decide [RadarSessionManager].
 *
 * Hilos: Android entrega los callbacks GATT en hilos del binder. Todo se
 * reenvía al hilo principal antes de tocar estado, de modo que esta clase y
 * quien la use trabajan en un único hilo y no necesitan locks.
 *
 * Notificaciones: el stack solo admite una notificación en vuelo por
 * dispositivo. Disparar varias seguidas (un mensaje son decenas de
 * fragmentos) hacía que el stack rechazara las siguientes y se perdieran.
 * Por eso cada dispositivo tiene su cola y el siguiente fragmento sale en
 * `onNotificationSent`.
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

    private val main = Handler(Looper.getMainLooper())

    private var server: BluetoothGattServer? = null

    private val devices = mutableMapOf<String, BluetoothDevice>()
    private val subscribed = mutableSetOf<String>()

    /** Fragmentos pendientes de notificar, por dispositivo. */
    private val outbox = mutableMapOf<String, ArrayDeque<ByteArray>>()

    /** Dispositivos con una notificación en vuelo (esperando onNotificationSent). */
    private val inFlight = mutableSetOf<String>()
    private val failures = mutableMapOf<String, Int>()
    private val timeouts = mutableMapOf<String, Runnable>()

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
        timeouts.values.forEach { main.removeCallbacks(it) }
        timeouts.clear()
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        devices.clear()
        subscribed.clear()
        outbox.clear()
        inFlight.clear()
        failures.clear()
    }

    /** Corta el vínculo con un dispositivo conectado a nuestro servidor. */
    @SuppressLint("MissingPermission")
    fun disconnect(address: String) {
        val device = devices[address] ?: return
        runCatching { server?.cancelConnection(device) }
    }

    /**
     * Encola un fragmento para el dispositivo. Devuelve false solo si no hay
     * a quién notificar; el envío real es asíncrono y ordenado.
     */
    fun notifyMessage(address: String, payload: ByteArray): Boolean {
        if (server == null) return false
        if (address !in subscribed || address !in devices) return false
        outbox.getOrPut(address) { ArrayDeque() }.addLast(payload)
        pump(address)
        return true
    }

    private fun pump(address: String) {
        if (address in inFlight) return
        val queue = outbox[address] ?: return
        val next = queue.peekFirst() ?: return
        val device = devices[address] ?: return

        inFlight.add(address)
        if (sendNotification(device, next)) {
            queue.pollFirst()
            failures.remove(address)
            armTimeout(address)
        } else {
            inFlight.remove(address)
            val count = (failures[address] ?: 0) + 1
            if (count >= MAX_NOTIFY_FAILURES) {
                Log.e(TAG, "Notificación rechazada $count veces; se descarta el fragmento")
                queue.pollFirst()
                failures.remove(address)
                if (queue.isNotEmpty()) main.postDelayed({ pump(address) }, RETRY_DELAY_MS)
            } else {
                failures[address] = count
                main.postDelayed({ pump(address) }, RETRY_DELAY_MS)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(device: BluetoothDevice, payload: ByteArray): Boolean {
        val gattServer = server ?: return false
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

    /** Si onNotificationSent nunca llega, no bloqueamos la cola para siempre. */
    private fun armTimeout(address: String) {
        timeouts.remove(address)?.let { main.removeCallbacks(it) }
        val task = Runnable {
            timeouts.remove(address)
            if (inFlight.remove(address)) {
                Log.w(TAG, "Timeout esperando onNotificationSent de $address")
                pump(address)
            }
        }
        timeouts[address] = task
        main.postDelayed(task, NOTIFY_TIMEOUT_MS)
    }

    private fun forget(address: String) {
        devices.remove(address)
        subscribed.remove(address)
        outbox.remove(address)
        inFlight.remove(address)
        failures.remove(address)
        timeouts.remove(address)?.let { main.removeCallbacks(it) }
    }

    private val callback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val address = device.address ?: return
            main.post {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        devices[address] = device
                        Log.d(TAG, "Peer conectado: $address")
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        forget(address)
                        onDisconnected(address)
                    }
                }
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val address = device.address ?: return
            main.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Notificación falló: status=$status")
                }
                timeouts.remove(address)?.let { main.removeCallbacks(it) }
                inFlight.remove(address)
                pump(address)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            // Respuesta inmediata en el hilo del binder: el stack espera la respuesta ya.
            if (characteristic.uuid != RadarUuids.PROFILE_CHAR) {
                respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val profile = profileProvider()
            val slice = if (offset in 0..profile.size) profile.copyOfRange(offset, profile.size) else ByteArray(0)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
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
            val payload = value?.copyOf() ?: ByteArray(0)

            if (responseNeeded) {
                respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }

            val uuid = characteristic.uuid
            main.post {
                when (uuid) {
                    RadarUuids.CONTROL_CHAR -> onControl(address, payload)
                    RadarUuids.MESSAGE_CHAR -> onMessage(address, payload)
                    else -> Unit
                }
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
                if (responseNeeded) {
                    respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                }
                return
            }

            // Responder primero: el cliente espera el ACK del descriptor para seguir.
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)

            val enabled =
                value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            main.post {
                if (enabled) {
                    // El dispositivo puede haberse suscrito sin que el callback de
                    // conexión llegara antes: lo registramos para poder notificarle.
                    devices.putIfAbsent(address, device)
                    subscribed.add(address)
                    onSubscribed(address)
                } else {
                    subscribed.remove(address)
                    outbox.remove(address)
                }
            }
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
        const val RETRY_DELAY_MS = 40L
        const val NOTIFY_TIMEOUT_MS = 1_500L
        const val MAX_NOTIFY_FAILURES = 25
    }
}
