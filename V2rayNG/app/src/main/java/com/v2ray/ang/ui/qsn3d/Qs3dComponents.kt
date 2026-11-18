package com.v2ray.ang.ui.qsn3d

import android.animation.ValueAnimator
import android.os.Build
import android.provider.Settings
import androidx.annotation.DrawableRes
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.v2ray.ang.senpai.CdnType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Returns true only when the screen is in the foreground and OS animation scale permits motion.
 */
@Composable
fun rememberMotionActive(): Boolean {
    val context = LocalContext.current
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val inForeground = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val animatorEnabled = remember(context) {
        runCatching {
            val scale = Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
            val animatorsAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                true
            }
            scale > 0f && animatorsAllowed
        }.getOrDefault(true)
    }
    return inForeground && animatorEnabled
}

enum class OrbVisualMode {
    IDLE,
    CONNECTING,
    SCANNING,
    CONNECTED,
}

/**
 * Clean, understated obsidian-slate backdrop with subtle radial telemetry rings and single top glow.
 * Eliminates visual clutter behind cards while smoothly shifting hue when connected.
 */
@Composable
fun Cosmic3dBackdrop(
    isConnected: Boolean = false,
    isBusy: Boolean = false,
    @DrawableRes backgroundImageRes: Int? = null,
    modifier: Modifier = Modifier,
) {
    val motionActive = rememberMotionActive()
    val haloColor by animateColorAsState(
        targetValue = when {
            isConnected -> QsColors.ConnectedEmerald.copy(alpha = 0.16f)
            isBusy -> QsColors.SolarGold.copy(alpha = 0.14f)
            else -> QsColors.ElectricCyan.copy(alpha = 0.12f)
        },
        animationSpec = tween(700),
        label = "backdrop_halo",
    )

    val phase = if (motionActive) {
        val transition = rememberInfiniteTransition(label = "ambient_phase")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(14_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "phase",
        ).value
    } else {
        0.2f
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        QsColors.DeepVoid,
                        QsColors.MidnightNavy,
                        QsColors.DeepVoid,
                    )
                )
            )
    ) {
        if (backgroundImageRes != null) {
            Image(
                painter = painterResource(backgroundImageRes),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(0.11f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                QsColors.DeepVoid.copy(alpha = 0.72f),
                                QsColors.MidnightNavy.copy(alpha = 0.86f),
                                QsColors.DeepVoid.copy(alpha = 0.94f),
                            )
                        )
                    )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.28f)
            val driftX = cos(phase * 2f * PI).toFloat() * (w * 0.03f)
            val glowCenter = Offset(center.x + driftX, center.y)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(haloColor, Color.Transparent),
                    center = glowCenter,
                    radius = w * 0.85f
                ),
                center = glowCenter,
                radius = w * 0.85f
            )

            // Subtle architectural instrument arcs
            val ringStroke = Stroke(width = 1.dp.toPx())
            drawCircle(
                color = QsColors.BorderSubtle.copy(alpha = 0.22f),
                radius = w * 0.36f,
                center = center,
                style = ringStroke,
            )
            drawCircle(
                color = QsColors.BorderSubtle.copy(alpha = 0.14f),
                radius = w * 0.56f,
                center = center,
                style = ringStroke,
            )
        }
    }
}

/**
 * Crisp slate architectural card with controlled elevation and hairline 1dp border.
 * Keeps surface contrast clean without loud neon borders.
 */
