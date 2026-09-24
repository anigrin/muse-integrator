package com.goral.museintegrator.analysis

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * graph-v1: recovers the Mind-graph trace from a Muse results screenshot by pixel analysis.
 *
 * Nothing here is hardcoded to a device resolution. Every anchor is found by structure:
 *
 *  - the three-segment colour bar down the left edge of the plot gives all four y anchors,
 *    so calibration never depends on the gridlines rendering;
 *  - gridlines are found as near-full-width light rows and masked, because on the reference
 *    screenshot they are #C8BFD0 against a #292033 plot, close enough to the #F3F2F7 trace
 *    that a naive threshold picks up two phantom samples in every single column;
 *  - the bird strip is excluded by blue dominance rather than by row position;
 *  - the trace is reduced per column to a centroid (which linearly interpolates a steep
 *    transition, the behaviour we want) plus a min/max envelope, kept so a later algorithm
 *    can recover the extremes a centroid flattens.
 *
 * Validated against a 10-minute reference session: mean 12.79, P5 4.12, P95 24.61, stable to
 * <0.01 points across luminance thresholds from 185 to 230.
 */
object GraphAnalyzerV1 : GraphAnalyzer {

    override val version: String = "graph-v1"

    private const val PLATEAU_TOLERANCE = 12
    private const val MIN_PLATEAU_PX = 30
    private const val MIN_BAR_RUN_PX = 120
    private const val DISTINCT_COLOR_DELTA = 22
    private const val GRIDLINE_COVERAGE = 0.70

    /**
     * The three bands render as near-equal thirds. Without this constraint the triple
     * (page background, Active, Neutral) satisfies every other test and the detector silently
     * anchors the top of the axis ~900px too high, producing a plausible-looking wrong score.
     */
    private const val MAX_BAND_LENGTH_RATIO = 2.5

    override fun analyze(
        bitmap: Bitmap,
        roiIn: Rect?,
        classifiedSeconds: Double,
        classifiedSecondsSource: String
    ): AnalysisResult {
        val w = bitmap.width
        val h = bitmap.height
        val roi = (roiIn ?: Rect(0, 0, w, h)).let {
            Rect(max(0, it.left), max(0, it.top), min(w, it.right), min(h, it.bottom))
        }
        if (roi.width() < 80 || roi.height() < 80) {
            throw GraphAnalysisException("Search region too small: ${roi.width()}x${roi.height()}")
        }

        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        val img = Image(px, w, h)
        val warnings = mutableListOf<String>()

        val bar = findCalibrationBar(img, roi)
            ?: throw GraphAnalysisException(
                "Could not find the Mind graph's three-colour calibration bar. " +
                    "Is the Mind card expanded and fully on screen?"
            )

        if (luminance(bar.calmColor) > luminance(bar.activeColor)) {
            warnings += "Band luminance increases downward; the axis may be inverted relative to " +
                "the reference layout. Scores assume Calm is the bottom band."
        }

        val plotBackground = medianColor(img, bar.barRight + 6, bar.yTop + 4, roi.right - 4, bar.yBottom - 4)
        val gridRows = findGridlineRows(img, bar, roi, plotBackground)
        val extent = findPlotExtent(img, bar, roi, gridRows, plotBackground)
        val curveThreshold = deriveCurveThreshold(img, bar, extent, gridRows, plotBackground, warnings)
        val birdStrip = findBirdStrip(img, bar, extent)

        val bounds = GraphBounds(
            barLeft = bar.barLeft, barRight = bar.barRight,
            plotLeft = extent.first, plotRight = extent.second,
            yActiveTop = bar.yTop,
            yActiveNeutral = bar.boundary1,
            yNeutralCalm = bar.boundary2,
            yCalmBottom = bar.yBottom,
            gridlineRows = gridRows,
            birdStripTop = birdStrip.first, birdStripBottom = birdStrip.second,
            activeColor = bar.activeColor, neutralColor = bar.neutralColor, calmColor = bar.calmColor
        )

        val samples = extractTrace(img, bounds, gridRows, curveThreshold, classifiedSeconds, warnings)
        if (samples.size < 20) {
            throw GraphAnalysisException("Only ${samples.size} trace columns recovered; capture is unusable.")
        }

        val linearValues = DoubleArray(samples.size) { GraphScoring.linearScore(samples[it].yCentroid, bounds) }
        val bandedValues = DoubleArray(samples.size) { GraphScoring.bandedScore(samples[it].yCentroid, bounds) }

        val envelopeWidth = samples.map {
            GraphScoring.linearScore(it.yMin.toDouble(), bounds) -
                GraphScoring.linearScore(it.yMax.toDouble(), bounds)
        }.average()

        val third = 100.0 / 3.0
        val calmFrac = bandedValues.count { it < third }.toDouble() / bandedValues.size
        val activeFrac = bandedValues.count { it >= 2 * third }.toDouble() / bandedValues.size

        // Derived from the columns that actually carry trace, not from the detected plot
        // extent: Muse leaves dead space at the plot edges, so the extent overstates the
        // resolution the trace was really drawn at.
        //
        // The plot is a fixed pixel width for a given screen, so px/second falls as sessions
        // get longer. Once it drops below 1 a column can span more than one underlying sample,
        // which compresses the percentile tails without touching the mean.
        val pxPerSecond = samples.size / classifiedSeconds
        if (pxPerSecond < 1.0) {
            warnings += "Plot resolution is %.2f px/s; columns may merge samples, so P5 and P95 ".format(pxPerSecond) +
                "are less comparable with shorter sessions. The mean and AUC are unaffected."
        }

        val interpolated = samples.count { it.interpolated }
        if (interpolated > samples.size * 0.02) {
            warnings += "$interpolated of ${samples.size} columns had no trace pixel and were " +
                "interpolated; check the capture for an overlay or a partially scrolled card."
        }

        return AnalysisResult(
            algorithmVersion = version,
            linear = GraphScoring.scoreSet(linearValues, classifiedSeconds),
            banded = GraphScoring.scoreSet(bandedValues, classifiedSeconds),
            bounds = bounds,
            samples = samples,
            classifiedSeconds = classifiedSeconds,
            classifiedSecondsSource = classifiedSecondsSource,
            pxPerSecond = pxPerSecond,
            interpolatedColumns = interpolated,
            meanEnvelopeWidthPoints = envelopeWidth,
            fractionCalm = calmFrac,
            fractionNeutral = 1.0 - calmFrac - activeFrac,
            fractionActive = activeFrac,
            warnings = warnings
        )
    }

