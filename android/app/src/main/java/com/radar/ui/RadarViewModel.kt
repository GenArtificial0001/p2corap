package com.radar.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.radar.chat.Message
import com.radar.chat.MessageKind
import com.radar.media.AdpcmCodec
import com.radar.media.ImageCompressor
import com.radar.media.MediaKind
import com.radar.media.VideoCompressor
import com.radar.media.VoicePlayer
import com.radar.media.VoiceRecorder
import com.radar.relay.InviteLink
import com.radar.session.RadarSessionManager
import com.radar.session.SendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Retiene la sesión fuera del ciclo de vida de la Activity.
 *
 * Sin esto, rotar el teléfono recrea la Activity, mata la sesión y genera un
 * código de sala nuevo: el chat se perdía y los peers dejaban de entenderse.
 */
class RadarViewModel(application: Application) : AndroidViewModel(application) {

    val session = RadarSessionManager(application)

    /**
     * La Activity se recrea al rotar, pero el ViewModel no. Si el usuario
     * detuvo Radar, no queremos que la nueva composición lo reencienda.
     */
    var stoppedByUser: Boolean = false

    /**
     * Borrador del mensaje. Vive aquí (RAM) y no en `rememberSaveable`: el
     * estado guardado de una Activity puede sobrevivir a la muerte del proceso,
     * y Radar promete no dejar texto en ningún sitio persistente.
     */
    var draft by mutableStateOf("")

    /** Fotos/videos recibidos que el usuario decidió ver (por defecto se muestran difuminados). */
    val revealed = mutableStateListOf<Long>()

    val recorder = VoiceRecorder()
    val player = VoicePlayer()

    private val settings = getApplication<Application>()
        .getSharedPreferences("radar_settings", Context.MODE_PRIVATE)

    private val _notice = MutableStateFlow<String?>(null)

    /** Aviso breve para el usuario (errores de envío, enlaces inválidos…). */
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _busy = MutableStateFlow<String?>(null)

