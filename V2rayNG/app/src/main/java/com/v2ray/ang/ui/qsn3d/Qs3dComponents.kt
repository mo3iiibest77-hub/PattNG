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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
 * Returns true only when the screen is active in the foreground (`Lifecycle.State.STARTED`)
 * and OS Reduce Motion / Animator Scale allows visual motion.
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
            val osEnabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else true
            osEnabled && scale > 0f
        }.getOrDefault(true)
    }
    return inForeground && animatorEnabled
}

/**
 * Architectural hero banner blending a generated dark-obsidian artwork cleanly into cards.
 */
@Composable
fun SectionHeroBanner(
    @DrawableRes imageRes: Int,
    title: String,
    subtitle: String,
    accent: Color,
    height: Dp = 108.dp,
    badgeText: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF070C1B))
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(
                    listOf(accent.copy(alpha = 0.50f), QsColors.GlassBorderTop.copy(alpha = 0.20f))
                ),
                shape = RoundedCornerShape(20.dp)
            )
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .alpha(0.72f)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xE6050916),
                            Color(0xBA070D1E),
                            Color(0x55081024),
                        )
                    )
                )
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            if (!badgeText.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(accent.copy(alpha = 0.22f))
                        .border(0.75.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = accent,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Column {
                Text(
                    text = title,
                    color = QsColors.TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = QsColors.TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

enum class OrbVisualMode {
    DISCONNECTED,
    PROCESSING,
    CONNECTED,
    ERROR
}

private data class Vec3(val x: Float, val y: Float, val z: Float)

private fun projectPoint(
    v: Vec3,
    yaw: Float,
    pitch: Float,
    cx: Float,
    cy: Float,
    scale: Float,
): Pair<Offset, Float> {
    val cosY = cos(yaw)
    val sinY = sin(yaw)
    val cosP = cos(pitch)
    val sinP = sin(pitch)

    val rx = v.x * cosY - v.z * sinY
    val rz1 = v.x * sinY + v.z * cosY
    val ry = v.y * cosP - rz1 * sinP
    val rz = v.y * sinP + rz1 * cosP

    val camDist = 3.2f
    val persp = camDist / (camDist + rz + 1.4f)
    return Offset(cx + rx * scale * persp, cy + ry * scale * persp) to persp
}

/**
 * Genuine 60fps 3D animated space backdrop featuring:
 * - True perspective 3D rotating crystal polyhedron (octahedron + inner cube lattice)
 * - Scrolling infinite 3D synth-grid horizon zooming toward the viewer
 * - Volumetric plasma aurora fields & parallax particle depth field
 */
@Composable
fun Cosmic3dBackdrop(
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
) {
    val motionActive = rememberMotionActive()
    val spin: Float
    val gridScroll: Float
    val auroraBreath: Float
    if (motionActive) {
        val transition = rememberInfiniteTransition(label = "cosmic_3d_bg")
        spin = transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 18000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "poly_spin"
        ).value
        gridScroll = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "grid_scroll"
        ).value
        auroraBreath = transition.animateFloat(
            initialValue = 0.72f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4800, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "aurora_breath"
        ).value
    } else {
        spin = 0.55f
        gridScroll = 0.25f
        auroraBreath = 0.85f
    }

    val accentColor by animateColorAsState(
        targetValue = if (isConnected) QsColors.ConnectedEmerald else QsColors.ElectricCyan,
        animationSpec = tween(700),
        label = "bg_accent"
    )

    val octaVertices = remember {
        listOf(
            Vec3(0f, -1.25f, 0f),
            Vec3(1.1f, 0f, 0f),
            Vec3(0f, 0f, 1.1f),
            Vec3(-1.1f, 0f, 0f),
            Vec3(0f, 0f, -1.1f),
            Vec3(0f, 1.25f, 0f),
        )
    }
    val octaEdges = remember {
        listOf(
            0 to 1, 0 to 2, 0 to 3, 0 to 4,
            5 to 1, 5 to 2, 5 to 3, 5 to 4,
            1 to 2, 2 to 3, 3 to 4, 4 to 1
        )
    }

    val particles = remember {
        List(42) { idx ->
            val fx = ((idx * 67 + 23) % 100) / 100f
            val fy = ((idx * 43 + 11) % 100) / 100f
            val depth = 0.35f + (idx % 5) * 0.18f
            Triple(fx, fy, depth)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        QsColors.DeepVoid,
                        Color(0xFF080E23),
                        Color(0xFF0C1532),
                        QsColors.DeepVoid
                    )
                )
            )
    ) {
        val w = size.width
        val h = size.height

        // 1. Volumetric glowing nebula clouds
        val orb1Center = Offset(w * (0.24f + 0.1f * sin(spin)), h * 0.20f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accentColor.copy(alpha = 0.24f * auroraBreath), Color.Transparent),
                center = orb1Center,
                radius = w * 0.72f
            ),
            radius = w * 0.72f,
            center = orb1Center
        )

        val orb2Center = Offset(w * 0.80f, h * (0.55f + 0.08f * cos(spin)))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(QsColors.PlasmaViolet.copy(alpha = 0.22f * auroraBreath), Color.Transparent),
                center = orb2Center,
                radius = w * 0.78f
            ),
            radius = w * 0.78f,
            center = orb2Center
        )

        // 2. Rotating 3D wireframe polyhedron in upper-center orbital field
        val polyCx = w * 0.5f
        val polyCy = h * 0.31f
        val polyScale = w * 0.42f
        val projected = octaVertices.map { v ->
            projectPoint(v, yaw = spin, pitch = 0.42f + 0.22f * sin(spin * 0.5f), polyCx, polyCy, polyScale)
        }
        for ((a, b) in octaEdges) {
            val (p1, d1) = projected[a]
            val (p2, d2) = projected[b]
            val depthAlpha = (((d1 + d2) * 0.5f).coerceIn(0.35f, 1.1f)) * 0.26f
            drawLine(
                brush = Brush.linearGradient(
                    listOf(
                        accentColor.copy(alpha = depthAlpha),
                        QsColors.PlasmaViolet.copy(alpha = depthAlpha * 0.9f),
                    ),
                    start = p1,
                    end = p2
                ),
                start = p1,
                end = p2,
                strokeWidth = 1.6f
            )
        }
        projected.forEach { (pt, d) ->
            drawCircle(
                color = accentColor.copy(alpha = (0.45f * d).coerceIn(0.12f, 0.55f)),
                radius = (2.6f * d).dp.toPx(),
                center = pt
            )
        }

        // 3. Scrolling 3D perspective horizon plane
        val horizonY = h * 0.64f
        val rows = 10
        for (i in 0 until rows) {
            val t = ((i + gridScroll) / rows.toFloat()).coerceIn(0f, 1f)
            val curvedY = horizonY + (h - horizonY) * (t * t)
            drawLine(
                color = accentColor.copy(alpha = 0.14f * t),
                start = Offset(0f, curvedY),
                end = Offset(w, curvedY),
                strokeWidth = 1.2f
            )
        }
        val vpX = w * 0.5f
        for (j in -6..6) {
            val bottomX = vpX + j * (w * 0.23f)
            drawLine(
                color = QsColors.PlasmaViolet.copy(alpha = 0.11f),
                start = Offset(vpX, horizonY),
                end = Offset(bottomX, h),
                strokeWidth = 1.1f
            )
        }

        // 4. Parallax starfield
        particles.forEachIndexed { idx, (fx, fy, depth) ->
            val yShift = ((fy + gridScroll * 0.18f * depth) % 1f) * h
            val tw = 0.25f + 0.55f * absSin(spin + idx * 0.7f)
            drawCircle(
                color = if (idx % 3 == 0) accentColor.copy(alpha = tw * depth)
                else Color.White.copy(alpha = tw * 0.7f * depth),
                radius = (1.1f + depth * 1.4f).dp.toPx(),
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
 * Architectural 3D Obsidian card with specular bevel rim, layered interior depth gradient,
 * and subtle spring tactile press feedback.
 */
@Composable
fun Glass3dCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    glowColor: Color = QsColors.ElectricCyan.copy(alpha = 0.36f),
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
        targetValue = if (pressed && onClick != null) 4f else 0f,
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
                cameraDistance = 14f * density
            }
            .then(clickMod),
        shape = RoundedCornerShape(cornerRadius),
        color = Color.Transparent,
        shadowElevation = 16.dp,
        border = BorderStroke(
            width = 1.1.dp,
            brush = Brush.verticalGradient(
                colors = listOf(glowColor, QsColors.GlassBorderTop.copy(alpha = 0.16f), QsColors.GlassBorderBottom)
            )
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            QsColors.GlassSurfaceElevated,
                            QsColors.GlassSurface,
                            Color(0xCC0A1128),
                        )
                    )
                )
                .padding(16.dp),
            content = content
        )
    }
}

