package com.radar.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.radar.ble.RadarPeer
import com.radar.chat.Message

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // El chat no debe quedar en screenshots ni en el carrusel de recientes.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    RadarRoot(viewModel)
                }
            }
        }
    }
}

@Composable
private fun RadarRoot(vm: RadarViewModel) {
    val context = LocalContext.current
    val session = vm.session

    val peers by session.ble.peers.collectAsState()
    val connected by session.chat.connected.collectAsState()
    val messages by session.chat.messages.collectAsState()
    val error by session.ble.error.collectAsState()
    val advertising by session.ble.advertising.collectAsState()
    val scanning by session.ble.scanning.collectAsState()
    val bluetoothOn by session.ble.bluetoothOn.collectAsState()
    val stats by session.stats.collectAsState()
    val roomCode by session.roomCode.collectAsState()
    val running by session.running.collectAsState()

    val required = remember { requiredPermissions() }
    var granted by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }
    var draft by remember { mutableStateOf("") }
    var showRoomDialog by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted) {
            vm.stoppedByUser = false
            session.start()
        }
    }

    fun startIfPossible() {
        if (!granted) {
            launcher.launch(required)
            return
        }
        vm.stoppedByUser = false
        session.start()
    }

    LaunchedEffect(Unit) {
        if (granted) {
            if (!vm.stoppedByUser && !session.running.value) session.start()
        } else {
            launcher.launch(required)
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
                        !bluetoothOn -> "Bluetooth apagado"
                        stats.links > 0 -> "Malla activa · ${stats.links} vínculo(s)"
                        scanning || advertising -> "Buscando pares…"
                        else -> "Radar detenido"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stats.links > 0) {
                    Text(
                        text = "${stats.relayed} relayado(s) · salto máx ${stats.maxHop}" +
                            if (stats.queued > 0) " · ${stats.queued} en cola" else "",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "Sala · $roomCode",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showRoomDialog = true }) {
                Text("Sala")
            }
            if (running) {
                TextButton(onClick = {
                    vm.stoppedByUser = true
                    session.stop()
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

        if (showRoomDialog) {
            RoomDialog(
                current = roomCode,
                onJoin = { code ->
                    val ok = session.joinRoom(code)
                    if (ok) showRoomDialog = false
                    ok
                },
                onNewRoom = {
                    session.newRoom()
                    showRoomDialog = false
                },
                onDismiss = { showRoomDialog = false }
            )
        }

        when {
            !granted -> PermissionGate(
                onRetry = { launcher.launch(required) },
                onOpenSettings = {
                    runCatching {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            )

            !running -> Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Radar está detenido", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { startIfPossible() }) { Text("Iniciar Radar") }
            }

            connected -> ChatPanel(
                messages = messages,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    session.send(draft)
                    draft = ""
                }
            )

            else -> PeerList(
                peers = peers,
                scanning = scanning,
                bluetoothOn = bluetoothOn,
                onConnect = { session.connect(it) },
                onRetry = { startIfPossible() }
            )
        }
    }
}

@Composable
private fun PermissionGate(onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Radar necesita Bluetooth y notificaciones",
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "El escaneo BLE usa el radio del teléfono. No se envía nada a ningún servidor.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Conceder permisos") }
        TextButton(onClick = onOpenSettings) { Text("Abrir ajustes de la app") }
    }
}

@Composable
private fun PeerList(
    peers: List<RadarPeer>,
    scanning: Boolean,
    bluetoothOn: Boolean,
    onConnect: (String) -> Unit,
    onRetry: () -> Unit
) {
    if (peers.isEmpty()) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = when {
                    !bluetoothOn -> "Activá Bluetooth para empezar"
                    scanning -> "Nadie cerca todavía"
                    else -> "Radar apagado"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Cada dispositivo conectado reenvía los mensajes: la red crece con la gente.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Reintentar") }
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
                text = "Sesión cifrada. Al caer el último vínculo, el chat desaparece.",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (message.hops > 0) {
                                    Text(
                                        text = "${message.hops} salto(s)",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (message.isMine) {
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (message.delivered) "✓ entregado" else "○ enviado",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
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

@Composable
private fun RoomDialog(
    current: String,
    onJoin: (String) -> Boolean,
    onNewRoom: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var code by remember { mutableStateOf(current) }
    var error by remember { mutableStateOf<String?>(null) }
    var copied by remember { mutableStateOf(false) }

    val qr = remember(current) { qrBitmap(current) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            code = contents
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sala cifrada") },
        text = {
            Column {
                Text(
                    "Compartí este código con el grupo. La clave nunca viaja por la red.",
                    fontSize = 13.sp
                )

                qr?.let {
                    Spacer(Modifier.height(12.dp))
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "Código QR de la sala",
                        modifier = Modifier
                            .size(180.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = {
                        code = it
                        error = null
                    },
                    label = { Text("Código de sala") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(ClipData.newPlainText("Sala Radar", current))
                        copied = true
                    }) {
                        Text(if (copied) "Copiado" else "Copiar")
                    }
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "Unite a mi sala Radar: $current")
                        }
                        runCatching {
                            context.startActivity(Intent.createChooser(share, "Compartir sala"))
                        }
                    }) {
                        Text("Compartir")
                    }
                    TextButton(onClick = {
                        scanner.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt("Escaneá el QR de la sala")
                                .setBeepEnabled(false)
                                .setOrientationLocked(true)
                        )
                    }) {
                        Text("Escanear")
                    }
                }

                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onJoin(code)) error = "Código inválido: 20 caracteres (A-Z, 0-9)"
            }) {
                Text("Unirse")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onNewRoom) { Text("Sala nueva") }
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }
        }
    )
}

private fun qrBitmap(text: String, size: Int = 512): Bitmap? = runCatching {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val pixels = IntArray(size * size)
    for (y in 0 until size) {
        val row = y * size
        for (x in 0 until size) {
            pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}.getOrNull()

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
