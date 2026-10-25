package com.example.paymentsmstracker.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.example.paymentsmstracker.R
import com.example.paymentsmstracker.home.DaySpend
import com.example.paymentsmstracker.home.Money
import java.math.BigDecimal
import java.time.format.TextStyle
import java.util.Locale

/** Last 7 days of spend: hatched bars, the selected day solid with a yellow value tag. Tap a bar to select it. */
class WeekBarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val d = resources.displayMetrics.density
    private val ink = ContextCompat.getColor(context, R.color.mono_ink)
    private val yellow = ContextCompat.getColor(context, R.color.mono_yellow)
    private val onYellow = ContextCompat.getColor(context, R.color.mono_on_yellow)
    private val font = ResourcesCompat.getFont(context, R.font.outfit)

    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.mono_meta)
        textSize = 12f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = font
    }
    private val labelOn = Paint(label).apply { color = ink; isFakeBoldText = true }
    private val tagBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = yellow }
    private val tagText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = onYellow
        textSize = 12.5f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = font
        isFakeBoldText = true
    }
    private val hatch = HatchDrawable(yellow, ink, d, 9f * d)
    private val rect = RectF()

    private var days: List<DaySpend> = emptyList()
    var selected: Int = 6
        private set

    fun setDays(value: List<DaySpend>) {
        days = value
        selected = value.lastIndex
        contentDescription = value.joinToString(", ") { "${dayName(it)} ${Money.format(it.amount)}" }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (days.isEmpty()) return
        val labelH = 22f * d
        val tagH = 30f * d
        val top = tagH + 8f * d
        val bottom = height - labelH
        val slot = width / days.size.toFloat()
        val barW = minOf(slot * 0.62f, 34f * d)
        val max = days.maxOf { it.amount }.takeIf { it.signum() > 0 } ?: BigDecimal.ONE

        days.forEachIndexed { i, day ->
            val cx = slot * i + slot / 2f
            val frac = day.amount.toFloat() / max.toFloat()
            val h = maxOf(6f * d, (bottom - top) * frac)
            rect.set(cx - barW / 2f, bottom - h, cx + barW / 2f, bottom)
            if (i == selected) {
                canvas.drawRoundRect(rect, 9f * d, 9f * d, solid)
                val tag = Money.format(day.amount).replace("₹", "₹\u2009")
                val tw = tagText.measureText(tag) + 18f * d
                val tl = (cx - tw / 2f).coerceIn(0f, width - tw)
                val tr = RectF(tl, rect.top - tagH - 6f * d, tl + tw, rect.top - 6f * d)
                canvas.drawRoundRect(tr, tagH / 2f, tagH / 2f, tagBg)
                canvas.drawText(tag, tr.centerX(), tr.centerY() - (tagText.descent() + tagText.ascent()) / 2f, tagText)
            } else {
                hatch.setBounds(rect.left.toInt(), rect.top.toInt(), rect.right.toInt(), rect.bottom.toInt())
                hatch.draw(canvas)
            }
            canvas.drawText(dayName(day), cx, height - 5f * d, if (i == selected) labelOn else label)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (days.isEmpty()) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val i = (event.x / (width / days.size.toFloat())).toInt().coerceIn(0, days.lastIndex)
            if (i != selected) {
                selected = i
                announceForAccessibility("${dayName(days[i])} ${Money.format(days[i].amount)}")
                invalidate()
            }
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun dayName(day: DaySpend): String =
        day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
}
