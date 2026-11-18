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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.v2ray.ang.R
import com.v2ray.ang.senpai.CdnType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Returns true only when the screen is active in the foreground (`Lifecycle.State.STARTED`)
 * and OS Reduce Motion / Animator Scale permits visual motion.
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

private data class Vec3(val x: Float, val y: Float, val z: Float)

private fun buildFibonacciLattice(count: Int, radius: Float): List<Vec3> {
    if (count <= 1) return emptyList()
    val golden = (1.0 + sqrt(5.0)) / 2.0
    return List(count) { i ->
        val y = 1.0 - (i.toDouble() / (count - 1)) * 2.0
        val r = sqrt((1.0 - y * y).coerceAtLeast(0.0))
        val theta = 2.0 * PI * i / golden
        Vec3(
            x = (cos(theta) * r * radius).toFloat(),
            y = (y * radius).toFloat(),
            z = (sin(theta) * r * radius).toFloat(),
        )
    }
}

private fun rotateVec3(p: Vec3, yaw: Float, pitch: Float): Vec3 {
    val cy = cos(yaw)
    val sy = sin(yaw)
    val x1 = p.x * cy + p.z * sy
    val z1 = -p.x * sy + p.z * cy

    val cp = cos(pitch)
    val sp = sin(pitch)
    val y2 = p.y * cp - z1 * sp
    val z2 = p.y * sp + z1 * cp
    return Vec3(x1, y2, z2)
}

/**
 * Rich 3D Atmospheric Backdrop displaying each tab's dedicated hero illustration
 * blended cleanly under translucent frosted dark gradients + a subtle 3D rotating ambient node field.
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
            isConnected -> QsColors.ConnectedEmerald.copy(alpha = 0.24f)
            isBusy -> QsColors.SolarGold.copy(alpha = 0.20f)
            else -> QsColors.ElectricCyan.copy(alpha = 0.18f)
        },
        animationSpec = tween(700),
        label = "backdrop_halo",
    )

    val phase = if (motionActive) {
        val transition = rememberInfiniteTransition(label = "backdrop_motion")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(22_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "bg_phase",
        ).value
    } else {
        0.5f
    }

    val bgNodes = remember { buildFibonacciLattice(34, 1f) }

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
                    .graphicsLayer {
                        val driftScale = 1.03f + 0.015f * sin(phase)
                        scaleX = driftScale
                        scaleY = driftScale
                        translationX = cos(phase) * 8f
                    }
                    .alpha(0.32f)
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                QsColors.DeepVoid.copy(alpha = 0.62f),
                                QsColors.MidnightNavy.copy(alpha = 0.72f),
                                QsColors.DeepVoid.copy(alpha = 0.88f),
                            )
                        )
                    )
            )
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val center = Offset(w * 0.5f, h * 0.28f)
            val radius = minOf(w, h) * 0.44f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(haloColor, Color.Transparent),
                    center = center,
                    radius = w * 0.88f
                ),
                center = center,
                radius = w * 0.88f
            )

            // Subtle 3D projected ambient lattice points
            val camDist = radius * 3.1f
            bgNodes.forEach { unit ->
                val scaled = Vec3(unit.x * radius, unit.y * radius, unit.z * radius)
                val rot = rotateVec3(scaled, phase, 0.35f)
                val perspective = camDist / (camDist - rot.z)
                val sx = center.x + rot.x * perspective
                val sy = center.y + rot.y * perspective
                val depthAlpha = ((rot.z + radius) / (2f * radius)).coerceIn(0.06f, 0.34f)
                drawCircle(
                    color = (if (isConnected) QsColors.AuroraMint else QsColors.ElectricCyan).copy(alpha = depthAlpha),
                    radius = (1.8.dp.toPx() * perspective).coerceIn(1f, 4f),
                    center = Offset(sx, sy),
                )
            }
        }
    }
}

private fun extractWrappedSegment(
    pm: PathMeasure,
    totalLen: Float,
    startDist: Float,
    endDist: Float,
    dest: Path,
) {
    val s = ((startDist % totalLen) + totalLen) % totalLen
    val e = ((endDist % totalLen) + totalLen) % totalLen
    if (s < e) {
        pm.getSegment(s, e, dest, startWithMoveTo = true)
    } else {
        pm.getSegment(s, totalLen, dest, startWithMoveTo = true)
        pm.getSegment(0f, e, dest, startWithMoveTo = true)
    }
}

/**
 * Translucent Frosted 3D Glass Card with:
 * - Multi-layer glass border (top specular reflection + hairline perimeter)
 * - Signature animated traveling light beam gliding smoothly along the rounded card perimeter
 * - Tactile 3D perspective press tilt when clickable
 */