/**
 * High-craft 3D Orbital Gimbal Connection Sphere with nested rotating Saturnian rings,
 * energy radar sweep during connection/scan, and glowing plasma state transitions.
 */
@Composable
fun ConnectionOrb3d(
    mode: OrbVisualMode,
    progressFraction: Float?,
    primaryText: String,
    secondaryText: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionActive = rememberMotionActive()
    val ringRotation: Float
    val breath: Float
    val shockwave: Float
    if (motionActive) {
        val transition = rememberInfiniteTransition(label = "orb_anim")
        ringRotation = transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = if (mode == OrbVisualMode.PROCESSING) 2600 else 10000,
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "ring_rot"
        ).value
        breath = transition.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                animation = tween(2200, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "orb_breath"
        ).value
        shockwave = transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(2400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "orb_shockwave"
        ).value
    } else {
        ringRotation = 24f
        breath = 1.0f
        shockwave = 0f
    }

    val primaryColor by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.ConnectedEmerald
            OrbVisualMode.PROCESSING -> QsColors.PlasmaViolet
            OrbVisualMode.ERROR -> QsColors.DangerCrimson
            OrbVisualMode.DISCONNECTED -> QsColors.ElectricCyan
        },
        animationSpec = tween(550),
        label = "orb_color"
    )
    val secondaryColor by animateColorAsState(
        targetValue = when (mode) {
            OrbVisualMode.CONNECTED -> QsColors.AuroraMint
            OrbVisualMode.PROCESSING -> QsColors.ElectricCyan
            OrbVisualMode.ERROR -> QsColors.SolarGold
            OrbVisualMode.DISCONNECTED -> QsColors.PlasmaViolet
        },
        animationSpec = tween(550),
        label = "orb_sec_color"
    )

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(stiffness = 450f),
        label = "orb_press"
    )

    Box(
        modifier = modifier
            .size(236.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val maxR = size.minDimension / 2f

            // Volumetric outer shockwave aura
            if (mode == OrbVisualMode.CONNECTED || mode == OrbVisualMode.PROCESSING) {
                val waveRadius = maxR * (0.66f + 0.32f * shockwave)
                drawCircle(
                    color = primaryColor.copy(alpha = (1f - shockwave) * 0.35f),
                    radius = waveRadius,
                    center = center,
                    style = Stroke(width = 2.5f)
                )
            }

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(primaryColor.copy(alpha = 0.34f), Color.Transparent),
                    center = center,
                    radius = maxR
                ),
                radius = maxR,
                center = center
            )

            // 3D tilted Saturnian orbital arc 1
            val tiltAngle = ringRotation * (PI / 180.0).toFloat()
            val rx1 = maxR * 0.90f
            val ry1 = maxR * 0.44f
            val orbitPath = Path()
            for (deg in 0..360 step 6) {
                val rad = (deg * PI / 180.0).toFloat()
                val lx = cos(rad) * rx1
                val ly = sin(rad) * ry1
                val wx = center.x + lx * cos(tiltAngle) - ly * sin(tiltAngle)
                val wy = center.y + lx * sin(tiltAngle) + ly * cos(tiltAngle)
                if (deg == 0) orbitPath.moveTo(wx, wy) else orbitPath.lineTo(wx, wy)
            }
            drawPath(
                path = orbitPath,
                brush = Brush.linearGradient(listOf(primaryColor.copy(alpha = 0.7f), secondaryColor.copy(alpha = 0.25f))),
                style = Stroke(width = 2.2f)
            )

            // Second orthogonal tilted orbital ring
            val tiltAngle2 = (-ringRotation * 0.7f) * (PI / 180.0).toFloat()
            val orbitPath2 = Path()
            for (deg in 0..360 step 6) {
                val rad = (deg * PI / 180.0).toFloat()
                val lx = cos(rad) * (maxR * 0.85f)
                val ly = sin(rad) * (maxR * 0.34f)
                val wx = center.x + lx * cos(tiltAngle2) - ly * sin(tiltAngle2)
                val wy = center.y + lx * sin(tiltAngle2) + ly * cos(tiltAngle2)
                if (deg == 0) orbitPath2.moveTo(wx, wy) else orbitPath2.lineTo(wx, wy)
            }
            drawPath(
                path = orbitPath2,
                brush = Brush.linearGradient(listOf(secondaryColor.copy(alpha = 0.55f), Color.Transparent)),
                style = Stroke(width = 1.6f)
            )

            // Sweep arc around main sphere
            drawArc(
                brush = Brush.sweepGradient(listOf(primaryColor, secondaryColor, primaryColor.copy(alpha = 0.1f))),
                startAngle = ringRotation,
                sweepAngle = if (mode == OrbVisualMode.PROCESSING) 220f else 120f,
                useCenter = false,
                topLeft = Offset(center.x - maxR * 0.78f, center.y - maxR * 0.78f),
                size = Size(maxR * 1.56f, maxR * 1.56f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )

            if (mode == OrbVisualMode.PROCESSING && progressFraction != null) {
                drawArc(
                    color = QsColors.AuroraMint,
                    startAngle = -90f,
                    sweepAngle = 360f * progressFraction.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(center.x - maxR * 0.71f, center.y - maxR * 0.71f),
                    size = Size(maxR * 1.42f, maxR * 1.42f),
                    style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // Inner 3D shaded obsidian sphere with off-center specular studio light
            val coreRadius = maxR * 0.62f * (if (mode == OrbVisualMode.CONNECTED) breath else 1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.55f),
                        Color(0xFF141E3C),
                        Color(0xFF060A18),
                    ),
                    center = Offset(center.x - coreRadius * 0.34f, center.y - coreRadius * 0.36f),
                    radius = coreRadius * 1.38f
                ),
                radius = coreRadius,
                center = center
            )

            // Glossy sphere rim
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.45f), primaryColor.copy(alpha = 0.75f), Color.Transparent)
                ),
                radius = coreRadius,
                center = center,
                style = Stroke(width = 2.dp.toPx())
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 30.dp)
        ) {
            Text(
                text = primaryText,
                color = QsColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = secondaryText,
                color = primaryColor.copy(alpha = 0.92f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2
            )
        }
    }
}

