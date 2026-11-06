package com.radar.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Píldora de estado. Es una «live region»: TalkBack anuncia los cambios
 * (por ejemplo «Malla activa · 2 vínculos») sin que el usuario deba buscarlos.
 */
@Composable
fun StatusPill(text: String, color: Color, pulsing: Boolean, modifier: Modifier = Modifier) {
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "pill")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot"
    )

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(8.dp)
                .alpha(if (pulsing && !reduceMotion) dotAlpha else 1f)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** Número grande con rótulo pequeño, para las estadísticas de la malla. */
@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Cuatro barras de intensidad. Sustituye al número crudo de dBm como señal principal. */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val level = when {
        rssi >= -60 -> 4
        rssi >= -70 -> 3
        rssi >= -80 -> 2
        else -> 1
    }
    val on = RadarColors.Cyan
    val off = MaterialTheme.colorScheme.outline
    Canvas(modifier.size(width = 20.dp, height = 14.dp)) {
        val barW = size.width / 7f
        for (i in 0 until 4) {
            val h = size.height * (0.4f + 0.2f * i)
            drawRoundRect(
                color = if (i < level) on else off,
                topLeft = Offset(i * barW * 2f, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2f)
            )
        }
    }
}

/** Marca de Radar: dos anillos, aguja y punto. Se usa en la barra superior. */
@Composable
fun RadarMark(modifier: Modifier = Modifier, tint: Color = RadarColors.Cyan) {
    Canvas(modifier.size(30.dp)) {
        val c = center
        val r = size.minDimension / 2f
        val w = 1.8.dp.toPx()
        drawCircle(tint.copy(alpha = 0.45f), radius = r - w, style = Stroke(w))
        drawCircle(tint.copy(alpha = 0.85f), radius = r * 0.55f, style = Stroke(w))
        drawLine(tint, c, Offset(c.x + r * 0.72f, c.y - r * 0.5f), w)
        drawCircle(Color.White, radius = 2.2.dp.toPx(), center = c)
    }
}

/** Ícono de radar para la barra de navegación (hereda el color del contenido). */
@Composable
fun RadarGlyph(modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val c = center
        val r = size.minDimension / 2f - 1.dp.toPx()
        val w = 1.8.dp.toPx()
        drawCircle(tint, radius = r, style = Stroke(w))
        drawCircle(tint, radius = r * 0.5f, style = Stroke(w))
        drawLine(tint, c, Offset(c.x + r * 0.8f, c.y - r * 0.55f), w)
        drawCircle(tint, radius = 2.dp.toPx(), center = c)
    }
}

/** Ícono de burbuja de chat para la barra de navegación. */
@Composable
fun ChatGlyph(modifier: Modifier = Modifier) {
    val tint = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = 1.8.dp.toPx()
        val body = Size(size.width - 2 * w, size.height * 0.68f)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w, w + 1.dp.toPx()),
            size = body,
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(w)
        )
        val tail = Path().apply {
            moveTo(size.width * 0.28f, body.height + w)
            lineTo(size.width * 0.22f, size.height - 1.dp.toPx())
            lineTo(size.width * 0.5f, body.height + w)
        }
        drawPath(tail, tint, style = Stroke(w))
    }
}

/** Avatar circular con iniciales y un tono derivado de la dirección. */
@Composable
fun PeerAvatar(seed: String, label: String, modifier: Modifier = Modifier) {
    val tones = listOf(
        RadarColors.Cyan, RadarColors.Violet, RadarColors.Mint,
        RadarColors.Amber, RadarColors.CyanSoft
    )
    val tone = tones[(seed.hashCode() and 0x7FFFFFFF) % tones.size]
    Box(
        modifier = modifier
            .size(44.dp)
            .background(tone.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = tone,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

/** Tarjeta base de la app: superficie con borde fino. */
@Composable
fun RadarCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        content = content
    )
}

