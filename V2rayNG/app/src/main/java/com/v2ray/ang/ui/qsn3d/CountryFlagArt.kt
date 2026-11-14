package com.v2ray.ang.ui.qsn3d

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Extracts a canonical ISO-3166-1 alpha-2 country code (e.g. "SE", "DE", "US")
 * from an exit country code, country name, or server remark flag emoji.
 */
fun normalizeCountryCode(rawCountry: String, remarksFallback: String = ""): String {
    val cleaned = rawCountry.trim()
    if (cleaned.length == 2 && cleaned.all { it in 'A'..'Z' || it in 'a'..'z' }) {
        return cleaned.uppercase(Locale.US)
    }

    // Check Regional Indicator Flag Emojis in rawCountry or remarksFallback (e.g. 🇸🇪 -> SE)
    for (source in listOf(cleaned, remarksFallback)) {
        val codePoints = source.codePoints().toArray()
        for (i in 0 until codePoints.size - 1) {
            val a = codePoints[i]
            val b = codePoints[i + 1]
            if (a in 0x1F1E6..0x1F1FF && b in 0x1F1E6..0x1F1FF) {
                val c1 = ('A'.code + (a - 0x1F1E6)).toChar()
                val c2 = ('A'.code + (b - 0x1F1E6)).toChar()
                return "$c1$c2"
            }
        }
    }

    val lower = (cleaned.ifEmpty { remarksFallback }).lowercase(Locale.US)
    val byKeyword = listOf(
        "sweden" to "SE", "stockholm" to "SE",
        "germany" to "DE", "frankfurt" to "DE", "berlin" to "DE",
        "netherlands" to "NL", "holland" to "NL", "amsterdam" to "NL",
        "finland" to "FI", "helsinki" to "FI",
        "united states" to "US", "america" to "US", "new york" to "US", "dallas" to "US", "los angeles" to "US",
        "united kingdom" to "GB", "england" to "GB", "london" to "GB", "britain" to "GB",
        "france" to "FR", "paris" to "FR",
        "turkey" to "TR", "istanbul" to "TR", "ankara" to "TR",
        "emirates" to "AE", "dubai" to "AE", "uae" to "AE",
        "japan" to "JP", "tokyo" to "JP",
        "singapore" to "SG",
        "canada" to "CA", "toronto" to "CA",
        "switzerland" to "CH", "zurich" to "CH",
        "norway" to "NO", "oslo" to "NO",
        "poland" to "PL", "warsaw" to "PL",
        "romania" to "RO", "bucharest" to "RO",
        "austria" to "AT", "vienna" to "AT",
        "italy" to "IT", "milan" to "IT",
        "spain" to "ES", "madrid" to "ES",
        "ukraine" to "UA", "kyiv" to "UA",
        "denmark" to "DK", "copenhagen" to "DK",
        "ireland" to "IE", "dublin" to "IE",
        "belgium" to "BE", "brussels" to "BE",
        "czech" to "CZ", "prague" to "CZ",
        "luxembourg" to "LU",
        "hong kong" to "HK",
        "korea" to "KR", "seoul" to "KR",
        "australia" to "AU", "sydney" to "AU",
        "russia" to "RU", "moscow" to "RU",
        "india" to "IN", "mumbai" to "IN",
        "brazil" to "BR",
        "portugal" to "PT",
        "greece" to "GR",
        "hungary" to "HU",
        "bulgaria" to "BG",
    )
    for ((kw, iso) in byKeyword) {
        if (kw in lower) return iso
    }
    return ""
}

fun countryDisplayLabel(isoCode: String, isPersian: Boolean): String {
    if (isoCode.length != 2) return isoCode
    val upper = isoCode.uppercase(Locale.US)
    val faNames = mapOf(
        "SE" to "سوئد", "DE" to "آلمان", "NL" to "هلند", "FI" to "فنلاند",
        "US" to "آمریکا", "GB" to "بریتانیا", "FR" to "فرانسه", "TR" to "ترکیه",
        "AE" to "امارات", "JP" to "ژاپن", "SG" to "سنگاپور", "CA" to "کانادا",
        "CH" to "سوئیس", "NO" to "نروژ", "PL" to "لهستان", "RO" to "رومانی",
        "AT" to "اتریش", "IT" to "ایتالیا", "ES" to "اسپانیا", "UA" to "اوکراین",
        "DK" to "دانمارک", "IE" to "ایرلند", "BE" to "بلژیک", "CZ" to "جمهوری چک",
        "LU" to "لوکزامبورگ", "HK" to "هنگ‌کنگ", "KR" to "کره جنوبی", "AU" to "استرالیا",
        "RU" to "روسیه", "IN" to "هند", "BR" to "برزیل", "PT" to "پرتغال",
        "GR" to "یونان", "HU" to "مجارستان", "BG" to "بلغارستان",
    )
    if (isPersian) {
        faNames[upper]?.let { return "$it ($upper)" }
    }
    val locName = runCatching {
        Locale("", upper).getDisplayCountry(if (isPersian) Locale("fa") else Locale.ENGLISH)
    }.getOrNull().orEmpty()
    return if (locName.isNotBlank() && !locName.equals(upper, ignoreCase = true)) {
        "$locName ($upper)"
    } else upper
}

