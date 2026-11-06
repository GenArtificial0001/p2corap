package com.radar.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Sistema de diseño de Radar: una pantalla de señal sobre fondo profundo.
 *
 * - Cian = «tú / señal viva». Violeta = acento secundario. Menta = estado OK.
 *   Ámbar = atención. Coral = error.
 * - Todos los pares texto/fondo superan 4.5:1 (WCAG AA); el texto tenue
 *   (#93A1BA sobre #0E1522) ronda 6.7:1.
 */
object RadarColors {
    val Ink = Color(0xFF070B12)
    val Surface = Color(0xFF0E1522)
    val SurfaceHigh = Color(0xFF162033)
    val Outline = Color(0xFF2A3753)
    val OutlineSoft = Color(0xFF1B2538)

    val Cyan = Color(0xFF3DE0FF)
    val CyanSoft = Color(0xFF7FB2FF)
    val Violet = Color(0xFF8B7CFF)
    val Mint = Color(0xFF4CE3A5)
    val Amber = Color(0xFFFFC857)
    val Coral = Color(0xFFFF6B7A)

    val Text = Color(0xFFE8EEF8)
    val TextDim = Color(0xFF93A1BA)

    /** Texto sobre superficies cian (burbuja propia, botón primario). */
    val OnCyan = Color(0xFF00212B)

    /** Fondo de pantalla: leve resplandor cian arriba, que da profundidad sin distraer. */
    val Backdrop: Brush = Brush.verticalGradient(
        0f to Color(0xFF0B1626),
        0.45f to Ink,
        1f to Ink
    )

    val MineBubble: Brush = Brush.linearGradient(listOf(Cyan, CyanSoft))
}

private val RadarScheme = darkColorScheme(
    primary = RadarColors.Cyan,
    onPrimary = RadarColors.OnCyan,
    primaryContainer = Color(0xFF0B3A47),
    onPrimaryContainer = Color(0xFFBDF1FF),
    secondary = RadarColors.Violet,
    onSecondary = Color(0xFF16124A),
    secondaryContainer = Color(0xFF2A2750),
    onSecondaryContainer = Color(0xFFE0DCFF),
    tertiary = RadarColors.Mint,
    onTertiary = Color(0xFF00281A),
    background = RadarColors.Ink,
    onBackground = RadarColors.Text,
    surface = RadarColors.Surface,
    onSurface = RadarColors.Text,
    surfaceVariant = RadarColors.SurfaceHigh,
    onSurfaceVariant = RadarColors.TextDim,
    outline = RadarColors.Outline,
    outlineVariant = RadarColors.OutlineSoft,
    error = RadarColors.Coral,
    onError = Color(0xFF3B0610),
    errorContainer = Color(0xFF4A1620),
    onErrorContainer = Color(0xFFFFD9DD)
)

private val base = Typography()

private val RadarTypography = Typography(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 20.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.4.sp)
)

private val RadarShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

/** Ritmo de espaciado (múltiplos de 4dp). */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Ancho a partir del cual se muestran radar y chat lado a lado. */
val TwoPaneBreakpoint = 720.dp

@Composable
fun RadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RadarScheme,
        typography = RadarTypography,
        shapes = RadarShapes,
        content = content
    )
}
