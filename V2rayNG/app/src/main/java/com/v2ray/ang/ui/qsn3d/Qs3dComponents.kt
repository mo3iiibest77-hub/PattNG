package com.v2ray.ang.ui.qsn3d

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.v2ray.ang.senpai.CdnType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbVisualMode {
    DISCONNECTED,
    PROCESSING,
    CONNECTED,
    ERROR
}

/**
 * Animated 3D depth starfield & perspective horizon grid canvas background.
 */
@Composable
fun Cosmic3dBackdrop(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "cosmic_bg")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 16000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "bg_drift"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.65f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bg_pulse"
    )

    val stars = remember {
        List(36) { idx ->
            val fx = ((idx * 73 + 19) % 100) / 100f
            val fy = ((idx * 41 + 7) % 100) / 100f
            val size = 1.2f + (idx % 4) * 0.65f
            Triple(fx, fy, size)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        QsColors.DeepVoid,
                        Color(0xFF090F22),
                        Color(0xFF0E1731),
                        QsColors.DeepVoid
                    )
                )
            )
    ) {
        val w = size.width
        val h = size.height

        // Volumetric aurora orbs
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    QsColors.ElectricCyan.copy(alpha = 0.14f * pulse),
                    Color.Transparent
                ),
                center = Offset(w * (0.25f + 0.08f * sin(drift * 2f * PI.toFloat())), h * 0.22f),
                radius = w * 0.68f
            ),
            radius = w * 0.68f,
            center = Offset(w * (0.25f + 0.08f * sin(drift * 2f * PI.toFloat())), h * 0.22f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    QsColors.PlasmaViolet.copy(alpha = 0.13f * pulse),
                    Color.Transparent
                ),
                center = Offset(w * 0.80f, h * (0.58f + 0.06f * cos(drift * 2f * PI.toFloat()))),
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = Offset(w * 0.80f, h * (0.58f + 0.06f * cos(drift * 2f * PI.toFloat())))
        )

        // Subtle 3D perspective horizon grid near bottom
        val horizonY = h * 0.62f
        val lineCount = 9
        for (i in 0..lineCount) {
            val t = i.toFloat() / lineCount
            val curvedY = horizonY + (h - horizonY) * (t * t)
            drawLine(
                color = QsColors.ElectricCyan.copy(alpha = 0.055f),
                start = Offset(0f, curvedY),
                end = Offset(w, curvedY),
                strokeWidth = 1f
            )
        }
        val vpX = w * 0.5f
        for (j in -5..5) {
            val bottomX = vpX + j * (w * 0.22f)
            drawLine(
                color = QsColors.ElectricCyan.copy(alpha = 0.045f),
                start = Offset(vpX, horizonY),
                end = Offset(bottomX, h),
                strokeWidth = 1f
            )
        }

        // Drifting particles
        stars.forEachIndexed { index, (fx, fy, r) ->
            val yShift = ((fy + drift * (0.15f + (index % 3) * 0.06f)) % 1f) * h
            val alpha = 0.22f + 0.45f * absSin(drift * PI.toFloat() * 2f + index)
            drawCircle(
                color = if (index % 3 == 0) QsColors.ElectricCyan.copy(alpha = alpha)
                else Color.White.copy(alpha = alpha * 0.75f),
                radius = r.dp.toPx(),
                center = Offset(fx * w, yShift)
            )
        }
    }
}

private fun absSin(rad: Float): Float {
    val s = sin(rad)
    return if (s < 0f) -s else s
}

/**
 * 3D elevated glass card with perspective specular rim and tactile press depth.
 */
@Composable
fun Glass3dCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    glowColor: Color = QsColors.ElectricCyan.copy(alpha = 0.28f),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.975f else 1f,
        animationSpec = spring(stiffness = 500f),
        label = "card_scale"
    )
    val tiltX by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 3.5f else 0f,
        animationSpec = spring(stiffness = 500f),
        label = "card_tiltx"
    )

    val clickMod = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = null,
            onClick = onClick
        )
    } else Modifier

    Surface(
        modifier = modifier
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                rotationX = tiltX
                cameraDistance = 12f * density
            }
            .then(clickMod),
        shape = RoundedCornerShape(cornerRadius),
        color = QsColors.GlassSurface,
        shadowElevation = 12.dp,
        border = BorderStroke(
            width = 1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(glowColor, QsColors.GlassBorderBottom)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.055f),
                            Color.Transparent,
                            glowColor.copy(alpha = 0.05f)
                        )
                    )
                )
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * Hero animated 3D Holographic Connection Orb using multi-ring gyroscopic rotationX/rotationY
 * and luminous swept core energy.
 */