    // ---------------------------------------------------------------- calibration bar

    private class Bar(
        val barLeft: Int, val barRight: Int,
        val yTop: Int, val boundary1: Int, val boundary2: Int, val yBottom: Int,
        val activeColor: Int, val neutralColor: Int, val calmColor: Int
    )

    /**
     * The bar is the leftmost narrow column group holding three stacked constant-colour
     * plateaus. Detected structurally so it survives a theme change.
     */
    private fun findCalibrationBar(img: Image, roi: Rect): Bar? {
        val searchRight = roi.left + roi.width() / 3
        var best: Bar? = null
        var runStartX = -1
        var lastGood: Bar? = null

        for (x in roi.left until min(searchRight, img.width)) {
            val candidate = barAtColumn(img, x, roi)
            if (candidate != null) {
                if (runStartX < 0) runStartX = x
                lastGood = candidate
            } else if (runStartX >= 0) {
                val width = x - runStartX
                if (width in 3..40 && lastGood != null) {
                    best = Bar(
                        runStartX, x - 1,
                        lastGood.yTop, lastGood.boundary1, lastGood.boundary2, lastGood.yBottom,
                        lastGood.activeColor, lastGood.neutralColor, lastGood.calmColor
                    )
                    return best
                }
                runStartX = -1; lastGood = null
            }
        }
        return best
    }

    private fun barAtColumn(img: Image, x: Int, roi: Rect): Bar? {
        // Build vertical constant-colour plateaus.
        data class Plateau(val y0: Int, val y1: Int, val color: Int)
        val plateaus = mutableListOf<Plateau>()
        var y = roi.top
        while (y < roi.bottom) {
            val start = y
            val c0 = img.get(x, y)
            var yy = y + 1
            while (yy < roi.bottom && colorDelta(img.get(x, yy), c0) <= PLATEAU_TOLERANCE) yy++
            if (yy - start >= MIN_PLATEAU_PX) {
                plateaus += Plateau(start, yy - 1, averageColumnColor(img, x, start, yy - 1))
            }
            y = yy
        }
        // Look for three adjacent, mutually distinct, near-equal plateaus.
        var best: Bar? = null
        var bestRatio = Double.MAX_VALUE
        for (i in 0..plateaus.size - 3) {
            val a = plateaus[i]; val b = plateaus[i + 1]; val c = plateaus[i + 2]
            if (b.y0 - a.y1 > 3 || c.y0 - b.y1 > 3) continue
            if (c.y1 - a.y0 < MIN_BAR_RUN_PX) continue
            if (colorDelta(a.color, b.color) < DISTINCT_COLOR_DELTA) continue
            if (colorDelta(b.color, c.color) < DISTINCT_COLOR_DELTA) continue
            if (colorDelta(a.color, c.color) < DISTINCT_COLOR_DELTA) continue
            // Reject near-greyscale stacks (scrollbars, dividers).
            if (chroma(a.color) + chroma(b.color) + chroma(c.color) < 18) continue
            val la = a.y1 - a.y0 + 1; val lb = b.y1 - b.y0 + 1; val lc = c.y1 - c.y0 + 1
            val ratio = max(la, max(lb, lc)).toDouble() / min(la, min(lb, lc)).toDouble()
            if (ratio > MAX_BAND_LENGTH_RATIO) continue
            if (ratio < bestRatio) {
                bestRatio = ratio
                best = Bar(x, x, a.y0, b.y0, c.y0, c.y1, a.color, b.color, c.color)
            }
        }
        return best
    }

