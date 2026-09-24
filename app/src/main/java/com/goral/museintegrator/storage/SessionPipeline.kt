package com.goral.museintegrator.storage

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Log
import com.goral.museintegrator.analysis.AnalysisResult
import com.goral.museintegrator.analysis.GraphAnalyzer
import com.goral.museintegrator.analysis.GraphAnalyzers
import com.goral.museintegrator.capture.MuseMetadata
import com.goral.museintegrator.data.MuseDatabase
import com.goral.museintegrator.data.SessionDao
import org.json.JSONObject
import java.io.File

data class IngestOutcome(
    val stored: Boolean,
    val userMessage: String,
    val sessionDirName: String?,
    val score: Double? = null
)

/**
 * Capture -> raw preservation -> analysis -> database -> archive.
 *
 * Raw files are written before analysis runs, deliberately: if the analyzer throws on an
 * unfamiliar layout, the screenshot and view tree survive and the session can be recovered by
 * a later algorithm instead of being lost.
 */
object SessionPipeline {

    private const val TAG = "MusePipeline"

    fun alreadyCaptured(context: Context, sessionLabel: String): Boolean =
        SessionDao(MuseDatabase(context)).existsByLabel(sessionLabel)

    fun ingest(
        context: Context,
        bitmap: Bitmap,
        roi: Rect?,
        metadata: MuseMetadata,
        viewTreeJson: String,
        sourceKind: String = "capture"
    ): IngestOutcome {
        val dao = SessionDao(MuseDatabase(context))
        val capturedAt = System.currentTimeMillis()
        val dirName = SessionStore.dirNameFor(metadata.sessionEpochMs, capturedAt)
        val dir = SessionStore.sessionDir(context, dirName)

        // ---- raw preservation first, unconditionally
        val sourceFile = File(dir, SessionStore.SOURCE_IMAGE)
        SessionStore.writePng(sourceFile, bitmap)
        File(dir, SessionStore.VIEW_TREE).writeText(viewTreeJson)

        val sha = SessionStore.sha256(sourceFile)
        val dHash = SessionStore.dHash(bitmap)
        File(dir, SessionStore.HASH).writeText("sha256=$sha\ndHash=$dHash\n")

        if (sourceKind == "capture" && dao.existsByHash(sha)) {
            return IngestOutcome(false, "Already captured that screen.", dirName)
        }

        val classifiedSeconds = metadata.classifiedSeconds
        val duration = metadata.durationSeconds
        // Classified time above the session duration means a band legend was misread. On both
        // reference sessions the gap is a constant 57s of headband calibration, so a negative
        // gap is a parse failure, not a real Muse state.
        val classifiedIsPlausible =
            classifiedSeconds != null && (duration == null || classifiedSeconds <= duration)

        val denominator: Double
        val denominatorSource: String
        when {
            classifiedSeconds != null && classifiedSeconds > 0 && classifiedIsPlausible -> {
                denominator = classifiedSeconds.toDouble()
                denominatorSource = "bandLegendSum"
            }
            metadata.durationSeconds != null && metadata.durationSeconds > 0 -> {
                // Fallback only. Muse does not plot its calibration period, so the header
                // duration overstates the plotted span and inflates the AUC.
                denominator = metadata.durationSeconds.toDouble()
                denominatorSource = "sessionDurationFallback"
            }
            else -> {
                denominator = 1.0
                denominatorSource = "unknown"
            }
        }

        File(dir, SessionStore.METADATA).writeText(
            JSONObject().apply {
                put("metadata", metadata.toJson())
                put("capturedAtEpochMs", capturedAt)
                put("sourceKind", sourceKind)
                put("imageSha256", sha)
                put("imageDHash", dHash)
                put("imageWidth", bitmap.width)
                put("imageHeight", bitmap.height)
                put("regionOfInterest", roi?.let {
                    JSONObject().apply {
                        put("left", it.left); put("top", it.top)
                        put("right", it.right); put("bottom", it.bottom)
                    }
                } ?: JSONObject.NULL)
                put("rawTexts", org.json.JSONArray(metadata.rawTexts))
            }.toString(2)
        )

        val analyzer = GraphAnalyzers.current
        val result = try {
            analyzer.analyze(bitmap, roi, denominator, denominatorSource)
        } catch (t: Throwable) {
            Log.e(TAG, "analysis failed for $dirName", t)
            // Raw files are already on disk, so this session is recoverable later.
            return IngestOutcome(false, "Captured, but analysis failed: ${t.message}", dirName)
        }

        writeAnalysisFiles(dir, result)

        val sessionId = dao.insertSession(ContentValues().apply {
            put("dirName", dirName)
            put("sessionLabel", metadata.sessionLabel)
            metadata.sessionEpochMs?.let { put("sessionEpochMs", it) }
            put("capturedAtEpochMs", capturedAt)
            metadata.durationSeconds?.let { put("durationSeconds", it) }
            metadata.classifiedSeconds?.let { put("classifiedSeconds", it) }
            metadata.calmPercent?.let { put("calmPercent", it) }
            metadata.musePoints?.let { put("musePoints", it) }
            metadata.recoveries?.let { put("recoveries", it) }
            metadata.birds?.let { put("birds", it) }
            metadata.activeSeconds?.let { put("activeSeconds", it) }
            metadata.neutralSeconds?.let { put("neutralSeconds", it) }
            metadata.calmSeconds?.let { put("calmSeconds", it) }
            put("imageSha256", sha)
            put("imageDHash", dHash)
            put("sourceKind", sourceKind)
        })

        insertAnalysisRow(dao, sessionId, result)

        if (ArchiveManager.hasFolder(context)) ArchiveManager.archiveSession(context, dir)

        val score = result.linear.mean
        return IngestOutcome(
            stored = true,
            userMessage = "Calm Score %.1f  (P25 %.1f / P75 %.1f)".format(
                score, result.linear.percentiles.p25, result.linear.percentiles.p75
            ),
            sessionDirName = dirName,
            score = score
        )
    }

