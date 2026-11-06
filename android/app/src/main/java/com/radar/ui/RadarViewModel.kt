package com.radar.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.radar.session.RadarSessionManager

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

    override fun onCleared() {
        session.stop()
        super.onCleared()
    }
}