    // ---------------------------------------------------------------- gridlines, extent

    private fun findGridlineRows(img: Image, bar: Bar, roi: Rect, background: Int): List<Int> {
        val x0 = bar.barRight + 4
        val x1 = roi.right - 2
        if (x1 - x0 < 40) return emptyList()
        val rows = mutableListOf<Int>()
        for (y in bar.yTop..bar.yBottom) {
            var lit = 0
            for (x in x0 until x1) {
                if (luminance(img.get(x, y)) > luminance(background) + 45) lit++
            }
            if (lit.toDouble() / (x1 - x0) >= GRIDLINE_COVERAGE) rows += y
        }
        return rows
    }

    private fun findPlotExtent(
        img: Image, bar: Bar, roi: Rect, gridRows: List<Int>, background: Int
    ): Pair<Int, Int> {
        val left = bar.barRight + 2
        if (gridRows.isNotEmpty()) {
            // Walk the gridline rightwards and stop at the first real break, rather than taking
            // the rightmost bright pixel in the row. On a wide (unfolded) capture there is UI
            // beyond the card's right edge that clears the brightness test, and taking the
            // rightmost lit pixel overstated the plot width by 77px.
            val y = gridRows[gridRows.size / 2]
            val threshold = luminance(background) + 45
            var right = left
            var gap = 0
            for (x in left until roi.right) {
                if (luminance(img.get(x, y)) > threshold) {
                    right = x
                    gap = 0
                } else {
                    gap++
                    if (gap > 3) break
                }
            }
            if (right - left > 40) return left to right
        }
        // Fall back to the rightmost column containing anything markedly brighter than the plot.
        var right = left
        for (x in left until roi.right) {
            for (y in bar.yTop..bar.yBottom) {
                if (luminance(img.get(x, y)) > luminance(background) + 60) { right = x; break }
            }
        }
        return left to max(right, left + 40)
    }

    private fun deriveCurveThreshold(
        img: Image, bar: Bar, extent: Pair<Int, Int>, gridRows: List<Int>,
        background: Int, warnings: MutableList<String>
    ): Double {
        val gridSet = gridRows.toHashSet()
        var peak = 0.0
        for (x in extent.first..extent.second step 3) {
            for (y in bar.yTop..bar.yBottom) {
                if (y in gridSet) continue
                val l = luminance(img.get(x, y))
                if (l > peak) peak = l
            }
        }
        val gridLum = if (gridRows.isEmpty()) luminance(background)
        else luminance(img.get((extent.first + extent.second) / 2, gridRows[gridRows.size / 2]))
        if (peak - gridLum < 20) {
            warnings += "Trace and gridline brightness differ by only ${(peak - gridLum).roundToInt()}; " +
                "extraction relies on gridline row masking alone."
        }
        return max(gridLum + 12.0, peak - 30.0)
    }

    private fun findBirdStrip(img: Image, bar: Bar, extent: Pair<Int, Int>): Pair<Int, Int> {
        var top = -1; var bottom = -1
        val limit = min(bar.yBottom + 70, img.height - 1)
        for (y in bar.yBottom + 1..limit) {
            var blue = 0
            for (x in extent.first..extent.second step 4) if (isBlueDominant(img.get(x, y))) blue++
            if (blue > (extent.second - extent.first) / 4 / 6) {
                if (top < 0) top = y
                bottom = y
            }
        }
        return top to bottom
    }

    // ---------------------------------------------------------------- trace

