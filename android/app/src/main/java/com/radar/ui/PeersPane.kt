package com.radar.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.radar.ble.Proximity
import com.radar.ble.RadarPeer
import com.radar.session.MeshStats

/**
 * Pestaña «Cerca»: radar, estado de la malla y lista de dispositivos.
 *
 * Todo vive en un único `LazyColumn` para que, en pantallas bajas (teléfono
 * apaisado), el radar y la lista se desplacen juntos en vez de pelearse por
 * el espacio.
 */
@Composable
fun PeersPane(
    peers: List<RadarPeer>,
    stats: MeshStats,
    scanning: Boolean,
    bluetoothOn: Boolean,
    connectingTo: String?,
    onConnect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm + Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item(key = "scope") {
            RadarScope(
                peers = peers,
                linked = stats.linkedAddresses,
                active = scanning,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .heightIn(max = 340.dp)
                    .fillMaxWidth()
            )
        }

        item(key = "headline") {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when {
                        !bluetoothOn -> "Bluetooth apagado"
                        peers.isEmpty() -> if (scanning) "Buscando a tu alrededor…" else "Radar en pausa"
                        peers.size == 1 -> "1 dispositivo cerca"
                        else -> "${peers.size} dispositivos cerca"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Cada dispositivo vinculado reenvía los mensajes: la red crece con la gente.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = Space.xs)
                        .widthIn(max = 360.dp)
                )
            }
        }

        if (stats.links > 0) {
            item(key = "stats") { MeshStatsRow(stats) }
        }

        if (peers.isEmpty()) {
            item(key = "empty") {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Space.xs))
                    FilledTonalButton(onClick = onRetry) { Text("Reintentar búsqueda") }
                }
            }
        } else {
            items(peers, key = { it.address }) { peer ->
                PeerCard(
                    peer = peer,
                    linked = peer.address in stats.linkedAddresses,
                    connecting = connectingTo == peer.address,
                    busy = connectingTo != null,
                    onConnect = { onConnect(peer.address) },
                    onDisconnect = { onDisconnect(peer.address) }
                )
            }
        }

        item(key = "footer") { Spacer(Modifier.height(Space.lg)) }
    }
}

@Composable
private fun MeshStatsRow(stats: MeshStats) {
    Row(
        modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        StatTile("${stats.links}", "vínculos", Modifier.weight(1f))
        StatTile("${stats.relayed}", "relayados", Modifier.weight(1f))
        StatTile("${stats.maxHop}", "salto máx", Modifier.weight(1f))
        if (stats.queued > 0) StatTile("${stats.queued}", "en cola", Modifier.weight(1f))
    }
}

@Composable
private fun PeerCard(
    peer: RadarPeer,
    linked: Boolean,
    connecting: Boolean,
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val tail = peer.address.takeLast(5).uppercase()
    val proximity = when (peer.proximity) {
        Proximity.CERCA -> "Muy cerca"
        Proximity.MEDIO -> "A pocos metros"
        Proximity.LEJOS -> "Lejos"
    }
    val summary = "Radar $tail, $proximity, ${peer.rssi} dBm" +
        if (linked) ", vinculado" else ""

    RadarCard(
        modifier = Modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .semantics { contentDescription = summary }
    ) {
        Row(
            modifier = Modifier.padding(Space.md - Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PeerAvatar(seed = peer.address, label = tail.takeLast(2))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Space.md - Space.xs)
            ) {
                Text(
                    text = "Radar · $tail",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalBars(peer.rssi)
                    Spacer(Modifier.size(Space.sm))
                    Text(
                        text = "$proximity · ${peer.rssi} dBm",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            when {
                linked -> OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Soltar") }

                connecting -> FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.size(Space.sm))
                    Text("Uniendo")
                }

                else -> Button(
                    onClick = onConnect,
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Conectar") }
            }
        }
    }
}
