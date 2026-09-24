package com.goral.museintegrator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.goral.museintegrator.R

/**
 * The reconstructed within-session trace — the granular view Muse's own summary throws away.
 *
 * Band regions are drawn as recessive boundary rules with a key, not as heavy background
 * fills: the trace is the subject, and flooding a third of the plot with colour would compete
 * with it. Percentile rules are labelled directly rather than legended.
 */
class TraceChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private var values: DoubleArray = DoubleArray(0)
    private var envelopeLow: DoubleArray = DoubleArray(0)
    private var envelopeHigh: DoubleArray = DoubleArray(0)
    private var p25 = Double.NaN
    private var p75 = Double.NaN
    private var mean = Double.NaN
    private var durationSeconds = 0.0

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val seriesColor = ContextCompat.getColor(context, R.color.chartSeries)
    private val inkSecondary = ContextCompat.getColor(context, R.color.textSecondary)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.6f)
        strokeJoin = Paint.Join.ROUND; color = seriesColor
    }
    private val envelopePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = withAlpha(seriesColor, 40)
    }
    private val bandRulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
        color = ContextCompat.getColor(context, R.color.chartGrid)
    }
    private val meanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1.4f)
        color = withAlpha(seriesColor, 190)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(4f)), 0f)
    }
    private val percentilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f)
        color = withAlpha(seriesColor, 110)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(2f), dp(4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkSecondary; textSize = dp(10f)
    }

    fun setTrace(
        values: DoubleArray,
        envelopeLow: DoubleArray,
        envelopeHigh: DoubleArray,
        p25: Double,
        p75: Double,
        mean: Double,
        durationSeconds: Double
    ) {
        this.values = values
        this.envelopeLow = envelopeLow
        this.envelopeHigh = envelopeHigh
        this.p25 = p25; this.p75 = p75; this.mean = mean
        this.durationSeconds = durationSeconds
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return

        val padLeft = dp(30f); val padRight = dp(46f)
        val padTop = dp(10f); val padBottom = dp(18f)
        val plotWidth = width - padLeft - padRight
        val plotHeight = height - padTop - padBottom
        if (plotWidth <= 0 || plotHeight <= 0) return

        // Fixed 0..100 so traces from different sessions are visually comparable.
        fun yAt(v: Double) = padTop + plotHeight * (1f - (v / 100.0).toFloat())
        fun xAt(i: Int) = padLeft + plotWidth * i / (values.size - 1).coerceAtLeast(1).toFloat()

        val third = 100.0 / 3.0
        canvas.drawLine(padLeft, yAt(third), padLeft + plotWidth, yAt(third), bandRulePaint)
        canvas.drawLine(padLeft, yAt(2 * third), padLeft + plotWidth, yAt(2 * third), bandRulePaint)

        val keyX = padLeft + plotWidth + dp(6f)
        drawBandKey(canvas, keyX, yAt(100.0), yAt(2 * third), R.color.bandActive, "Active")
        drawBandKey(canvas, keyX, yAt(2 * third), yAt(third), R.color.bandNeutral, "Neutral")
        drawBandKey(canvas, keyX, yAt(third), yAt(0.0), R.color.bandCalm, "Calm")

        if (envelopeLow.size == values.size && envelopeHigh.size == values.size) {
            val path = Path()
            for (i in values.indices) {
                val x = xAt(i); val y = yAt(envelopeHigh[i])
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in values.indices.reversed()) path.lineTo(xAt(i), yAt(envelopeLow[i]))
            path.close()
            canvas.drawPath(path, envelopePaint)
        }

        val line = Path()
        for (i in values.indices) {
            val x = xAt(i); val y = yAt(values[i])
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        canvas.drawPath(line, linePaint)

        // Percentile rules, labelled directly rather than legended.
        if (!p25.isNaN() && !p75.isNaN()) {
            for ((value, label) in listOf(p25 to "P25", p75 to "P75")) {
                val y = yAt(value)
                canvas.drawLine(padLeft, y, padLeft + plotWidth, y, percentilePaint)
                canvas.drawText(label, padLeft + plotWidth - dp(20f), y - dp(3f), labelPaint)
            }
        }

        if (!mean.isNaN()) {
            val y = yAt(mean)
            canvas.drawLine(padLeft, y, padLeft + plotWidth, y, meanPaint)
            canvas.drawText("mean %.1f".format(mean), padLeft + dp(2f), y - dp(3f), labelPaint)
        }

        canvas.drawText("0:00", padLeft, height - dp(4f), labelPaint)
        val end = formatClock(durationSeconds)
        canvas.drawText(
            end, padLeft + plotWidth - labelPaint.measureText(end), height - dp(4f), labelPaint
        )
        canvas.drawText("0", dp(2f), yAt(0.0), labelPaint)
        canvas.drawText("100", dp(2f), yAt(100.0) + dp(9f), labelPaint)
    }

    private fun drawBandKey(canvas: Canvas, x: Float, yTop: Float, yBottom: Float, colorRes: Int, label: String) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = ContextCompat.getColor(context, colorRes)
        }
        canvas.drawRoundRect(x, yTop, x + dp(4f), yBottom, dp(2f), dp(2f), paint)
        canvas.drawText(label, x + dp(8f), (yTop + yBottom) / 2f + dp(3.5f), labelPaint)
    }

    private fun formatClock(seconds: Double): String {
        val total = seconds.toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