@DrawableRes
private fun silkFlagDrawableFor(isoCode: String): Int? = when (isoCode.uppercase(Locale.US)) {
    "SE" -> R.drawable.np_flag_se
    "DE" -> R.drawable.np_flag_de
    "NL" -> R.drawable.np_flag_nl
    "US" -> R.drawable.np_flag_us
    "GB" -> R.drawable.np_flag_gb
    "FR" -> R.drawable.np_flag_fr
    else -> null
}

/**
 * Windscribe-inspired high-resolution 3D flowing Country Flag backdrop.
 * Uses dedicated 3D satin-silk WebP wallpapers for major VPN regions and falls back
 * seamlessly to crisp animated geometric Canvas flag rendering for any other country.
 */
@Composable
fun WindscribeCountryFlagBackdrop(
    countryCode: String,
    motionActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val iso = countryCode.uppercase(Locale.US)
    val drift = if (motionActive) {
        rememberInfiniteTransition(label = "flag_drift").animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
            label = "flag_phase"
        ).value
    } else 0.7f

    val scaleBreath = 1.04f + 0.03f * sin(drift)
    val shiftX = 10f * cos(drift)
    val shiftY = 8f * sin(drift * 0.7f)
    val drawableRes = silkFlagDrawableFor(iso)

    Box(modifier = modifier.fillMaxSize()) {
        if (drawableRes != null) {
            Image(
                painter = painterResource(drawableRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleBreath
                        scaleY = scaleBreath
                        translationX = shiftX
                        translationY = shiftY
                    }
            )
        } else {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scaleBreath
                        scaleY = scaleBreath
                        translationX = shiftX
                    }
            ) {
                drawCrispCountryFlag(iso)
                drawSilkWaveLightingOverlay(drift)
            }
        }
        // Soft Windscribe-inspired atmospheric gradient blend so glass cards & foreground text stay legible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0x52040814),
                            Color(0x2E040814),
                            Color(0xA6040814),
                        )
                    )
                )
        )
    }
}

/**
 * Circular Windscribe-style country flag medallion with glossy specular bezel.
 */
@Composable
fun CountryFlagMedallion(
    countryCode: String,
    size: Dp = 36.dp,
    borderColor: Color = QsColors.ConnectedEmerald,
    modifier: Modifier = Modifier,
) {
    val iso = countryCode.uppercase(Locale.US)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(1.5.dp, borderColor.copy(alpha = 0.85f), CircleShape)
            .background(Color(0xFF0A1228))
    ) {
        val drawableRes = silkFlagDrawableFor(iso)
        if (drawableRes != null) {
            Image(
                painter = painterResource(drawableRes),
                contentDescription = iso,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCrispCountryFlag(iso)
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x38FFFFFF), Color.Transparent),
                        center = Offset(this.size.width * 0.3f, this.size.height * 0.25f),
                        radius = this.size.minDimension * 0.7f
                    )
                )
            }
        }
    }
}

private fun DrawScope.drawSilkWaveLightingOverlay(phase: Float) {
    val bands = 5
    for (i in 0 until bands) {
        val t = (i / bands.toFloat()) + 0.08f * sin(phase + i * 1.1f)
        val x0 = size.width * (t - 0.12f)
        val x1 = size.width * (t + 0.16f)
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Color(0x28FFFFFF), Color(0x38000000), Color.Transparent),
                startX = x0,
                endX = x1
            )
        )
    }
}

