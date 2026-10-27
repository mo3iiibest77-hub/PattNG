package com.v2ray.ang.ui.qsn

import android.content.Context
import android.graphics.ImageDecoder
import android.graphics.Movie
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlin.math.max

/** The original GIF shipped in assets, byte for byte. */
private const val GIF_ASSET = "qs_background.gif"

/** The GIF was authored at 10 fps; ticking faster would only burn battery. */
private const val FRAME_MS = 100L

/** Quiet Storm NG gold. */
private val QsGold = Color(0xFF8A5A00)

/** Cloudflare brand orange, used only for the small badge. */
private val QsCloudflareOrange = Color(0xFFF6821F)

/**
 * The background of every Quiet Storm NG screen.
 *
 * It renders the original `assets/qs_background.gif` — the same file, not a
 * re-creation — through the platform animated-GIF decoder, under a soft scrim
 * that keeps the text readable, and above the content it draws the wordmark
 * and a small Cloudflare badge.
 *
 * Constraints that matter, because this sits behind every screen:
 *
 *  - the GIF is decoded once and drawn by the platform decoder; Compose only
 *    asks for a new frame ten times a second, which is the GIF's own rate;
 *  - on API 28+ `AnimatedImageDrawable` animates on the platform side, on
 *    24–27 the same file is played through `Movie` at the same rate;
 *  - the frame loop stops completely when the screen is not resumed and when
 *    the system asks for reduced motion, and the last frame stays visible;
 *  - the layer is a Compose canvas, so it never intercepts a touch, a scroll
 *    or an accessibility traversal.
 */
@Composable
fun QsBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { animatorDisabled(context) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = shouldAnimateBackground(
        resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED),
        reduceMotion = reduceMotion,
    )

    Box(modifier = modifier.fillMaxSize()) {
        QsGifLayer(animate = animate, modifier = Modifier.fillMaxSize())

        // Scrim: the GIF is mid-tone, so a light veil is what keeps the text
        // contrast high without hiding the artwork completely.
        val surface = MaterialTheme.colorScheme.surface
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            drawRect(color = surface.copy(alpha = 0.58f))
        }

        Column(modifier = Modifier.fillMaxSize()) {
            QsBrandBand()
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                content()
            }
        }
    }
}

/**
 * The wordmark: Quiet Storm NG in gold, next to a small Cloudflare mark.
 *
 * It lives in the background layer so every screen carries the same identity
 * without each of them re-implementing a header.
 */
@Composable
private fun QsBrandBand() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QsCloudflareMark(size = 17.dp, modifier = Modifier.padding(end = 7.dp))
        Text(
            text = "Quiet Storm NG",
            color = QsGold,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            letterSpacing = 0.7.sp,
        )
    }
}

/** A small, self-contained Cloudflare cloud; no extra asset or dependency. */
@Composable
fun QsCloudflareMark(size: Dp = 16.dp, modifier: Modifier = Modifier) {
    ComposeCanvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val r = h * 0.26f
        drawCircle(QsCloudflareOrange, r, Offset(w * 0.30f, h * 0.60f))
        drawCircle(QsCloudflareOrange, r * 1.22f, Offset(w * 0.50f, h * 0.44f))
        drawCircle(QsCloudflareOrange, r * 0.92f, Offset(w * 0.71f, h * 0.60f))
        drawRoundRect(
            color = QsCloudflareOrange,
            topLeft = Offset(w * 0.15f, h * 0.56f),
            size = Size(w * 0.70f, h * 0.24f),
            cornerRadius = CornerRadius(h * 0.12f),
        )
    }
}

/**
 * Draws the GIF and advances it at its own frame rate.
 *
 * The frame counter is read inside the draw lambda, so a tick invalidates the
 * draw phase only: no recomposition of the screen above, no allocation per
 * frame beyond what the platform decoder does anyway.
 */
