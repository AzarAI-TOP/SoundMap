package top.azarai.soundmap.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.azarai.soundmap.audio.DialPosition
import top.azarai.soundmap.ui.theme.Accent
import top.azarai.soundmap.ui.theme.AccentSoft
import top.azarai.soundmap.ui.theme.DialTick
import top.azarai.soundmap.ui.theme.DialTrack
import top.azarai.soundmap.ui.theme.OnSurfaceMuted
import top.azarai.soundmap.ui.theme.Surface
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Ring kept well inside the box so the 前/后/左/右 labels sit clearly outside it.
private const val OUTER_FRACTION = 0.80f
private const val INNER_FRACTION = 0.34f

/**
 * The clock-like dial. The center is the power toggle; the surrounding ring picks a
 * direction (angle, up = front) and distance (radius from center).
 */
@Composable
fun ClockDial(
    enabled: Boolean,
    position: DialPosition,
    onToggle: () -> Unit,
    onPositionChange: (DialPosition) -> Unit,
    modifier: Modifier = Modifier,
) {
    val markerColor by animateColorAsState(if (enabled) Accent else OnSurfaceMuted, label = "marker")
    val rayAlpha by animateFloatAsState(if (enabled) 1f else 0.25f, label = "ray")

    Box(modifier = modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        toDialPosition(offset, size.width.toFloat())?.let(onPositionChange)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            toDialPosition(offset, size.width.toFloat())?.let(onPositionChange)
                        },
                        onDrag = { change, _ ->
                            toDialPosition(change.position, size.width.toFloat())?.let(onPositionChange)
                        },
                    )
                },
        ) {
            drawDial(position, markerColor, rayAlpha)
        }

        // Direction labels around the dial.
        DialLabel("前", Alignment.TopCenter)
        DialLabel("后", Alignment.BottomCenter)
        DialLabel("左", Alignment.CenterStart)
        DialLabel("右", Alignment.CenterEnd)

        PowerToggle(enabled = enabled, onToggle = onToggle)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.DialLabel(text: String, alignment: Alignment) {
    Text(
        text = text,
        color = OnSurfaceMuted,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.align(alignment).size(width = 24.dp, height = 20.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PowerToggle(enabled: Boolean, onToggle: () -> Unit) {
    val bg by animateColorAsState(if (enabled) Accent else Surface, label = "toggleBg")
    val ring by animateColorAsState(if (enabled) Accent else DialTick, label = "toggleRing")
    Box(
        modifier = Modifier
            .fillMaxWidth(INNER_FRACTION * OUTER_FRACTION)
            .aspectRatio(1f)
            .align(Alignment.Center)
            .clip(CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(Unit) { detectTapGestures { onToggle() } },
        ) {
            val r = size.minDimension / 2f
            drawCircle(color = bg, radius = r)
            drawCircle(color = ring, radius = r, style = Stroke(width = r * 0.08f))
        }
        Text(
            text = if (enabled) "ON" else "OFF",
            color = if (enabled) Surface else OnSurfaceMuted,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun DrawScope.drawDial(position: DialPosition, markerColor: Color, rayAlpha: Float) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val outerR = size.minDimension / 2f * OUTER_FRACTION
    val innerR = outerR * INNER_FRACTION

    // Track ring.
    drawCircle(color = DialTrack, radius = outerR, style = Stroke(width = outerR * 0.015f))

    // 12 clock ticks.
    for (i in 0 until 12) {
        val a = Math.toRadians(i * 30.0)
        val sinA = sin(a).toFloat()
        val cosA = cos(a).toFloat()
        val isCardinal = i % 3 == 0
        val tickOuter = outerR
        val tickInner = outerR - outerR * (if (isCardinal) 0.10f else 0.06f)
        drawLine(
            color = DialTick,
            start = Offset(cx + sinA * tickInner, cy - cosA * tickInner),
            end = Offset(cx + sinA * tickOuter, cy - cosA * tickOuter),
            strokeWidth = if (isCardinal) outerR * 0.012f else outerR * 0.007f,
        )
    }

    // Marker position along the chosen direction/distance.
    val markerDist = innerR + position.radius.coerceIn(0f, 1f) * (outerR - innerR)
    val rad = Math.toRadians(position.angleDeg.toDouble())
    val mx = cx + (sin(rad) * markerDist).toFloat()
    val my = cy - (cos(rad) * markerDist).toFloat()

    // Ray from center to marker.
    drawLine(
        color = AccentSoft.copy(alpha = rayAlpha),
        start = Offset(cx, cy),
        end = Offset(mx, my),
        strokeWidth = outerR * 0.02f,
    )

    // Soft halo + solid marker dot.
    drawCircle(color = markerColor.copy(alpha = 0.18f * rayAlpha + 0.05f), radius = outerR * 0.10f, center = Offset(mx, my))
    drawCircle(color = markerColor, radius = outerR * 0.05f, center = Offset(mx, my))
}

/**
 * Maps a touch point to a [DialPosition]. Returns null for touches inside the center
 * (the power toggle's territory).
 */
private fun toDialPosition(point: Offset, widthPx: Float): DialPosition? {
    val center = widthPx / 2f
    val outerR = center * OUTER_FRACTION
    val innerR = outerR * INNER_FRACTION
    val dx = point.x - center
    val dy = point.y - center
    val dist = hypot(dx, dy)
    if (dist < innerR * 0.85f) return null

    var angle = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
    if (angle < 0f) angle += 360f
    val radius = ((dist - innerR) / (outerR - innerR)).coerceIn(0f, 1f)
    return DialPosition(angleDeg = angle, radius = radius)
}
