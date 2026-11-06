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
 * Lado Central: se conecta a un peer, habilita notificaciones, lee su perfil
 * y envía paquetes fragmentados.
 *
 * Secuencia (una sola operación GATT en vuelo a la vez):
 * conectar → descubrir servicios → escribir CCCD → leer perfil → `onReady`.
 * Recién entonces se permiten escrituras de la app (handshake y mensajes).
 *
 * Hilos: los callbacks del stack llegan en hilos del binder; todos se
 * reenvían al hilo principal, donde viven la cola y los flags de esta clase.
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

    /** Evita avisar `onReady` dos veces (lectura de perfil fallida + timeout, etc.). */
    private var readyFired = false

    private val queue = ArrayDeque<Pair<BluetoothGattCharacteristic, ByteArray>>()
    private var writing = false
    private var writeFailures = 0

    private val writeTimeout = Runnable {
        if (writing) {
            Log.w(TAG, "Timeout esperando onCharacteristicWrite")
            writing = false
            pump()
        }
    }

    /** Si no llegó onReady en este lapso, se da por fallida la conexión. */
    private val connectTimeout = Runnable {
        Log.w(TAG, "Timeout conectando con $target")
        giveUp()
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        target = address
        closedByUser = false
        attempts = 0
        readyFired = false
        armConnectTimeout()
        connectInternal(address)
    }

    private fun armConnectTimeout() {
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connectInternal(address: String) {
        val device = (try {
            manager?.adapter?.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            null
        }) ?: run {
            Log.e(TAG, "Dirección inválida: $address")
            giveUp()
            return
        }
        val newGatt = try {
            device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: SecurityException) {
            Log.e(TAG, "Sin permiso BLUETOOTH_CONNECT para conectar", e)
            giveUp()
            return
        }
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

    /** Cancela temporizadores y avisa que el vínculo se perdió. */
    @SuppressLint("MissingPermission")
    private fun giveUp() {
        handler.removeCallbacks(writeTimeout)
        handler.removeCallbacks(connectTimeout)
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
        handler.removeCallbacks(writeTimeout)
        handler.removeCallbacks(connectTimeout)
        runCatching { gatt?.close() }
        gatt = null
        messageChar = null
        controlChar = null
        writing = false
        readyFired = false

        val address = target
        if (!closedByUser && address != null && attempts < MAX_ATTEMPTS) {
            attempts++
            val delay = RETRY_BASE_DELAY_MS * attempts
            Log.w(TAG, "Reintentando conexión con $address en ${delay}ms (intento $attempts)")
            // Sin este timeout, un reintento colgado dejaba la UI en "Conectando…" para siempre.
            armConnectTimeout()
            handler.postDelayed({
                if (!closedByUser) connectInternal(address)
            }, delay)
        } else {
            queue.clear()
            onDisconnected()
        }
    }

    /** Llama al listener de listo (una sola vez) y cancela el timeout de conexión. */
    private fun fireReady() {
        if (readyFired) return
        readyFired = true
        handler.removeCallbacks(connectTimeout)
        onReady()
    }

    /** Lectura del alias, ya con el CCCD habilitado (una operación por vez). */
    @SuppressLint("MissingPermission")
    private fun readProfile(g: BluetoothGatt) {
        val profile = g.getService(RadarUuids.SERVICE)
            ?.getCharacteristic(RadarUuids.PROFILE_CHAR)
        // Sin perfil legible seguimos igual: el alias es cosmético.
        if (profile == null || !g.readCharacteristic(profile)) fireReady()
    }

    @SuppressLint("MissingPermission")
    private fun pump() {
        if (writing) return
        val gatt = gatt ?: return
        val next = queue.peekFirst() ?: return

        writing = true
        val (characteristic, payload) = next
        @Suppress("DEPRECATION")
        characteristic.value = payload
        @Suppress("DEPRECATION")
        val started = gatt.writeCharacteristic(characteristic)

        if (started) {
            queue.pollFirst()
            writeFailures = 0
            handler.removeCallbacks(writeTimeout)
            handler.postDelayed(writeTimeout, WRITE_TIMEOUT_MS)
        } else {
            writing = false
            writeFailures++
            if (writeFailures >= MAX_WRITE_FAILURES) {
                Log.e(TAG, "El stack rechazó $writeFailures escrituras seguidas; se descarta el paquete")
                queue.pollFirst()
                writeFailures = 0
                if (queue.isNotEmpty()) handler.postDelayed({ pump() }, WRITE_RETRY_DELAY_MS)
            } else {
                Log.w(TAG, "writeCharacteristic rechazó el paquete; reintento $writeFailures")
                handler.postDelayed({ pump() }, WRITE_RETRY_DELAY_MS)
            }
        }
    }

    private val callback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
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
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "discoverServices falló: $status")
                    retryOrGiveUp()
                    return@post
                }

                val service = g.getService(RadarUuids.SERVICE) ?: run {
                    Log.e(TAG, "El peer no expone el servicio Radar")
                    giveUp()
                    return@post
                }

                controlChar = service.getCharacteristic(RadarUuids.CONTROL_CHAR)
                messageChar = service.getCharacteristic(RadarUuids.MESSAGE_CHAR)

                val message = messageChar
                if (message == null) {
                    Log.e(TAG, "Falta el characteristic de mensajes")
                    giveUp()
                    return@post
                }

                // El stack GATT solo admite una operación a la vez. Primero el CCCD
                // (lo imprescindible para recibir); el perfil se lee después, en el
                // callback del descriptor, y `onReady` recién al terminar esa lectura.
                g.setCharacteristicNotification(message, true)
                val cccd = message.getDescriptor(RadarUuids.CCCD)
                if (cccd != null) {
                    @Suppress("DEPRECATION")
                    cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    val started = g.writeDescriptor(cccd)
                    if (!started) {
                        Log.e(TAG, "writeDescriptor rechazado por el stack")
                        giveUp()
                    }
                } else {
                    Log.w(TAG, "El peer no expone CCCD; se sigue sin notificaciones")
                    readProfile(g)
                }
            }
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            if (descriptor.uuid != RadarUuids.CCCD) return
            handler.post {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    readProfile(g)
                } else {
                    Log.e(TAG, "No se pudo habilitar notificaciones: status=$status")
                    giveUp()
                }
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
            val uuid = characteristic.uuid
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            handler.post {
                if (uuid != RadarUuids.PROFILE_CHAR) return@post
                if (status == BluetoothGatt.GATT_SUCCESS) onProfile(value)
                // Éxito o no, el enlace ya está usable: se libera el handshake.
                fireReady()
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = characteristic.value?.copyOf() ?: return
            val uuid = characteristic.uuid
            handler.post {
                when (uuid) {
                    RadarUuids.CONTROL_CHAR -> onControl(value)
                    RadarUuids.MESSAGE_CHAR -> onMessage(value)
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handler.post {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Escritura falló: $status")
                }
                handler.removeCallbacks(writeTimeout)
                writing = false
                pump()
            }
        }
    }

    private companion object {
        const val TAG = "RadarGattClient"
        const val MAX_ATTEMPTS = 1
        const val RETRY_BASE_DELAY_MS = 600L
        const val WRITE_TIMEOUT_MS = 2_000L
        const val WRITE_RETRY_DELAY_MS = 200L
        const val MAX_WRITE_FAILURES = 5
        const val CONNECT_TIMEOUT_MS = 10_000L
    }
}
