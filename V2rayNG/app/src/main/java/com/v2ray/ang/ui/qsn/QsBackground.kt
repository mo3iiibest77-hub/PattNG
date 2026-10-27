package com.v2ray.ang.ui.qsn

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.v2ray.ang.ui.compose.QsGreenFill
import kotlinx.coroutines.delay

/** How often the drift is advanced. Five frames a second is invisible for a 20 s motion. */
private const val DRIFT_STEP_MS = 200L

/** One full drift cycle in steps. */
private const val DRIFT_STEPS = 100

/** Resting phase used when the motion is disabled. */
private const val RESTING_PHASE = 0.35f

/**
 * The background of the Quiet Storm NG screens.
 *
 * A static vertical wash plus two very soft radial glows that drift slowly.
 * Deliberate constraints, because this sits behind every screen:
 *
 *  - it is drawn with two radial gradients and one linear gradient, no bitmaps,
 *    no blur filters, no shader allocation per frame;
 *  - the drift is advanced five times per second instead of on every frame, so
 *    a 20 second cycle costs ~1/12 of a 60 fps animation;
 *  - it stops completely when the activity is not resumed, and stays static
 *    when the system animator duration scale is 0 (accessibility setting);
 *  - state is read inside the draw lambda, so a tick invalidates the draw phase
 *    only and never triggers recomposition of the screen above it.
 */
@Composable
fun QsBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { animatorDisabled(context) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = !reduceMotion && lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    var phase by remember { mutableStateOf(RESTING_PHASE) }
    LaunchedEffect(animate) {
        if (!animate) {
            phase = RESTING_PHASE
            return@LaunchedEffect
        }
        var step = 0
        while (true) {
            delay(DRIFT_STEP_MS)
            step = (step + 1) % DRIFT_STEPS
            phase = step.toFloat() / DRIFT_STEPS.toFloat()
        }
    }

    val base = MaterialTheme.colorScheme.background
    val bottom = lerp(base, MaterialTheme.colorScheme.surfaceVariant, 0.45f)
    // secondary is the gold fill in the light scheme and the warm gold in the
    // dark one, so the same code produces the right tint in both.
    val warmGlow = MaterialTheme.colorScheme.secondary
    val coolGlow = QsGreenFill

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(base, bottom),
                    startY = 0f,
                    endY = height,
                ),
            )

            // Warm glow, upper area: it sits behind the status card.
            val warmCenter = Offset(
                x = width * (0.18f + 0.10f * phase),
                y = height * (0.10f + 0.06f * phase),
            )
            val warmRadius = width * 0.85f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(warmGlow.copy(alpha = 0.13f), Color.Transparent),
                    center = warmCenter,
                    radius = warmRadius,
                ),
                radius = warmRadius,
                center = warmCenter,
            )

            // Cool glow, lower area: keeps the bottom from looking flat.
            val coolCenter = Offset(
                x = width * (0.85f - 0.12f * phase),
                y = height * (0.86f - 0.05f * phase),
            )
            val coolRadius = width * 0.75f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(coolGlow.copy(alpha = 0.08f), Color.Transparent),
                    center = coolCenter,
                    radius = coolRadius,
                ),
                radius = coolRadius,
                center = coolCenter,
            )
        }
        content()
    }
}

/** True when the user (or the system) disabled animations. */
private fun animatorDisabled(context: android.content.Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (_: Exception) {
    false
}
