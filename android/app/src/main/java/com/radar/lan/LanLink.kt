package com.radar.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Wi-Fi local **sin servidor ni internet**: los teléfonos de una misma red se encuentran por mDNS
 * (NSD) y se conectan directo por TCP. Sirve con un router, pero también —y es el caso de una
 * emergencia— con **un teléfono compartiendo su punto de acceso (hotspot)**: no hace falta
 * electricidad de la red ni salida a internet.
 *
 * - Cada dispositivo escucha en un puerto libre y se anuncia como `_radar._tcp.` con un nombre
 *   que lleva el hash de la sala; solo se conecta con quien esté en su misma sala.
 * - Para no abrir dos conexiones entre el mismo par, inicia la conexión quien tiene la etiqueta
 *   aleatoria menor.
 * - Tras conectar se intercambia un saludo con sala y etiqueta; si no coincide, se cierra.
 * - Cada conexión es un «vecino» más de la malla: lo que viaja son los mismos paquetes cifrados.
 *
 * Los callbacks llegan en el hilo principal. Límites: una red con «aislamiento de clientes»
 * (wifi de invitados, algunas empresas) impide la conexión directa.
 */
class LanLink(
    context: Context,
    private val onPacket: (address: String, packet: ByteArray) -> Unit,
    private val onPeer: (address: String, up: Boolean) -> Unit
) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val _peers = MutableStateFlow(0)

    /** Conexiones LAN abiertas. */
    val peers: StateFlow<Int> = _peers.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var roomId = ""
    private var selfTag = ""
    private var server: ServerSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null

    @Volatile
    private var generation = 0

    private val pool = Executors.newCachedThreadPool()
    private val counter = AtomicInteger()

    private val conns = ConcurrentHashMap<String, Conn>()

    /** Etiquetas de los pares con conexión ya establecida (en el hilo principal). */
    private val connectedTags = mutableSetOf<String>()

    /** Servicios vistos en la red (nombre → info), para reintentar si la conexión falló. */
    private val seen = LinkedHashMap<String, NsdServiceInfo>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private val retryTask = object : Runnable {
        override fun run() {
            if (!_running.value) return
            // Reintenta los pares vistos a los que nos toca llamar y aún no están conectados.
            seen.values.forEach { consider(it) }
            main.postDelayed(this, RETRY_MS)
        }
    }

    // ------------------------------------------------------------------ ciclo

    fun start(room: String) {
        stop()
        val manager = nsd ?: run {
            Log.w(TAG, "NSD no disponible")
            return
        }
        val socket = try {
            ServerSocket(0)
        } catch (e: IOException) {
            Log.w(TAG, "No se pudo abrir el puerto local", e)
            return
        }

        generation++
        val gen = generation
        roomId = room
        selfTag = Random.nextBytes(4).joinToString("") { "%02x".format(it) }
        server = socket
        _running.value = true

        runCatching {
            multicastLock = wifi?.createMulticastLock("radar-lan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        pool.execute { acceptLoop(socket, gen) }
        register(manager, socket.localPort, gen)
        discover(manager, gen)
        main.postDelayed(retryTask, RETRY_MS)
    }

    fun stop() {
        generation++
        main.removeCallbacks(retryTask)
        nsd?.let { manager ->
            registration?.let { runCatching { manager.unregisterService(it) } }
            discovery?.let { runCatching { manager.stopServiceDiscovery(it) } }
        }
        registration = null
        discovery = null
        runCatching { server?.close() }
        server = null
        runCatching { multicastLock?.release() }
        multicastLock = null
        val dropped = conns.keys.toList()
        conns.values.forEach { it.close() }
        conns.clear()
        connectedTags.clear()
        dropped.forEach { onPeer(it, false) }
        seen.clear()
        resolveQueue.clear()
        resolving = false
        _peers.value = 0
        _running.value = false
    }

    // ----------------------------------------------------------------- envío

    fun send(address: String, packet: ByteArray): Boolean = conns[address]?.send(packet) ?: false

    fun queuedBytes(address: String): Long = conns[address]?.queued?.get() ?: 0L

    // -------------------------------------------------------------- anuncio

    private fun localServiceName(): String = "$PREFIX${roomId.take(ROOM_CHARS)}-$selfTag"

    private fun register(manager: NsdManager, port: Int, gen: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = localServiceName()
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Registro NSD falló: $errorCode")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "registerService", it) }
    }

    private fun discover(manager: NsdManager, gen: Int) {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Descubrimiento falló: $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { if (gen == generation) onFound(info) }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { if (gen == generation) seen.remove(info.serviceName) }
            }
        }
        discovery = listener
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "discoverServices", it) }
    }

    // ------------------------------------------------------------- conexión

    private data class Parsed(val room: String, val tag: String)

    private fun parse(name: String): Parsed? {
        val match = NAME_RE.find(name) ?: return null
        return Parsed(match.groupValues[1], match.groupValues[2])
    }

    private fun onFound(info: NsdServiceInfo) {
        val parsed = parse(info.serviceName) ?: return
        if (parsed.room != roomId.take(ROOM_CHARS) || parsed.tag == selfTag) return
        seen[info.serviceName] = info
        consider(info)
    }

    /** ¿Nos toca llamar a este par y todavía no estamos conectados? Entonces se resuelve. */
    private fun consider(info: NsdServiceInfo) {
        val parsed = parse(info.serviceName) ?: return
        if (parsed.tag in connectedTags) return
        // Solo inicia quien tiene la etiqueta menor: el otro espera la llamada.
        if (selfTag >= parsed.tag) return
        if (resolveQueue.any { it.serviceName == info.serviceName }) return
        resolveQueue.addLast(info)
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (resolving) return
        val manager = nsd ?: return
        val next = resolveQueue.removeFirstOrNull() ?: return
        resolving = true
        val gen = generation
        runCatching {
            manager.resolveService(next, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val host = info.host
                    val port = info.port
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                    if (gen == generation && host != null) {
                        pool.execute { connectTo(host, port, parse(next.serviceName)?.tag, gen) }
                    }
                }
            })
        }.onFailure {
            resolving = false
        }
    }

    private fun acceptLoop(socket: ServerSocket, gen: Int) {
        while (gen == generation) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            if (conns.size >= MAX_CONNECTIONS) {
                runCatching { client.close() }
                continue
            }
            pool.execute { handshake(client, gen) }
        }
    }

    private fun connectTo(host: InetAddress, port: Int, expectedTag: String?, gen: Int) {
        if (gen != generation) return
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        } catch (e: IOException) {
            runCatching { socket.close() }
            Log.w(TAG, "No se pudo conectar con ${host.hostAddress}: ${e.message}")
            return
        }
        handshake(socket, gen)
    }

    /** Ambos lados envían su saludo y leen el del otro; si la sala no coincide, se cierra. */
    private fun handshake(socket: Socket, gen: Int) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = HELLO_TIMEOUT_MS
            LanFraming.write(socket.getOutputStream(), "$HELLO|$roomId|$selfTag".toByteArray())
            val reply = LanFraming.read(socket.getInputStream())
            val parts = reply?.toString(Charsets.UTF_8)?.split("|")
            if (parts == null || parts.size != 3 || parts[0] != HELLO || parts[1] != roomId) {
                socket.close()
                return
            }
            val remoteTag = parts[2]
            socket.soTimeout = 0

            val address = "$ADDRESS_PREFIX${counter.incrementAndGet()}"
            val conn = Conn(socket, address, remoteTag, gen)
            main.post {
                if (gen != generation || remoteTag == selfTag || remoteTag in connectedTags) {
                    conn.close()
                    return@post
                }
                connectedTags.add(remoteTag)
                conns[address] = conn
                _peers.value = conns.size
                conn.start()
                onPeer(address, true)
            }
        } catch (_: IOException) {
            runCatching { socket.close() }
        }
    }

    // ------------------------------------------------------------------ socket

    private inner class Conn(
        private val socket: Socket,
        val address: String,
        private val tag: String,
        private val gen: Int
    ) {
        val queued = AtomicLong(0)
        private val outbox = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var closed = false

        fun start() {
            pool.execute { readLoop() }
            pool.execute { writeLoop() }
        }

        fun send(packet: ByteArray): Boolean {
            if (closed || packet.isEmpty() || packet.size > LanFraming.MAX_FRAME) return false
            if (queued.get() > MAX_QUEUED_BYTES) return false
            queued.addAndGet(packet.size.toLong())
            outbox.offer(packet)
            return true
        }

        private fun readLoop() {
            try {
                val input = socket.getInputStream()
                while (!closed) {
                    val frame = LanFraming.read(input) ?: break
                    main.post { if (!closed && gen == generation) onPacket(address, frame) }
                }
            } catch (_: IOException) {
                // cierre normal o corte
            } finally {
                close()
            }
        }

        private fun writeLoop() {
            try {
                val output = socket.getOutputStream()
                while (!closed) {
                    val packet = outbox.take()
                    // Un paquete vacío es la señal de cierre: nunca se escribe.
                    if (closed || packet.isEmpty()) break
                    LanFraming.write(output, packet)
                    queued.addAndGet(-packet.size.toLong())
                }
            } catch (_: IOException) {
                // se cerró
            } catch (_: InterruptedException) {
                // cierre
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            outbox.clear()
            outbox.offer(ByteArray(0)) // despierta al escritor bloqueado en take()
            main.post {
                // Una conexión duplicada rechazada nunca llegó a registrarse: no debe liberar
                // la etiqueta de la legítima.
                if (conns.remove(address) != null) {
                    connectedTags.remove(tag)
                    _peers.value = conns.size
                    onPeer(address, false)
                }
            }
        }
    }

    companion object {
        /** Prefijo de las direcciones LAN en el conjunto de vecinos de la sesión. */
        const val ADDRESS_PREFIX = "lan:"

        private const val TAG = "RadarLan"
        private const val SERVICE_TYPE = "_radar._tcp."
        private const val PREFIX = "radar-"
        private const val ROOM_CHARS = 12
        private const val HELLO = "RDR1"
        private const val MAX_CONNECTIONS = 8
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val HELLO_TIMEOUT_MS = 4_000
        private const val RETRY_MS = 15_000L
        private const val MAX_QUEUED_BYTES = 512 * 1024L
        private val NAME_RE = Regex("^radar-([0-9a-f]{12})-([0-9a-f]{8})")
    }
}