private fun DrawScope.drawCrispCountryFlag(iso: String) {
    val w = size.width
    val h = size.height
    when (iso) {
        "SE" -> {
            drawRect(Color(0xFF005B99))
            drawRect(Color(0xFFFECD00), Offset(w * 0.28f, 0f), Size(w * 0.14f, h))
            drawRect(Color(0xFFFECD00), Offset(0f, h * 0.40f), Size(w, h * 0.20f))
        }
        "FI" -> {
            drawRect(Color(0xFFF5F8FC))
            drawRect(Color(0xFF003580), Offset(w * 0.28f, 0f), Size(w * 0.15f, h))
            drawRect(Color(0xFF003580), Offset(0f, h * 0.38f), Size(w, h * 0.24f))
        }
        "NO" -> {
            drawRect(Color(0xFFBA0C2F))
            drawRect(Color.White, Offset(w * 0.26f, 0f), Size(w * 0.18f, h))
            drawRect(Color.White, Offset(0f, h * 0.36f), Size(w, h * 0.28f))
            drawRect(Color(0xFF00205B), Offset(w * 0.30f, 0f), Size(w * 0.10f, h))
            drawRect(Color(0xFF00205B), Offset(0f, h * 0.42f), Size(w, h * 0.16f))
        }
        "DK" -> {
            drawRect(Color(0xFFC60C30))
            drawRect(Color.White, Offset(w * 0.28f, 0f), Size(w * 0.12f, h))
            drawRect(Color.White, Offset(0f, h * 0.42f), Size(w, h * 0.16f))
        }
        "CH" -> {
            drawRect(Color(0xFFD52B1E))
            drawRect(Color.White, Offset(w * 0.43f, h * 0.20f), Size(w * 0.14f, h * 0.60f))
            drawRect(Color.White, Offset(w * 0.30f, h * 0.40f), Size(w * 0.40f, h * 0.20f))
        }
        "DE" -> horizontalStripes(Color(0xFF0F1117), Color(0xFFDD0000), Color(0xFFFFCE00))
        "NL" -> horizontalStripes(Color(0xFFAE1C28), Color.White, Color(0xFF21468B))
        "LU" -> horizontalStripes(Color(0xFFED2939), Color.White, Color(0xFF00A1DE))
        "AT" -> horizontalStripes(Color(0xFFED2939), Color.White, Color(0xFFED2939))
        "HU" -> horizontalStripes(Color(0xFFCD2A3E), Color.White, Color(0xFF436F4D))
        "BG" -> horizontalStripes(Color.White, Color(0xFF00966E), Color(0xFFD62612))
        "EE" -> horizontalStripes(Color(0xFF0072CE), Color(0xFF111318), Color.White)
        "LT" -> horizontalStripes(Color(0xFFFDB913), Color(0xFF006A44), Color(0xFFC1272D))
        "RU" -> horizontalStripes(Color.White, Color(0xFF0039A6), Color(0xFFD52B1E))
        "AM" -> horizontalStripes(Color(0xFFD90012), Color(0xFF0033A0), Color(0xFFF2A800))
        "AZ" -> horizontalStripes(Color(0xFF00B5E2), Color(0xFFEF3340), Color(0xFF509E2F))
        "FR" -> verticalStripes(Color(0xFF002395), Color.White, Color(0xFFED2939))
        "IT" -> verticalStripes(Color(0xFF009246), Color.White, Color(0xFFCE2B37))
        "IE" -> verticalStripes(Color(0xFF169B62), Color.White, Color(0xFFFF883E))
        "BE" -> verticalStripes(Color(0xFF111318), Color(0xFFFDDA24), Color(0xFFEF3340))
        "RO", "MD" -> verticalStripes(Color(0xFF002B7F), Color(0xFFFCD116), Color(0xFFCE1126))
        "PL" -> {
            drawRect(Color.White, Offset.Zero, Size(w, h * 0.5f))
            drawRect(Color(0xFFDC143C), Offset(0f, h * 0.5f), Size(w, h * 0.5f))
        }
        "UA" -> {
            drawRect(Color(0xFF005BBB), Offset.Zero, Size(w, h * 0.5f))
            drawRect(Color(0xFFFFD500), Offset(0f, h * 0.5f), Size(w, h * 0.5f))
        }
        "LV" -> {
            drawRect(Color(0xFF9E3039))
            drawRect(Color.White, Offset(0f, h * 0.40f), Size(w, h * 0.20f))
        }
        "CZ" -> {
            drawRect(Color.White, Offset.Zero, Size(w, h * 0.5f))
            drawRect(Color(0xFFD7141A), Offset(0f, h * 0.5f), Size(w, h * 0.5f))
            drawPath(
                Path().apply {
                    moveTo(0f, 0f)
                    lineTo(w * 0.48f, h * 0.5f)
                    lineTo(0f, h)
                    close()
                },
                Color(0xFF11457E)
            )
        }
        "TR" -> {
            drawRect(Color(0xFFE30A17))
            drawCircle(Color.White, radius = h * 0.25f, center = Offset(w * 0.42f, h * 0.5f))
            drawCircle(Color(0xFFE30A17), radius = h * 0.20f, center = Offset(w * 0.47f, h * 0.5f))
            drawFivePointStar(Offset(w * 0.63f, h * 0.5f), h * 0.11f, Color.White)
        }
        "AE" -> {
            drawRect(Color(0xFF00732F), Offset(w * 0.25f, 0f), Size(w * 0.75f, h / 3f))
            drawRect(Color.White, Offset(w * 0.25f, h / 3f), Size(w * 0.75f, h / 3f))
            drawRect(Color(0xFF101318), Offset(w * 0.25f, 2f * h / 3f), Size(w * 0.75f, h / 3f))
            drawRect(Color(0xFFFF0000), Offset.Zero, Size(w * 0.25f, h))
        }
        "JP" -> {
            drawRect(Color(0xFFFDFDFE))
            drawCircle(Color(0xFFBC002D), radius = h * 0.29f, center = Offset(w * 0.5f, h * 0.5f))
        }
        "KR" -> {
            drawRect(Color.White)
            drawCircle(Color(0xFFCD2E3A), radius = h * 0.24f, center = Offset(w * 0.5f, h * 0.46f))
            drawCircle(Color(0xFF0047A0), radius = h * 0.24f, center = Offset(w * 0.5f, h * 0.54f))
        }
        "SG" -> {
            drawRect(Color(0xFFEF3340), Offset.Zero, Size(w, h * 0.5f))
            drawRect(Color.White, Offset(0f, h * 0.5f), Size(w, h * 0.5f))
            drawCircle(Color.White, radius = h * 0.17f, center = Offset(w * 0.25f, h * 0.25f))
            drawCircle(Color(0xFFEF3340), radius = h * 0.14f, center = Offset(w * 0.29f, h * 0.25f))
        }
        "CA" -> {
            drawRect(Color(0xFFFF0000), Offset.Zero, Size(w * 0.25f, h))
            drawRect(Color.White, Offset(w * 0.25f, 0f), Size(w * 0.50f, h))
            drawRect(Color(0xFFFF0000), Offset(w * 0.75f, 0f), Size(w * 0.25f, h))
            drawCircle(Color(0xFFFF0000), radius = h * 0.18f, center = Offset(w * 0.5f, h * 0.5f))
        }
        "US" -> {
            val stripeH = h / 13f
            for (i in 0 until 13) {
                drawRect(if (i % 2 == 0) Color(0xFFB22234) else Color.White, Offset(0f, i * stripeH), Size(w, stripeH + 1f))
            }
            drawRect(Color(0xFF3C3B6E), Offset.Zero, Size(w * 0.42f, stripeH * 7f))
        }
        "GB" -> {
            drawRect(Color(0xFF012169))
            drawLine(Color.White, Offset.Zero, Offset(w, h), strokeWidth = h * 0.18f)
            drawLine(Color.White, Offset(0f, h), Offset(w, 0f), strokeWidth = h * 0.18f)
            drawLine(Color(0xFFC8102E), Offset.Zero, Offset(w, h), strokeWidth = h * 0.08f)
            drawLine(Color(0xFFC8102E), Offset(0f, h), Offset(w, 0f), strokeWidth = h * 0.08f)
            drawRect(Color.White, Offset(w * 0.42f, 0f), Size(w * 0.16f, h))
            drawRect(Color.White, Offset(0f, h * 0.36f), Size(w, h * 0.28f))
            drawRect(Color(0xFFC8102E), Offset(w * 0.45f, 0f), Size(w * 0.10f, h))
            drawRect(Color(0xFFC8102E), Offset(0f, h * 0.42f), Size(w, h * 0.16f))
        }
        "ES" -> {
            drawRect(Color(0xFFAA151B), Offset.Zero, Size(w, h * 0.25f))
            drawRect(Color(0xFFF1BF00), Offset(0f, h * 0.25f), Size(w, h * 0.50f))
            drawRect(Color(0xFFAA151B), Offset(0f, h * 0.75f), Size(w, h * 0.25f))
        }
        else -> {
            // Crisp futuristic emerald-cyan sovereign gradient for any uncovered code
            drawRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFF0B2545), Color(0xFF134E5E), Color(0xFF0F2027))
                )
            )
        }
    }
}

private fun DrawScope.horizontalStripes(top: Color, mid: Color, bot: Color) {
    val sh = size.height / 3f
    drawRect(top, Offset.Zero, Size(size.width, sh + 1f))
    drawRect(mid, Offset(0f, sh), Size(size.width, sh + 1f))
    drawRect(bot, Offset(0f, sh * 2f), Size(size.width, sh + 1f))
}

private fun DrawScope.verticalStripes(left: Color, mid: Color, right: Color) {
    val sw = size.width / 3f
    drawRect(left, Offset.Zero, Size(sw + 1f, size.height))
    drawRect(mid, Offset(sw, 0f), Size(sw + 1f, size.height))
    drawRect(right, Offset(sw * 2f, 0f), Size(sw + 1f, size.height))
}

private fun DrawScope.drawFivePointStar(center: Offset, outerR: Float, color: Color) {
    val innerR = outerR * 0.39f
    val path = Path()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val angle = -PI / 2.0 + i * (PI / 5.0)
        val x = center.x + (r * cos(angle)).toFloat()
        val y = center.y + (r * sin(angle)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    drawPath(path, color)
}
