package com.goral.museintegrator.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.goral.museintegrator.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Integrated Calm Score over time: one line series with its interquartile range as a band.
 *
 * The band is the same hue at low alpha rather than a second colour, because it is a spread
 * around the same measure, not a second series. One y-axis only, and 0 sits at the bottom to
 * match the orientation Muse itself uses, where calm is low on the plot.
 */
class TrendChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    data class Point(
        val sessionId: Long,
        val timeMs: Long,
        val score: Double,
        val p25: Double,
        val p75: Double
    )

    var onPointSelected: ((Point) -> Unit)? = null

    private var points: List<Point> = emptyList()
    private var selectedIndex: Int = -1

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val seriesColor = ContextCompat.getColor(context, R.color.chartSeries)
    private val gridColor = ContextCompat.getColor(context, R.color.chartGrid)
    private val inkPrimary = ContextCompat.getColor(context, R.color.textPrimary)
    private val inkSecondary = ContextCompat.getColor(context, R.color.textSecondary)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = seriesColor
    }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(seriesColor, 46)
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = seriesColor
    }
    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = ContextCompat.getColor(context, R.color.chartSurface)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = gridColor
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkSecondary
        textSize = dp(11f)
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkPrimary
        textSize = dp(13f)
        isFakeBoldText = true
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = inkSecondary
        textSize = dp(13f)
    }

    private val dateFormat = SimpleDateFormat("d MMM", Locale.getDefault())

    fun setPoints(newPoints: List<Point>) {
        points = newPoints.sortedBy { it.timeMs }
        selectedIndex = if (points.isEmpty()) -1 else points.size - 1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) {
            canvas.drawText(
                "No scored sessions yet",
                dp(12f), height / 2f, emptyPaint
            )
            return
        }

        val padLeft = dp(34f)
        val padRight = dp(12f)
        val padTop = dp(18f)
        val padBottom = dp(22f)
        val plotWidth = width - padLeft - padRight
        val plotHeight = height - padTop - padBottom
        if (plotWidth <= 0 || plotHeight <= 0) return

        val lo = points.minOf { min(it.p25, it.score) }
        val hi = points.maxOf { max(it.p75, it.score) }
        val pad = max(2.0, (hi - lo) * 0.15)
        val yMin = max(0.0, lo - pad)
        val yMax = min(100.0, hi + pad).coerceAtLeast(yMin + 4.0)

        fun xAt(i: Int): Float =
            if (points.size == 1) padLeft + plotWidth / 2f
            else padLeft + plotWidth * i / (points.size - 1).toFloat()

        fun yAt(v: Double): Float =
            padTop + plotHeight * (1f - ((v - yMin) / (yMax - yMin)).toFloat())

        // Recessive grid: three reference rows, labelled, nothing more.
        for (k in 0..2) {
            val v = yMin + (yMax - yMin) * k / 2.0
            val y = yAt(v)
            canvas.drawLine(padLeft, y, padLeft + plotWidth, y, gridPaint)
            canvas.drawText("%.0f".format(v), dp(4f), y + dp(4f), labelPaint)
        }

        // Interquartile band.
        if (points.size > 1) {
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = xAt(i); val y = yAt(p.p75)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in points.indices.reversed()) {
                path.lineTo(xAt(i), yAt(points[i].p25))
            }
            path.close()
            canvas.drawPath(path, bandPaint)
        }

        // Series line.
        val line = Path()
        points.forEachIndexed { i, p ->
            val x = xAt(i); val y = yAt(p.score)
            if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }
        canvas.drawPath(line, linePaint)

        // Markers, with a surface ring so overlapping points stay separable.
        points.forEachIndexed { i, p ->
            val x = xAt(i); val y = yAt(p.score)
            val r = if (i == selectedIndex) dp(5.5f) else dp(4f)
            canvas.drawCircle(x, y, r, markerPaint)
            canvas.drawCircle(x, y, r, markerRingPaint)
        }

        // Direct-label the selected point only. A number on every point is noise.
        val sel = points.getOrNull(selectedIndex)
        if (sel != null) {
            val x = xAt(selectedIndex)
            val y = yAt(sel.score)
            val text = "%.1f".format(sel.score)
            val textWidth = valuePaint.measureText(text)
            val tx = (x - textWidth / 2f).coerceIn(padLeft, padLeft + plotWidth - textWidth)
            canvas.drawText(text, tx, max(padTop + dp(10f), y - dp(10f)), valuePaint)
        }

        // Endpoint date labels only.
        canvas.drawText(dateFormat.format(Date(points.first().timeMs)), padLeft, height - dp(6f), labelPaint)
        val lastLabel = dateFormat.format(Date(points.last().timeMs))
        canvas.drawText(
            lastLabel,
            padLeft + plotWidth - labelPaint.measureText(lastLabel),
            height - dp(6f),
            labelPaint
        )
    }

    /** Canvas has no hover, so tap-to-select is the equivalent inspection affordance. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (points.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val padLeft = dp(34f)
                val plotWidth = width - padLeft - dp(12f)
                val fraction = ((event.x - padLeft) / plotWidth).coerceIn(0f, 1f)
                val index = if (points.size == 1) 0
                else (fraction * (points.size - 1)).toInt().coerceIn(0, points.size - 1)
                val refined = (index - 1..index + 1)
                    .filter { it in points.indices }
                    .minByOrNull { abs(event.x - (padLeft + plotWidth * it / max(1, points.size - 1).toFloat())) }
                    ?: index
                if (refined != selectedIndex) {
                    selectedIndex = refined
                    invalidate()
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP -> {
                points.getOrNull(selectedIndex)?.let { onPointSelected?.invoke(it) }
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun withAlpha(color: Int, alpha: Int) =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
