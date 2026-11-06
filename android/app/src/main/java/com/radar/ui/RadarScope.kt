package com.radar.ui

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.radar.ble.RadarPeer
import kotlin.math.cos
import kotlin.math.sin

/**
 * Respeta «Quitar animaciones» de Accesibilidad (escala de animación = 0).
 * Con eso activo el radar se dibuja quieto y legible.
 */
@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/** Ángulo estable (0..359) para un peer: no cambia mientras siga siendo el mismo dispositivo. */
internal fun bearingOf(address: String): Float =
    ((address.hashCode() and 0x7FFFFFFF) % 360).toFloat()

/** RSSI → fracción del radio del radar (0 = centro, 1 = borde). Más señal, más cerca. */
internal fun radiusOf(rssi: Int): Float {
    val t = ((-rssi) - 40f) / 55f
    return 0.22f + 0.70f * t.coerceIn(0f, 1f)
}

/**
 * Pantalla de radar: anillos de distancia, barrido y un punto por cada peer.
 *
 * Los puntos se «encienden» cuando el barrido los toca y se atenúan después,
 * como un radar real. El ángulo es solo identidad visual (BLE no da dirección);
 * la distancia relativa sí sale del RSSI. Los peers vinculados llevan un aro menta.
 */
@Composable
fun RadarScope(
    peers: List<RadarPeer>,
    linked: Set<String>,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val reduceMotion = rememberReduceMotion()
    val animate = active && !reduceMotion

    val transition = rememberInfiniteTransition(label = "scope")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep"
    )
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "pulse"
    )

    val description = when {
        !active -> "Radar detenido"
        peers.isEmpty() -> "Radar buscando. No hay dispositivos cerca."
        else -> "Radar con ${peers.size} dispositivo(s) cerca, ${linked.size} vinculado(s)."
    }

    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { contentDescription = description }
    ) {
        val c = center
        val r = size.minDimension / 2f
        val hair = 1.dp.toPx()
        val dim = if (active) 1f else 0.45f

        // Disco base con degradado radial.
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color(0xFF123047),
                0.6f to Color(0xFF0B1727),
                1f to Color(0xFF080D16),
                center = c,
                radius = r
            ),
            radius = r
        )

        // Anillos de distancia y retícula.
        for (i in 1..4) {
            drawCircle(
                color = RadarColors.Cyan.copy(alpha = (0.10f + 0.035f * i) * dim),
                radius = r * i / 4f,
                style = Stroke(width = hair)
            )
        }
        val grid = RadarColors.Cyan.copy(alpha = 0.10f * dim)
        drawLine(grid, Offset(c.x - r, c.y), Offset(c.x + r, c.y), hair)
        drawLine(grid, Offset(c.x, c.y - r), Offset(c.x, c.y + r), hair)

        // Onda que sale del centro.
        if (animate) {
            drawCircle(
                color = RadarColors.Cyan.copy(alpha = (1f - pulse) * 0.30f),
                radius = r * pulse,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        // Barrido: cola degradada detrás de una aguja.
        val angle = if (animate) sweep else 38f
        if (active) {
            rotate(degrees = angle, pivot = c) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        0f to Color.Transparent,
                        0.78f to Color.Transparent,
                        1f to RadarColors.Cyan.copy(alpha = 0.34f),
                        center = c
                    ),
                    radius = r
                )
                drawLine(
                    color = RadarColors.Cyan.copy(alpha = 0.85f),
                    start = c,
                    end = Offset(c.x + r, c.y),
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        // Peers.
        peers.forEach { peer ->
            val bearing = bearingOf(peer.address)
            val rad = Math.toRadians(bearing.toDouble())
            val dist = r * radiusOf(peer.rssi)
            val p = Offset(
                c.x + dist * cos(rad).toFloat(),
                c.y + dist * sin(rad).toFloat()
            )
            val isLinked = peer.address in linked

            // Tras el paso del barrido el punto brilla y se apaga gradualmente.
            val sinceSweep = ((angle - bearing) % 360f + 360f) % 360f
            val glow = if (animate) {
                val k = 1f - sinceSweep / 360f
                0.35f + 0.65f * k * k
            } else {
                1f
            }
            val tone = if (isLinked) RadarColors.Mint else RadarColors.Cyan

            drawCircle(tone.copy(alpha = 0.22f * glow), radius = 13.dp.toPx(), center = p)
            drawCircle(tone.copy(alpha = glow), radius = 5.dp.toPx(), center = p)
            if (isLinked) {
                drawCircle(
                    color = RadarColors.Mint.copy(alpha = 0.9f),
                    radius = 9.dp.toPx(),
                    center = p,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }
        }

        // Tú, en el centro.
        drawCircle(RadarColors.Cyan.copy(alpha = 0.25f * dim), radius = 14.dp.toPx(), center = c)
        drawCircle(Color.White.copy(alpha = if (active) 1f else 0.7f), radius = 4.5.dp.toPx(), center = c)

        // Borde exterior.
        drawCircle(
            color = RadarColors.Cyan.copy(alpha = 0.35f * dim),
            radius = r - hair / 2f,
            style = Stroke(width = hair)
        )
    }
}
