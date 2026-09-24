package com.goral.museintegrator.analysis

import org.json.JSONArray
import org.json.JSONObject

/**
 * Geometry recovered from a Muse Mind-graph screenshot, in raw image pixels.
 *
 * Everything downstream is derived from these anchors, so persisting them is what makes a
 * future graph-v2 auditable: you can see exactly where v1 thought the plot was.
 */
data class GraphBounds(
    val barLeft: Int,
    val barRight: Int,
    val plotLeft: Int,
    val plotRight: Int,
    val yActiveTop: Int,
    val yActiveNeutral: Int,
    val yNeutralCalm: Int,
    val yCalmBottom: Int,
    val gridlineRows: List<Int>,
    val birdStripTop: Int,
    val birdStripBottom: Int,
    val activeColor: Int,
    val neutralColor: Int,
    val calmColor: Int
) {
    val plotWidth: Int get() = plotRight - plotLeft + 1
    val plotHeight: Int get() = yCalmBottom - yActiveTop

    fun toJson(): JSONObject = JSONObject().apply {
        put("barLeft", barLeft); put("barRight", barRight)
        put("plotLeft", plotLeft); put("plotRight", plotRight)
        put("yActiveTop", yActiveTop); put("yActiveNeutral", yActiveNeutral)
        put("yNeutralCalm", yNeutralCalm); put("yCalmBottom", yCalmBottom)
        put("plotWidth", plotWidth); put("plotHeight", plotHeight)
        put("gridlineRows", JSONArray(gridlineRows))
        put("birdStripTop", birdStripTop); put("birdStripBottom", birdStripBottom)
        put("activeColor", String.format("#%06X", activeColor and 0xFFFFFF))
        put("neutralColor", String.format("#%06X", neutralColor and 0xFFFFFF))
        put("calmColor", String.format("#%06X", calmColor and 0xFFFFFF))
    }
}

/**
 * One plotted column. [yCentroid] is the estimator used for scoring; [yMin]/[yMax] preserve the
 * stroke envelope so a later algorithm can recover extremes the centroid flattens out.
 */
data class TraceSample(
    val x: Int,
    val tSeconds: Double,
    val yCentroid: Double,
    val yMin: Int,
    val yMax: Int,
    val interpolated: Boolean
)

data class Percentiles(
    val p5: Double, val p25: Double, val p50: Double, val p75: Double, val p95: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("p5", p5); put("p25", p25); put("p50", p50); put("p75", p75); put("p95", p95)
    }
}

data class ScoreSet(
    val mean: Double,
    val auc: Double,
    val percentiles: Percentiles,
    val minimum: Double,
    val maximum: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("integratedCalmScore", mean)
        put("areaUnderCurve", auc)
        put("percentiles", percentiles.toJson())
        put("minimum", minimum); put("maximum", maximum)
    }
}

data class AnalysisResult(
    val algorithmVersion: String,
    val linear: ScoreSet,
    val banded: ScoreSet,
    val bounds: GraphBounds,
    val samples: List<TraceSample>,
    val classifiedSeconds: Double,
    val classifiedSecondsSource: String,
    val pxPerSecond: Double,
    val interpolatedColumns: Int,
    val meanEnvelopeWidthPoints: Double,
    val fractionCalm: Double,
    val fractionNeutral: Double,
    val fractionActive: Double,
    val warnings: List<String>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("algorithmVersion", algorithmVersion)
        put("linear", linear.toJson())
        put("banded", banded.toJson())
        put("bounds", bounds.toJson())
        put("classifiedSeconds", classifiedSeconds)
        put("classifiedSecondsSource", classifiedSecondsSource)
        put("pxPerSecond", pxPerSecond)
        put("sampleCount", samples.size)
        put("interpolatedColumns", interpolatedColumns)
        put("meanEnvelopeWidthPoints", meanEnvelopeWidthPoints)
        put("fractionCalm", fractionCalm)
        put("fractionNeutral", fractionNeutral)
        put("fractionActive", fractionActive)
        put("warnings", JSONArray(warnings))
    }

    /** The trace as CSV. Envelope columns are what a v2 algorithm will want. */
    fun traceCsv(): String {
        val sb = StringBuilder("xPixel,tSeconds,scoreLinear,scoreBanded,scoreEnvelopeLow,scoreEnvelopeHigh,interpolated\n")
        for (s in samples) {
            sb.append(s.x).append(',')
                .append(fmt(s.tSeconds)).append(',')
                .append(fmt(GraphScoring.linearScore(s.yCentroid, bounds))).append(',')
                .append(fmt(GraphScoring.bandedScore(s.yCentroid, bounds))).append(',')
                .append(fmt(GraphScoring.linearScore(s.yMax.toDouble(), bounds))).append(',')
                .append(fmt(GraphScoring.linearScore(s.yMin.toDouble(), bounds))).append(',')
                .append(if (s.interpolated) 1 else 0).append('\n')
        }
        return sb.toString()
    }

    private fun fmt(v: Double) = String.format("%.4f", v)
}

class GraphAnalysisException(message: String) : Exception(message)
