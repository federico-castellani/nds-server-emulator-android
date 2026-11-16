package io.github.federicocastellani.ndsstation.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.federicocastellani.ndsstation.data.ScreenLayout
import io.github.federicocastellani.ndsstation.emulation.DsKey
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** A round (A/B/X/Y) or pill-shaped (L/R/Start/Select) on-screen button. */
data class VButton(val key: DsKey, val label: String, val rect: Rect, val round: Boolean) {
    fun hit(p: Offset): Boolean {
        // A little larger than drawn: thumbs are not precise.
        val r = rect.inflate(rect.minDimension * 0.15f)
        if (!round) return r.contains(p)
        return hypot(p.x - rect.center.x, p.y - rect.center.y) <= r.width / 2
    }
}

/** Where the two DS screens and the virtual controls go, in pixels. */
class Geometry(
    val top: Rect,
    val bottom: Rect,
    val buttons: List<VButton>,
    val dpadCenter: Offset,
    val dpadRadius: Float,
) {
    /** 8-way D-pad: diagonals press two directions, the very center presses nothing. */
    fun dpadMask(p: Offset): Int {
        val dx = p.x - dpadCenter.x
        val dy = p.y - dpadCenter.y
        val distance = hypot(dx, dy)
        if (dpadRadius <= 0f || distance > dpadRadius * 1.25f || distance < dpadRadius * 0.18f) return 0
        val angle = Math.toDegrees(atan2(-dy, dx).toDouble()).let { if (it < 0) it + 360 else it }
        val sector = ((angle + 22.5) / 45).toInt() % 8   // 0 = right, counter-clockwise
        return when (sector) {
            0 -> DsKey.Right.bit
            1 -> DsKey.Right.bit or DsKey.Up.bit
            2 -> DsKey.Up.bit
            3 -> DsKey.Up.bit or DsKey.Left.bit
            4 -> DsKey.Left.bit
            5 -> DsKey.Left.bit or DsKey.Down.bit
            6 -> DsKey.Down.bit
            else -> DsKey.Down.bit or DsKey.Right.bit
        }
    }

    fun maskAt(p: Offset): Int = buttons.fold(dpadMask(p)) { mask, b -> if (b.hit(p)) mask or b.key.bit else mask }

    companion object {
        private const val ASPECT = 4f / 3f

        fun compute(width: Float, height: Float, layout: ScreenLayout, showControls: Boolean, scale: Float, dp: Float): Geometry {
            val landscape = width > height
            val gap = 6 * dp
            val sideBySide = layout == ScreenLayout.SideBySide

            // --- Screens ------------------------------------------------------------
            val top: Rect
            val bottom: Rect
            if (sideBySide) {
                val sw = min((width - gap) / 2, height * ASPECT)
                val sh = sw / ASPECT
                val y = if (landscape) (height - sh) / 2 else 0f
                val x = (width - (2 * sw + gap)) / 2
                top = Rect(x, y, x + sw, y + sh)
                bottom = Rect(x + sw + gap, y, x + 2 * sw + gap, y + sh)
            } else if (landscape) {
                // Stacked in the middle; the sides are left to the controls.
                val sh = (height - gap) / 2
                val sw = sh * ASPECT
                val x = (width - sw) / 2
                top = Rect(x, 0f, x + sw, sh)
                bottom = Rect(x, sh + gap, x + sw, 2 * sh + gap)
            } else {
                // Portrait: screens at the top, at most ~62% of the height so the controls fit.
                var sw = width
                var sh = sw / ASPECT
                val limit = height * (if (showControls) 0.62f else 1f)
                if (2 * sh + gap > limit) {
                    sh = (limit - gap) / 2
                    sw = sh * ASPECT
                }
                val x = (width - sw) / 2
                top = Rect(x, 0f, x + sw, sh)
                bottom = Rect(x, sh + gap, x + sw, 2 * sh + gap)
            }

            if (!showControls) return Geometry(top, bottom, emptyList(), Offset.Zero, 0f)

            // --- Controls -------------------------------------------------------------
            val u = 52 * dp * scale             // base unit
            val pad = 16 * dp
            val dpadRadius = 1.35f * u
            val faceSize = 0.95f * u
            val clusterRadius = 1.35f * u
            val shoulder = Size(1.9f * u, 0.75f * u)
            val pill = Size(1.45f * u, 0.5f * u)

            val clusterY = if (landscape) height * 0.56f else height - pad - pill.height - pad - clusterRadius
            val left = Offset(pad + clusterRadius, clusterY)
            val right = Offset(width - pad - clusterRadius, clusterY)

            fun face(key: DsKey, label: String, dx: Float, dy: Float) = VButton(
                key, label,
                Rect(Offset(right.x + dx * 0.95f * u - faceSize / 2, right.y + dy * 0.95f * u - faceSize / 2), Size(faceSize, faceSize)),
                round = true,
            )

            val shoulderY = if (landscape) pad else clusterY - clusterRadius - pad - shoulder.height
            val pillY = height - pad - pill.height
            val buttons = listOf(
                face(DsKey.X, "X", 0f, -1f),
                face(DsKey.A, "A", 1f, 0f),
                face(DsKey.B, "B", 0f, 1f),
                face(DsKey.Y, "Y", -1f, 0f),
                VButton(DsKey.L, "L", Rect(Offset(pad, shoulderY), shoulder), round = false),
                VButton(DsKey.R, "R", Rect(Offset(width - pad - shoulder.width, shoulderY), shoulder), round = false),
                if (landscape) {
                    VButton(DsKey.Select, "SELECT", Rect(Offset(left.x - pill.width / 2, pillY), pill), round = false)
                } else {
                    VButton(DsKey.Select, "SELECT", Rect(Offset(width / 2 - pill.width - pad / 2, pillY), pill), round = false)
                },
                if (landscape) {
                    VButton(DsKey.Start, "START", Rect(Offset(right.x - pill.width / 2, pillY), pill), round = false)
                } else {
                    VButton(DsKey.Start, "START", Rect(Offset(width / 2 + pad / 2, pillY), pill), round = false)
                },
            )
            return Geometry(top, bottom, buttons, left, dpadRadius)
        }
    }
}

