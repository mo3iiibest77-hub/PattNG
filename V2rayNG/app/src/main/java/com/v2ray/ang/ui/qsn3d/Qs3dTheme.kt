package com.v2ray.ang.ui.qsn3d

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

object QsColors {
    val DeepVoid = Color(0xFF040712)
    val MidnightNavy = Color(0xFF0A1020)
    val NebulaSurface = Color(0xDE101830)
    val GlassSurface = Color(0xD8111B36)
    val GlassSurfaceElevated = Color(0xEB162344)
    val GlassBorderTop = Color(0x558BB8FF)
    val GlassBorderBottom = Color(0x1F38BDF8)

    val ElectricCyan = Color(0xFF38BDF8)
    val AuroraMint = Color(0xFF2DD4BF)
    val PlasmaViolet = Color(0xFFA855F7)
    val SolarGold = Color(0xFFF59E0B)
    val CloudflareOrange = Color(0xFFF97316)
    val CloudFrontSky = Color(0xFF0EA5E9)
    val ConnectedEmerald = Color(0xFF10B981)
    val DangerCrimson = Color(0xFFEF4444)

    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFB4C6FC).copy(alpha = 0.86f)
    val TextMuted = Color(0xFF64748B)
}

private val QsDarkScheme = darkColorScheme(
    primary = QsColors.ElectricCyan,
    onPrimary = Color(0xFF031525),
    secondary = QsColors.PlasmaViolet,
    onSecondary = Color.White,
    tertiary = QsColors.AuroraMint,
    background = QsColors.DeepVoid,
    onBackground = QsColors.TextPrimary,
    surface = QsColors.MidnightNavy,
    onSurface = QsColors.TextPrimary,
    surfaceVariant = Color(0xFF1A2444),
    onSurfaceVariant = QsColors.TextSecondary,
    error = QsColors.DangerCrimson,
    onError = Color.White,
)

private val QsTypography = Typography(
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif),
)

@Composable
fun Qs3dTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QsDarkScheme,
        typography = QsTypography,
        content = content
    )
}
