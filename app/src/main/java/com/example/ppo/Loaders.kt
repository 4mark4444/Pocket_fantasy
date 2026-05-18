package com.example.ppo

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

// Ported from https://github.com/SamHerbert/SVG-Loaders ("hearts" + "circles") by Sam Herbert.
// Recreated natively in Compose because SVG SMIL <animate> doesn't render on Android.

private const val HEART_LEFT =
    "M30.262 57.02L7.195 40.723c-5.84-3.976-7.56-12.06-3.842-18.063 3.715-6 11.467-7.65 17.306-3.68" +
    "l4.52 3.76 2.6-5.274c3.717-6.002 11.47-7.65 17.305-3.68 5.84 3.97 7.56 12.054 3.842 18.062" +
    "L34.49 56.118c-.897 1.512-2.793 1.915-4.228.9z"

private const val HEART_RIGHT =
    "M105.512 56.12l-14.44-24.272c-3.716-6.008-1.996-14.093 3.843-18.062 5.835-3.97 13.588-2.322 17.306 3.68" +
    "l2.6 5.274 4.52-3.76c5.84-3.97 13.592-2.32 17.307 3.68 3.718 6.003 1.998 14.088-3.842 18.064" +
    "L109.74 57.02c-1.434 1.014-3.33.61-4.228-.9z"

private const val HEART_CENTER =
    "M67.408 57.834l-23.01-24.98c-5.864-6.15-5.864-16.108 0-22.248 5.86-6.14 15.37-6.14 21.234 0" +
    "L70 16.168l4.368-5.562c5.863-6.14 15.375-6.14 21.235 0 5.863 6.14 5.863 16.098 0 22.247" +
    "l-23.007 24.98c-1.43 1.556-3.757 1.556-5.188 0z"

@Composable
private fun rememberPath(svgPathData: String): Path =
    remember(svgPathData) { PathParser().parsePathString(svgPathData).toPath() }

/**
 * Three-heart loader shown in the story card while the model is loading / prefilling
 * (before the first novel byte streams). viewBox 140×64.
 *
 * Animation: the two side hearts pulse alpha 0.5↔1.0 over 1.4 s, offset by half a
 * period so they alternate; the center heart is static at full opacity.
 */
@Composable
fun HeartsLoader(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.tertiary,
) {
    val left   = rememberPath(HEART_LEFT)
    val right  = rememberPath(HEART_RIGHT)
    val center = rememberPath(HEART_CENTER)

    val transition = rememberInfiniteTransition(label = "hearts")
    val spec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 700, easing = LinearEasing),
        repeatMode = RepeatMode.Reverse,
    )
    // Two animations 180° out of phase: when one is at 0.5, the other is at 1.0.
    val leftAlpha  by transition.animateFloat(0.5f, 1.0f, spec, label = "left")
    val rightAlpha by transition.animateFloat(1.0f, 0.5f, spec, label = "right")

    Canvas(modifier = modifier.size(width = 105.dp, height = 48.dp)) {
        val sx = size.width  / 140f
        val sy = size.height /  64f
        scale(sx, sy, pivot = Offset.Zero) {
            drawPath(left,   color = tint.copy(alpha = leftAlpha))
            drawPath(right,  color = tint.copy(alpha = rightAlpha))
            drawPath(center, color = tint)
        }
    }
}

/**
 * Three-circle pulse loader shown inside an option tile while that tile is waiting
 * for its </option_N> close tag. viewBox 45×45, all circles centered at (22, 22).
 *
 * Animation:
 *   - Two outer rings expand r 6→22, stroke 2→0, alpha 1→0 over 3 s, staggered 1.5 s apart.
 *   - The inner circle heartbeats r through (6, 1, 2, 3, 4, 5, 6) over 1.5 s.
 */
@Composable
fun OptionPulseLoader(
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.tertiary,
) {
    val transition = rememberInfiniteTransition(label = "pulse")

    // 0→1 over 3 s, restarting. The second ring lags by half a period.
    val ringSpec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 3000, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )
    val ringA by transition.animateFloat(0f, 1f, ringSpec, label = "ringA")
    val ringB by transition.animateFloat(0.5f, 1.5f, ringSpec, label = "ringB")

    // Heartbeat — 0→1 over 1.5 s, linear, restarting.
    val heartSpec = infiniteRepeatable<Float>(
        animation = tween(durationMillis = 1500, easing = LinearEasing),
        repeatMode = RepeatMode.Restart,
    )
    val heart by transition.animateFloat(0f, 1f, heartSpec, label = "heart")

    Canvas(modifier = modifier.size(32.dp)) {
        val s = size.minDimension / 45f      // viewBox 45 × 45
        val center = Offset(size.width / 2f, size.height / 2f)

        fun drawRing(phase: Float) {
            // SMIL `values` arrays use calcMode="linear" → simple lerp from start to end.
            val r  = (6f  + (22f - 6f) * phase) * s
            val sw = (2f  + (0f  - 2f) * phase) * s
            val a  =  1f  + (0f  - 1f) * phase
            if (sw > 0f && a > 0f) {
                drawCircle(
                    color  = tint.copy(alpha = a),
                    radius = r,
                    center = center,
                    style  = Stroke(width = sw),
                )
            }
        }
        drawRing(ringA)
        // ringB wraps past 1.0 — fold it back to [0, 1] so it visually offsets ringA by half.
        drawRing(if (ringB > 1f) ringB - 1f else ringB)

        // Heartbeat keyframes: 6 → 1 → 2 → 3 → 4 → 5 → 6 across 1.5 s (6 segments of 0.25 s).
        val keyframes = floatArrayOf(6f, 1f, 2f, 3f, 4f, 5f, 6f)
        val segments  = keyframes.size - 1                       // 6
        val pos       = (heart * segments).coerceIn(0f, segments.toFloat())
        val idx       = pos.toInt().coerceAtMost(segments - 1)
        val t         = pos - idx
        val rInner    = (keyframes[idx] + (keyframes[idx + 1] - keyframes[idx]) * t) * s
        drawCircle(
            color  = tint,
            radius = rInner,
            center = center,
            style  = Stroke(width = 2f * s),
        )
    }
}