@Composable
fun Holographic3dConnectOrb(
    mode: OrbVisualMode,
    title: String,
    subtitle: String,
    cdnType: CdnType,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current.density
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    val accentColor by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.ConnectedEmerald
            OrbVisualMode.PROCESSING -> when (cdnType) {
                CdnType.CLOUDFRONT -> QsColors.CloudFrontSky
                else -> QsColors.ElectricCyan
            }
            OrbVisualMode.ERROR -> QsColors.DangerCrimson
            OrbVisualMode.DISCONNECTED -> Color(0xFF475569)
        },
        animationSpec = tween(500),
        label = "orb_accent"
    )

    val secondaryGlow by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.AuroraMint
            OrbVisualMode.PROCESSING -> QsColors.SolarGold
            OrbVisualMode.ERROR -> Color(0xFFF97316)
            OrbVisualMode.DISCONNECTED -> QsColors.ElectricCyan.copy(alpha = 0.55f)
        },
        animationSpec = tween(500),
        label = "orb_secondary"
    )

    val infinite = rememberInfiniteTransition(label = "orb_anim")
    val spinFast by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = if (mode == OrbVisualMode.PROCESSING) 2200 else 7200, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "spin_fast"
    )
    val spinSlow by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 10500, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "spin_slow"
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "orb_breathe"
    )
    val ripple by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(durationMillis = 2400, easing = LinearEasing),
            RepeatMode.Restart
        ),
        label = "orb_ripple"
    )

    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(stiffness = 450f),
        label = "orb_press"
    )

    Box(
        modifier = modifier
            .size(236.dp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Outward sonar shockwaves when active/connected
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val baseR = size.minDimension * 0.34f
            if (mode == OrbVisualMode.CONNECTED || mode == OrbVisualMode.PROCESSING) {
                val r1 = baseR + (size.minDimension * 0.16f) * ripple
                val a1 = (1f - ripple) * 0.42f
                drawCircle(
                    color = accentColor.copy(alpha = a1),
                    radius = r1,
                    center = c,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
            // Volumetric backdrop glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        accentColor.copy(alpha = 0.28f * breathe),
                        secondaryGlow.copy(alpha = 0.10f),
                        Color.Transparent
                    ),
                    center = c,
                    radius = size.minDimension * 0.50f
                ),
                radius = size.minDimension * 0.50f,
                center = c
            )
        }

        // 3D Outer tilted gyroscope ring
        Box(
            modifier = Modifier
                .size(204.dp)
                .graphicsLayer {
                    rotationX = 58f
                    rotationY = 22f
                    rotationZ = spinFast
                    cameraDistance = 12f * density
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            Color.Transparent,
                            accentColor.copy(alpha = 0.85f),
                            secondaryGlow,
                            Color.Transparent
                        )
                    ),
                    startAngle = 0f,
                    sweepAngle = 290f,
                    useCenter = false,
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // 3D Counter-rotating inner orbital ring
        Box(
            modifier = Modifier
                .size(182.dp)
                .graphicsLayer {
                    rotationX = -52f
                    rotationY = -28f
                    rotationZ = spinSlow
                    cameraDistance = 12f * density
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            secondaryGlow.copy(alpha = 0.8f),
                            Color.Transparent,
                            accentColor.copy(alpha = 0.65f),
                            Color.Transparent
                        )
                    ),
                    startAngle = 40f,
                    sweepAngle = 260f,
                    useCenter = false,
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Luminous 3D Core Sphere
        Box(
            modifier = Modifier
                .size(144.dp)
                .graphicsLayer {
                    val s = if (mode == OrbVisualMode.PROCESSING) breathe else 1f
                    scaleX = s
                    scaleY = s
                }
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            accentColor.copy(alpha = 0.42f),
                            Color(0xFF101B38),
                            Color(0xFF060B1A)
                        ),
                        center = Offset(75f, 55f)
                    )
                )
                .border(
                    width = 2.dp,
                    brush = Brush.verticalGradient(
                        listOf(
                            Color.White.copy(alpha = 0.55f),
                            accentColor,
                            secondaryGlow.copy(alpha = 0.5f)
                        )
                    ),
                    shape = CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    text = title,
                    color = QsColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = subtitle,
                        color = accentColor.copy(alpha = 0.95f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun ProviderPillBadge(
    cdnType: CdnType,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (cdnType) {
        CdnType.CLOUDFLARE -> "Cloudflare" to QsColors.CloudflareOrange
        CdnType.CLOUDFRONT -> "CloudFront" to QsColors.CloudFrontSky
        CdnType.UNKNOWN -> "Auto CDN" to QsColors.TextSecondary
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .border(1.dp, color.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = color,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