/** Draws the D-pad and buttons; pressed parts are highlighted. */
fun DrawScope.drawControls(geometry: Geometry, pressed: Int, opacity: Float, text: TextMeasurer) {
    if (geometry.dpadRadius <= 0f) return
    val fill = Color.White.copy(alpha = 0.18f * opacity + 0.05f)
    val active = Color.White.copy(alpha = 0.55f * opacity + 0.15f)
    val stroke = Color.White.copy(alpha = 0.6f * opacity)
    val label = Color.White.copy(alpha = 0.95f * opacity + 0.05f)
    val strokeWidth = 1.5f * density

    // D-pad: a plus shape inside a soft circle.
    val c = geometry.dpadCenter
    val r = geometry.dpadRadius
    drawCircle(Color.Black.copy(alpha = 0.12f * opacity), r, c)
    val arm = r * 0.36f
    fun arm(rect: Rect, on: Boolean) {
        drawRoundRect(if (on) active else fill, rect.topLeft, rect.size, CornerRadius(arm * 0.3f))
        drawRoundRect(stroke, rect.topLeft, rect.size, CornerRadius(arm * 0.3f), style = Stroke(strokeWidth))
    }
    arm(Rect(c.x - arm, c.y - r * 0.92f, c.x + arm, c.y - arm * 0.6f), pressed and DsKey.Up.bit != 0)
    arm(Rect(c.x - arm, c.y + arm * 0.6f, c.x + arm, c.y + r * 0.92f), pressed and DsKey.Down.bit != 0)
    arm(Rect(c.x - r * 0.92f, c.y - arm, c.x - arm * 0.6f, c.y + arm), pressed and DsKey.Left.bit != 0)
    arm(Rect(c.x + arm * 0.6f, c.y - arm, c.x + r * 0.92f, c.y + arm), pressed and DsKey.Right.bit != 0)

    for (b in geometry.buttons) {
        val on = pressed and b.key.bit != 0
        if (b.round) {
            drawCircle(if (on) active else fill, b.rect.width / 2, b.rect.center)
            drawCircle(stroke, b.rect.width / 2, b.rect.center, style = Stroke(strokeWidth))
        } else {
            val corner = CornerRadius(b.rect.height / 2)
            drawRoundRect(if (on) active else fill, b.rect.topLeft, b.rect.size, corner)
            drawRoundRect(stroke, b.rect.topLeft, b.rect.size, corner, style = Stroke(strokeWidth))
        }
        val style = TextStyle(
            color = label, fontWeight = FontWeight.Bold,
            fontSize = if (b.round) 18.sp else if (b.label.length > 1) 11.sp else 15.sp,
        )
        val layout = text.measure(b.label, style)
        drawText(layout, topLeft = Offset(b.rect.center.x - layout.size.width / 2, b.rect.center.y - layout.size.height / 2))
    }
}

/** True when [new] presses a key that [old] did not (used for haptic feedback). */
fun pressesNewKey(old: Int, new: Int) = new and old.inv() != 0
