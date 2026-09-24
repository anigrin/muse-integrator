package com.goral.museintegrator.analysis

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * A scoring algorithm over a preserved source.png.
 *
 * Adding graph-v2 means implementing this and appending it to [GraphAnalyzers.all]. Nothing
 * about an existing session is mutated: a new analysis file is written alongside the old one,
 * and every historical source.png can be re-run through the new implementation.
 */
interface GraphAnalyzer {
    val version: String

    fun analyze(
        bitmap: Bitmap,
        roiIn: Rect?,
        classifiedSeconds: Double,
        classifiedSecondsSource: String
    ): AnalysisResult
}

object GraphAnalyzers {
    /** Registration order is historical; the last entry is current. */
    val all: List<GraphAnalyzer> = listOf(GraphAnalyzerV1)

    val current: GraphAnalyzer get() = all.last()

    fun byVersion(version: String): GraphAnalyzer? = all.firstOrNull { it.version == version }
}
