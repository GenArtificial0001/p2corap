package com.radar.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.radar.chat.MessageKind
import com.radar.relay.InviteLink
import com.radar.relay.RelayClient
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: RadarViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Con targetSdk 35 Android 15 fuerza el modo borde a borde: sin manejar los
        // insets, el encabezado quedaba debajo de la barra de estado.
        val transparent = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(transparent),
            navigationBarStyle = SystemBarStyle.dark(transparent)
        )

        // El chat no debe quedar en screenshots ni en el carrusel de recientes.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }

        // Invitación recibida al abrir la app desde un enlace (solo la primera vez, no al rotar).
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            RadarTheme {
                RadarRoot(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) viewModel.handleInvite(intent.dataString)
    }
}

private const val TAB_NEARBY = 0
private const val TAB_CHAT = 1

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RadarRoot(vm: RadarViewModel) {
    val context = LocalContext.current
    val session = vm.session

    val peers by session.ble.peers.collectAsState()
    val connected by session.chat.connected.collectAsState()
    val messages by session.chat.messages.collectAsState()
    val peerAlias by session.chat.peerAlias.collectAsState()
    val bleError by session.ble.error.collectAsState()
    val advertising by session.ble.advertising.collectAsState()
    val scanning by session.ble.scanning.collectAsState()
    val bluetoothOn by session.ble.bluetoothOn.collectAsState()
    val stats by session.stats.collectAsState()
    val roomCode by session.roomCode.collectAsState()
    val running by session.running.collectAsState()
    val connectingTo by session.connectingTo.collectAsState()
    val linkError by session.linkError.collectAsState()
    val lanRunning by session.lanRunning.collectAsState()
    val lanPeers by session.lanPeers.collectAsState()
    val relayUrl by session.relayUrl.collectAsState()
    val relayState by session.relayState.collectAsState()
    val relayMembers by session.relayMembers.collectAsState()
    val notice by vm.notice.collectAsState()
    val busy by vm.busy.collectAsState()
    val playingId by vm.player.playingId.collectAsState()
    val positionMs by vm.player.positionMs.collectAsState()

    val required = remember { requiredPermissions() }
    var granted by remember {
        mutableStateOf(
            required.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    var showRoom by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(TAB_NEARBY) }
    var viewingId by remember { mutableStateOf<Long?>(null) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        granted = result.values.all { it }
        if (granted) {
            vm.stoppedByUser = false
            session.start()
        }
    }

    // ---- selectores del sistema para adjuntar (no necesitan permisos de almacenamiento)

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.sendPhoto(uri) }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) vm.sendVideo(uri) }

    val cameraShot = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap -> if (bitmap != null) vm.sendBitmap(bitmap) }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) cameraShot.launch(null) }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok ->
        // El gesto original ya terminó: se pide tocar el micrófono otra vez.
        if (ok) vm.postNotice("Permiso concedido. Toca el micrófono para grabar.")
    }

    fun has(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

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

    // Los avisos se cierran solos a los 5 s.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(5_000)
            vm.clearNotice()
        }
    }

    val links = stats.links
    val state = when {
        !granted -> LinkState.PERMISSIONS
        !bluetoothOn && links == 0 -> LinkState.BLUETOOTH_OFF
        !running -> LinkState.PAUSED
        links > 0 -> LinkState.ACTIVE
        else -> LinkState.SEARCHING
    }

    // Al lograr el primer vínculo se salta al chat; si todos se caen, se vuelve al radar.
    var previousLinks by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(links) {
        if (previousLinks == 0 && links > 0) tab = TAB_CHAT
        if (links == 0) tab = TAB_NEARBY
        previousLinks = links
    }

    val errorText = linkError ?: bleError
    val roomText = roomLabel(roomCode)

    val internetUi = InternetUi(
        lanEnabled = lanRunning,
        lanPeers = lanPeers,
        roomLink = session.roomLink(),
        onToggleLan = vm::setLan,
        relayUrl = relayUrl,
        state = relayState,
        members = relayMembers,
        inviteLink = session.inviteLink(),
        onConnect = { text ->
            if (InviteLink.parse(text) != null) vm.handleInvite(text) else vm.enableInternet(text)
        },
        onDisable = vm::disableInternet
    )
    val internetLabel = when {
        lanPeers > 0 -> "Wi-Fi local"
        relayUrl != null && relayState == RelayClient.State.CONNECTED -> "Internet"
        else -> null
    }

    val composerActions = ComposerActions(
        onSend = {
            session.send(vm.draft)
            vm.draft = ""
        },
        onSticker = vm::sendSticker,
        onPickPhoto = {
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onPickVideo = {
            videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
        },
        onTakePhoto = {
            if (has(Manifest.permission.CAMERA)) cameraShot.launch(null)
            else cameraPermission.launch(Manifest.permission.CAMERA)
        },
        onStartRecording = {
            if (has(Manifest.permission.RECORD_AUDIO)) {
                vm.startRecording()
            } else {
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                false
            }
        },
        onFinishRecording = vm::finishRecording
    )

    val mediaActions = MediaActions(
        revealed = vm.revealed,
        playingId = playingId,
        positionMs = positionMs,
        onReveal = vm::reveal,
        onToggleAudio = vm::toggleAudio,
        onOpen = { viewingId = it.id },
        onCancel = session::cancelTransfer
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(RadarColors.Backdrop)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
            )
    ) {
        // BoxWithConstraintsScope es un DSL marker: dentro de Column/Row no se puede
        // leer maxWidth con receptor implícito, así que se captura aquí.
        val availableWidth = maxWidth
        val twoPane = availableWidth >= TwoPaneBreakpoint
        val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        val chatVisible = twoPane || tab == TAB_CHAT
        val gated = state == LinkState.PERMISSIONS || state == LinkState.PAUSED
        val showBottomBar = !twoPane && !gated && !keyboardOpen

        // Mensajes sin leer: los recibidos mientras se está en otra pestaña.
        var seen by remember { mutableIntStateOf(0) }
        LaunchedEffect(chatVisible, messages.size) {
            if (chatVisible || messages.size < seen) seen = messages.size
        }
        val unread = if (chatVisible) 0 else (messages.size - seen).coerceAtLeast(0)

        BackHandler(enabled = !twoPane && tab == TAB_CHAT) { tab = TAB_NEARBY }

        Column(modifier = Modifier.fillMaxSize()) {
            RadarTopBar(
                state = state,
                links = links,
                roomText = roomText,
                publicRoom = isPublicRoom(roomCode),
                running = running,
                showRoomText = availableWidth >= 400.dp,
                onRoom = { showRoom = true },
                onToggle = {
                    if (running) {
                        vm.stoppedByUser = true
                        session.stop()
                    } else {
                        startIfPossible()
                    }
                }
            )

            if (errorText != null && state != LinkState.PERMISSIONS) {
                ErrorBanner(
                    message = errorText,
                    actionLabel = if (!bluetoothOn) "Ajustes" else null,
                    onAction = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        }
                    }
                )
            }
            busy?.let { BusyBanner(message = it, onCancel = vm::cancelBusy) }
            notice?.let { NoticeBanner(message = it, onDismiss = vm::clearNotice) }

            // Sin barra inferior (modo ancho o teclado abierto) el contenido
            // debe respetar el borde inferior del sistema y el teclado.
            val contentModifier = Modifier
                .weight(1f)
                .fillMaxSize()
                .let {
                    if (showBottomBar) {
                        it
                    } else {
                        it.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    }
                }

            Column(modifier = contentModifier) {
                when (state) {
                    LinkState.PERMISSIONS -> PermissionGate(
                        onGrant = { launcher.launch(required) },
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

                    LinkState.PAUSED -> PausedState(onStart = { startIfPossible() })

                    else -> {
                        val nearby: @Composable (Modifier) -> Unit = { m ->
                            PeersPane(
                                peers = peers,
                                stats = stats,
                                scanning = scanning || advertising,
                                bluetoothOn = bluetoothOn,
                                connectingTo = connectingTo,
                                onConnect = { session.connect(it) },
                                onDisconnect = { session.disconnect(it) },
                                onRetry = { startIfPossible() },
                                modifier = m
                            )
                        }
                        val chat: @Composable (Modifier) -> Unit = { m ->
                            ChatPane(
                                messages = messages,
                                links = if (connected) links else 0,
                                peerAlias = peerAlias,
                                roomLabel = roomText,
                                internetLabel = internetLabel,
                                draft = vm.draft,
                                recorder = vm.recorder,
                                composer = composerActions,
                                media = mediaActions,
                                onDraftChange = { vm.draft = it },
                                onGoToRadar = { tab = TAB_NEARBY },
                                modifier = m
                            )
                        }

                        if (twoPane) {
                            val sidebar = (availableWidth * 0.4f).coerceIn(340.dp, 440.dp)
                            Row(modifier = Modifier.fillMaxSize()) {
                                nearby(Modifier.width(sidebar).fillMaxHeight())
                                VerticalDivider(color = PaneDivider)
                                chat(Modifier.weight(1f).fillMaxHeight())
                            }
                        } else {
                            Crossfade(
                                targetState = tab,
                                modifier = Modifier.fillMaxSize(),
                                label = "tab"
                            ) { current ->
                                if (current == TAB_CHAT) {
                                    chat(Modifier.fillMaxSize())
                                } else {
                                    nearby(Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                }
            }

            if (showBottomBar) {
                RadarBottomBar(
                    selected = tab,
                    unread = unread,
                    onSelect = { tab = it }
                )
            }
        }
    }

    if (showRoom) {
        RoomSheet(
            current = roomCode,
            internet = internetUi,
            onJoin = { session.joinRoom(it) },
            onNewRoom = { session.newRoom() },
            onDismiss = { showRoom = false }
        )
    }

    // Visores a pantalla completa de lo que el usuario tocó.
    val viewing = messages.firstOrNull { it.id == viewingId }
    if (viewing != null) {
        when (viewing.kind) {
            MessageKind.IMAGE -> ImageViewer(viewing) { viewingId = null }
            MessageKind.VIDEO -> VideoViewer(viewing) { viewingId = null }
            else -> Unit
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
