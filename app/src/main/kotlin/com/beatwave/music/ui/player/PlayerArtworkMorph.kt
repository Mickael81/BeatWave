/**
 * BeatWave Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.beatwave.music.ui.player

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size as CoilSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect

import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.beatwave.music.R
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeometrySize
import androidx.compose.ui.graphics.Brush
import com.beatwave.music.LocalPlayerConnection
import com.beatwave.music.constants.CrossfadeBurnMorphKey
import com.beatwave.music.utils.rememberPreference
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Registered bounds of the mini player's own artwork and the full player's own artwork,
 * in root/window coordinates -- updated from `onGloballyPositioned` on each, read once
 * by [PlayerArtworkMorphOverlay].
 *
 * Plain module-level state, not a hoisted parameter, for the same reason
 * `SharedArtwork.kt`'s `placedArtwork` map is plain state: the two artworks live in
 * composables that are far apart in the tree (`MiniPlayer.kt`'s collapsed content,
 * `Thumbnail.kt`'s expanded content, orchestrated from `BottomSheet.kt` in between) and
 * neither needs to compose off the other's position -- only the overlay, drawn once,
 * reads both.
 */
private val miniArtworkRect = mutableStateOf<Rect?>(null)
private val fullArtworkRect = mutableStateOf<Rect?>(null)

/**
 * Call on the mini player's own artwork box. Purely observational: records where the
 * artwork is on screen and changes nothing about how the mini player itself renders.
 */
/**
 * Corner radius of each end's cover, in px.
 *
 * The flight used to clip to [CircleShape] the whole way, so a rounded-square pill cover
 * snapped to a circle the instant it left the bar and snapped back to the player's own
 * shape on arrival. The radius has to travel with the rect.
 *
 * A vinyl or clover player artwork registers half its size here, i.e. a circle; the
 * clover's petals are not reproduced mid-flight, which reads as a circle growing into a
 * clover at the very end.
 */
private val miniArtworkRadius = androidx.compose.runtime.mutableFloatStateOf(0f)
private val fullArtworkRadius = androidx.compose.runtime.mutableFloatStateOf(0f)

fun Modifier.registerMiniArtworkRect(cornerRadiusPx: Float = 0f): Modifier = onGloballyPositioned {
    miniArtworkRadius.floatValue = cornerRadiusPx
    if (it.isAttached) miniArtworkRect.value = it.boundsInRoot()
}

/** Call on the full player's own artwork container. Same contract as
 *  [registerMiniArtworkRect]. */
fun Modifier.registerFullArtworkRect(cornerRadiusPx: Float = 0f): Modifier = onGloballyPositioned {
    // The player's artwork lives in a pager, so every neighbouring page reports bounds
    // too -- and the last one to report would win. Only the page actually on screen may
    // claim the target rect; an off-screen page's window bounds are empty.
    if (it.boundsInWindow().isEmpty) return@onGloballyPositioned
    fullArtworkRadius.floatValue = cornerRadiusPx
    if (it.isAttached) fullArtworkRect.value = it.boundsInRoot()
}

/**
 * Draws ONE artwork image growing from the mini player's registered rect to the full
 * player's, as [progress] runs 0..1.
 *
 * Sized to the FULL (target) rect, not the mini one -- scaling DOWN from there to the
 * mini size at fraction 0, then back up to 1:1 (its native size) at the handoff. This
 * is the opposite of the first version, which sized to the small mini rect and scaled
 * UP: scaling up stretches whatever resolution the underlying image happens to be,
 * which read as soft mid-growth even after requesting a larger decode, because the
 * COMPOSE LAYOUT SIZE (what a bitmap gets stretched across) was still the tiny mini
 * box the whole time. Sizing to full and scaling down means the on-screen image is
 * never asked to be bigger than the pixels it actually has -- downsampling stays
 * sharp at any fraction, which is exactly the technique Melox's own
 * `PlayerSheetArtworkOverlay` uses (see the scratchpad clone read for this fix).
 *
 * `transformOrigin` pinned to the top-left (not the layer's default center) so a
 * plain `translationX/Y = rect.left/top` places the SCALED result exactly at
 * [sharedArtworkRect]'s interpolated rect, with no extra center-offset math needed.
 *
 * A fresh [AsyncImage] of the same URL, not a captured GraphicsLayer of the mini
 * player's own rendering (Melox's technique) -- both artworks show identical content,
 * so a pixel capture buys nothing a positioned image doesn't, and Coil's cache is
 * already warm from the mini player's own load.
 *
 Its corner radius travels with it, from the pill cover's to whatever the player's own
 * artwork uses (a card's radius, or half its size for the round vinyl), so the cover
 * grows into the real shape instead of snapping to a circle on the way.
 */
