package com.radar.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Íconos propios dibujados en una rejilla de 24×24. El set base de Material (core) no trae
 * micrófono, cámara ni galería; dibujarlos evita sumar la librería extendida (varios MB).
 * Heredan el color del contenido, igual que un `Icon`.
 */
private fun DrawScope.stroke(): Stroke =
    Stroke(width = 1.8f * (size.width / 24f), cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.u(v: Float): Float = v * (size.width / 24f)

@Composable
fun MicGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val st = stroke()
        drawRoundRect(tint, Offset(u(8.5f), u(2.5f)), Size(u(7f), u(12f)), CornerRadius(u(3.5f)), st)
        drawArc(tint, 0f, 180f, false, Offset(u(5f), u(6.5f)), Size(u(14f), u(11f)), style = st)
        drawLine(tint, Offset(u(12f), u(17.5f)), Offset(u(12f), u(21.5f)), st.width, StrokeCap.Round)
    }
}

@Composable
fun SmileGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val st = stroke()
        drawCircle(tint, u(9.5f), Offset(u(12f), u(12f)), style = st)
        drawCircle(tint, u(1.2f), Offset(u(9f), u(10f)), style = Fill)
        drawCircle(tint, u(1.2f), Offset(u(15f), u(10f)), style = Fill)
        drawArc(tint, 20f, 140f, false, Offset(u(7.5f), u(8f)), Size(u(9f), u(8f)), style = st)
    }
}

@Composable
fun CameraGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val st = stroke()
        drawRoundRect(tint, Offset(u(2.5f), u(7f)), Size(u(19f), u(12.5f)), CornerRadius(u(3f)), st)
        drawRoundRect(tint, Offset(u(8f), u(3.8f)), Size(u(8f), u(3.2f)), CornerRadius(u(1f)), st)
        drawCircle(tint, u(3.6f), Offset(u(12f), u(13.2f)), style = st)
    }
}

@Composable
fun ImageGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val st = stroke()
        drawRoundRect(tint, Offset(u(3f), u(4f)), Size(u(18f), u(16f)), CornerRadius(u(3f)), st)
        drawCircle(tint, u(1.7f), Offset(u(9f), u(9.5f)), style = Fill)
        val path = Path().apply {
            moveTo(u(3.5f), u(17.5f))
            lineTo(u(9f), u(12.5f))
            lineTo(u(13f), u(16f))
            lineTo(u(16f), u(13.5f))
            lineTo(u(20.5f), u(17.5f))
        }
        drawPath(path, tint, style = st)
    }
}

@Composable
fun VideoGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val st = stroke()
        drawRoundRect(tint, Offset(u(2.5f), u(6f)), Size(u(13f), u(12f)), CornerRadius(u(3f)), st)
        val path = Path().apply {
            moveTo(u(15.5f), u(10.5f))
            lineTo(u(21.5f), u(7f))
            lineTo(u(21.5f), u(17f))
            lineTo(u(15.5f), u(13.5f))
            close()
        }
        drawPath(path, tint, style = st)
    }
}

@Composable
fun PlayGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        val path = Path().apply {
            moveTo(u(8f), u(5f))
            lineTo(u(19f), u(12f))
            lineTo(u(8f), u(19f))
            close()
        }
        drawPath(path, tint, style = Fill)
    }
}

@Composable
fun PauseGlyph(modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp) {
    Canvas(modifier.size(size)) {
        drawRoundRect(tint, Offset(u(6.5f), u(5f)), Size(u(4f), u(14f)), CornerRadius(u(1.2f)))
        drawRoundRect(tint, Offset(u(13.5f), u(5f)), Size(u(4f), u(14f)), CornerRadius(u(1.2f)))
    }
}
