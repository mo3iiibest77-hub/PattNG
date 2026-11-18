package com.v2ray.ang.ui.qsn3d

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Tinted Obsidian Slate + Precision Cyan/Emerald design tokens.
 * Structured surface hierarchy (Base -> Elevated -> Card -> Inset) with crisp hairline borders
 * and tabular numeric support so telemetry values stay rock-steady during live updates.
 */
object QsColors {
    // Structural Surfaces (60% canvas + 30% elevated containers)
    val DeepVoid = Color(0xFF0A0F18)
    val MidnightNavy = Color(0xFF0F1624)
    val NebulaSurface = Color(0xFF131C2E)
    val GlassSurface = Color(0xFF151F32)
    val GlassSurfaceElevated = Color(0xFF1B273E)
    val SurfaceCard = Color(0xFF151F32)
    val SurfaceElevated = Color(0xFF1A253B)
    val SurfaceInset = Color(0xFF0D1420)

    // Hairline Borders
    val BorderSubtle = Color(0xFF24324B)
    val BorderStrong = Color(0xFF334668)
    val GlassBorderTop = Color(0xFF2C3D5C)
    val GlassBorderBottom = Color(0xFF1D2A42)

    // Primary & Semantic Accents (10% intentional emphasis)
    val ElectricCyan = Color(0xFF22D3EE)
    val AccentPrimary = Color(0xFF0EA5E9)
    val AuroraMint = Color(0xFF34D399)
    val PlasmaViolet = Color(0xFF818CF8)
    val NeonRose = Color(0xFFF43F5E)
    val SolarGold = Color(0xFFF59E0B)
    val CloudflareOrange = Color(0xFFF97316)
    val CloudFrontSky = Color(0xFF38BDF8)
    val ConnectedEmerald = Color(0xFF10B981)
    val DangerCrimson = Color(0xFFEF4444)

    // Content Hierarchy
    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

object QsTextStyles {
    val TabularStat = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontFeatureSettings = "tnum",
    )
    val MonoCode = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontFeatureSettings = "tnum",
    )
}

private val QsDarkScheme = darkColorScheme(
    primary = QsColors.ElectricCyan,
    onPrimary = Color(0xFF04151E),
    secondary = QsColors.PlasmaViolet,
    onSecondary = Color.White,
    tertiary = QsColors.AuroraMint,
    background = QsColors.DeepVoid,
    onBackground = QsColors.TextPrimary,
    surface = QsColors.MidnightNavy,
    onSurface = QsColors.TextPrimary,
    surfaceVariant = QsColors.SurfaceElevated,
    onSurfaceVariant = QsColors.TextSecondary,
    outline = QsColors.BorderSubtle,
    error = QsColors.DangerCrimson,
    onError = Color.White,
)

private val QsTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        lineHeight = 16.sp,
    ),
)

@Composable
fun Qs3dTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QsDarkScheme,
        typography = QsTypography,
        content = content
    )
}