    private fun extractTrace(
        img: Image, b: GraphBounds, gridRows: List<Int>, threshold: Double,
        classifiedSeconds: Double, warnings: MutableList<String>
    ): List<TraceSample> {
        val gridSet = gridRows.toHashSet()
        val yLo = max(0, b.yActiveTop - 6)
        val yHi = min(img.height - 1, b.yCalmBottom + 6)

        val centroid = DoubleArray(b.plotWidth) { Double.NaN }
        val mins = IntArray(b.plotWidth) { -1 }
        val maxs = IntArray(b.plotWidth) { -1 }

        for (i in 0 until b.plotWidth) {
            val x = b.plotLeft + i
            var sum = 0.0; var n = 0; var lo = Int.MAX_VALUE; var hi = Int.MIN_VALUE
            for (y in yLo..yHi) {
                if (y in gridSet) continue
                val c = img.get(x, y)
                if (isBlueDominant(c)) continue          // bird glyphs
                if (isWarmDominant(c)) continue          // recovery stars
                if (luminance(c) < threshold) continue
                sum += y; n++
                if (y < lo) lo = y
                if (y > hi) hi = y
            }
            if (n > 0) { centroid[i] = sum / n; mins[i] = lo; maxs[i] = hi }
        }

        val present = (0 until b.plotWidth).filter { !centroid[it].isNaN() }
        if (present.isEmpty()) throw GraphAnalysisException("No trace pixels found inside the plot area.")

        // Clip leading/trailing empty columns rather than extrapolating into them: Muse does not
        // plot its calibration period, and inventing values there would corrupt the mean.
        val first = present.first(); val last = present.last()
        val leadingTrimmed = first + (b.plotWidth - 1 - last)
        if (leadingTrimmed > b.plotWidth * 0.25) {
            warnings += "$leadingTrimmed of ${b.plotWidth} columns at the plot edges held no trace."
        }

        val out = ArrayList<TraceSample>(last - first + 1)
        val secondsPerColumn = classifiedSeconds / (last - first + 1).toDouble()
        for (i in first..last) {
            var yc = centroid[i]; var lo = mins[i]; var hi = maxs[i]
            var interpolated = false
            if (yc.isNaN()) {
                interpolated = true
                var a = i; while (a >= first && centroid[a].isNaN()) a--
                var c = i; while (c <= last && centroid[c].isNaN()) c++
                yc = when {
                    a < first -> centroid[c]
                    c > last -> centroid[a]
                    else -> centroid[a] + (centroid[c] - centroid[a]) * (i - a).toDouble() / (c - a)
                }
                lo = yc.roundToInt(); hi = lo
            }
            out += TraceSample(
                x = b.plotLeft + i,
                tSeconds = (i - first) * secondsPerColumn,
                yCentroid = yc, yMin = lo, yMax = hi, interpolated = interpolated
            )
        }
        return out
    }

    // ---------------------------------------------------------------- pixel helpers

    private class Image(val px: IntArray, val width: Int, val height: Int) {
        fun get(x: Int, y: Int): Int = px[y * width + x]
    }

    private fun luminance(c: Int): Double =
        (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3.0

    private fun chroma(c: Int): Int {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return max(r, max(g, b)) - min(r, min(g, b))
    }

    private fun colorDelta(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
            abs((a and 0xFF) - (b and 0xFF))
        )
    )

    private fun isBlueDominant(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val b = c and 0xFF
        return b > r + 50 && b > 110
    }

    /**
     * Recovery markers render as gold stars in the bird strip. On both reference sessions they
     * sit entirely below the plot area, so the row range already excludes them — this is a
     * cheap guard in case one is ever drawn inside the plot. The trace is near-white
     * (#F3F2F7), which is not warm-dominant, so no real sample is lost to it.
     */
    private fun isWarmDominant(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        return r > b + 60 && r > 150 && g < r - 30
    }

    private fun averageColumnColor(img: Image, x: Int, y0: Int, y1: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (y in y0..y1) {
            val c = img.get(x, y)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++
        }
        if (n == 0) return 0
        return (((r / n).toInt() and 0xFF) shl 16) or
            (((g / n).toInt() and 0xFF) shl 8) or ((b / n).toInt() and 0xFF)
    }

    private fun medianColor(img: Image, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        if (x1 <= x0 || y1 <= y0) return 0
        val lums = ArrayList<Pair<Double, Int>>()
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) { val c = img.get(x, y); lums += luminance(c) to c; x += 5 }
            y += 3
        }
        if (lums.isEmpty()) return 0
        lums.sortBy { it.first }
        return lums[lums.size / 2].second
    }
}