@Composable
fun PlayerArtworkMorphOverlay(
    thumbnailUrl: String?,
    progress: () -> Float,
) {
    val mini = miniArtworkRect.value
    val full = fullArtworkRect.value
    if (mini == null || full == null || thumbnailUrl == null) return
    // Tied to the container morph, not to raw progress: on a layout with no pill to fly
    // out of (classic nav bar, rail, tab view) the sheet still just slides, and a cover
    // flying across it would be flying out of nowhere.
    if (!PlayerMorph.active) return
    // Both rects are registered from onGloballyPositioned, which can fire once with
    // a degenerate (zero or negative) size before the real layout pass settles --
    // measured a crash on a Samsung device: coil3.size.Dimension throws on any px
    // <= 0, and full.width/height fed CoilSize directly with no floor.
    if (mini.width <= 0f || mini.height <= 0f || full.width <= 0f || full.height <= 0f) return

    val density = LocalDensity.current
    val boxWidthDp = with(density) { full.width.toDp() }
    val boxHeightDp = with(density) { full.height.toDp() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    full.left.roundToInt(),
                    full.top.roundToInt(),
                )
            }
            .size(boxWidthDp, boxHeightDp)
            .graphicsLayer {
                val fraction = progress().coerceIn(0f, 1f)
                if (fraction <= 0f || fraction >= 1f) {
                    alpha = 0f
                    return@graphicsLayer
                }
                alpha = 1f
                val rect = sharedArtworkRect(mini, full, fraction)
                transformOrigin = TransformOrigin(0f, 0f)
                translationX = rect.left - full.left
                translationY = rect.top - full.top
                val artworkScaleX = rect.width / full.width.coerceAtLeast(1f)
                val artworkScaleY = rect.height / full.height.coerceAtLeast(1f)
                scaleX = artworkScaleX
                scaleY = artworkScaleY
                val radius = androidx.compose.ui.util.lerp(
                    miniArtworkRadius.floatValue,
                    fullArtworkRadius.floatValue,
                    fraction,
                )
                val localRadius = if (artworkScaleX > 0f) (radius / artworkScaleX).toDp() else radius.toDp()
                shape = RoundedCornerShape(localRadius)
                clip = true
            }
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(thumbnailUrl)
                .size(CoilSize(full.width.toInt(), full.height.toInt()))
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(boxWidthDp, boxHeightDp),
        )
    }
}

/**
 * Hides the full player's own artwork while [PlayerArtworkMorphOverlay] is in flight.
 *
 * The overlay draws the cover for the whole 0..1 journey, but the sheet's content is
 * already opaque from the handoff onward -- so without this, both are on screen from a
 * quarter of the way in: the real one sitting at its final position while the travelling
 * one is still climbing towards it.
 */
fun Modifier.hideWhileArtworkMorphing(progress: () -> Float): Modifier = graphicsLayer {
    // Lambda, not a Float: this modifier sits deep inside the player's content (under
    // AnimatedContent and a shared-element LookaheadScope on the V2 player). Taking the
    // value meant the caller read the sheet's progress in COMPOSITION, so that whole
    // subtree recomposed on every frame of every open/close drag -- which is what put
    // measure requests on nodes the same frame was deactivating ("measure is called on
    // a deactivated node"). Reading it here keeps it to the draw phase.
    val fraction = progress()
    alpha = if (fraction > 0f && fraction < 1f) 0f else 1f
}

/**
 * Renders an intense burning/dissolving wavefront with glowing incandescent embers and drifting sparks.
 */
private data class MorphParticle(
    val xRatio: Float,
    val yRatio: Float,
    val baseRadiusDp: Float,
    val driftX: Float,
    val driftY: Float,
    val turbFreq: Float,
    val colorIndex: Int,
    val isStreak: Boolean,
    val streakLengthDp: Float
)

