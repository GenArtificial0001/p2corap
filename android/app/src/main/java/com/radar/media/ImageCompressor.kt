package com.radar.media

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.ByteArrayOutputStream

/**
 * Reduce una foto a un JPEG que se pueda mandar por BLE en pocos segundos (≈30-60 KB).
 * Todo en memoria: la foto original nunca se copia a ningún archivo de la app.
 */
object ImageCompressor {

    class Result(val jpeg: ByteArray, val width: Int, val height: Int)

    /** Lados máximos que se prueban, de mayor a menor calidad visual. */
    private val SIDES = intArrayOf(480, 400, 320, 240)
    private val QUALITIES = intArrayOf(68, 52, 40)

    /** Objetivo de tamaño: ≈4-8 s por BLE. El tope duro es [MediaKind.IMAGE]. */
    private const val TARGET_BYTES = 64 * 1024

    fun fromUri(resolver: ContentResolver, uri: Uri): Result? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Decodifica ya reducida (potencias de 2) para no cargar 12 MP en memoria.
        var sample = 1
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        while (longest / (sample * 2) >= SIDES[0]) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        val degrees = resolver.openInputStream(uri)?.use { exifRotation(it) } ?: 0
        val upright = rotate(decoded, degrees)
        return try {
            fromBitmap(upright)
        } finally {
            if (upright !== decoded) decoded.recycle()
            upright.recycle()
        }
    }

    fun fromBitmap(source: Bitmap): Result? {
        val flat = flattenOnWhite(source)
        try {
            var fallback: Result? = null
            for (side in SIDES) {
                val scaled = scaleTo(flat, side)
                try {
                    for (quality in QUALITIES) {
                        val out = ByteArrayOutputStream()
                        if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) continue
                        val bytes = out.toByteArray()
                        val result = Result(bytes, scaled.width, scaled.height)
                        if (bytes.size <= TARGET_BYTES) return result
                        if (bytes.size <= MediaKind.IMAGE.maxBytes) fallback = result
                    }
                } finally {
                    if (scaled !== flat) scaled.recycle()
                }
            }
            return fallback
        } finally {
            if (flat !== source) flat.recycle()
        }
    }

    private fun scaleTo(bitmap: Bitmap, side: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= side) return bitmap
        val ratio = side.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * ratio).toInt()),
            maxOf(1, (bitmap.height * ratio).toInt()),
            true
        )
    }

    /** JPEG no tiene alfa: sin esto, un PNG transparente saldría con fondo negro. */
    private fun flattenOnWhite(bitmap: Bitmap): Bitmap {
        if (!bitmap.hasAlpha()) return bitmap
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun exifRotation(stream: java.io.InputStream): Int = runCatching {
        when (
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        ) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    private fun rotate(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
