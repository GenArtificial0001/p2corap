package com.radar.media

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/**
 * Convierte un video de la galería en un clip diminuto apto para BLE:
 * H.264 a 240p, ≈220 kbps, **sin audio** y de **5 s** como máximo (≈100-160 KB).
 *
 * Es la única parte de Radar que necesita un archivo temporal (el transcodificador del sistema
 * escribe a disco). Vive en la caché de la app, se lee a RAM y se borra en cuanto termina,
 * o falla, o se cancela.
 */
@OptIn(UnstableApi::class)
object VideoCompressor {

    const val MAX_CLIP_MS = 5_000L
    private const val TARGET_HEIGHT = 240
    private const val VIDEO_BITRATE = 220_000

    class Result(val bytes: ByteArray, val durationMs: Int, val width: Int, val height: Int)

    /** Permite cancelar una compresión en curso. */
    class Handle internal constructor(private val transformer: Transformer, private val file: File) {
        fun cancel() {
            runCatching { transformer.cancel() }
            file.delete()
        }
    }

    /**
     * Debe llamarse desde el hilo principal (requisito de [Transformer]). Los callbacks también
     * llegan en él. [onDone] recibe un [Result] o un mensaje de error.
     */
    fun compress(
        context: Context,
        uri: Uri,
        onDone: (Result?, String?) -> Unit
    ): Handle? {
        val app = context.applicationContext
        val source = probe(app, uri)
        val clipEnd = if (source.durationMs > 0) minOf(source.durationMs, MAX_CLIP_MS) else MAX_CLIP_MS

        val out = File(app.cacheDir, "radar_clip_${System.nanoTime()}.mp4")

        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(0)
            .setEndPositionMs(clipEnd)
            .build()
        val item = MediaItem.Builder().setUri(uri).setClippingConfiguration(clipping).build()

        val effects = if (source.height in 1..TARGET_HEIGHT) {
            Effects(emptyList(), emptyList())
        } else {
            Effects(emptyList(), listOf(Presentation.createForHeight(TARGET_HEIGHT)))
        }
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(true)
            .setEffects(effects)
            .build()

        val encoders = DefaultEncoderFactory.Builder(app)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(VIDEO_BITRATE).build()
            )
            .build()

        val transformer = Transformer.Builder(app)
            .setEncoderFactory(encoders)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    val result = runCatching { readAndDelete(out) }.getOrNull()
                    if (result == null) {
                        onDone(null, "No se pudo leer el video comprimido")
                    } else if (result.bytes.size > MediaKind.VIDEO.maxBytes) {
                        onDone(null, "El video quedó muy pesado. Prueba con uno más corto.")
                    } else {
                        onDone(result, null)
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    out.delete()
                    onDone(null, "No se pudo comprimir el video (${exportException.errorCodeName})")
                }
            })
            .build()

        return try {
            transformer.start(edited, out.absolutePath)
            Handle(transformer, out)
        } catch (e: Exception) {
            out.delete()
            onDone(null, "No se pudo iniciar la compresión")
            null
        }
    }

    private class Probe(val durationMs: Long, val height: Int)

    /** Duración y alto (ya rotado) del video de origen. */
    private fun probe(context: Context, uri: Uri): Probe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val duration = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            // Con 90/270° el alto visual es el ancho almacenado.
            Probe(duration, if (rotation == 90 || rotation == 270) w else h)
        } catch (_: Exception) {
            Probe(0L, 0)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun readAndDelete(file: File): Result {
        try {
            val retriever = MediaMetadataRetriever()
            var duration = 0
            var width = 0
            var height = 0
            try {
                retriever.setDataSource(file.absolutePath)
                duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                width = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                height = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            } finally {
                runCatching { retriever.release() }
            }
            return Result(file.readBytes(), duration, width, height)
        } finally {
            file.delete()
        }
    }
}

/** Entrega bytes en RAM a [android.media.MediaPlayer] / [MediaMetadataRetriever] sin archivo. */
class BytesDataSource(private val data: ByteArray) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= data.size) return -1
        val count = minOf(size.toLong(), data.size - position).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, count)
        return count
    }

    override fun getSize(): Long = data.size.toLong()

    override fun close() = Unit
}
