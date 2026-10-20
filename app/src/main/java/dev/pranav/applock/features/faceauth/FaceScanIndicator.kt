package dev.pranav.applock.features.faceauth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp

enum class FaceScanState { Scanning, Success, Failed }

/**
 * Face-scan animation drawn in the app theme: corner brackets with a small face glyph that
 * pulses while scanning, turns into a check on success and shakes in the error color on failure.
 */
@Composable
fun FaceScanIndicator(state: FaceScanState, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val error = MaterialTheme.colorScheme.error

    val infinite = rememberInfiniteTransition(label = "scan")
    val pulse by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1f, label = "pulse",
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val glyphAlpha by infinite.animateFloat(
        initialValue = 0.45f, targetValue = 1f, label = "glyph",
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
    )
    val success by animateFloatAsState(
        if (state == FaceScanState.Success) 1f else 0f, tween(450), label = "success",
    )
    val shake = remember { Animatable(0f) }
    LaunchedEffect(state) {
        if (state == FaceScanState.Failed) {
            for (target in floatArrayOf(-1f, 1f, -0.7f, 0.7f, 0f)) shake.animateTo(target, tween(70))
        }
    }

    Canvas(modifier.size(120.dp)) {
        val s = size.minDimension
        val color = if (state == FaceScanState.Failed) error else primary
        val stroke = Stroke(width = s * 0.04f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val scale = if (state == FaceScanState.Scanning) pulse else 1f - 0.08f * success

        withTransform({
            translate(left = shake.value * s * 0.06f)
            scale(scale, scale, pivot = center)
        }) {
            // Corner brackets: draw the top-left one, mirror for the others.
            val pad = s * 0.08f
            val r = s * 0.2f
            val arm = s * 0.14f
            val bracket = Path().apply {
                moveTo(pad, pad + r + arm)
                lineTo(pad, pad + r)
                arcTo(Rect(pad, pad, pad + 2 * r, pad + 2 * r), 180f, 90f, false)
                lineTo(pad + r + arm, pad)
            }
            for ((sx, sy) in listOf(1f to 1f, -1f to 1f, 1f to -1f, -1f to -1f)) {
                withTransform({ scale(sx, sy, pivot = center) }) {
                    drawPath(bracket, color, style = stroke)
                }
            }

            // Face glyph fades out as the check fades in.
            val faceAlpha = (if (state == FaceScanState.Scanning) glyphAlpha else 1f) * (1f - success)
            if (faceAlpha > 0f) {
                val c = color.copy(alpha = faceAlpha)
                drawLine(c, Offset(s * 0.37f, s * 0.38f), Offset(s * 0.37f, s * 0.46f), stroke.width, StrokeCap.Round)
                drawLine(c, Offset(s * 0.63f, s * 0.38f), Offset(s * 0.63f, s * 0.46f), stroke.width, StrokeCap.Round)
                val nose = Path().apply {
                    moveTo(s * 0.5f, s * 0.4f)
                    lineTo(s * 0.5f, s * 0.54f)
                    lineTo(s * 0.46f, s * 0.54f)
                }
                drawPath(nose, c, style = stroke)
                val mouth = Path().apply {
                    arcTo(Rect(s * 0.38f, s * 0.54f, s * 0.62f, s * 0.7f), 25f, 130f, true)
                }
                drawPath(mouth, c, style = stroke)
            }

            if (success > 0f) {
                val check = Path().apply {
                    moveTo(s * 0.3f, s * 0.52f)
                    lineTo(s * 0.44f, s * 0.66f)
                    lineTo(s * 0.72f, s * 0.36f)
                }
                val measure = PathMeasure().apply { setPath(check, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * success, part, true)
                drawPath(part, color, style = stroke)
            }
        }
    }
}