@Composable
fun Glass3dCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    glowColor: Color = QsColors.BorderSubtle,
    surfaceColor: Color = QsColors.SurfaceCard,
    elevationDp: Dp = 4.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val resolvedBorder = if (glowColor == QsColors.BorderSubtle || glowColor.alpha < 0.08f) {
        QsColors.BorderSubtle
    } else {
        glowColor.copy(alpha = glowColor.alpha.coerceIn(0.24f, 0.55f))
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = surfaceColor,
        shadowElevation = elevationDp,
        border = BorderStroke(1.dp, resolvedBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * Precision Magnetic Tunnel Trigger Orb.
 * Distinct animations per mode:
 * - IDLE: calibrated 36-tick bezel + subtle breathing halo + clean vector power glyph
 * - CONNECTING / SCANNING: sweeping radar arc, progress track (if scanTotal > 0), orbiting satellite dot & sonar wave
 * - CONNECTED: serene harmonic shield waves + slow orbital lock ring + crisp shield-check glyph
 */
@Composable
fun ConnectionOrb3d(
    isRunning: Boolean,
    isBusy: Boolean,
    mode: OrbVisualMode = when {
        isRunning && isBusy -> OrbVisualMode.SCANNING
        isRunning -> OrbVisualMode.CONNECTED
        isBusy -> OrbVisualMode.CONNECTING
        else -> OrbVisualMode.IDLE
    },
    scanProgressFraction: Float? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 196.dp,
    centerContent: @Composable BoxScope.() -> Unit = {},
) {
    val motionActive = rememberMotionActive()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.94f else 1.0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "orb_press",
    )

    val primaryColor by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.ConnectedEmerald
            OrbVisualMode.SCANNING -> QsColors.SolarGold
            OrbVisualMode.CONNECTING -> QsColors.ElectricCyan
            OrbVisualMode.IDLE -> QsColors.ElectricCyan
        },
        animationSpec = tween(420),
        label = "orb_primary_color",
    )

    val secondaryColor by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.AuroraMint
            OrbVisualMode.SCANNING -> QsColors.ElectricCyan
            OrbVisualMode.CONNECTING -> QsColors.PlasmaViolet
            OrbVisualMode.IDLE -> QsColors.BorderStrong
        },
        animationSpec = tween(420),
        label = "orb_secondary_color",
    )

    val spinDeg: Float
    val pulse: Float
    val wavePhase: Float
    if (motionActive) {
        val infinite = rememberInfiniteTransition(label = "orb_motion")
        spinDeg = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when (mode) {
                        OrbVisualMode.CONNECTING, OrbVisualMode.SCANNING -> 2200
                        OrbVisualMode.CONNECTED -> 9000
                        OrbVisualMode.IDLE -> 16000
                    },
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orb_spin",
        ).value
        pulse = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "orb_pulse",
        ).value
        wavePhase = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orb_wave",
        ).value
    } else {
        spinDeg = 0f
        pulse = 0.5f
        wavePhase = 0f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (scanProgressFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(260),
        label = "orb_scan_progress",
    )

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
            }
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) / 2f

            // 1. Outward sonar / harmonic shield wave (Connected or Scanning/Connecting)
            if (mode != OrbVisualMode.IDLE) {
                val waveRadius1 = maxR * (0.64f + 0.34f * wavePhase)
                val waveAlpha1 = (1f - wavePhase) * 0.42f
                drawCircle(
                    color = primaryColor.copy(alpha = waveAlpha1),
                    radius = waveRadius1,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.6.dp.toPx())
                )

                val p2 = (wavePhase + 0.5f) % 1f
                val waveRadius2 = maxR * (0.64f + 0.34f * p2)
                val waveAlpha2 = (1f - p2) * 0.28f
                drawCircle(
                    color = secondaryColor.copy(alpha = waveAlpha2),
                    radius = waveRadius2,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.2.dp.toPx())
                )
            } else {
                // Idle gentle ambient aura
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            primaryColor.copy(alpha = 0.08f + pulse * 0.06f),
                            Color.Transparent,
                        ),
                        center = Offset(cx, cy),
                        radius = maxR * 0.96f,
                    ),
                    radius = maxR * 0.96f,
                    center = Offset(cx, cy),
                )
            }

            // 2. Calibrated 36-tick outer bezel
            val tickOuterR = maxR * 0.89f
            val tickCount = 36
            for (i in 0 until tickCount) {
                val isMajor = i % 3 == 0
                val angleDeg = i * (360f / tickCount)
                val angleRad = (angleDeg - 90f) * (PI / 180.0)
                val innerR = tickOuterR - (if (isMajor) 5.5.dp.toPx() else 2.8.dp.toPx())
                val tickColor = if (mode == OrbVisualMode.CONNECTED || (mode != OrbVisualMode.IDLE && isMajor)) {
                    primaryColor.copy(alpha = if (isMajor) 0.72f else 0.30f)
                } else {
                    QsColors.BorderStrong.copy(alpha = if (isMajor) 0.75f else 0.38f)
                }
                drawLine(
                    color = tickColor,
                    start = Offset(
                        cx + cos(angleRad).toFloat() * innerR,
                        cy + sin(angleRad).toFloat() * innerR,
                    ),
                    end = Offset(
                        cx + cos(angleRad).toFloat() * tickOuterR,
                        cy + sin(angleRad).toFloat() * tickOuterR,
                    ),
                    strokeWidth = if (isMajor) 1.5.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // 3. Orbital track & dynamic sweep / scan progress
            val ringR = maxR * 0.75f
            val ringStroke = 3.2.dp.toPx()
            drawCircle(
                color = QsColors.BorderSubtle.copy(alpha = 0.75f),
                radius = ringR,
                center = Offset(cx, cy),
                style = Stroke(width = ringStroke),
            )

            if (mode == OrbVisualMode.SCANNING && scanProgressFraction != null && animatedProgress > 0.01f) {
                drawArc(
                    brush = Brush.sweepGradient(listOf(primaryColor, secondaryColor, primaryColor)),
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(cx - ringR, cy - ringR),
                    size = Size(ringR * 2f, ringR * 2f),
                    style = Stroke(width = ringStroke + 1.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            if (mode == OrbVisualMode.CONNECTING || mode == OrbVisualMode.SCANNING) {
                rotate(degrees = spinDeg, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0.0f to Color.Transparent,
                            0.55f to primaryColor.copy(alpha = 0.85f),
                            0.85f to secondaryColor,
                            1.0f to Color.Transparent,
                        ),
                        startAngle = 0f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx - ringR, cy - ringR),
                        size = Size(ringR * 2f, ringR * 2f),
                        style = Stroke(width = ringStroke + 1.2.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
                // Orbiting telemetry node dot
                val orbAngle = (spinDeg + 125f - 90f) * (PI / 180.0)
                val dotOffset = Offset(
                    cx + cos(orbAngle).toFloat() * ringR,
                    cy + sin(orbAngle).toFloat() * ringR,
                )
                drawCircle(color = primaryColor, radius = 4.2.dp.toPx(), center = dotOffset)
                drawCircle(color = Color.White, radius = 2.dp.toPx(), center = dotOffset)
            } else if (mode == OrbVisualMode.CONNECTED) {
                rotate(degrees = spinDeg, pivot = Offset(cx, cy)) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(
                                QsColors.ConnectedEmerald,
                                QsColors.AuroraMint,
                                QsColors.ElectricCyan,
                                QsColors.ConnectedEmerald,
                            )
                        ),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(cx - ringR, cy - ringR),
                        size = Size(ringR * 2f, ringR * 2f),
                        style = Stroke(width = ringStroke + 0.6.dp.toPx()),
                    )
                }
            }

            // 4. Inner Machined Slate Dome
            val coreR = maxR * 0.62f
            val coreBrush = when (mode) {
                OrbVisualMode.CONNECTED -> Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF103F35),
                        Color(0xFF0C2B27),
                        Color(0xFF0A1B1E),
                    ),
                    center = Offset(cx - coreR * 0.25f, cy - coreR * 0.28f),
                    radius = coreR * 1.35f,
                )
                OrbVisualMode.SCANNING, OrbVisualMode.CONNECTING -> Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF162C46),
                        Color(0xFF101E33),
                        Color(0xFF0B1322),
                    ),
                    center = Offset(cx - coreR * 0.25f, cy - coreR * 0.28f),
                    radius = coreR * 1.35f,
                )
                OrbVisualMode.IDLE -> Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1D2940),
                        Color(0xFF131C2D),
                        Color(0xFF0D1422),
                    ),
                    center = Offset(cx - coreR * 0.25f, cy - coreR * 0.28f),
                    radius = coreR * 1.35f,
                )
            }
            drawCircle(brush = coreBrush, radius = coreR, center = Offset(cx, cy))

            // Specular rim highlight
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = if (mode == OrbVisualMode.IDLE) 0.35f else 0.72f),
                        QsColors.BorderSubtle.copy(alpha = 0.45f),
                    ),
                    startY = cy - coreR,
                    endY = cy + coreR,
                ),
                radius = coreR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }

        // Center content slot (falls back to clean vector glyph if no custom content provided)
        Box(contentAlignment = Alignment.Center) {
            centerContent()
        }
    }
}

