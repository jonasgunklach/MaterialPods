package app.materialpods

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Resolved dynamic (wallpaper) colors as ARGB ints. */
data class Pal(
    val primary: Int, val onPrimary: Int,
    val primaryContainer: Int, val onPrimaryContainer: Int,
    val secondaryContainer: Int, val onSecondaryContainer: Int,
    val tertiary: Int, val onTertiary: Int,
    val inverse: Int, val onInverse: Int, val inversePrimary: Int,
)

/** One battery reading for drawing; level null = unknown/disconnected. */
data class Cell(val label: String, val level: Int?, val charging: Boolean, val icon: Icon)
enum class Icon { BUDS, LEFT, RIGHT, CASE }

/**
 * Shape-first widgets drawn with Canvas: the widget *is* the shape (cookie, pill, rounded slab),
 * floating on the wallpaper. Glance can't draw these, so each widget is one bitmap.
 */
object Art {
    private val heavy: Typeface = Typeface.create(Typeface.DEFAULT, 800, false)
    private val medium: Typeface = Typeface.create(Typeface.DEFAULT, 500, false)

    private fun canvas(w: Int, h: Int): Pair<Bitmap, Canvas> {
        val b = Bitmap.createBitmap(w.coerceAtLeast(1), h.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        return b to Canvas(b)
    }
    private fun fill(color: Int, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; this.alpha = alpha }
    private fun line(color: Int, w: Float, alpha: Int = 255) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.alpha = alpha; style = Paint.Style.STROKE; strokeWidth = w
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = true, align: Paint.Align = Paint.Align.CENTER, alpha: Int = 255) =
        c.drawText(s, x, y, fill(color, alpha).apply { textSize = size; typeface = if (bold) heavy else medium; textAlign = align })

