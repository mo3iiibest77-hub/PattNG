package com.v2ray.ang.ui.qsn3d

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object QsColors {
    val DeepVoid = Color(0xFF040712)
    val MidnightNavy = Color(0xFF0B1124)
    val NebulaSurface = Color(0xCC111932)
    val GlassSurface = Color(0xB3151F3E)
    val GlassBorderTop = Color(0x4D8BB8FF)
    val GlassBorderBottom = Color(0x1A38BDF8)

    val ElectricCyan = Color(0xFF38BDF8)
    val AuroraMint = Color(0xFF2DD4BF)
    val PlasmaViolet = Color(0xFFA855F7)
    val SolarGold = Color(0xFFF59E0B)
    val CloudflareOrange = Color(0xFFF97316)
    val CloudFrontSky = Color(0xFF0EA5E9)
    val ConnectedEmerald = Color(0xFF10B981)
    val DangerCrimson = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFF1F5F9)
    val TextSecondary = Color(0xFF94A3B8)
    val TextMuted = Color(0xFF64748B)
}

private val QsDarkScheme = darkColorScheme(
    primary = QsColors.ElectricCyan,
    onPrimary = Color(0xFF031525),
    secondary = QsColors.PlasmaViolet,
    onSecondary = Color.White,
    tertiary = QsColors.SolarGold,
    background = QsColors.DeepVoid,
    onBackground = QsColors.TextPrimary,
    surface = QsColors.MidnightNavy,
    onSurface = QsColors.TextPrimary,
    surfaceVariant = Color(0xFF1A2444),
    onSurfaceVariant = QsColors.TextSecondary,
    error = QsColors.DangerCrimson,
    onError = Color.White,
)

@Composable
fun Qs3dTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QsDarkScheme,
        content = content
    )
}