/**
 * Clean vector glyph drawn inside the center of ConnectionOrb3d (no emojis).
 */
@Composable
fun OrbCenterGlyph(
    mode: OrbVisualMode,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 44.dp,
) {
    val tint by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.AuroraMint
            OrbVisualMode.SCANNING -> QsColors.SolarGold
            OrbVisualMode.CONNECTING -> QsColors.ElectricCyan
            OrbVisualMode.IDLE -> QsColors.TextPrimary
        },
        animationSpec = tween(300),
        label = "glyph_tint",
    )

    Canvas(modifier = modifier.size(sizeDp)) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val strokeW = 2.8.dp.toPx()

        when (mode) {
            OrbVisualMode.CONNECTED -> {
                // Shield outline + check mark
                val shield = Path().apply {
                    moveTo(cx, h * 0.14f)
                    lineTo(w * 0.80f, h * 0.28f)
                    lineTo(w * 0.80f, h * 0.52f)
                    cubicTo(w * 0.80f, h * 0.74f, cx, h * 0.88f, cx, h * 0.88f)
                    cubicTo(cx, h * 0.88f, w * 0.20f, h * 0.74f, w * 0.20f, h * 0.52f)
                    lineTo(w * 0.20f, h * 0.28f)
                    close()
                }
                drawPath(
                    path = shield,
                    color = tint,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                val check = Path().apply {
                    moveTo(w * 0.36f, h * 0.52f)
                    lineTo(w * 0.46f, h * 0.62f)
                    lineTo(w * 0.66f, h * 0.40f)
                }
                drawPath(
                    path = check,
                    color = Color.White,
                    style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            OrbVisualMode.SCANNING, OrbVisualMode.CONNECTING -> {
                // Crisp lightning pulse glyph
                val bolt = Path().apply {
                    moveTo(w * 0.54f, h * 0.16f)
                    lineTo(w * 0.32f, h * 0.54f)
                    lineTo(w * 0.50f, h * 0.54f)
                    lineTo(w * 0.45f, h * 0.84f)
                    lineTo(w * 0.68f, h * 0.46f)
                    lineTo(w * 0.50f, h * 0.46f)
                    close()
                }
                drawPath(path = bolt, color = tint)
            }
            OrbVisualMode.IDLE -> {
                // Classic power emblem
                val arcR = minOf(w, h) * 0.32f
                drawArc(
                    color = tint,
                    startAngle = -52f,
                    sweepAngle = 284f,
                    useCenter = false,
                    topLeft = Offset(cx - arcR, cy - arcR + 2.dp.toPx()),
                    size = Size(arcR * 2f, arcR * 2f),
                    style = Stroke(width = strokeW, cap = StrokeCap.Round),
                )
                drawLine(
                    color = QsColors.ElectricCyan,
                    start = Offset(cx, cy - arcR - 3.dp.toPx()),
                    end = Offset(cx, cy + 1.dp.toPx()),
                    strokeWidth = strokeW,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/**
 * Precision donut quota ring with tabular numeral percentage and clean single track.
 */
@Composable
fun Animated3dQuotaChart(
    usedFraction: Float,
    usedPercentText: String,
    caption: String,
    accentColor: Color = QsColors.ElectricCyan,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 128.dp,
) {
    val animatedSweep by animateFloatAsState(
        targetValue = (usedFraction.coerceIn(0f, 1f) * 360f),
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "quota_sweep"
    )

    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 11.dp.toPx()
            val inset = strokePx * 0.85f
            val arcSize = Size(size.width - inset * 2f, size.height - inset * 2f)
            val topLeft = Offset(inset, inset)

            drawArc(
                color = QsColors.SurfaceInset,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                color = QsColors.BorderSubtle,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = 1.dp.toPx())
            )

            if (animatedSweep > 0.5f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        listOf(
                            accentColor,
                            if (usedFraction > 0.85f) QsColors.SolarGold else QsColors.AuroraMint,
                            accentColor,
                        )
                    ),
                    startAngle = -90f,
                    sweepAngle = animatedSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = usedPercentText,
                style = QsTextStyles.TabularStat,
                color = QsColors.TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = caption,
                color = QsColors.TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Clean tabular live speed monitor pair (`↓ Download` and `↑ Upload`).
 */
@Composable
fun LiveSpeedHudRow(
    downloadLabel: String,
    downloadFormatted: String,
    downloadBps: Long,
    uploadLabel: String,
    uploadFormatted: String,
    uploadBps: Long,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpeedMeterTile(
            label = downloadLabel,
            value = downloadFormatted,
            bps = downloadBps,
            isDownload = true,
            isRunning = isRunning,
            accent = QsColors.ConnectedEmerald,
            modifier = Modifier.weight(1f),
        )
        SpeedMeterTile(
            label = uploadLabel,
            value = uploadFormatted,
            bps = uploadBps,
            isDownload = false,
            isRunning = isRunning,
            accent = QsColors.ElectricCyan,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeedMeterTile(
    label: String,
    value: String,
    bps: Long,
    isDownload: Boolean,
    isRunning: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val samples = remember { mutableStateListOf(0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f, 0.08f) }
    LaunchedEffect(bps, isRunning) {
        val norm = if (!isRunning) 0.06f else {
            val kbps = (bps / 1024f)
            when {
                kbps <= 1f -> 0.10f
                kbps < 100f -> 0.22f + (kbps / 100f) * 0.25f
                kbps < 1500f -> 0.47f + ((kbps - 100f) / 1400f) * 0.35f
                else -> 0.94f
            }.coerceIn(0.08f, 0.98f)
        }
        if (samples.size >= 8) samples.removeAt(0)
        samples.add(norm)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = QsColors.SurfaceCard,
        border = BorderStroke(1.dp, if (isRunning && bps > 0L) accent.copy(alpha = 0.28f) else QsColors.BorderSubtle),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        if (isDownload) {
                            moveTo(w * 0.5f, h * 0.1f)
                            lineTo(w * 0.5f, h * 0.85f)
                            moveTo(w * 0.22f, h * 0.58f)
                            lineTo(w * 0.5f, h * 0.86f)
                            lineTo(w * 0.78f, h * 0.58f)
                        } else {
                            moveTo(w * 0.5f, h * 0.9f)
                            lineTo(w * 0.5f, h * 0.15f)
                            moveTo(w * 0.22f, h * 0.42f)
                            lineTo(w * 0.5f, h * 0.14f)
                            lineTo(w * 0.78f, h * 0.42f)
                        }
                    }
                    drawPath(
                        path = path,
                        color = accent,
                        style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                Text(
                    text = label,
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (isRunning) value else "0 B/s",
                style = QsTextStyles.TabularStat,
                color = QsColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                samples.forEachIndexed { idx, s ->
                    val frac = if (idx == samples.lastIndex) 1f else 0.45f + (idx / 14f)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((s * 10f).coerceAtLeast(2f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(
                                if (isRunning && bps > 0L) accent.copy(alpha = frac * 0.82f)
                                else QsColors.BorderSubtle
                            )
                    )
                }
            }
        }
    }
}

@Composable
fun CdnProviderBadge(cdnType: CdnType, modifier: Modifier = Modifier) {
    val (bg, fg, label) = when (cdnType) {
        CdnType.CLOUDFLARE -> Triple(
            QsColors.CloudflareOrange.copy(alpha = 0.16f),
            QsColors.CloudflareOrange,
            "Cloudflare"
        )
        CdnType.CLOUDFRONT -> Triple(
            QsColors.CloudFrontSky.copy(alpha = 0.16f),
            QsColors.CloudFrontSky,
            "CloudFront"
        )
        CdnType.UNKNOWN -> Triple(
            QsColors.BorderSubtle.copy(alpha = 0.5f),
            QsColors.TextSecondary,
            "CDN"
        )
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun StandardPipelineBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    val cleanLabel = label.ifBlank { "STANDARD" }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(QsColors.AuroraMint.copy(alpha = 0.14f))
            .border(1.dp, QsColors.AuroraMint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = cleanLabel,
            color = QsColors.AuroraMint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun MetricPill(
    label: String,
    value: String,
    accent: Color = QsColors.ElectricCyan,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(QsColors.SurfaceInset)
            .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = QsColors.TextMuted, fontSize = 10.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = QsTextStyles.TabularStat,
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatusDot(active: Boolean) {
    Box(
        modifier = Modifier
            .size(9.dp)
            .clip(CircleShape)
            .background(if (active) QsColors.ConnectedEmerald else QsColors.TextMuted)
    )
    Spacer(Modifier.width(7.dp))
}
