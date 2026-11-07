package com.radar.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Estado global que resume la barra superior. */
enum class LinkState { PERMISSIONS, BLUETOOTH_OFF, PAUSED, SEARCHING, ACTIVE }

@Composable
fun RadarTopBar(
    state: LinkState,
    links: Int,
    roomText: String,
    publicRoom: Boolean,
    running: Boolean,
    showRoomText: Boolean,
    onRoom: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (label, tone, pulsing) = when (state) {
        LinkState.PERMISSIONS -> Triple("Permisos pendientes", RadarColors.Amber, false)
        LinkState.BLUETOOTH_OFF -> Triple("Bluetooth apagado", RadarColors.Coral, false)
        LinkState.PAUSED -> Triple("En pausa", MaterialTheme.colorScheme.onSurfaceVariant, false)
        LinkState.SEARCHING -> Triple("Buscando pares", RadarColors.Cyan, true)
        LinkState.ACTIVE -> Triple(
            if (links == 1) "Malla activa · 1 vínculo" else "Malla activa · $links vínculos",
            RadarColors.Mint,
            true
        )
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = Space.md, end = Space.sm, top = Space.sm, bottom = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadarMark()
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = "Radar",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            StatusPill(text = label, color = tone, pulsing = pulsing)
        }

        if (state != LinkState.PERMISSIONS) {
            // Chip de sala: el candado cambia de color si la sala es pública.
            TextButton(
                onClick = onRoom,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (publicRoom) RadarColors.Amber else RadarColors.Mint,
                    modifier = Modifier.size(18.dp)
                )
                if (showRoomText) {
                    Text(
                        text = roomText,
                        modifier = Modifier.padding(start = 8.dp),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
                Icon(
                    imageVector = if (running) Icons.Filled.Close else Icons.Filled.PlayArrow,
                    contentDescription = if (running) "Detener Radar" else "Iniciar Radar",
                    tint = if (running) MaterialTheme.colorScheme.onSurfaceVariant else RadarColors.Cyan
                )
            }
        }
    }
}

@Composable
fun ErrorBanner(
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, RadarColors.Coral.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            if (actionLabel != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

@Composable
fun RadarBottomBar(
    selected: Int,
    unread: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        selectedTextColor = MaterialTheme.colorScheme.onSurface,
        indicatorColor = MaterialTheme.colorScheme.primaryContainer,
        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    )
    NavigationBar(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selected == 0,
            onClick = { onSelect(0) },
            icon = { RadarGlyph() },
            label = { Text("Cerca") },
            colors = colors
        )
        NavigationBarItem(
            selected = selected == 1,
            onClick = { onSelect(1) },
            icon = {
                BadgedBox(
                    badge = {
                        if (unread > 0) {
                            Badge(containerColor = RadarColors.Violet) {
                                Text(if (unread > 99) "99+" else "$unread")
                            }
                        }
                    }
                ) { ChatGlyph() }
            },
            label = { Text(if (unread > 0) "Chat · $unread nuevos" else "Chat") },
            colors = colors
        )
    }
}

@Composable
fun PermissionGate(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        RadarScope(
            peers = emptyList(),
            linked = emptySet(),
            active = false,
            modifier = Modifier.widthIn(max = 200.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            text = "Radar necesita tu permiso",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.md))
        Column(
            modifier = Modifier.widthIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs)
        ) {
            Reason("Bluetooth", "Encontrar y conectar con personas cercanas. Sin internet.")
            Reason("Notificaciones", "Mantener la malla activa con la pantalla apagada.")
            Reason("Cámara (opcional)", "Solo para leer el QR de una sala.")
        }
        Spacer(Modifier.height(Space.md))
        Text(
            text = "No se envía nada a ningún servidor. Los mensajes viven solo en la memoria del teléfono.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 420.dp)
        )
        Spacer(Modifier.height(Space.lg))
        Button(
            onClick = onGrant,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Conceder permisos") }
        TextButton(onClick = onOpenSettings, modifier = Modifier.heightIn(min = 48.dp)) {
            Text("Abrir ajustes de la app")
        }
    }
}

@Composable
private fun Reason(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun PausedState(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        RadarScope(
            peers = emptyList(),
            linked = emptySet(),
            active = false,
            modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth()
        )
        Spacer(Modifier.height(Space.lg))
        Text(
            text = "Radar en pausa",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Space.xs))
        Text(
            text = "No estás visible ni escaneando. El chat de esta sesión ya se borró.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp)
        )
        Spacer(Modifier.height(Space.lg))
        Button(
            onClick = onStart,
            modifier = Modifier.widthIn(max = 360.dp).fillMaxWidth().heightIn(min = 52.dp)
        ) { Text("Iniciar Radar") }
    }
}

/** Color translúcido de fondo para el separador entre paneles en modo ancho. */
internal val PaneDivider = Color(0xFF1B2538)

/** Aviso informativo breve (no es un error): arriba del contenido, se cierra solo. */
@Composable
fun NoticeBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(1.dp, RadarColors.Cyan.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Cerrar aviso",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** Tarea larga en curso (comprimir un video): barra indeterminada y opción de cancelar. */
@Composable
fun BusyBanner(message: String, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.xs),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = RadarColors.Cyan
            )
            Row(
                modifier = Modifier.padding(start = 14.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                TextButton(onClick = onCancel) { Text("Cancelar") }
            }
        }
    }
}
