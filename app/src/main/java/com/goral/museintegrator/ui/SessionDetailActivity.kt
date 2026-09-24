package com.goral.museintegrator.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import com.goral.museintegrator.R
import com.goral.museintegrator.analysis.GraphAnalyzers
import com.goral.museintegrator.data.MuseDatabase
import com.goral.museintegrator.data.SessionDao
import com.goral.museintegrator.storage.SessionStore
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SessionDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_detail)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
        val dao = SessionDao(MuseDatabase(this))
        val session = dao.session(sessionId)
        if (session == null) { finish(); return }

        val dir = File(SessionStore.root(this), session.dirName)
        val analyses = dao.analysesForSession(sessionId)
        val current = analyses.firstOrNull { it.algorithmVersion == GraphAnalyzers.current.version }
            ?: analyses.lastOrNull()

        val dateFormat = SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.getDefault())
        title = dateFormat.format(Date(session.orderingTime))

        findViewById<TextView>(R.id.headlineScore).text =
            current?.let { "%.2f".format(it.integratedCalmScore) } ?: "—"
        findViewById<TextView>(R.id.headlineCaption).text = buildString {
            append("Integrated Calm Score · lower is calmer")
            current?.let { append("\nAUC %.0f score·seconds".format(it.areaUnderCurve)) }
        }

        loadTrace(dir, current?.algorithmVersion)
        findViewById<TextView>(R.id.detailBody).text = buildDetailText(session, analyses, dir)

        findViewById<Button>(R.id.shareZipButton).setOnClickListener { shareZip(dir) }
    }

    private fun loadTrace(dir: File, version: String?) {
        val chart = findViewById<TraceChartView>(R.id.traceChart)
        if (version == null) return
        val csv = SessionStore.traceFile(dir, version)
        if (!csv.exists()) return

        val values = mutableListOf<Double>()
        val low = mutableListOf<Double>()
        val high = mutableListOf<Double>()
        var lastT = 0.0
        csv.forEachLine { line ->
            if (line.startsWith("xPixel")) return@forEachLine
            val parts = line.split(',')
            if (parts.size < 6) return@forEachLine
            lastT = parts[1].toDoubleOrNull() ?: lastT
            parts[2].toDoubleOrNull()?.let { values += it }
            parts[4].toDoubleOrNull()?.let { low += it }
            parts[5].toDoubleOrNull()?.let { high += it }
        }
        if (values.isEmpty()) return

        val analysis = runCatching {
            JSONObject(SessionStore.analysisFile(dir, version).readText())
                .getJSONObject("linear")
        }.getOrNull()
        val percentiles = analysis?.optJSONObject("percentiles")

        chart.setTrace(
            values = values.toDoubleArray(),
            envelopeLow = low.toDoubleArray(),
            envelopeHigh = high.toDoubleArray(),
            p25 = percentiles?.optDouble("p25") ?: Double.NaN,
            p75 = percentiles?.optDouble("p75") ?: Double.NaN,
            mean = analysis?.optDouble("integratedCalmScore") ?: Double.NaN,
            durationSeconds = lastT
        )
    }

    private fun buildDetailText(
        session: com.goral.museintegrator.data.SessionRow,
        analyses: List<com.goral.museintegrator.data.AnalysisRow>,
        dir: File
    ): String = buildString {
        appendLine("MUSE REPORTED")
        appendLine("  session       ${session.sessionLabel ?: "—"}")
        appendLine("  duration      ${session.durationSeconds?.let { fmt(it) } ?: "—"}")
        appendLine("  classified    ${session.classifiedSeconds?.let { fmt(it) } ?: "—"}")
        session.durationSeconds?.let { d ->
            session.classifiedSeconds?.let { c ->
                appendLine("  uncounted     ${fmt((d - c).coerceAtLeast(0))}  (calibration)")
            }
        }
        appendLine("  calm          ${session.calmPercent?.let { "$it%" } ?: "—"}")
        appendLine("  active        ${session.activeSeconds?.let { fmt(it) } ?: "—"}")
        appendLine("  neutral       ${session.neutralSeconds?.let { fmt(it) } ?: "—"}")
        appendLine("  calm time     ${session.calmSeconds?.let { fmt(it) } ?: "—"}")
        appendLine("  muse points   ${session.musePoints ?: "—"}")
        appendLine("  recoveries    ${session.recoveries ?: "—"}")
        appendLine("  birds         ${session.birds ?: "—"}")
        appendLine()

        for (a in analyses) {
            appendLine("ANALYSIS ${a.algorithmVersion}")
            appendLine("  score         %.3f".format(a.integratedCalmScore))
            appendLine("  auc           %.0f".format(a.areaUnderCurve))
            appendLine("  P5  / P25     %.2f / %.2f".format(a.p5, a.p25))
            appendLine("  median        %.2f".format(a.p50))
            appendLine("  P75 / P95     %.2f / %.2f".format(a.p75, a.p95))
            appendLine("  min / max     %.2f / %.2f".format(a.minimum, a.maximum))
            appendLine("  band-anchored %.3f".format(a.bandedScore))
            appendLine("  samples       ${a.sampleCount} at %.3f px/s".format(a.pxPerSecond))
            if (!a.warnings.isNullOrBlank()) appendLine("  warnings      ${a.warnings}")
            appendLine()
        }

        appendLine("RAW FILES")
        dir.listFiles()?.sortedBy { it.name }?.forEach {
            appendLine("  %-28s %6d KB".format(it.name, it.length() / 1024))
        }
    }

    private fun fmt(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)

    private fun shareZip(dir: File) {
        try {
            val outDir = File(cacheDir, "exports").apply { mkdirs() }
            val zipFile = File(outDir, "${dir.name}.zip")
            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                dir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    if (!file.isFile) return@forEach
                    zip.putNextEntry(ZipEntry("${dir.name}/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", zipFile)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    "Export session"
                )
            )
        } catch (t: Throwable) {
            Toast.makeText(this, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_SESSION_ID = "sessionId"
    }
}
