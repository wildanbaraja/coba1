package com.islandbar.ui.island

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Rounded rectangle with *continuous* corners (curvature eases in instead of
 * jumping at the tangent point), the way Apple draws its UI. Based on the
 * corner-smoothing construction Figma published; 0.6 is close to iOS.
 * When the radius reaches half the height it becomes an exact capsule.
 */
class ContinuousRoundedShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        return Outline.Generic(continuousRectPath(size, r, smoothing))
    }

    override fun equals(other: Any?) =
        other is ContinuousRoundedShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode() = radius.hashCode() * 31 + smoothing.hashCode()
}

internal fun continuousRectPath(size: Size, radiusPx: Float, smoothingIn: Float, path: Path = Path()): Path {
    path.reset()
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return path
    val budget = min(w, h) / 2f
    val r = radiusPx.coerceIn(0f, budget)
    if (r < 0.5f) {
        path.addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h))
        return path
    }

    var s = smoothingIn
    var p = (1 + s) * r
    s = min(s, budget / r - 1f).coerceAtLeast(0f)
    p = min(p, budget)

    val arcMeasure = 90f * (1 - s)
    val arcLen = (sin(rad(arcMeasure / 2)) * r * sqrt(2f))
    val alpha = (90f - arcMeasure) / 2f
    val p3ToP4 = r * tan(rad(alpha / 2))
    val beta = 45f * s
    val c = p3ToP4 * cos(rad(beta))
    val d = c * tan(rad(beta))
    val b = (p - arcLen - c - d) / 3f
    val a = 2f * b

    // One corner in local "distance along edge" coordinates (e1 = incoming edge, e2 = outgoing).
    val p0 = Offset(arcLen + d, d)
    val p3 = Offset(d, arcLen + d)
    val center = Offset(r, r)
    val (k0, k3) = arcControls(p0, p3, center, r, arcMeasure)

    // Corner mappings, clockwise from the top-right.
    val corners: List<(Float, Float) -> Offset> = listOf(
        { e1, e2 -> Offset(w - e1, e2) },          // top-right
        { e1, e2 -> Offset(w - e2, h - e1) },      // bottom-right
        { e1, e2 -> Offset(e1, h - e2) },          // bottom-left
        { e1, e2 -> Offset(e2, e1) },              // top-left
    )
    val start = corners[3](0f, p) // end of top-left corner = start of the top edge
    path.moveTo(start.x, start.y)
    for (map in corners) {
        fun pt(o: Offset) = map(o.x, o.y)
        val s0 = map(p, 0f)
        path.lineTo(s0.x, s0.y)
        val c1 = map(p - a, 0f); val c2 = map(p - a - b, 0f); val e = pt(p0)
        path.cubicTo(c1.x, c1.y, c2.x, c2.y, e.x, e.y)
        val q1 = pt(k0); val q2 = pt(k3); val e2 = pt(p3)
        path.cubicTo(q1.x, q1.y, q2.x, q2.y, e2.x, e2.y)
        val c3 = map(0f, p - a - b); val c4 = map(0f, p - a); val e3 = map(0f, p)
        path.cubicTo(c3.x, c3.y, c4.x, c4.y, e3.x, e3.y)
    }
    path.close()
    return path
}

/** Cubic control points approximating the circular arc p0→p3 around [center]. */
private fun arcControls(p0: Offset, p3: Offset, center: Offset, r: Float, sweepDeg: Float): Pair<Offset, Offset> {
    val k = 4f / 3f * tan(rad(sweepDeg / 4f)) * r
    fun tangent(p: Offset, toward: Offset): Offset {
        val v = p - center
        var t = Offset(-v.y, v.x)
        val len = hypot(t.x, t.y).takeIf { it > 0f } ?: return Offset.Zero
        t = Offset(t.x / len, t.y / len)
        val dir = toward - p
        return if (t.x * dir.x + t.y * dir.y < 0) Offset(-t.x, -t.y) else t
    }
    return (p0 + tangent(p0, p3) * k) to (p3 + tangent(p3, p0) * k)
}

private fun rad(deg: Float) = (deg * PI / 180.0).toFloat()

/**
 * The gooey "neck" between two circles that are pulling apart (classic metaball
 * construction). Returns null once they are too far apart to touch.
 * [v] controls how fat the neck is (0..1).
 */
internal fun metaballBridge(
    c1: Offset, r1: Float,
    c2: Offset, r2: Float,
    v: Float,
    handleSize: Float = 2.4f,
    path: Path = Path(),
): Path? {
    val d = hypot(c2.x - c1.x, c2.y - c1.y)
    if (v <= 0.01f || r1 <= 0f || r2 <= 0f || d <= abs(r1 - r2) || d == 0f) return null

    var u1 = 0f
    var u2 = 0f
    if (d < r1 + r2) {
        u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2 * r1 * d)).coerceIn(-1f, 1f))
        u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2 * r2 * d)).coerceIn(-1f, 1f))
    }
    val between = atan2(c2.y - c1.y, c2.x - c1.x)
    val maxSpread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val pi = PI.toFloat()

    val a1 = between + u1 + (maxSpread - u1) * v
    val a2 = between - u1 - (maxSpread - u1) * v
    val a3 = between + pi - u2 - (pi - u2 - maxSpread) * v
    val a4 = between - pi + u2 + (pi - u2 - maxSpread) * v

    fun polar(c: Offset, a: Float, r: Float) = Offset(c.x + r * cos(a), c.y + r * sin(a))
    val p1 = polar(c1, a1, r1)
    val p2 = polar(c1, a2, r1)
    val p3 = polar(c2, a3, r2)
    val p4 = polar(c2, a4, r2)

    val total = r1 + r2
    val d2Base = min(v * handleSize, hypot(p1.x - p3.x, p1.y - p3.y) / total)
    val d2 = d2Base * min(1f, d * 2 / total)
    val h1 = polar(p1, a1 - pi / 2, r1 * d2)
    val h2 = polar(p2, a2 + pi / 2, r1 * d2)
    val h3 = polar(p3, a3 + pi / 2, r2 * d2)
    val h4 = polar(p4, a4 - pi / 2, r2 * d2)

    path.reset()
    path.moveTo(p1.x, p1.y)
    path.cubicTo(h1.x, h1.y, h3.x, h3.y, p3.x, p3.y)
    path.lineTo(p4.x, p4.y)
    path.cubicTo(h4.x, h4.y, h2.x, h2.y, p2.x, p2.y)
    path.close()
    return path
}
