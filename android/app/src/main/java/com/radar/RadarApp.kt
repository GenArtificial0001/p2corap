package com.radar

import android.app.Application

/**
 * Sin inicialización pesada a propósito: en Radar no hay singletons con
 * estado persistente. Todo lo que importa vive en la sesión.
 */
class RadarApp : Application()