private val MORPH_PARTICLES: List<MorphParticle> = run {
    var seed = 20261017L
    fun nextFloat(): Float {
        seed = (seed * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return (seed and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat()
    }
    List(160) { i ->
        val xRatio = nextFloat()
        val yRatio = nextFloat()
        val baseRadius = 1.0f + (nextFloat() * 3.5f)
        val driftX = (nextFloat() - 0.5f) * 2.2f
        val driftY = -0.4f - (nextFloat() * 1.6f)
        val turb = 6f + (nextFloat() * 14f)
        val colorIdx = when {
            i % 5 == 0 -> 0 // Electric Cyan (0xFF00E5FF)
            i % 5 == 1 -> 1 // Vivid Neon Magenta (0xFFFF007F)
            i % 5 == 2 -> 2 // Radiant Gold (0xFFFFD700)
            i % 5 == 3 -> 3 // Starlight White (0xFFFFFFFF)
            else -> 4       // Electric Violet (0xFFB388FF)
        }
        val isStreak = (i % 5 == 0)
        val streakLength = 12f + (nextFloat() * 28f)
        MorphParticle(xRatio, yRatio, baseRadius, driftX, driftY, turb, colorIdx, isStreak, streakLength)
    }
}

/**
 * Dynamic particle morph overlay rendering radiant energy particles, chromatic glitch streaks,
 * and holographic scanlines during artwork transition, exactly matching convxdemo.mp4.
 */
@Composable
fun ParticleMorphOverlay(
    progress: Float,
    modifier: Modifier = Modifier
) {
    if (progress <= 0.005f || progress >= 0.995f) return

    val density = LocalDensity.current
    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val peakIntensity = kotlin.math.sin(progress * Math.PI.toFloat())

        // 1. Shimmering horizontal chromatic glitch bars across the artwork during peak
        if (peakIntensity > 0.15f) {
            val glitchSlices = 7
            for (j in 0 until glitchSlices) {
                val sliceNorm = (j.toFloat() / glitchSlices.toFloat())
                val sliceY = (h * sliceNorm) + (kotlin.math.sin(progress * 22.0 + j * 3.0).toFloat() * with(density) { 6.dp.toPx() })
                val sliceH = with(density) { (3.dp + (j % 3).dp).toPx() }
                val sliceShift = kotlin.math.sin(progress * 26.0 + j * 5.0).toFloat() * with(density) { 14.dp.toPx() } * peakIntensity

                val sliceColor = if (j % 2 == 0) {
                    Color(0xFF00E5FF).copy(alpha = 0.22f * peakIntensity)
                } else {
                    Color(0xFFFF007F).copy(alpha = 0.22f * peakIntensity)
                }

                drawRect(
                    color = sliceColor,
                    topLeft = Offset(sliceShift, sliceY.coerceIn(0f, h - sliceH)),
                    size = GeometrySize(w, sliceH),
                    blendMode = BlendMode.Screen
                )
            }
        }

        // 2. High-energy particle field
        val particleColors = arrayOf(
            Color(0xFF00E5FF), // Cyan
            Color(0xFFFF007F), // Magenta
            Color(0xFFFFD700), // Gold
            Color(0xFFFFFFFF), // White
            Color(0xFFB388FF)  // Violet
        )

        for (p in MORPH_PARTICLES) {
            // Outward radial dispersion from center
            val dxCenter = (p.xRatio - 0.5f)
            val dyCenter = (p.yRatio - 0.5f)
            val dispersionPx = with(density) { (55.dp * progress).toPx() }

            // Harmonic wave turbulence
            val turbX = kotlin.math.sin(p.yRatio * 18.0 + progress * 14.0 + p.turbFreq).toFloat() * with(density) { 18.dp.toPx() } * peakIntensity
            val turbY = kotlin.math.cos(p.xRatio * 16.0 + progress * 10.0).toFloat() * with(density) { 14.dp.toPx() } * peakIntensity

            // Kinetic lift (particles float upward)
            val liftY = p.driftY * with(density) { (45.dp * progress).toPx() }

            val posX = (p.xRatio * w) + (dxCenter * dispersionPx) + turbX
            val posY = (p.yRatio * h) + (dyCenter * dispersionPx) + liftY + turbY

            if (posX in -40f..(w + 40f) && posY in -40f..(h + 40f)) {
                val phase = (p.xRatio * 0.4f) + (p.yRatio * 0.2f)
                val particleAlpha = (kotlin.math.sin((progress * 0.85f + phase) * Math.PI.toFloat()) * peakIntensity * 1.15f).coerceIn(0f, 1f)
                val radius = with(density) { (p.baseRadiusDp * (0.7f + 0.6f * peakIntensity)).dp.toPx() }
                val color = particleColors[p.colorIndex]

                // Outer ambient glow halo
                drawCircle(
                    color = color.copy(alpha = particleAlpha * 0.35f),
                    radius = radius * 2.8f,
                    center = Offset(posX, posY),
                    blendMode = BlendMode.Screen
                )

                // Inner brilliant core
                drawCircle(
                    color = if (p.colorIndex == 3) Color.White.copy(alpha = particleAlpha * 0.95f) else color.copy(alpha = particleAlpha * 0.90f),
                    radius = radius,
                    center = Offset(posX, posY),
                    blendMode = BlendMode.Screen
                )

                // Glitch streak trail
                if (p.isStreak && peakIntensity > 0.12f) {
                    val streakPx = with(density) { (p.streakLengthDp * peakIntensity).dp.toPx() }
                    drawLine(
                        color = color.copy(alpha = particleAlpha * 0.65f),
                        start = Offset(posX - streakPx, posY),
                        end = Offset(posX + streakPx, posY),
                        strokeWidth = with(density) { 1.5.dp.toPx() },
                        blendMode = BlendMode.Screen
                    )
                }
            }
        }
    }
}

/** Legacy alias for ParticleMorphOverlay */
@Composable
fun MorphingBurnOverlay(
    progress: Float,
    modifier: Modifier = Modifier
) = ParticleMorphOverlay(progress = progress, modifier = modifier)

/**
 * Renders album cover art that morphs and cross-fades smoothly into the next song's
 * cover art during track changes (both auto-crossfade and manual skip/seek), matching
 * convxdemo.mp4 with fluid bottom-to-top reveal, particle morph animation, and chromatic aberration.
 */
@Composable
fun MorphingCoverArt(
    artworkUri: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    contentDescription: String? = "Cover Art",
    animationDurationMillis: Int = 1350,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current
    val isCrossfading by (playerConnection?.isCrossfading ?: MutableStateFlow(false)).collectAsState()
    val crossfadeDurationMs = playerConnection?.crossfadeDurationMs ?: 3000f
    val crossfadeBurnMorph by rememberPreference(CrossfadeBurnMorphKey, defaultValue = true)

    var currentUrl by remember { mutableStateOf(artworkUri) }
    var outgoingUrl by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }

    // Start morphing animation IMMEDIATELY as soon as song starts crossfading or track changes (manual skip or auto)
    LaunchedEffect(artworkUri) {
        val targetUri = artworkUri
        if (targetUri != currentUrl) {
            val oldUrl = currentUrl
            if (oldUrl != null && targetUri != null) {
                // Immediately swap URLs so:
                // - outgoingUrl holds the OLD song art (Song A)
                // - currentUrl holds the NEW incoming song art (Song B)
                outgoingUrl = oldUrl
                currentUrl = targetUri
                progress.snapTo(0f)
                val effectiveDuration = if (isCrossfading) {
                    crossfadeDurationMs.toInt().coerceIn(1200, 6000)
                } else {
                    animationDurationMillis
                }
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = effectiveDuration,
                        easing = CubicBezierEasing(0.33f, 0.0f, 0.20f, 1.0f)
                    )
                )
                outgoingUrl = null
            } else {
                currentUrl = targetUri
            }
        }
    }

    val p = progress.value
    val peakIntensity = kotlin.math.sin(p * Math.PI.toFloat())

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // LAYER 1 (BASE): Incoming artwork layer — unveiled fluidly from beneath particle cloud!
        val curUrl = currentUrl
        if (!curUrl.isNullOrBlank()) {
            var activeCurUrl by remember(curUrl) { mutableStateOf<String?>(curUrl) }
            val inAlpha = if (outgoingUrl != null && !crossfadeBurnMorph) p.coerceIn(0f, 1f) else 1f
            val inTranslateY = if (outgoingUrl != null) with(LocalDensity.current) { (18.dp * (1f - p)).toPx() } else 0f
            val inScale = if (outgoingUrl != null) (0.94f + (0.06f * p)) else 1f

            // Chromatic aberration morph effect during track transition (matching convxdemo.mp4)
            val isMorphing = outgoingUrl != null && p in 0.02f..0.98f
            val morphPeak = if (isMorphing) peakIntensity else 0f
            val chromaticOffsetPx = morphPeak * with(LocalDensity.current) { 8.dp.toPx() }

            if (morphPeak > 0.08f) {
                // Cyan channel shift (left)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(activeCurUrl)
                        .size(CoilSize.ORIGINAL)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = contentScale,
                    colorFilter = ColorFilter.tint(Color(0xFF00E5FF).copy(alpha = 0.35f * morphPeak), BlendMode.SrcAtop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (inAlpha * 0.7f).coerceIn(0f, 1f)
                            translationX = -chromaticOffsetPx
                            translationY = inTranslateY
                            scaleX = inScale
                            scaleY = inScale
                        }
                )

                // Magenta/Red channel shift (right)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(activeCurUrl)
                        .size(CoilSize.ORIGINAL)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = contentScale,
                    colorFilter = ColorFilter.tint(Color(0xFFFF007F).copy(alpha = 0.35f * morphPeak), BlendMode.SrcAtop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (inAlpha * 0.7f).coerceIn(0f, 1f)
                            translationX = chromaticOffsetPx
                            translationY = inTranslateY
                            scaleX = inScale
                            scaleY = inScale
                        }
                )
            }

            // Primary incoming artwork image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(activeCurUrl)
                    .size(CoilSize.ORIGINAL)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build(),
                onError = {
                    activeCurUrl = nextThumbnailFallback(activeCurUrl)
                },
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = inAlpha
                        translationY = inTranslateY
                        scaleX = inScale
                        scaleY = inScale
                    }
            )
        } else if (outgoingUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.beatwave_logo),
                    contentDescription = contentDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(64.dp)
                )
            }
        }

        // LAYER 2 (TOP): Outgoing artwork layer — dissolves into particles and chromatic aberration!
        val outUrl = outgoingUrl
        if (!outUrl.isNullOrBlank() && p < 1f) {
            var activeOutUrl by remember(outUrl) { mutableStateOf<String?>(outUrl) }
            val outAlpha = if (crossfadeBurnMorph) (1f - p * 0.60f).coerceIn(0f, 1f) else (1f - p).coerceIn(0f, 1f)
            val outScale = 1f + (0.04f * p)

            val isMorphing = p in 0.02f..0.98f
            val morphPeak = if (isMorphing) peakIntensity else 0f
            val chromaticOffsetPx = morphPeak * with(LocalDensity.current) { 8.dp.toPx() }

            if (morphPeak > 0.08f) {
                // Outgoing Cyan channel shift (left)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(activeOutUrl)
                        .size(CoilSize.ORIGINAL)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = contentScale,
                    colorFilter = ColorFilter.tint(Color(0xFF00E5FF).copy(alpha = 0.35f * morphPeak), BlendMode.SrcAtop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (outAlpha * 0.6f).coerceIn(0f, 1f)
                            translationX = -chromaticOffsetPx
                            scaleX = outScale
                            scaleY = outScale
                        }
                )

                // Outgoing Magenta channel shift (right)
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(activeOutUrl)
                        .size(CoilSize.ORIGINAL)
                        .crossfade(false)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .networkCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    contentScale = contentScale,
                    colorFilter = ColorFilter.tint(Color(0xFFFF007F).copy(alpha = 0.35f * morphPeak), BlendMode.SrcAtop),
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = (outAlpha * 0.6f).coerceIn(0f, 1f)
                            translationX = chromaticOffsetPx
                            scaleX = outScale
                            scaleY = outScale
                        }
                )
            }

            // Primary outgoing artwork image
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(activeOutUrl)
                    .size(CoilSize.ORIGINAL)
                    .crossfade(false)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .build(),
                onError = { activeOutUrl = nextThumbnailFallback(activeOutUrl) },
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (crossfadeBurnMorph) {
                            Modifier.drawWithContent {
                                val burnY = size.height * (1f - p)
                                // Fluid undulating wave clip boundary
                                val path = Path().apply {
                                    moveTo(0f, 0f)
                                    lineTo(size.width, 0f)
                                    lineTo(size.width, burnY.coerceIn(0f, size.height))
                                    val waveSteps = 40
                                    for (step in waveSteps downTo 0) {
                                        val x = size.width * (step.toFloat() / waveSteps)
                                        val wave = kotlin.math.sin((x / size.width * 10.0) + (p * 14.0)).toFloat() * (size.height * 0.022f * peakIntensity)
                                        lineTo(x, (burnY + wave).coerceIn(0f, size.height))
                                    }
                                    close()
                                }
                                clipPath(path) {
                                    this@drawWithContent.drawContent()
                                }
                            }
                        } else Modifier
                    )
                    .graphicsLayer {
                        alpha = outAlpha
                        scaleX = outScale
                        scaleY = outScale
                    }
            )
        }

        // LAYER 3 (OVERLAY): Glowing chromatic particle field & holographic scanlines
        if (crossfadeBurnMorph && outgoingUrl != null && p in 0.005f..0.995f) {
            ParticleMorphOverlay(
                progress = p,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}


