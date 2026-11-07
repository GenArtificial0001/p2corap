package com.radar.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.radar.chat.TextLimit
import com.radar.media.EmojiCatalog
import com.radar.media.StickerCatalog
import com.radar.media.VoiceRecorder

private enum class Tray { NONE, ATTACH, EMOJI }

/** Todo lo que el compositor necesita de fuera (permisos, selectores del sistema, envío). */
class ComposerActions(
    val onSend: () -> Unit,
    val onSticker: (Int) -> Unit,
    val onPickPhoto: () -> Unit,
    val onPickVideo: () -> Unit,
    val onTakePhoto: () -> Unit,
    /** Intenta empezar a grabar (pide permiso si hace falta). Devuelve true si empezó. */
    val onStartRecording: () -> Boolean,
    val onFinishRecording: (send: Boolean) -> Unit
)

@Composable
fun Composer(
    draft: String,
    enabled: Boolean,
    recorder: VoiceRecorder,
    actions: ComposerActions,
    onDraftChange: (String) -> Unit
) {
    var tray by remember { mutableStateOf(Tray.NONE) }
    var recording by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = LocalHapticFeedback.current

    BackHandler(enabled = tray != Tray.NONE) { tray = Tray.NONE }
    // Si se pierde el vínculo mientras se grababa, se descarta.
    LaunchedEffect(enabled) {
        if (!enabled && recording) {
            actions.onFinishRecording(false)
            recording = false
        }
    }

    val bytes = TextLimit.byteLength(draft)
    val showCounter = bytes >= (TextLimit.MAX_BYTES * 0.75f).toInt()
    val hasText = draft.isNotBlank()

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = tray != Tray.NONE && !recording,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            when (tray) {
                Tray.ATTACH -> AttachTray(
                    onPhoto = { tray = Tray.NONE; actions.onPickPhoto() },
                    onCamera = { tray = Tray.NONE; actions.onTakePhoto() },
                    onVideo = { tray = Tray.NONE; actions.onPickVideo() }
                )

                Tray.EMOJI -> EmojiTray(
                    onEmoji = { emoji -> onDraftChange(TextLimit.truncate(draft + emoji)) },
                    onSticker = { id ->
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onSticker(id)
                    }
                )

                Tray.NONE -> Unit
            }
        }

        if (recording) {
            RecorderBar(
                recorder = recorder,
                onCancel = {
                    actions.onFinishRecording(false)
                    recording = false
                },
                onSend = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    actions.onFinishRecording(true)
                    recording = false
                }
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.sm, vertical = Space.sm),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = {
                        tray = if (tray == Tray.ATTACH) Tray.NONE else Tray.ATTACH
                        if (tray != Tray.NONE) {
                            focus.clearFocus()
                            keyboard?.hide()
                        }
                    },
                    enabled = enabled,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        imageVector = if (tray == Tray.ATTACH) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (tray == Tray.ATTACH) "Cerrar adjuntos" else "Adjuntar foto o video",
                        tint = if (enabled) RadarColors.Cyan else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = draft,
                    onValueChange = { onDraftChange(TextLimit.truncate(it)) },
                    modifier = Modifier
                        .weight(1f)
                        .onFocusChanged { if (it.isFocused && tray != Tray.NONE) tray = Tray.NONE },
                    enabled = enabled,
                    placeholder = {
                        Text(if (enabled) "Mensaje efímero…" else "Conéctate a un par para escribir")
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = {
                                if (tray == Tray.EMOJI) {
                                    tray = Tray.NONE
                                } else {
                                    tray = Tray.EMOJI
                                    focus.clearFocus()
                                    keyboard?.hide()
                                }
                            },
                            enabled = enabled
                        ) {
                            SmileGlyph(
                                tint = if (tray == Tray.EMOJI) RadarColors.Cyan
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 5,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        disabledContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedBorderColor = MaterialTheme.colorScheme.primary
                    )
                )

                Spacer(Modifier.width(Space.sm))

                if (hasText) {
                    FilledIconButton(
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            actions.onSend()
                        },
                        enabled = enabled,
                        modifier = Modifier.size(56.dp),
                        colors = sendColors()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar mensaje")
                    }
                } else {
                    FilledIconButton(
                        onClick = {
                            tray = Tray.NONE
                            focus.clearFocus()
                            keyboard?.hide()
                            if (actions.onStartRecording()) recording = true
                        },
                        enabled = enabled,
                        modifier = Modifier.size(56.dp),
                        colors = sendColors()
                    ) {
                        MicGlyph(tint = LocalContentColorOrDefault())
                    }
                }
            }
        }

        if (showCounter && !recording) {
            Text(
                text = "$bytes / ${TextLimit.MAX_BYTES} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = if (bytes >= TextLimit.MAX_BYTES) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = Space.lg, bottom = Space.xs)
            )
        }
    }
}

