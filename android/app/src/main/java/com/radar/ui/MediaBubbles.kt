package com.radar.ui

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.radar.chat.Message
import com.radar.media.AdpcmCodec
import com.radar.media.BytesDataSource
import com.radar.media.Sticker
import com.radar.media.StickerCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Acciones y estado que las burbujas de medios necesitan del resto de la app. */
class MediaActions(
    val revealed: List<Long>,
    val playingId: Long?,
    val positionMs: Int,
    val onReveal: (Long) -> Unit,
    val onToggleAudio: (Message) -> Unit,
    val onOpen: (Message) -> Unit,
    val onCancel: (Long) -> Unit
)

internal fun formatDuration(ms: Int): String {
    val total = (ms + 500) / 1000
    return "%d:%02d".format(total / 60, total % 60)
}

/** Fotos y videos que llegan de otras personas se muestran tapados hasta que se toque. */
private fun Message.hiddenUntilTap(actions: MediaActions): Boolean =
    !isMine && id !in actions.revealed

// ----------------------------------------------------------------------- sticker

@Composable
fun StickerCard(sticker: Sticker, modifier: Modifier = Modifier, compact: Boolean = false) {
    val side = if (compact) 96.dp else 132.dp
    Column(
        modifier = modifier
            .size(side)
            .clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(Color(sticker.from), Color(sticker.to))))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(sticker.glyph, fontSize = if (compact) 40.sp else 58.sp)
        Text(
            text = sticker.label,
            fontSize = if (compact) 11.sp else 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
fun StickerContent(message: Message) {
    val sticker = StickerCatalog.find(message.stickerId)
    if (sticker == null) {
        Text("Sticker no disponible", color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        StickerCard(sticker, Modifier.semantics { contentDescription = "Sticker: ${sticker.label}" })
    }
}

// ------------------------------------------------------------------------- foto

@Composable
fun ImageContent(message: Message, actions: MediaActions) {
    val aspect = aspectOf(message, 4f / 3f)
    val frame = Modifier
        .widthIn(max = 260.dp)
        .aspectRatio(aspect)
        .clip(RoundedCornerShape(16.dp))

    when {
        message.failed -> MediaFailed(frame, "No se pudo recibir la foto")
        message.released -> MediaFailed(frame, "Foto liberada para ahorrar memoria")
        message.media == null -> TransferPlaceholder(frame, message, actions, "Recibiendo foto")
        message.hiddenUntilTap(actions) ->
            HiddenMedia(frame, "Foto") { actions.onReveal(message.id) }

        else -> {
            val bitmap by produceState<ImageBitmap?>(null, message.id, message.media.size) {
                value = withContext(Dispatchers.Default) { decodeImage(message.media) }
            }
            val image = bitmap
            if (image == null) {
                Box(frame.background(MaterialTheme.colorScheme.surfaceVariant))
            } else {
                Box(frame.clickable { actions.onOpen(message) }) {
                    Image(
                        bitmap = image,
                        contentDescription = "Foto",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    SendingBadge(message, actions)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------ video

@Composable
fun VideoContent(message: Message, actions: MediaActions) {
    val aspect = aspectOf(message, 16f / 9f)
    val frame = Modifier
        .widthIn(max = 260.dp)
        .aspectRatio(aspect)
        .clip(RoundedCornerShape(16.dp))

    when {
        message.failed -> MediaFailed(frame, "No se pudo recibir el video")
        message.released -> MediaFailed(frame, "Video liberado para ahorrar memoria")
        message.media == null -> TransferPlaceholder(frame, message, actions, "Recibiendo video")
        message.hiddenUntilTap(actions) ->
            HiddenMedia(frame, "Video · ${formatDuration(message.durationMs)}") {
                actions.onReveal(message.id)
            }

        else -> {
            val thumb by produceState<ImageBitmap?>(null, message.id, message.media.size) {
                value = withContext(Dispatchers.Default) { videoFrame(message.media) }
            }
            Box(frame.background(Color.Black).clickable { actions.onOpen(message) }) {
                thumb?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(52.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { PlayGlyph(tint = Color.White, size = 28.dp) }
                Text(
                    text = formatDuration(message.durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                SendingBadge(message, actions)
            }
        }
    }
}

// ------------------------------------------------------------------------ audio

@Composable
fun AudioContent(message: Message, actions: MediaActions, ink: Color, soft: Color) {
    val playing = actions.playingId == message.id
    val duration = message.durationMs.coerceAtLeast(1)
    val fraction = if (playing) (actions.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val ready = message.media != null && !message.failed

    val bars by produceState<FloatArray?>(null, message.id, message.media?.size) {
        value = message.media?.let { data ->
            withContext(Dispatchers.Default) {
                AdpcmCodec.decode(data)?.let { AdpcmCodec.waveform(it, WAVE_BARS) }
            }
        }
    }

    Row(
        modifier = Modifier.widthIn(min = 200.dp, max = 260.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = { actions.onToggleAudio(message) },
            enabled = ready,
            modifier = Modifier
                .size(44.dp)
                .background(ink.copy(alpha = if (ready) 0.16f else 0.08f), CircleShape)
        ) {
            if (playing) PauseGlyph(tint = ink) else PlayGlyph(tint = ink)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            when {
                message.failed -> Text("No se pudo recibir el audio", color = soft, fontSize = 13.sp)
                message.released -> Text("Audio liberado", color = soft, fontSize = 13.sp)
                message.media == null -> {
                    LinearProgressIndicator(
                        progress = { message.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = ink,
                        trackColor = ink.copy(alpha = 0.2f)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Recibiendo ${(message.progress * 100).toInt()}%",
                            color = soft,
                            style = MaterialTheme.typography.labelSmall
                        )
                        IconButton(onClick = { actions.onCancel(message.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.Close, "Cancelar", tint = soft, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                else -> Waveform(bars, fraction, ink)
            }
            if (message.media != null) {
                Text(
                    text = if (playing) {
                        "${formatDuration(actions.positionMs)} / ${formatDuration(duration)}"
                    } else {
                        formatDuration(duration)
                    },
                    color = soft,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (message.isMine && message.inTransfer) {
                LinearProgressIndicator(
                    progress = { message.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    color = ink,
                    trackColor = ink.copy(alpha = 0.2f)
                )
            }
        }
    }
}

@Composable
private fun Waveform(bars: FloatArray?, played: Float, tint: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
    ) {
        val data = bars ?: FloatArray(WAVE_BARS) { 0.25f }
        val slot = size.width / data.size
        val barWidth = slot * 0.58f
        data.forEachIndexed { i, v ->
            val h = (size.height * (0.14f + 0.86f * v)).coerceAtLeast(3.dp.toPx())
            val on = (i + 1f) / data.size <= played
            drawRoundRect(
                color = tint.copy(alpha = if (on) 1f else 0.42f),
                topLeft = Offset(i * slot + (slot - barWidth) / 2f, (size.height - h) / 2f),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

// ------------------------------------------------------------------- piezas comunes

@Composable
private fun HiddenMedia(modifier: Modifier, label: String, onReveal: () -> Unit) {
    Column(
        modifier = modifier
            .background(Brush.linearGradient(listOf(Color(0xFF1B2538), Color(0xFF0B1220))))
            .clickable(onClickLabel = "Mostrar", onClick = onReveal)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = Color.White, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "Toca para ver",
            color = RadarColors.Cyan,
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            "Lo envió otra persona; no se abre solo.",
            color = RadarColors.TextDim,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TransferPlaceholder(
    modifier: Modifier,
    message: Message,
    actions: MediaActions,
    label: String
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress = { message.progress },
                    modifier = Modifier.size(52.dp),
                    color = RadarColors.Cyan,
                    strokeWidth = 4.dp
                )
                Text(
                    "${(message.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            IconButton(onClick = { actions.onCancel(message.id) }, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, "Cancelar recepción")
            }
        }
    }
}

@Composable
private fun MediaFailed(modifier: Modifier, text: String) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
        Text(
            text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

/** Velo con porcentaje mientras el emisor aún está mandando el archivo. */
@Composable
private fun BoxScopeSendingBadge(message: Message) {
    Text(
        text = "Enviando ${(message.progress * 100).toInt()}%",
        color = Color.White,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SendingBadge(
    message: Message,
    actions: MediaActions
) {
    if (!(message.isMine && message.inTransfer)) return
    Row(
        modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BoxScopeSendingBadge(message)
        IconButton(onClick = { actions.onCancel(message.id) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, "Cancelar envío", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}

private fun aspectOf(message: Message, fallback: Float): Float =
    if (message.width > 0 && message.height > 0) {
        (message.width.toFloat() / message.height).coerceIn(0.6f, 2.0f)
    } else {
        fallback
    }

private const val WAVE_BARS = 30

// ------------------------------------------------------------------- decodificación

/** Decodifica una foto recibida, rechazando dimensiones absurdas (bomba de descompresión). */
private fun decodeImage(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth !in 1..4096 || bounds.outHeight !in 1..4096) return null
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}

private fun videoFrame(bytes: ByteArray): ImageBitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(BytesDataSource(bytes))
        retriever.getFrameAtTime(0)?.asImageBitmap()
    } catch (_: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

// ------------------------------------------------------------------------ visores

@Composable
fun ImageViewer(message: Message, onDismiss: () -> Unit) {
    val data = message.media ?: return
    val bitmap by produceState<ImageBitmap?>(null, message.id) {
        value = withContext(Dispatchers.Default) { decodeImage(data) }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Foto a pantalla completa",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            CloseButton(onDismiss)
        }
    }
}

@Composable
fun VideoViewer(message: Message, onDismiss: () -> Unit) {
    val data = message.media ?: return
    val player = remember { MediaPlayer() }
    var failed by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                runCatching {
                                    player.setDisplay(holder)
                                    player.setDataSource(BytesDataSource(data))
                                    player.isLooping = true
                                    player.setOnErrorListener { _, _, _ ->
                                        failed = true
                                        true
                                    }
                                    player.setOnPreparedListener {
                                        it.start()
                                        playing = true
                                    }
                                    player.prepareAsync()
                                }.onFailure { failed = true }
                            }

                            override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, ht: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                runCatching { player.setDisplay(null) }
                            }
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspectOf(message, 16f / 9f))
                    .clickable {
                        runCatching {
                            if (player.isPlaying) {
                                player.pause()
                                playing = false
                            } else {
                                player.start()
                                playing = true
                            }
                        }
                    }
            )
            if (failed) {
                Text(
                    "No se pudo reproducir este video",
                    color = Color.White,
                    modifier = Modifier.padding(24.dp)
                )
            } else if (!playing) {
                PlayGlyph(tint = Color.White.copy(alpha = 0.85f), size = 56.dp)
            }
            CloseButton(onDismiss)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.CloseButton(onDismiss: () -> Unit) {
    IconButton(
        onClick = onDismiss,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(16.dp)
            .size(48.dp)
            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
    ) {
        Icon(Icons.Filled.Close, contentDescription = "Cerrar", tint = Color.White)
    }
}