@Composable
fun Glass3dCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    glowColor: Color = QsColors.ElectricCyan.copy(alpha = 0.32f),
    surfaceColor: Color = QsColors.SurfaceCard,
    elevationDp: Dp = 8.dp,
    contentPadding: Dp = 16.dp,
    beamSeed: Int = 0,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current.density
    val motionActive = rememberMotionActive()
    val seedVal = remember(glowColor, beamSeed) {
        abs(glowColor.value.toInt() xor (beamSeed * 2654435761L).toInt())
    }
    val initialPhase = remember(seedVal) { (seedVal % 100) / 100f }
    val clockwise = remember(seedVal) { (seedVal % 2) == 0 }
    val durationMs = remember(seedVal) { 5400 + (seedVal % 4) * 900 }

    val beamPhase = if (motionActive) {
        rememberInfiniteTransition(label = "card_beam_flow").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = durationMs, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "card_beam_phase"
        ).value
    } else {
        0.18f
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 460f),
        label = "card_scale"
    )
    val tiltX by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 3.5f else 0f,
        animationSpec = spring(stiffness = 460f),
        label = "card_tiltx"
    )

    val shape = RoundedCornerShape(cornerRadius)
    val baseBorder = Brush.verticalGradient(
        colors = listOf(
            QsColors.GlassBorderTop.copy(alpha = 0.45f),
            QsColors.BorderSubtle.copy(alpha = 0.65f),
            QsColors.GlassBorderBottom.copy(alpha = 0.25f),
        )
    )

    val beamLead = if (glowColor == QsColors.BorderSubtle) QsColors.ElectricCyan else glowColor.copy(alpha = 0.92f)
    val beamTrail = QsColors.PlasmaViolet.copy(alpha = 0.65f)

    val perimeterPath = remember { Path() }
    val beamSegmentPath = remember { Path() }
    val coreSegmentPath = remember { Path() }
    val pathMeasure = remember { PathMeasure() }

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
                cameraDistance = 16f * density
            }
            .drawWithContent {
                drawContent()
                val crPx = cornerRadius.toPx().coerceAtMost(size.minDimension * 0.48f)
                val inset = 0.9.dp.toPx()
                val w = (size.width - inset * 2f).coerceAtLeast(2f)
                val h = (size.height - inset * 2f).coerceAtLeast(2f)
                perimeterPath.reset()
                perimeterPath.addRoundRect(
                    RoundRect(
                        rect = Rect(inset, inset, inset + w, inset + h),
                        cornerRadius = CornerRadius(crPx, crPx)
                    )
                )
                pathMeasure.setPath(perimeterPath, forceClosed = true)
                val totalLen = pathMeasure.length
                if (totalLen > 4f && glowColor.alpha > 0.05f) {
                    val progress = ((beamPhase + initialPhase) % 1f).let { if (clockwise) it else 1f - it }
                    val beamFraction = 0.22f
                    val coreFraction = 0.07f
                    val startDist = progress * totalLen
                    val endDist = startDist + totalLen * beamFraction

                    beamSegmentPath.reset()
                    extractWrappedSegment(pathMeasure, totalLen, startDist, endDist, beamSegmentPath)

                    val coreStartDist = startDist + totalLen * (beamFraction - coreFraction)
                    coreSegmentPath.reset()
                    extractWrappedSegment(pathMeasure, totalLen, coreStartDist, endDist, coreSegmentPath)

                    val tangentOffset = pathMeasure.getPosition(endDist % totalLen)
                    val tailOffset = pathMeasure.getPosition(startDist % totalLen)
                    val beamBrush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            beamTrail,
                            beamLead,
                            Color.White.copy(alpha = 0.90f),
                        ),
                        start = tailOffset,
                        end = tangentOffset
                    )

                    drawPath(
                        path = beamSegmentPath,
                        brush = beamBrush,
                        alpha = 0.26f,
                        style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = beamSegmentPath,
                        brush = beamBrush,
                        alpha = 0.85f,
                        style = Stroke(width = 1.7.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                    drawPath(
                        path = coreSegmentPath,
                        color = Color.White.copy(alpha = 0.78f),
                        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }
            .then(clickMod),
        shape = shape,
        color = surfaceColor,
        shadowElevation = elevationDp,
        border = BorderStroke(1.dp, baseBorder)
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
 * 3D Volumetric Gyroscope + Fibonacci Core Connect Orb:
 * - True 3D depth-sorted Fibonacci sphere lattice rotating inside the translucent glass sphere
 * - Counter-rotating 3D tilted gyroscope orbital rings + 36-tick precision aerospace bezel
 * - Expanding dual sonar/shield harmonic ripples + crisp center vector glyph
 * - Spring 3D perspective press physics
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
    sizeDp: Dp = 204.dp,
    centerContent: @Composable BoxScope.() -> Unit = {},
) {
    val density = LocalDensity.current.density
    val motionActive = rememberMotionActive()
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.93f else 1.0f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = 420f),
        label = "orb_press",
    )
    val pressTiltX by animateFloatAsState(
        targetValue = if (isPressed) 8f else 0f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 420f),
        label = "orb_tilt",
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
            OrbVisualMode.IDLE -> QsColors.PlasmaViolet
        },
        animationSpec = tween(420),
        label = "orb_secondary_color",
    )

    val spinDeg: Float
    val sphereYawRad: Float
    val pulse: Float
    val wavePhase: Float
    if (motionActive) {
        val infinite = rememberInfiniteTransition(label = "orb_3d_motion")
        spinDeg = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = when (mode) {
                        OrbVisualMode.CONNECTING, OrbVisualMode.SCANNING -> 2200
                        OrbVisualMode.CONNECTED -> 8500
                        OrbVisualMode.IDLE -> 15000
                    },
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orb_spin",
        ).value
        sphereYawRad = infinite.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (mode == OrbVisualMode.CONNECTING || mode == OrbVisualMode.SCANNING) 3600 else 9500,
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orb_sphere_yaw",
        ).value
        pulse = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "orb_pulse",
        ).value
        wavePhase = infinite.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "orb_wave",
        ).value
    } else {
        spinDeg = 0f
        sphereYawRad = 0.6f
        pulse = 0.5f
        wavePhase = 0f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = (scanProgressFraction ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(260),
        label = "orb_scan_progress",
    )

    val spherePoints = remember { buildFibonacciLattice(32, 1f) }

    Box(
        modifier = modifier
            .size(sizeDp)
            .graphicsLayer {
                scaleX = pressScale
                scaleY = pressScale
                rotationX = pressTiltX
                cameraDistance = 12f * density
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

            // 1. Expanding Sonar / Shield Ripples
            val rippleAlphaMult = if (mode == OrbVisualMode.IDLE) 0.22f else 0.50f
            val r1 = maxR * (0.64f + 0.34f * wavePhase)
            val a1 = (1f - wavePhase) * rippleAlphaMult
            drawCircle(
                color = primaryColor.copy(alpha = a1),
                radius = r1,
                center = Offset(cx, cy),
                style = Stroke(width = 1.8.dp.toPx())
            )
            val p2 = (wavePhase + 0.5f) % 1f
            val r2 = maxR * (0.64f + 0.34f * p2)
            val a2 = (1f - p2) * (rippleAlphaMult * 0.72f)
            drawCircle(
                color = secondaryColor.copy(alpha = a2),
                radius = r2,
                center = Offset(cx, cy),
                style = Stroke(width = 1.2.dp.toPx())
            )

            // 2. Outer Calibrated 36-Tick Precision Bezel
            val tickOuterR = maxR * 0.91f
            for (i in 0 until 36) {
                val isMajor = i % 3 == 0
                val angleRad = ((i * 10f) - 90f) * (PI / 180.0)
                val innerR = tickOuterR - (if (isMajor) 5.5.dp.toPx() else 2.8.dp.toPx())
                val tickColor = if (mode == OrbVisualMode.CONNECTED || isMajor) {
                    primaryColor.copy(alpha = if (isMajor) 0.78f else 0.32f)
                } else {
                    QsColors.BorderStrong.copy(alpha = 0.45f)
                }
                drawLine(
                    color = tickColor,
                    start = Offset(cx + cos(angleRad).toFloat() * innerR, cy + sin(angleRad).toFloat() * innerR),
                    end = Offset(cx + cos(angleRad).toFloat() * tickOuterR, cy + sin(angleRad).toFloat() * tickOuterR),
                    strokeWidth = if (isMajor) 1.6.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // 3. 3D Tilted Gyroscope Orbit Rings
            val gyroR = maxR * 0.80f
            rotate(degrees = 26f + spinDeg * 0.35f, pivot = Offset(cx, cy)) {
                drawOval(
                    color = primaryColor.copy(alpha = 0.28f + pulse * 0.16f),
                    topLeft = Offset(cx - gyroR, cy - gyroR * 0.42f),
                    size = Size(gyroR * 2f, gyroR * 0.84f),
                    style = Stroke(width = 1.4.dp.toPx()),
                )
            }
            rotate(degrees = -34f - spinDeg * 0.28f, pivot = Offset(cx, cy)) {
                drawOval(
                    color = secondaryColor.copy(alpha = 0.24f + pulse * 0.14f),
                    topLeft = Offset(cx - gyroR, cy - gyroR * 0.38f),
                    size = Size(gyroR * 2f, gyroR * 0.76f),
                    style = Stroke(width = 1.2.dp.toPx()),
                )
            }

            // 4. Orbital Progress / Sweep Track
            val ringR = maxR * 0.72f
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
                    style = Stroke(width = ringStroke + 1.2.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            rotate(degrees = spinDeg, pivot = Offset(cx, cy)) {
                val sweepSpan = if (mode == OrbVisualMode.CONNECTED) 360f else 135f
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to if (mode == OrbVisualMode.CONNECTED) primaryColor.copy(alpha = 0.65f) else Color.Transparent,
                        0.55f to primaryColor.copy(alpha = 0.88f),
                        0.85f to secondaryColor,
                        1.0f to if (mode == OrbVisualMode.CONNECTED) primaryColor.copy(alpha = 0.65f) else Color.Transparent,
                    ),
                    startAngle = 0f,
                    sweepAngle = sweepSpan,
                    useCenter = false,
                    topLeft = Offset(cx - ringR, cy - ringR),
                    size = Size(ringR * 2f, ringR * 2f),
                    style = Stroke(width = ringStroke + 1.dp.toPx(), cap = StrokeCap.Round),
                )
            }

            // Orbiting telemetry node on perimeter
            val orbAngle = (spinDeg + 125f - 90f) * (PI / 180.0)
            val dotOffset = Offset(cx + cos(orbAngle).toFloat() * ringR, cy + sin(orbAngle).toFloat() * ringR)
            drawCircle(color = primaryColor, radius = 4.4.dp.toPx(), center = dotOffset)
            drawCircle(color = Color.White, radius = 2.dp.toPx(), center = dotOffset)

            // 5. Volumetric 3D Glass Core Dome
            val coreR = maxR * 0.60f
            val coreBrush = when (mode) {
                OrbVisualMode.CONNECTED -> Brush.radialGradient(
                    colors = listOf(Color(0xFF135244), Color(0xFF0B2F2B), Color(0xFF07161B)),
                    center = Offset(cx - coreR * 0.28f, cy - coreR * 0.30f),
                    radius = coreR * 1.4f,
                )
                OrbVisualMode.SCANNING, OrbVisualMode.CONNECTING -> Brush.radialGradient(
                    colors = listOf(Color(0xFF19375C), Color(0xFF102038), Color(0xFF091220)),
                    center = Offset(cx - coreR * 0.28f, cy - coreR * 0.30f),
                    radius = coreR * 1.4f,
                )
                OrbVisualMode.IDLE -> Brush.radialGradient(
                    colors = listOf(Color(0xFF1F2E49), Color(0xFF121C30), Color(0xFF0B111E)),
                    center = Offset(cx - coreR * 0.28f, cy - coreR * 0.30f),
                    radius = coreR * 1.4f,
                )
            }
            drawCircle(brush = coreBrush, radius = coreR, center = Offset(cx, cy))

            // 6. 3D Rotating Fibonacci Constellation Inside Core Dome (depth-projected)
            val latticeR = coreR * 0.78f
            val camDist = coreR * 2.8f
            spherePoints.forEach { unit ->
                val scaled = Vec3(unit.x * latticeR, unit.y * latticeR, unit.z * latticeR)
                val rot = rotateVec3(scaled, sphereYawRad, 0.38f)
                val depthScale = camDist / (camDist - rot.z)
                val sx = cx + rot.x * depthScale
                val sy = cy + rot.y * depthScale
                val depthFrac = ((rot.z + latticeR) / (2f * latticeR)).coerceIn(0.12f, 0.85f)
                drawCircle(
                    color = primaryColor.copy(alpha = depthFrac * (if (mode == OrbVisualMode.IDLE) 0.45f else 0.72f)),
                    radius = (1.6.dp.toPx() * depthScale).coerceIn(1f, 3.2.dp.toPx()),
                    center = Offset(sx, sy),
                )
            }

            // Glass Specular Rim Highlight
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.35f),
                        primaryColor.copy(alpha = if (mode == OrbVisualMode.IDLE) 0.35f else 0.75f),
                        QsColors.BorderSubtle.copy(alpha = 0.4f),
                    ),
                    startY = cy - coreR,
                    endY = cy + coreR,
                ),
                radius = coreR,
                center = Offset(cx, cy),
                style = Stroke(width = 1.6.dp.toPx()),
            )
        }

        Box(contentAlignment = Alignment.Center) {
            centerContent()
        }
    }
}

