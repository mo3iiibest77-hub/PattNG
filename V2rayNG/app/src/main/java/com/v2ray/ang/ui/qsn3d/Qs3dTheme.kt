package com.v2ray.ang.ui.qsn3d

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.v2ray.ang.R

/**
 * Bundled Vazirmatn typeface (OFL-1.1) for crisp Persian & Latin legibility across the entire app.
 */
val VazirFontFamily = FontFamily(
    Font(R.font.vazirmatn_regular, FontWeight.Normal),
    Font(R.font.vazirmatn_medium, FontWeight.Medium),
    Font(R.font.vazirmatn_medium, FontWeight.SemiBold),
    Font(R.font.vazirmatn_bold, FontWeight.Bold),
    Font(R.font.vazirmatn_extrabold, FontWeight.ExtraBold),
)

/**
 * Frosted Obsidian Glass + Aerospace Cyan/Emerald palette.
 * Balanced translucent card fills let the per-tab 3D hero artwork breathe while keeping contrast crisp.
 */
object QsColors {
    // Structural & Translucent Frosted Glass Surfaces (let animated hero backgrounds shine through)
    val DeepVoid = Color(0xFF070B14)
    val MidnightNavy = Color(0xFF0C1322)
    val NebulaSurface = Color(0x9610192D)
    val GlassSurface = Color(0x9C0E1729)
    val GlassSurfaceElevated = Color(0xB3142038)
    val SurfaceCard = Color(0x9C0E1729)
    val SurfaceElevated = Color(0xB3142038)
    val SurfaceInset = Color(0xAD09101E)

    // Hairline Borders & Glass Specular Highlights
    val BorderSubtle = Color(0xFF263652)
    val BorderStrong = Color(0xFF3A5075)
    val GlassBorderTop = Color(0x7093C5FD)
    val GlassBorderBottom = Color(0x3838BDF8)

    // Primary & Semantic Accents
    val ElectricCyan = Color(0xFF22D3EE)
    val AccentPrimary = Color(0xFF0EA5E9)
    val AuroraMint = Color(0xFF34D399)
    val PlasmaViolet = Color(0xFF818CF8)
    val NeonRose = Color(0xFFF43F5E)
    val SolarGold = Color(0xFFF59E0B)
    val CloudflareOrange = Color(0xFFF38020)
    val CloudFrontSky = Color(0xFF38BDF8)
    val ConnectedEmerald = Color(0xFF10B981)
    val DangerCrimson = Color(0xFFEF4444)

    // Content Hierarchy
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFFCBD5E1)
    val TextMuted = Color(0xFF94A3B8)
}

object QsTextStyles {
    val TabularStat = TextStyle(
        fontFamily = VazirFontFamily,
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
    displaySmall = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
    labelSmall = TextStyle(fontFamily = VazirFontFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
fun Qs3dTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = QsDarkScheme,
        typography = QsTypography,
        content = content
    )
}
