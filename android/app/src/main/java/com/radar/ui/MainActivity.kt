package com.radar.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.radar.ble.RadarPeer
import com.radar.chat.Message
import com.radar.session.RadarSessionManager

class MainActivity : ComponentActivity() {

    private lateinit var session: RadarSessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        session = RadarSessionManager(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadarRoot(session)
                }
            }
        }
    }

    override fun onDestroy() {
        session.stop()
        super.onDestroy()
    }
}

@Composable
private fun RadarRoot(session: RadarSessionManager) {
    val context = LocalContext.current

    val peers by session.ble.peers.collectAsState()
    val connected by session.chat.connected.collectAsState()
    val messages by session.chat.messages.collectAsState()
    val error by session.ble.error.collectAsState()
    val advertising by session.ble.advertising.collectAsState()
    val scanning by session.ble.scanning.collectAsState()
    val stats by session.stats.collectAsState()

    val required = remember { requiredPermissions() }
    var granted by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var started by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted) {
            session.start()
            started = true
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) {
            launcher.launch(required)
        } else {
            session.start()
            started = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Radar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = when {
                        !granted -> "Permisos pendientes"
                        stats.links > 0 -> "Malla activa · ${stats.links} vínculo(s)"
                        scanning || advertising -> "Buscando pares…"
                        else -> "Radar detenido"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats.links > 0) {
                    Text(
                        text = "${stats.relayed} relayado(s) · salto máx ${stats.maxHop}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (started) {
                TextButton(onClick = {
                    session.stop()
                    started = false
                }) {
                    Text("Detener")
                }
            }
        }

        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))
        HorizontalDivider()

        if (connected) {
            ChatPanel(
                messages = messages,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    session.send(draft)
                    draft = ""
                }
            )
        } else {
            PeerList(
                peers = peers,
                scanning = scanning,
                onConnect = { session.connect(it) }
            )
        }
    }
}

@Composable
private fun PeerList(peers: List<RadarPeer>, scanning: Boolean, onConnect: (String) -> Unit) {
    if (peers.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (scanning) "Nadie cerca todavía" else "Radar apagado",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Cada dispositivo conectado reenvía los mensajes: la red crece con la gente.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Spacer(Modifier.height(8.dp)) }
        items(peers, key = { it.address }) { peer ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(peer.address, fontSize = 14.sp)
                        Text(
                            text = "${peer.proximity.name.lowercase()} · ${peer.rssi} dBm",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { onConnect(peer.address) }) {
                        Text("Conectar")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatPanel(
    messages: List<Message>,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (messages.isEmpty()) {
            Text(
                text = "Sesión abierta. Los mensajes se reenvían entre dispositivos; " +
                    "al caer el último vínculo, el chat desaparece.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 16.dp)
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        if (message.isMine) Arrangement.End else Arrangement.Start
                ) {
                    Card {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(text = message.text, fontSize = 14.sp)
                            if (!message.isMine && message.hops > 0) {
                                Text(
                                    text = "${message.hops} salto(s)",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Mensaje efímero…") },
                singleLine = false,
                maxLines = 4
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = draft.isNotBlank()
            ) {
                Text("Enviar")
            }
        }
    }
}

private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(Manifest.permission.BLUETOOTH_SCAN)
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.toTypedArray()
