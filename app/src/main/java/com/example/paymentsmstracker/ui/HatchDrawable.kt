package com.example.paymentsmstracker.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Mono's state language: hatched = waiting on you, solid = done.
 * Draws a rounded shape filled with [fill], crossed by diagonal [ink] lines, with an [ink] outline.
 */
class HatchDrawable(
    private val fill: Int,
    private val ink: Int,
    private val density: Float,
    /** Corner radius in px; negative means fully round (pill or circle). */
    private val cornerPx: Float = -1f
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        strokeWidth = 1.5f * density
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
    }
    private val rect = RectF()
    private val clip = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val inset = outlinePaint.strokeWidth / 2f
        rect.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset)
        val r = if (cornerPx < 0) minOf(rect.width(), rect.height()) / 2f else cornerPx
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)

        canvas.drawPath(clip, fillPaint)
        val save = canvas.save()
        canvas.clipPath(clip)
        val step = 7f * density
        var x = rect.left - rect.height()
        while (x < rect.right) {
            canvas.drawLine(x, rect.bottom, x + rect.height(), rect.top, linePaint)
            x += step
        }
        canvas.restoreToCount(save)
        canvas.drawPath(clip, outlinePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        linePaint.alpha = alpha
        outlinePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
