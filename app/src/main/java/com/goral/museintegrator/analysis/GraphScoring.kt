package com.goral.museintegrator.analysis

/**
 * Pixel-row to 0..100 score, where 0 is the bottom of the Calm band and 100 the top of Active.
 *
 * Two mappings are kept because they answer different questions, and on real screenshots they
 * agree to about 0.2 points (the three bands render as near-equal thirds). Keeping both costs
 * two doubles per session and removes the need to have guessed right.
 */
object GraphScoring {

    /** Straight linear interpolation over the full plot height. Faithful to what is drawn. */
    fun linearScore(y: Double, b: GraphBounds): Double {
        val span = (b.yCalmBottom - b.yActiveTop).toDouble()
        if (span <= 0.0) return Double.NaN
        return ((b.yCalmBottom - y) / span) * 100.0
    }

    /**
     * Piecewise-linear through the band boundaries, so Calm occupies exactly 0..33.33 whatever
     * pixel height Muse gives it. Survives a Muse redesign that changes band proportions.
     */
    fun bandedScore(y: Double, b: GraphBounds): Double {
        val anchors = doubleArrayOf(
            b.yActiveTop.toDouble(), b.yActiveNeutral.toDouble(),
            b.yNeutralCalm.toDouble(), b.yCalmBottom.toDouble()
        )
        val scores = doubleArrayOf(100.0, 200.0 / 3.0, 100.0 / 3.0, 0.0)
        if (y <= anchors[0]) return 100.0
        if (y >= anchors[3]) return 0.0
        for (i in 0 until 3) {
            val y1 = anchors[i]; val y2 = anchors[i + 1]
            if (y in y1..y2) {
                if (y2 == y1) return scores[i]
                val f = (y - y1) / (y2 - y1)
                return scores[i] + f * (scores[i + 1] - scores[i])
            }
        }
        return Double.NaN
    }

    /**
     * Nearest-rank percentiles on an equally weighted sample set.
     *
     * Equal weighting is correct here: time is linear in x across the plot, so every column
     * represents the same slice of the session.
     */
    fun percentiles(valuesIn: DoubleArray): Percentiles {
        require(valuesIn.isNotEmpty()) { "no samples" }
        val v = valuesIn.clone()
        v.sort()
        fun q(p: Double): Double {
            // Linear interpolation between order statistics, matching numpy's default.
            val pos = p / 100.0 * (v.size - 1)
            val lo = kotlin.math.floor(pos).toInt()
            val hi = kotlin.math.ceil(pos).toInt()
            if (lo == hi) return v[lo]
            return v[lo] + (pos - lo) * (v[hi] - v[lo])
        }
        return Percentiles(q(5.0), q(25.0), q(50.0), q(75.0), q(95.0))
    }

    fun scoreSet(values: DoubleArray, classifiedSeconds: Double): ScoreSet {
        val mean = values.average()
        return ScoreSet(
            mean = mean,
            auc = mean * classifiedSeconds,
            percentiles = percentiles(values),
            minimum = values.min(),
            maximum = values.max()
        )
    }
}