/**
 * 3D Volumetric Animated Quota Ring Gauge for the Subscription Dashboard.
 * Features an extruded 3D shadow ring base, animated remaining-traffic sweep, and orbiting specular pulse.
 */
@Composable
fun Animated3dQuotaChart(
    remainingFraction: Float,
    centerPrimary: String,
    centerSecondary: String,
    modifier: Modifier = Modifier,
) {
    val motionActive = rememberMotionActive()
    val animatedSweep by animateFloatAsState(
        targetValue = remainingFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = if (motionActive) 1200 else 0, easing = FastOutSlowInEasing),
        label = "quota_sweep"
    )
    val highlightDeg = if (motionActive) {
        val transition = rememberInfiniteTransition(label = "quota_orbit")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "quota_glow_deg"
        ).value
    } else 45f

    Box(
        modifier = modifier.size(210.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension * 0.39f
            val strokePx = 18.dp.toPx()

            // Ambient backdrop glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(QsColors.ElectricCyan.copy(alpha = 0.24f), Color.Transparent),
                    center = c,
                    radius = r * 1.45f
                ),
                radius = r * 1.45f,
                center = c
            )

            // 3D depth extruded lower shadow arc (+7dp Z-offset effect)
            drawArc(
                color = Color(0xFF020612),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(c.x - r, c.y - r + 7.dp.toPx()),
                size = Size(r * 2f, r * 2f),
                style = Stroke(width = strokePx + 4.dp.toPx(), cap = StrokeCap.Round)
            )

            // Recessed track groove
            drawCircle(
                color = Color(0xFF1A2546),
                radius = r,
                center = c,
                style = Stroke(width = strokePx)
            )

            // Active remaining-traffic 3D gradient arc
            val sweepDeg = 360f * animatedSweep
            if (sweepDeg > 1f) {
                drawArc(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            QsColors.ElectricCyan,
                            QsColors.AuroraMint,
                            QsColors.PlasmaViolet,
                            QsColors.ElectricCyan
                        ),
                        center = c
                    ),
                    startAngle = -90f,
                    sweepAngle = sweepDeg,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )

                // Specular highlight tip dot orbiting edge
                val endRad = ((-90f + sweepDeg) * PI / 180.0).toFloat()
                val tip = Offset(c.x + r * cos(endRad), c.y + r * sin(endRad))
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = 5.dp.toPx(),
                    center = tip
                )
            }

            // Inner rotating holographic ring
            val radGlow = (highlightDeg * PI / 180.0).toFloat()
            drawArc(
                brush = Brush.sweepGradient(
                    colors = listOf(Color.Transparent, QsColors.ElectricCyan.copy(alpha = 0.6f), Color.Transparent),
                    center = c
                ),
                startAngle = highlightDeg,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.74f, c.y - r * 0.74f),
                size = Size(r * 1.48f, r * 1.48f),
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
            drawCircle(
                color = QsColors.AuroraMint.copy(alpha = 0.5f),
                radius = 2.5.dp.toPx(),
                center = Offset(c.x + r * 0.74f * cos(radGlow), c.y + r * 0.74f * sin(radGlow))
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerPrimary,
                color = QsColors.TextPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = centerSecondary,
                color = QsColors.AuroraMint,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Animated real-time Download/Upload throughput capsule designed exclusively for NovaPulse.
 */
@Composable
fun LiveSpeedHudRow(
    downloadSpeedText: String,
    uploadSpeedText: String,
    downloadLabel: String,
    uploadLabel: String,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val motionActive = rememberMotionActive()
    val phase = if (motionActive && active) {
        val transition = rememberInfiniteTransition(label = "speed_bars")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = (2f * PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(1600, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "bar_phase"
        ).value
    } else 0f

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SpeedMeterTile(
            label = downloadLabel,
            value = downloadSpeedText,
            accent = QsColors.AuroraMint,
            phase = phase,
            active = active,
            isDownload = true,
            modifier = Modifier.weight(1f)
        )
        SpeedMeterTile(
            label = uploadLabel,
            value = uploadSpeedText,
            accent = QsColors.ElectricCyan,
            phase = phase + 1.4f,
            active = active,
            isDownload = false,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SpeedMeterTile(
    label: String,
    value: String,
    accent: Color,
    phase: Float,
    active: Boolean,
    isDownload: Boolean,
    modifier: Modifier = Modifier,
) {
    Glass3dCard(
        modifier = modifier,
        cornerRadius = 18.dp,
        glowColor = accent.copy(alpha = if (active) 0.45f else 0.18f),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isDownload) "↓ $label" else "↑ $label",
                    color = QsColors.TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = value,
                    color = if (active) QsColors.TextPrimary else QsColors.TextMuted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(fontFeatureSettings = "tnum")
                )
            }
            Canvas(modifier = Modifier.size(width = 34.dp, height = 22.dp)) {
                val barCount = 5
                val barW = size.width / (barCount * 2f)
                for (i in 0 until barCount) {
                    val hFrac = if (active) {
                        0.30f + 0.65f * absSin(phase + i * 0.85f)
                    } else 0.22f
                    val bh = size.height * hFrac
                    val x = i * barW * 2f
                    drawRoundRect(
                        color = accent.copy(alpha = if (active) 0.85f else 0.25f),
                        topLeft = Offset(x, size.height - bh),
                        size = Size(barW, bh),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                    )
                }
            }
        }
    }
}

@Composable
fun CdnProviderBadge(cdnType: CdnType, modifier: Modifier = Modifier) {
    val (label, bg, fg) = when (cdnType) {
        CdnType.CLOUDFLARE -> Triple("Cloudflare", QsColors.CloudflareOrange.copy(alpha = 0.20f), QsColors.CloudflareOrange)
        CdnType.CLOUDFRONT -> Triple("CloudFront", QsColors.CloudFrontSky.copy(alpha = 0.20f), QsColors.CloudFrontSky)
        CdnType.UNKNOWN -> Triple("Auto CDN", Color.White.copy(alpha = 0.08f), QsColors.TextSecondary)
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .border(0.8.dp, fg.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x660B1228))
            .border(0.75.dp, accent.copy(alpha = 0.30f), RoundedCornerShape(14.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp)
    ) {
        Text(text = label, color = QsColors.TextSecondary, fontSize = 10.sp)
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = value,
            color = QsColors.TextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            style = TextStyle(fontFeatureSettings = "tnum")
        )
    }
}

@Composable
fun StatusDot(active: Boolean) {
    val color by animateColorAsState(
        targetValue = if (active) QsColors.ConnectedEmerald else QsColors.TextMuted,
        label = "status_dot"
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
    }
}
