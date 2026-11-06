package com.radar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.radar.chat.Message
import com.radar.chat.TextLimit
import java.text.DateFormat
import java.util.Date

private val MineShape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)
private val TheirsShape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)

/** Aparece el contador de bytes cuando se llega a este porcentaje del límite. */
private const val COUNTER_THRESHOLD = 0.75f

/**
 * Pestaña «Chat»: encabezado de cifrado, mensajes y compositor.
 *
 * El contenido se centra y limita a 760dp: en tablet o plegable abierto las
 * líneas largas cansan, y las burbujas se leen mejor con un ancho acotado.
 */
@Composable
fun ChatPane(
    messages: List<Message>,
    links: Int,
    peerAlias: String?,
    roomLabel: String,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onGoToRadar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val linked = links > 0

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxSize()
        ) {
            ChatHeader(links = links, peerAlias = peerAlias, roomLabel = roomLabel)

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (messages.isEmpty()) {
                    EmptyChat(linked = linked, onGoToRadar = onGoToRadar)
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = Space.md,
                            vertical = Space.sm
                        ),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(messages, key = { it.id }) { message ->
                            Bubble(message)
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                enabled = linked,
                onDraftChange = onDraftChange,
                onSend = onSend
            )
        }
    }
}

@Composable
private fun ChatHeader(links: Int, peerAlias: String?, roomLabel: String) {
    val who = when {
        links <= 0 -> "Sin vínculos"
        !peerAlias.isNullOrBlank() && links == 1 -> peerAlias
        links == 1 -> "1 vínculo"
        else -> "$links vínculos"
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = RadarColors.Mint,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp)) {
                Text(
                    text = who,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = "Cifrado AES-256 · $roomLabel",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun EmptyChat(linked: Boolean, onGoToRadar: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(RadarColors.Cyan.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = RadarColors.Cyan,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(Modifier.height(Space.md))
        Text(
            text = if (linked) "Canal listo" else "Todavía no hay nadie",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = if (linked) {
                "Escribí el primer mensaje. Es cifrado, efímero y desaparece al cerrarse el último vínculo."
            } else {
                "Conectate a un dispositivo desde el radar para empezar a chatear."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (!linked) {
            Spacer(Modifier.height(Space.md))
            FilledTonalButton(onClick = onGoToRadar) { Text("Buscar dispositivos") }
        }
    }
}

@Composable
private fun Bubble(message: Message) {
    val mine = message.isMine
    val time = remember(message.timestamp) {
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp))
    }
    val status = when {
        !mine -> ""
        message.delivered -> "Entregado"
        else -> "Enviado"
    }
    val hops = when (message.hops) {
        0 -> null
        1 -> "vía 1 salto"
        else -> "vía ${message.hops} saltos"
    }
    val spoken = buildString {
        append(if (mine) "Tú" else "Recibido")
        append(": ").append(message.text).append(". ").append(time)
        hops?.let { append(", ").append(it) }
        if (status.isNotEmpty()) append(", ").append(status)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (mine) 48.dp else 0.dp, end = if (mine) 0.dp else 48.dp),
        contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        val bubbleModifier = if (mine) {
            Modifier.clip(MineShape).background(RadarColors.MineBubble)
        } else {
            Modifier
                .clip(TheirsShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        }
        val ink = if (mine) RadarColors.OnCyan else MaterialTheme.colorScheme.onSurface
        val soft = if (mine) RadarColors.OnCyan.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant

        Column(
            modifier = bubbleModifier
                .semantics(mergeDescendants = true) { contentDescription = spoken }
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ink
                )
            }
            Row(
                modifier = Modifier.padding(top = 3.dp).align(Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                hops?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = soft)
                }
                Text(time, style = MaterialTheme.typography.labelSmall, color = soft)
                if (mine) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (message.delivered) RadarColors.OnCyan else soft,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = soft
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val bytes = TextLimit.byteLength(draft)
    val showCounter = bytes >= (TextLimit.MAX_BYTES * COUNTER_THRESHOLD).toInt()
    val canSend = enabled && draft.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = draft,
                onValueChange = { onDraftChange(TextLimit.truncate(it)) },
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = {
                    Text(if (enabled) "Mensaje efímero…" else "Conectate a un par para escribir")
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
            Spacer(Modifier.size(Space.sm))
            FilledIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSend()
                },
                enabled = canSend,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Enviar mensaje"
                )
            }
        }
        if (showCounter) {
            Text(
                text = "$bytes / ${TextLimit.MAX_BYTES} bytes",
                style = MaterialTheme.typography.labelSmall,
                color = if (bytes >= TextLimit.MAX_BYTES) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = Space.md, top = Space.xs)
            )
        }
    }
}
