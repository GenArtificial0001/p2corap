package com.radar.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.ParcelUuid
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Doble rol BLE: anuncia la presencia del dispositivo y escanea a los demás.
 *
 * Usa modos BALANCED (no LOW_LATENCY) para no vaciar la batería en dos horas.
 * El foreground service mantiene el proceso vivo; sin él, Doze corta el radio.
 */
class RadarBleManager(private val context: Context) {

    private val appContext = context.applicationContext

    private val manager =
        appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    val adapter: BluetoothAdapter? get() = manager?.adapter
    val isSupported: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true
    val supportsAdvertising: Boolean
        get() = adapter?.isMultipleAdvertisementSupported == true

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private val _peers = MutableStateFlow<List<RadarPeer>>(emptyList())
    val peers: StateFlow<List<RadarPeer>> = _peers.asStateFlow()

    private val _advertising = MutableStateFlow(false)
    val advertising: StateFlow<Boolean> = _advertising.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _bluetoothOn = MutableStateFlow(isEnabled)
    val bluetoothOn: StateFlow<Boolean> = _bluetoothOn.asStateFlow()

    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            val on = state == BluetoothAdapter.STATE_ON
            _bluetoothOn.value = on
            if (!on) {
                stopAll()
                _error.value = "Bluetooth apagado"
            }
        }
    }

    init {
        runCatching {
            ContextCompat.registerReceiver(
                appContext,
                adapterReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    // ---------------------------------------------------------------- advertise

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val bt = adapter ?: return fail("Bluetooth no disponible")
        if (!bt.isEnabled) return fail("Activá Bluetooth")
        if (!bt.isMultipleAdvertisementSupported) {
            return fail("Este dispositivo no soporta BLE advertising")
        }
        if (_advertising.value) return

        val adv = bt.bluetoothLeAdvertiser ?: return fail("Advertiser no disponible")
        advertiser = adv

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(RadarUuids.SERVICE))
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
        } catch (_: SecurityException) {
            fail("Falta permiso BLUETOOTH_ADVERTISE")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: SecurityException) {
            // sin permiso: nada que detener
        }
        _advertising.value = false
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            _advertising.value = true
            Log.d(TAG, "Advertising iniciado")
        }

        override fun onStartFailure(errorCode: Int) {
            _advertising.value = false
            _error.value = "No se pudo anunciar (código $errorCode)"
            Log.e(TAG, "Advertising falló: $errorCode")
        }
    }

    // ------------------------------------------------------------------- scan

    @SuppressLint("MissingPermission")
    fun startScanning() {
        val bt = adapter ?: return fail("Bluetooth no disponible")
        if (!bt.isEnabled) return fail("Activá Bluetooth")

        // En Android 11 y anteriores el scan BLE exige ubicación activa.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            return fail("Activá la ubicación para escanear BLE")
        }

        if (_scanning.value) return

        val sc = bt.bluetoothLeScanner ?: return fail("Scanner no disponible")
        scanner = sc

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(RadarUuids.SERVICE))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setReportDelay(0)
            .build()

        try {
            sc.startScan(listOf(filter), settings, scanCallback)
            _scanning.value = true
        } catch (_: SecurityException) {
            fail("Falta permiso BLUETOOTH_SCAN")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            // sin permiso: nada que detener
        }
        _scanning.value = false
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            upsert(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::upsert)
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _error.value = "Escaneo falló (código $errorCode)"
            Log.e(TAG, "Scan falló: $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    private fun upsert(result: ScanResult) {
        val address = result.device.address ?: return
        val peer = RadarPeer(
            address = address,
            rssi = result.rssi,
            lastSeenMs = SystemClock.elapsedRealtime()
        )
        _peers.value = _peers.value
            .filterNot { it.address == address }
            .plus(peer)
            .sortedByDescending { it.rssi }
    }

    /** Descarta peers que no se ven hace más de [ttlMs]. */
    fun prune(ttlMs: Long = 10_000L) {
        val now = SystemClock.elapsedRealtime()
        _peers.value = _peers.value.filter { now - it.lastSeenMs <= ttlMs }
    }

    fun clearError() {
        _error.value = null
    }

    @SuppressLint("MissingPermission")
    fun stopAll() {
        stopAdvertising()
        stopScanning()
        _peers.value = emptyList()
    }

    /** Libera el receiver. La sesión se encarga de llamarlo al cerrarse. */
    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { appContext.unregisterReceiver(adapterReceiver) }
        stopAll()
    }

    private fun isLocationEnabled(): Boolean {
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return true
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            Settings.Secure.getInt(
                appContext.contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun fail(message: String) {
        _error.value = message
        Log.w(TAG, message)
    }

    private companion object {
        const val TAG = "RadarBle"
    }
}