    /** Tarea larga en curso («Comprimiendo video…»), o null. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private var videoJob: VideoCompressor.Handle? = null

    init {
        // El relé se apaga al detener Radar: al volver a iniciar se restaura el último usado.
        viewModelScope.launch {
            session.running.collect { running ->
                if (!running) return@collect
                if (session.relayUrl.value == null) {
                    savedRelayUrl()?.let { session.enableInternet(it) }
                }
                if (settings.getBoolean(KEY_LAN, false) && !session.lanRunning.value) {
                    session.enableLan()
                }
            }
        }
    }

    fun clearNotice() {
        _notice.value = null
    }

    fun postNotice(text: String) {
        _notice.value = text
    }

    // ------------------------------------------------------------------ envío

    fun sendSticker(id: Int) {
        if (!session.sendSticker(id)) _notice.value = "Conéctate a alguien para enviar stickers"
    }

    fun sendPhoto(uri: Uri) {
        viewModelScope.launch {
            _busy.value = "Preparando foto…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ImageCompressor.fromUri(getApplication<Application>().contentResolver, uri)
                }.getOrNull()
            }
            _busy.value = null
            if (result == null) {
                _notice.value = "No se pudo leer la foto"
            } else {
                report(session.sendMedia(MediaKind.IMAGE, result.jpeg, 0, result.width, result.height))
            }
        }
    }

    /** Foto sacada con la cámara (miniatura devuelta por el sistema). */
    fun sendBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            _busy.value = "Preparando foto…"
            val result = withContext(Dispatchers.Default) {
                runCatching { ImageCompressor.fromBitmap(bitmap) }.getOrNull()
            }
            _busy.value = null
            if (result == null) {
                _notice.value = "No se pudo preparar la foto"
            } else {
                report(session.sendMedia(MediaKind.IMAGE, result.jpeg, 0, result.width, result.height))
            }
        }
    }

    fun sendVideo(uri: Uri) {
        if (videoJob != null) return
        _busy.value = "Comprimiendo video…"
        // Transformer exige el hilo principal; los callbacks también llegan en él.
        videoJob = VideoCompressor.compress(getApplication(), uri) { result, error ->
            videoJob = null
            _busy.value = null
            if (result == null) {
                _notice.value = error ?: "No se pudo preparar el video"
            } else {
                report(
                    session.sendMedia(
                        MediaKind.VIDEO, result.bytes, result.durationMs, result.width, result.height
                    )
                )
            }
        }
    }

    fun cancelBusy() {
        videoJob?.cancel()
        videoJob = null
        _busy.value = null
    }

    // ------------------------------------------------------------ nota de voz

    fun startRecording(): Boolean {
        player.stop()
        val ok = recorder.start()
        if (!ok) _notice.value = "No se pudo acceder al micrófono"
        return ok
    }

    /** Detiene la grabación; con [send] la envía, si no la descarta. */
    fun finishRecording(send: Boolean) {
        if (!send) {
            recorder.cancel()
            return
        }
        val pcm = recorder.stop()
        if (pcm == null) {
            _notice.value = "Mantén más tiempo para grabar una nota"
            return
        }
        viewModelScope.launch {
            val encoded = withContext(Dispatchers.Default) { AdpcmCodec.encode(pcm) }
            report(session.sendMedia(MediaKind.AUDIO, encoded, AdpcmCodec.duration(pcm.size)))
        }
    }

    fun toggleAudio(message: Message) {
        val data = message.media ?: return
        if (message.kind == MessageKind.AUDIO) player.toggle(message.id, data)
    }

    // ------------------------------------------------------- Wi-Fi local

    /** Activa o apaga la búsqueda de teléfonos en la misma red Wi-Fi (sin servidor ni internet). */
    fun setLan(enabled: Boolean) {
        if (enabled) session.enableLan() else session.disableLan()
        settings.edit().putBoolean(KEY_LAN, enabled).apply()
    }

    // ------------------------------------------------------------ internet

    fun enableInternet(url: String): Boolean {
        val ok = session.enableInternet(url)
        if (ok) {
            settings.edit().putString(KEY_RELAY, session.relayUrl.value).apply()
        } else {
            _notice.value = "La dirección debe empezar con wss://"
        }
        return ok
    }

    fun disableInternet() {
        session.disableInternet()
        settings.edit().remove(KEY_RELAY).apply()
    }

    /** Procesa un enlace de invitación (`https://…/join#CODIGO` o `radar://join?…`). */
    fun handleInvite(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val invite = InviteLink.parse(text)
        if (invite == null) {
            _notice.value = "Ese enlace no es una invitación de Radar válida"
            return false
        }
        val ok = session.acceptInvite(invite.relayUrl, invite.code)
        if (ok && invite.relayUrl == null) {
            settings.edit().putBoolean(KEY_LAN, true).apply()
            _notice.value = "Te uniste a la sala. Buscando en tu red Wi-Fi…"
        } else if (ok) {
            settings.edit().putString(KEY_RELAY, session.relayUrl.value).apply()
            _notice.value = "Te uniste a la sala. Conectando por internet…"
        } else {
            _notice.value = "No se pudo usar la invitación"
        }
        return ok
    }

    private fun savedRelayUrl(): String? = settings.getString(KEY_RELAY, null)

    // ---------------------------------------------------------------- varios

    fun reveal(id: Long) {
        if (id !in revealed) revealed.add(id)
    }

    private fun report(result: SendResult) {
        when (result) {
            SendResult.OK -> Unit
            SendResult.NO_PEERS -> _notice.value = "Conéctate a alguien para enviar archivos"
            SendResult.TOO_BIG -> _notice.value = "El archivo es demasiado grande para la malla"
            SendResult.BUSY -> _notice.value = "Ya hay envíos en curso. Espera a que terminen."
            SendResult.ERROR -> _notice.value = "No se pudo enviar. Reintenta."
        }
    }

    override fun onCleared() {
        player.stop()
        recorder.cancel()
        videoJob?.cancel()
        session.stop()
        super.onCleared()
    }

    private companion object {
        const val KEY_RELAY = "relay_url"
        const val KEY_LAN = "lan_enabled"
    }
}