    /** Circle with soft scallops ("cookie"/"sunny"); amp 0 = circle. */
    fun scallop(cx: Float, cy: Float, r: Float, lobes: Int, amp: Float) = Path().apply {
        val base = r / (1 + amp)
        for (i in 0..720) {
            val t = i / 720.0 * 2 * PI
            val rr = base * (1 + amp * cos(lobes * t))
            val x = (cx + rr * cos(t)).toFloat(); val y = (cy + rr * sin(t)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    // ---------- glyphs ----------

    /** One AirPod: ear tip + head + stem. [mirror] for the right one. */
    private fun bud(c: Canvas, cx: Float, cy: Float, s: Float, color: Int, mirror: Boolean = false) {
        c.save()
        if (mirror) c.scale(-1f, 1f, cx, cy)
        c.drawCircle(cx - s * 0.06f, cy - s * 0.18f, s * 0.24f, fill(color))
        c.drawCircle(cx - s * 0.30f, cy - s * 0.20f, s * 0.11f, fill(color))
        c.drawLine(cx + s * 0.06f, cy - s * 0.08f, cx + s * 0.13f, cy + s * 0.40f, line(color, s * 0.17f))
        c.restore()
    }

    private fun icon(c: Canvas, i: Icon, cx: Float, cy: Float, s: Float, color: Int, bg: Int) = when (i) {
        Icon.BUDS -> { bud(c, cx - s * 0.2f, cy, s * 0.75f, color); bud(c, cx + s * 0.2f, cy, s * 0.75f, color, mirror = true) }
        Icon.LEFT -> bud(c, cx, cy, s, color)
        Icon.RIGHT -> bud(c, cx, cy, s, color, mirror = true)
        Icon.CASE -> {
            val r = RectF(cx - s * 0.42f, cy - s * 0.30f, cx + s * 0.42f, cy + s * 0.30f)
            c.drawRoundRect(r, s * 0.2f, s * 0.2f, fill(color))
            c.drawLine(r.left, cy - s * 0.08f, r.right, cy - s * 0.08f, line(bg, s * 0.06f))
        }
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) = c.drawPath(Path().apply {
        moveTo(cx + s * 0.1f, cy - s * 0.5f); lineTo(cx - s * 0.3f, cy + s * 0.08f); lineTo(cx, cy + s * 0.08f)
        lineTo(cx - s * 0.1f, cy + s * 0.5f); lineTo(cx + s * 0.3f, cy - s * 0.08f); lineTo(cx, cy - s * 0.08f); close()
    }, fill(color))

    /** Listening-mode glyphs: 1 off, 2 noise cancellation, 3 transparency, 4 adaptive. */
    private fun mode(c: Canvas, m: Int, cx: Float, cy: Float, s: Float, color: Int) {
        val w = s * 0.11f
        when (m) {
            1 -> { c.drawCircle(cx, cy, s * 0.34f, line(color, w)); c.drawLine(cx - s * 0.3f, cy + s * 0.3f, cx + s * 0.3f, cy - s * 0.3f, line(color, w)) }
            2 -> { c.drawCircle(cx, cy, s * 0.38f, line(color, w)); c.drawCircle(cx, cy, s * 0.19f, fill(color)) }
            3 -> { c.drawCircle(cx, cy, s * 0.1f, fill(color)); c.drawCircle(cx, cy, s * 0.25f, line(color, w * 0.85f)); c.drawCircle(cx, cy, s * 0.4f, line(color, w * 0.85f, 140)) }
            else -> {
                c.drawCircle(cx, cy, s * 0.36f, line(color, w))
                c.drawPath(Path().apply { addArc(cx - s * 0.36f, cy - s * 0.36f, cx + s * 0.36f, cy + s * 0.36f, 90f, 180f); close() }, fill(color))
            }
        }
    }

    private fun pct(cell: Cell) = cell.level?.let { "$it%" } ?: "–"

    // ---------- widgets ----------

    /** Square: the whole widget is a cookie with the buds' battery in huge type. */
    fun cookie(w: Int, h: Int, cell: Cell, p: Pal): Bitmap {
        val (b, c) = canvas(w, h)
        val d = min(w, h).toFloat(); val cx = w / 2f; val cy = h / 2f
        c.drawPath(scallop(cx, cy, d / 2, 9, 0.075f), fill(p.primaryContainer))
        icon(c, cell.icon, cx, cy - d * 0.2f, d * 0.2f, p.onPrimaryContainer, p.primaryContainer)
        text(c, pct(cell), cx, cy + d * 0.12f, d * 0.27f, p.onPrimaryContainer)
        if (cell.charging) bolt(c, cx + d * 0.26f, cy - d * 0.2f, d * 0.1f, p.onPrimaryContainer)
        text(c, cell.label, cx, cy + d * 0.27f, d * 0.085f, p.onPrimaryContainer, bold = false, alpha = 200)
        return b
    }

    /** Rounded slab with thick 270° arc gauges, icon inside, value and label below. */
    fun gauges(w: Int, h: Int, cells: List<Cell>, p: Pal): Bitmap {
        val (b, c) = canvas(w, h)
        val pad = h * 0.1f
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), min(h * 0.32f, w * 0.2f), min(h * 0.32f, w * 0.2f), fill(p.secondaryContainer))
        val slot = (w - pad * 2) / cells.size
        val d = min(slot * 0.62f, h * 0.48f)
        val stroke = d * 0.17f
        cells.forEachIndexed { i, cell ->
            val cx = pad + slot * (i + 0.5f)
            val cy = pad + d / 2 + h * 0.04f
            val r = RectF(cx - d / 2, cy - d / 2, cx + d / 2, cy + d / 2)
            c.drawArc(r, 135f, 270f, false, line(p.onSecondaryContainer, stroke, 45))
            cell.level?.let { c.drawArc(r, 135f, 270f * it / 100f, false, line(p.primary, stroke)) }
            icon(c, cell.icon, cx, cy, d * 0.42f, p.onSecondaryContainer, p.secondaryContainer)
            val v = pct(cell) + if (cell.charging) " ⚡" else ""
            text(c, v, cx, cy + d / 2 + d * 0.32f, d * 0.27f, p.onSecondaryContainer)
            text(c, cell.label, cx, cy + d / 2 + d * 0.56f, d * 0.19f, p.onSecondaryContainer, bold = false, alpha = 190)
        }
        return b
    }

    /** Capsule: cookie icon on the left, big number, case on the second line. */
    fun pill(w: Int, h: Int, buds: Cell, case: Cell, p: Pal): Bitmap {
        val (b, c) = canvas(w, h)
        val r = h / 2f
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), r, r, fill(p.secondaryContainer))
        val ic = h * 0.38f
        c.drawPath(scallop(r, r, ic, 8, 0.07f), fill(p.tertiary))
        icon(c, Icon.BUDS, r, r, ic * 0.95f, p.onTertiary, p.tertiary)
        val x = r + ic + h * 0.18f
        text(c, pct(buds) + if (buds.charging) " ⚡" else "", x, r + h * 0.05f, h * 0.34f, p.onSecondaryContainer, align = Paint.Align.LEFT)
        text(c, "AirPods · ${case.label} ${pct(case)}", x, r + h * 0.3f, h * 0.15f, p.onSecondaryContainer, bold = false, align = Paint.Align.LEFT, alpha = 200)
        return b
    }

    /** Capsule of round mode buttons; the active one turns into a sunny shape. */
    fun modes(w: Int, h: Int, modes: List<Int>, current: Int?, p: Pal): Bitmap {
        val (b, c) = canvas(w, h)
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, fill(p.inverse))
        val pad = h * 0.14f
        val slot = (w - pad * 2) / modes.size
        val d = min(h - pad * 2, slot * 0.86f)
        modes.forEachIndexed { i, m ->
            val cx = pad + slot * (i + 0.5f); val cy = h / 2f
            if (m == current) {
                c.drawPath(scallop(cx, cy, d / 2, 8, 0.08f), fill(p.inversePrimary))
                mode(c, m, cx, cy, d * 0.5f, p.inverse)
            } else {
                c.drawCircle(cx, cy, d / 2, fill(p.onInverse, 38))
                mode(c, m, cx, cy, d * 0.5f, p.onInverse)
            }
        }
        return b
    }

    /** Horizontal padding used by [modes], so tap targets line up with the drawing. */
    fun modesPad(hDp: Float) = hDp * 0.14f
}