    fun storeAuxiliaryImage(context: Context, dirName: String, fileName: String, bitmap: Bitmap) {
        val dir = SessionStore.sessionDir(context, dirName)
        SessionStore.writePng(File(dir, fileName), bitmap)
        if (ArchiveManager.hasFolder(context)) ArchiveManager.archiveSession(context, dir)
    }

    /**
     * Re-runs an analyzer over every preserved source.png.
     *
     * This is the payoff of never mutating raw capture: a better algorithm can be applied to
     * the entire history without recapturing anything, and the old analysis files stay on disk
     * beside the new ones so the two can be compared.
     */
    fun reprocessAll(
        context: Context,
        analyzer: GraphAnalyzer = GraphAnalyzers.current,
        onProgress: (Int, Int, String) -> Unit = { _, _, _ -> }
    ): Pair<Int, Int> {
        val dao = SessionDao(MuseDatabase(context))
        val sessions = dao.allSessions()
        var ok = 0
        sessions.forEachIndexed { index, row ->
            onProgress(index + 1, sessions.size, row.dirName)
            val dir = File(SessionStore.root(context), row.dirName)
            val source = File(dir, SessionStore.SOURCE_IMAGE)
            if (!source.exists()) return@forEachIndexed

            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeFile(source.absolutePath, options) ?: return@forEachIndexed
            try {
                val roi = readRegionOfInterest(dir)
                val denominator = row.classifiedSeconds?.toDouble()
                    ?: row.durationSeconds?.toDouble() ?: 1.0
                val source2 = if (row.classifiedSeconds != null) "bandLegendSum" else "sessionDurationFallback"
                val result = analyzer.analyze(bitmap, roi, denominator, source2)
                writeAnalysisFiles(dir, result)
                insertAnalysisRow(dao, row.id, result)
                if (ArchiveManager.hasFolder(context)) ArchiveManager.archiveSession(context, dir)
                ok++
            } catch (t: Throwable) {
                Log.w(TAG, "reprocess failed for ${row.dirName}", t)
            } finally {
                bitmap.recycle()
            }
        }
        return ok to sessions.size
    }

    private fun readRegionOfInterest(dir: File): Rect? = runCatching {
        val json = JSONObject(File(dir, SessionStore.METADATA).readText())
        val r = json.optJSONObject("regionOfInterest") ?: return null
        Rect(r.getInt("left"), r.getInt("top"), r.getInt("right"), r.getInt("bottom"))
    }.getOrNull()

    private fun writeAnalysisFiles(dir: File, result: AnalysisResult) {
        SessionStore.analysisFile(dir, result.algorithmVersion).writeText(result.toJson().toString(2))
        SessionStore.traceFile(dir, result.algorithmVersion).writeText(result.traceCsv())
        SessionStore.boundsFile(dir, result.algorithmVersion).writeText(result.bounds.toJson().toString(2))
    }

    private fun insertAnalysisRow(dao: SessionDao, sessionId: Long, result: AnalysisResult) {
        dao.insertAnalysis(ContentValues().apply {
            put("sessionId", sessionId)
            put("algorithmVersion", result.algorithmVersion)
            put("createdAtEpochMs", System.currentTimeMillis())
            put("integratedCalmScore", result.linear.mean)
            put("areaUnderCurve", result.linear.auc)
            put("p5", result.linear.percentiles.p5)
            put("p25", result.linear.percentiles.p25)
            put("p50", result.linear.percentiles.p50)
            put("p75", result.linear.percentiles.p75)
            put("p95", result.linear.percentiles.p95)
            put("minimum", result.linear.minimum)
            put("maximum", result.linear.maximum)
            put("bandedScore", result.banded.mean)
            put("bandedAuc", result.banded.auc)
            put("bandedP5", result.banded.percentiles.p5)
            put("bandedP25", result.banded.percentiles.p25)
            put("bandedP50", result.banded.percentiles.p50)
            put("bandedP75", result.banded.percentiles.p75)
            put("bandedP95", result.banded.percentiles.p95)
            put("pxPerSecond", result.pxPerSecond)
            put("sampleCount", result.samples.size)
            put("interpolatedColumns", result.interpolatedColumns)
            put("warnings", result.warnings.joinToString("; "))
        })
    }

    /** Import path for screenshots taken before the app existed. */
    fun ingestImported(
        context: Context,
        bitmap: Bitmap,
        sessionEpochMs: Long?,
        durationSeconds: Int?,
        classifiedSeconds: Int?
    ): IngestOutcome {
        // classifiedSeconds is what the analyzer normalises by; splitting it across the three
        // band fields is how MuseMetadata carries it, so put it all in calmSeconds with the
        // other two zeroed rather than inventing a band breakdown we cannot know.
        val metadata = MuseMetadata(
            sessionLabel = sessionEpochMs?.let { "imported-$it" },
            sessionEpochMs = sessionEpochMs,
            durationSeconds = durationSeconds,
            calmPercent = null, musePoints = null, recoveries = null, birds = null,
            activeSeconds = if (classifiedSeconds != null) 0 else null,
            neutralSeconds = if (classifiedSeconds != null) 0 else null,
            calmSeconds = classifiedSeconds,
            rawTexts = emptyList()
        )
        return ingest(
            context = context,
            bitmap = bitmap,
            roi = null,
            metadata = metadata,
            viewTreeJson = JSONObject().put("note", "imported screenshot; no accessibility tree").toString(2),
            sourceKind = "import"
        )
    }
}
