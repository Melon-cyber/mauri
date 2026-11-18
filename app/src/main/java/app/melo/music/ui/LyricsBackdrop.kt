package app.melo.music.ui

import android.graphics.drawable.BitmapDrawable
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.palette.graphics.Palette
import app.melo.music.MeloApplication
import app.melo.music.data.Track
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin

/** Colours taken from the cover: a deep base that keeps white text readable and two glows for the moving light. */
@Immutable internal data class CoverPalette(val base: Color, val glowA: Color, val glowB: Color, val accent: Color)

internal val DefaultCoverPalette = CoverPalette(Color(0xFF2B2140), Color(0xFF5A3B82), Color(0xFF7C2E3E), Color(0xFF8E6CC4))

/** HSV adjustment: keeps the hue of the cover but caps brightness so lyrics stay readable on any artwork. */
internal fun tame(color: Color, maxValue: Float, minSaturation: Float = 0f): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    if (hsv[1] > .08f) hsv[1] = hsv[1].coerceAtLeast(minSaturation)
    hsv[2] = hsv[2].coerceAtMost(maxValue)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun coverPaletteOf(palette: Palette): CoverPalette {
    val fallback = 0xFF403257.toInt()
    val dominant = Color(palette.getDominantColor(fallback))
    val vibrant = palette.vibrantSwatch?.rgb?.let(::Color) ?: palette.lightVibrantSwatch?.rgb?.let(::Color) ?: dominant
    val muted = palette.darkVibrantSwatch?.rgb?.let(::Color) ?: palette.mutedSwatch?.rgb?.let(::Color) ?: dominant
    return CoverPalette(
        base = tame(dominant, .34f, .30f),
        glowA = tame(vibrant, .62f, .45f),
        glowB = tame(muted, .48f, .35f),
        accent = tame(vibrant, .95f, .35f),
    )
}

/** Palette of the current cover, smoothly cross-faded when the song changes. */
@Composable internal fun rememberCoverPalette(track: Track): CoverPalette {
    val context = LocalContext.current
    val target by produceState(DefaultCoverPalette, track.artwork) {
        if (track.artwork.isBlank()) { value = DefaultCoverPalette; return@produceState }
        runCatching {
            val app = context.applicationContext as MeloApplication
            val request = ImageRequest.Builder(context).data(app.offline.cover(track.artwork)).allowHardware(false).size(128).build()
            val drawable = context.imageLoader.execute(request).drawable as? BitmapDrawable
            if (drawable != null) value = withContext(Dispatchers.Default) { coverPaletteOf(Palette.from(drawable.bitmap).maximumColorCount(16).generate()) }
        }
    }
    val spec = tween<Color>(900)
    val base by animateColorAsState(target.base, spec, label = "coverBase")
    val a by animateColorAsState(target.glowA, spec, label = "coverGlowA")
    val b by animateColorAsState(target.glowB, spec, label = "coverGlowB")
    val accent by animateColorAsState(target.accent, spec, label = "coverAccent")
    return CoverPalette(base, a, b, accent)
}

/** Movement is skipped when the system asks for no animations (and in JVM UI tests, which never go idle otherwise). */
@Composable internal fun motionAllowed(userSetting: Boolean): Boolean {
    val context = LocalContext.current
    return remember(userSetting) {
        val scale = runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
        userSetting && scale > 0f && Build.FINGERPRINT != "robolectric"
    }
}

/**
 * Full-screen background in the colours of the cover: a heavily blurred copy of the artwork (Android 12+),
 * slowly drifting light from the cover's own colours, and a soft shade at the bottom for the controls.
 */
@Composable internal fun CoverBackdrop(track: Track, palette: CoverPalette, motion: Boolean, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as MeloApplication
    val phase = remember { mutableFloatStateOf(0f) }
    if (motion) LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        while (isActive) withFrameMillis { phase.floatValue = ((it - start) % 48_000L) / 48_000f }
    }
    // The phase is read only while drawing, so the moving light redraws without recomposing the screen.
    Box(modifier.fillMaxSize().background(palette.base)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && track.artwork.isNotBlank() && !track.artwork.startsWith("android.resource://")) {
            AsyncImage(app.offline.cover(track.artwork), null, Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.9f; scaleY = 1.9f; rotationZ = phase.floatValue * 90f; alpha = .55f
            }.blur(90.dp), contentScale = ContentScale.Crop)
        }
        Canvas(Modifier.fillMaxSize()) {
            val angle = phase.floatValue * 2f * Math.PI.toFloat()
            val w = size.width; val h = size.height; val r = maxOf(w, h)
            val first = Offset(w * (.25f + .22f * cos(angle)), h * (.22f + .12f * sin(angle * 2)))
            val second = Offset(w * (.78f + .18f * sin(angle)), h * (.62f + .16f * cos(angle * 1.5f)))
            drawRect(Brush.radialGradient(listOf(palette.glowA.copy(alpha = .75f), Color.Transparent), first, r * .62f))
            drawRect(Brush.radialGradient(listOf(palette.glowB.copy(alpha = .70f), Color.Transparent), second, r * .70f))
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = .10f), .55f to Color.Black.copy(alpha = .18f), 1f to Color.Black.copy(alpha = .55f)))
        }
    }
}

/** Playback position that advances every frame between the player's 0.5 s updates, so lines change exactly on time. */
@Composable internal fun rememberSmoothPosition(reported: Long, playing: Boolean, speed: Float, trackId: String): State<Long> {
    var anchor by remember(trackId) { mutableLongStateOf(reported) }
    var anchorAt by remember(trackId) { mutableLongStateOf(System.nanoTime()) }
    val shown = remember(trackId) { mutableLongStateOf(reported) }
    LaunchedEffect(reported, playing, trackId, speed) {
        val predicted = anchor + if (playing) ((System.nanoTime() - anchorAt) / 1_000_000L * speed).toLong() else 0L
        // Small differences are clock jitter: keep the running clock so the highlight never steps backwards.
        anchor = if (!playing || kotlin.math.abs(predicted - reported) > 350) reported else predicted
        anchorAt = System.nanoTime()
        shown.longValue = anchor
        if (playing) while (isActive) withFrameMillis {
            shown.longValue = anchor + ((System.nanoTime() - anchorAt) / 1_000_000L * speed).toLong()
        }
    }
    return shown
}

/** False in JVM UI tests, where waiting timers would never let the screen settle. */
internal val isRealDevice: Boolean get() = Build.FINGERPRINT != "robolectric"