@Composable
private fun sendColors() = IconButtonDefaults.filledIconButtonColors(
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/** Color del contenido del botón primario (los glifos propios no lo heredan solos fuera de `Icon`). */
@Composable
private fun LocalContentColorOrDefault() = MaterialTheme.colorScheme.onPrimary

// ------------------------------------------------------------------ grabadora

@Composable
private fun RecorderBar(recorder: VoiceRecorder, onCancel: () -> Unit, onSend: () -> Unit) {
    val elapsed by recorder.elapsedMs.collectAsState()
    val level by recorder.level.collectAsState()
    val limit by recorder.limitReached.collectAsState()
    val reduceMotion = rememberReduceMotion()

    val blink = rememberInfiniteTransition(label = "rec")
    val dot by blink.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "dot"
    )

    // Al llegar al máximo se envía sola: no se pierde lo grabado.
    LaunchedEffect(limit) { if (limit) onSend() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(52.dp)) {
            Icon(Icons.Filled.Delete, contentDescription = "Descartar grabación", tint = RadarColors.Coral)
        }
        Surface(
            modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, RadarColors.Coral.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .alpha(if (reduceMotion) 1f else dot)
                        .background(RadarColors.Coral, CircleShape)
                )
                Text(
                    text = formatDuration(elapsed) + " / " + formatDuration(VoiceRecorder.MAX_MS),
                    modifier = Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.width(12.dp))
                // Nivel de entrada: confirma que el micrófono está captando.
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant, CircleShape)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(level.coerceIn(0.04f, 1f))
                            .height(6.dp)
                            .background(RadarColors.Cyan, CircleShape)
                    )
                }
            }
        }
        Spacer(Modifier.width(Space.sm))
        FilledIconButton(
            onClick = onSend,
            modifier = Modifier.size(56.dp),
            colors = sendColors()
        ) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Enviar nota de voz")
        }
    }
}

// ----------------------------------------------------------------- bandeja adjuntos

@Composable
private fun AttachTray(onPhoto: () -> Unit, onCamera: () -> Unit, onVideo: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        AttachTile("Galería", RadarColors.Violet, onPhoto) { ImageGlyph(tint = it) }
        AttachTile("Cámara", RadarColors.Cyan, onCamera) { CameraGlyph(tint = it) }
        AttachTile("Video", RadarColors.Mint, onVideo) { VideoGlyph(tint = it) }
    }
}

@Composable
private fun AttachTile(
    label: String,
    tone: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    glyph: @Composable (androidx.compose.ui.graphics.Color) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn96()
            .clickable(onClickLabel = label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .background(tone.copy(alpha = 0.16f), RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center
        ) { glyph(tone) }
        Text(
            label,
            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun Modifier.widthIn96(): Modifier = this.then(Modifier.width(76.dp))

// ------------------------------------------------------------------ emojis/stickers

@Composable
private fun EmojiTray(onEmoji: (String) -> Unit, onSticker: (Int) -> Unit) {
    var stickers by remember { mutableStateOf(false) }
    var category by remember { mutableStateOf(0) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(286.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            Row(
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                TrayTab("Emojis", !stickers) { stickers = false }
                TrayTab("Stickers", stickers) { stickers = true }
            }

            if (stickers) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(104.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.sm)
                ) {
                    items(StickerCatalog.all, key = { it.id }) { sticker ->
                        Box(
                            Modifier.clickable(onClickLabel = "Enviar ${sticker.label}") {
                                onSticker(sticker.id)
                            }
                        ) { StickerCard(sticker, compact = true) }
                    }
                }
            } else {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = Space.md),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    items(EmojiCatalog.categories.size) { index ->
                        val cat = EmojiCatalog.categories[index]
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(
                                    if (index == category) RadarColors.Cyan.copy(alpha = 0.18f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                    CircleShape
                                )
                                .clickable(onClickLabel = cat.name) { category = index },
                            contentAlignment = Alignment.Center
                        ) { Text(cat.icon, fontSize = 22.sp) }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(46.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(Space.sm)
                ) {
                    items(EmojiCatalog.categories[category].emojis) { emoji ->
                        Box(
                            modifier = Modifier
                                .heightIn(min = 46.dp)
                                .clickable(onClickLabel = "Insertar") { onEmoji(emoji) },
                            contentAlignment = Alignment.Center
                        ) { Text(emoji, fontSize = 27.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrayTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .background(
                if (selected) RadarColors.Cyan.copy(alpha = 0.18f)
                else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(50)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) RadarColors.Cyan else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
