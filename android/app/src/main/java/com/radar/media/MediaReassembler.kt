package com.radar.media

/**
 * Reensambla transferencias de medios recibidas. Puro (sin Android): se prueba en JVM.
 *
 * Defensas ante un par hostil o defectuoso:
 * - como máximo [MAX_ACTIVE] transferencias a la vez y [MAX_BUFFERED_BYTES] retenidos;
 * - el trozo debe tener exactamente el tamaño que le corresponde por su índice;
 * - los trozos duplicados se ignoran; los de una transferencia desconocida, también;
 * - una transferencia sin actividad durante [IDLE_TIMEOUT_MS] se descarta.
 */
class MediaReassembler(private val clock: () -> Long = System::currentTimeMillis) {

    sealed interface Event {
        /** Llegó la cabecera: hay que mostrar un mensaje «recibiendo». */
        data class Started(val header: MediaHeader) : Event

        data class Progress(val transferId: Long, val fraction: Float) : Event

        data class Complete(val header: MediaHeader, val bytes: ByteArray) : Event
    }

    private class Partial(val header: MediaHeader, var lastActivity: Long) {
        val chunks = arrayOfNulls<ByteArray>(header.chunkCount)
        var received = 0
        var bytes = 0
    }

    private val active = LinkedHashMap<Long, Partial>()

    private var buffered = 0

    val activeCount: Int get() = active.size

    /** @return [Event.Started], o null si se rechaza (duplicada, demasiadas, sin memoria). */
    fun onHeader(header: MediaHeader): Event? {
        if (header.transferId in active) return null
        if (active.size >= MAX_ACTIVE) return null
        if (buffered + header.totalBytes > MAX_BUFFERED_BYTES) return null

        active[header.transferId] = Partial(header, clock())
        buffered += header.totalBytes
        return Event.Started(header)
    }

    fun onChunk(chunk: MediaChunk): Event? {
        val partial = active[chunk.transferId] ?: return null
        val header = partial.header
        if (chunk.index >= header.chunkCount) return null

        val expectedSize = if (chunk.index == header.chunkCount - 1) {
            header.totalBytes - chunk.index * MediaLimits.CHUNK_DATA
        } else {
            MediaLimits.CHUNK_DATA
        }
        if (chunk.data.size != expectedSize) return null

        partial.lastActivity = clock()
        if (partial.chunks[chunk.index] != null) return null // duplicado

        partial.chunks[chunk.index] = chunk.data
        partial.received++
        partial.bytes += chunk.data.size

        if (partial.received < header.chunkCount) {
            return Event.Progress(header.transferId, partial.received.toFloat() / header.chunkCount)
        }

        val out = ByteArray(partial.bytes)
        var offset = 0
        for (piece in partial.chunks) {
            val data = piece ?: return null
            data.copyInto(out, offset)
            offset += data.size
        }
        drop(header.transferId)
        return Event.Complete(header, out)
    }

    /** Descarta una transferencia (cancelada por el usuario). */
    fun drop(transferId: Long) {
        active.remove(transferId)?.let { buffered -= it.header.totalBytes }
    }

    /** Descarta todas (se cambió de sala o se cayó el último vínculo). */
    fun clear() {
        active.clear()
        buffered = 0
    }

    /** Descarta las que llevan demasiado sin actividad y devuelve sus ids. */
    fun expire(): List<Long> {
        val now = clock()
        val dead = active.filterValues { now - it.lastActivity > IDLE_TIMEOUT_MS }.keys.toList()
        dead.forEach(::drop)
        return dead
    }

    companion object {
        const val MAX_ACTIVE = 3
        const val MAX_BUFFERED_BYTES = 1_200_000
        const val IDLE_TIMEOUT_MS = 30_000L
    }
}