@Composable
private fun QsGifLayer(animate: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val animated = remember(context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) loadAnimated(context) else null
    }
    val movie = remember(context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) loadMovie(context) else null
    }
    var tick by remember { mutableIntStateOf(0) }
    val startedAt = remember { SystemClock.uptimeMillis() }

    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        while (true) {
            delay(FRAME_MS)
            tick++
        }
    }

    DisposableEffect(animated, animate) {
        val drawable = animated as? AnimatedImageDrawable
        if (drawable != null) {
            if (animate) drawable.start() else drawable.stop()
        }
        onDispose { drawable?.stop() }
    }

    ComposeCanvas(modifier = modifier) {
        // Reading the tick subscribes this draw phase to the frame counter.
        val frame = tick
        if (animated != null) {
            drawCropped(animated)
        } else if (movie != null) {
            val elapsed = if (animate) {
                ((SystemClock.uptimeMillis() - startedAt) % movie.duration().coerceAtLeast(1)).toInt()
            } else {
                frame * FRAME_MS.toInt()
            }
            drawMovieCropped(movie, elapsed)
        }
    }
}

/** CENTER_CROP rendering of a drawable, so no part of the artwork is squashed. */
private fun DrawScope.drawCropped(drawable: Drawable) {
    val iw = drawable.intrinsicWidth
    val ih = drawable.intrinsicHeight
    if (iw <= 0 || ih <= 0) return
    val scale = max(size.width / iw, size.height / ih)
    val dx = (size.width - iw * scale) / 2f
    val dy = (size.height - ih * scale) / 2f
    drawable.setBounds(0, 0, iw, ih)
    withTransform({
        translate(dx, dy)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        drawIntoCanvas { drawable.draw(it.nativeCanvas) }
    }
}

/** Same crop for the legacy [Movie] path. */
private fun DrawScope.drawMovieCropped(movie: Movie, elapsedMs: Int) {
    val iw = movie.width()
    val ih = movie.height()
    if (iw <= 0 || ih <= 0) return
    val scale = max(size.width / iw, size.height / ih)
    val dx = (size.width - iw * scale) / 2f
    val dy = (size.height - ih * scale) / 2f
    movie.setTime(elapsedMs)
    drawIntoCanvas { canvas ->
        val native = canvas.nativeCanvas
        native.save()
        native.translate(dx, dy)
        native.scale(scale, scale)
        movie.draw(native, 0f, 0f)
        native.restore()
    }
}

@RequiresApi(Build.VERSION_CODES.P)
private fun loadAnimated(context: Context): Drawable? = try {
    val source = ImageDecoder.createSource(context.assets, GIF_ASSET)
    ImageDecoder.decodeDrawable(source).also { drawable ->
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
        }
    }
} catch (e: Exception) {
    // A missing or unreadable asset must not take the whole screen down; the
    // scrim alone still gives a readable surface.
    null
}

@Suppress("DEPRECATION")
private fun loadMovie(context: Context): Movie? = try {
    context.assets.open(GIF_ASSET).use { stream -> Movie.decodeStream(stream) }
} catch (e: Exception) {
    null
}

/**
 * The single rule that decides whether the background may move.
 *
 * It stops when the screen is not resumed (backgrounded, covered by a dialog
 * that pauses the activity) and when the system asks for reduced motion.
 */
internal fun shouldAnimateBackground(resumed: Boolean, reduceMotion: Boolean): Boolean =
    resumed && !reduceMotion

/** Slightly translucent card surface, so the artwork stays visible behind lists. */
@Composable
fun qsCardSurface(alpha: Float = 0.90f): Color =
    MaterialTheme.colorScheme.surface.copy(alpha = alpha)

/** True when the user (or the system) disabled animations. */
private fun animatorDisabled(context: Context): Boolean = try {
    Settings.Global.getFloat(
        context.contentResolver,
        Settings.Global.ANIMATOR_DURATION_SCALE,
        1f,
    ) == 0f
} catch (_: Exception) {
    false
}