/**
 * Crisp vector glyph inside ConnectionOrb3d.
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
 * 3D Illuminated Donut Quota Gauge with tabular Vazirmatn readout.
 */
@Composable
fun Animated3dQuotaChart(
    usedFraction: Float,
    usedPercentText: String,
    caption: String,
    accentColor: Color = QsColors.ElectricCyan,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 130.dp,
) {
    val animatedSweep by animateFloatAsState(
        targetValue = (usedFraction.coerceIn(0f, 1f) * 360f),
        animationSpec = tween(durationMillis = 750, easing = FastOutSlowInEasing),
        label = "quota_sweep"
    )

    Box(modifier = modifier.size(sizeDp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = 12.dp.toPx()
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
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Text(
                text = caption,
                color = QsColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * 3D Illuminated Live Speed HUD Row:
 * Each tile uses Glass3dCard (traveling perimeter light beam) + smooth cubic Bezier telemetry wave +
 * dual-lane animated fiber packet conduit (downward for Download, upward for Upload).
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
    val motionActive = rememberMotionActive()
    val packetFlow = if (motionActive && isRunning) {
        rememberInfiniteTransition(label = "data_packet_stream").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1350, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "packet_t"
        ).value
    } else 0f

    val dlHistory = remember { mutableStateListOf(0.08f, 0.1f, 0.08f, 0.12f, 0.1f, 0.08f, 0.1f, 0.12f, 0.09f, 0.11f) }
    val ulHistory = remember { mutableStateListOf(0.08f, 0.1f, 0.08f, 0.12f, 0.1f, 0.08f, 0.1f, 0.12f, 0.09f, 0.11f) }

    LaunchedEffect(downloadBps, uploadBps, isRunning) {
        val dlNorm = if (!isRunning) 0.06f else {
            val scaled = ln(1.0 + downloadBps.coerceAtLeast(0L).toDouble()) / ln(1.0 + 10_000_000.0)
            scaled.toFloat().coerceIn(0.14f, 1.0f)
        }
        val ulNorm = if (!isRunning) 0.06f else {
            val scaled = ln(1.0 + uploadBps.coerceAtLeast(0L).toDouble()) / ln(1.0 + 4_000_000.0)
            scaled.toFloat().coerceIn(0.14f, 1.0f)
        }
        dlHistory.removeAt(0)
        dlHistory.add(dlNorm)
        ulHistory.removeAt(0)
        ulHistory.add(ulNorm)
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SpeedMeterTile(
            label = downloadLabel,
            value = if (isRunning) downloadFormatted else "0 B/s",
            accent = QsColors.AuroraMint,
            secondaryAccent = QsColors.ElectricCyan,
            flowProgress = packetFlow,
            history = dlHistory,
            active = isRunning,
            isDownload = true,
            beamSeed = 11,
            modifier = Modifier.weight(1f),
        )
        SpeedMeterTile(
            label = uploadLabel,
            value = if (isRunning) uploadFormatted else "0 B/s",
            accent = QsColors.ElectricCyan,
            secondaryAccent = QsColors.PlasmaViolet,
            flowProgress = packetFlow,
            history = ulHistory,
            active = isRunning,
            isDownload = false,
            beamSeed = 23,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SpeedMeterTile(
    label: String,
    value: String,
    accent: Color,
    secondaryAccent: Color,
    flowProgress: Float,
    history: List<Float>,
    active: Boolean,
    isDownload: Boolean,
    beamSeed: Int,
    modifier: Modifier = Modifier,
) {
    Glass3dCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        glowColor = accent.copy(alpha = if (active) 0.62f else 0.25f),
        contentPadding = 13.dp,
        beamSeed = beamSeed,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isDownload) "↓ $label" else "↑ $label",
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = value,
                    style = QsTextStyles.TabularStat,
                    color = if (active) QsColors.TextPrimary else QsColors.TextMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(7.dp))
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(18.dp)
                        .padding(end = 6.dp)
                ) {
                    val count = history.size.coerceAtLeast(2)
                    val stepX = size.width / (count - 1).toFloat()
                    val tracePath = Path()
                    val fillPath = Path()
                    for (i in 0 until count) {
                        val x = i * stepX
                        val y = size.height * (1f - history[i].coerceIn(0.06f, 0.96f))
                        if (i == 0) {
                            tracePath.moveTo(x, y)
                            fillPath.moveTo(x, size.height)
                            fillPath.lineTo(x, y)
                        } else {
                            val prevX = (i - 1) * stepX
                            val prevY = size.height * (1f - history[i - 1].coerceIn(0.06f, 0.96f))
                            val midX = (prevX + x) * 0.5f
                            tracePath.cubicTo(midX, prevY, midX, y, x, y)
                            fillPath.cubicTo(midX, prevY, midX, y, x, y)
                        }
                    }
                    fillPath.lineTo(size.width, size.height)
                    fillPath.close()
                    drawPath(
                        path = fillPath,
                        brush = Brush.verticalGradient(
                            colors = listOf(accent.copy(alpha = if (active) 0.36f else 0.12f), Color.Transparent)
                        )
                    )
                    drawPath(
                        path = tracePath,
                        brush = Brush.horizontalGradient(listOf(secondaryAccent.copy(alpha = 0.72f), accent)),
                        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
            }

            // Dual-lane 3D packet conduit
            Canvas(modifier = Modifier.size(width = 24.dp, height = 46.dp)) {
                val laneX1 = size.width * 0.30f
                val laneX2 = size.width * 0.70f
                drawLine(
                    color = accent.copy(alpha = if (active) 0.28f else 0.14f),
                    start = Offset(laneX1, 2.dp.toPx()),
                    end = Offset(laneX1, size.height - 2.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = secondaryAccent.copy(alpha = if (active) 0.28f else 0.14f),
                    start = Offset(laneX2, 2.dp.toPx()),
                    end = Offset(laneX2, size.height - 2.dp.toPx()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round
                )

                val packetCount = 3
                for (i in 0 until packetCount) {
                    val phase = (flowProgress + i / packetCount.toFloat()) % 1f
                    val yFrac1 = if (isDownload) phase else (1f - phase)
                    val yFrac2 = if (isDownload) ((phase + 0.45f) % 1f) else (1f - ((phase + 0.45f) % 1f))
                    val py1 = yFrac1 * size.height
                    val py2 = yFrac2 * size.height
                    val alpha1 = if (active) (1f - abs(yFrac1 - 0.5f) * 1.4f).coerceIn(0.28f, 1f) else 0.22f
                    val alpha2 = if (active) (1f - abs(yFrac2 - 0.5f) * 1.4f).coerceIn(0.22f, 0.9f) else 0.18f

                    drawRoundRect(
                        color = accent.copy(alpha = alpha1),
                        topLeft = Offset(laneX1 - 2.3.dp.toPx(), (py1 - 3.8.dp.toPx()).coerceIn(0f, size.height - 7.5.dp.toPx())),
                        size = Size(4.6.dp.toPx(), 7.5.dp.toPx()),
                        cornerRadius = CornerRadius(2.3.dp.toPx(), 2.3.dp.toPx())
                    )
                    drawRoundRect(
                        color = secondaryAccent.copy(alpha = alpha2),
                        topLeft = Offset(laneX2 - 1.9.dp.toPx(), (py2 - 3.dp.toPx()).coerceIn(0f, size.height - 6.dp.toPx())),
                        size = Size(3.8.dp.toPx(), 6.dp.toPx()),
                        cornerRadius = CornerRadius(1.9.dp.toPx(), 1.9.dp.toPx())
                    )
                }
            }
        }
    }
}

/**
 * Official Cloudflare / Amazon CloudFront Brand Logo Badges.
 */
@Composable
fun CdnProviderBadge(cdnType: CdnType, modifier: Modifier = Modifier) {
    val (bg, fg, label, iconRes) = when (cdnType) {
        CdnType.CLOUDFLARE -> Quad(
            QsColors.CloudflareOrange.copy(alpha = 0.18f),
            QsColors.CloudflareOrange,
            "Cloudflare",
            R.drawable.ic_logo_cloudflare,
        )
        CdnType.CLOUDFRONT -> Quad(
            QsColors.CloudFrontSky.copy(alpha = 0.18f),
            QsColors.CloudFrontSky,
            "CloudFront",
            R.drawable.ic_logo_cloudfront,
        )
        CdnType.UNKNOWN -> Quad(
            QsColors.CloudflareOrange.copy(alpha = 0.15f),
            QsColors.CloudflareOrange,
            "CDN",
            R.drawable.ic_logo_cloudflare,
        )
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(bg)
            .border(1.dp, fg.copy(alpha = 0.42f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = label,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

@Composable
fun StandardPipelineBadge(
    label: String,
    modifier: Modifier = Modifier,
) {
    val cleanLabel = label.ifBlank { "DIRECT" }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(QsColors.AuroraMint.copy(alpha = 0.16f))
            .border(1.dp, QsColors.AuroraMint.copy(alpha = 0.40f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_logo_direct),
            contentDescription = cleanLabel,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = cleanLabel,
            color = QsColors.AuroraMint,
            fontSize = 11.sp,
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
            .clip(RoundedCornerShape(13.dp))
            .background(QsColors.SurfaceInset)
            .border(1.dp, QsColors.BorderSubtle, RoundedCornerShape(13.dp))
            .padding(horizontal = 11.dp, vertical = 9.dp)
    ) {
        Text(text = label, color = QsColors.TextMuted, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(
            text = value,
            style = QsTextStyles.TabularStat,
            color = accent,
            fontSize = 13.sp,
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
            .size(10.dp)
            .clip(CircleShape)
            .background(if (active) QsColors.ConnectedEmerald else QsColors.TextMuted)
    )
    Spacer(Modifier.width(7.dp))
}
