package com.goral.museintegrator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.goral.museintegrator.R
import com.goral.museintegrator.analysis.GraphAnalyzers
import com.goral.museintegrator.capture.MuseAccessibilityService
import com.goral.museintegrator.data.AnalysisRow
import com.goral.museintegrator.data.MuseDatabase
import com.goral.museintegrator.data.SessionDao
import com.goral.museintegrator.data.SessionRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var trendChart: TrendChartView
    private lateinit var sessionList: RecyclerView
    private lateinit var emptyState: TextView
    private lateinit var serviceWarning: TextView
    private val adapter = SessionAdapter { row -> openDetail(row.id) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        trendChart = findViewById(R.id.trendChart)
        sessionList = findViewById(R.id.sessionList)
        emptyState = findViewById(R.id.emptyState)
        serviceWarning = findViewById(R.id.serviceWarning)

        sessionList.layoutManager = LinearLayoutManager(this)
        sessionList.adapter = adapter
        trendChart.onPointSelected = { point -> openDetail(point.sessionId) }
        serviceWarning.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        serviceWarning.visibility =
            if (accessibilityServiceEnabled(this)) View.GONE else View.VISIBLE
        reload()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.actionSettings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.actionImport -> { startActivity(Intent(this, ImportActivity::class.java)); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun openDetail(sessionId: Long) {
        startActivity(
            Intent(this, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId)
        )
    }

    private fun reload() {
        val dao = SessionDao(MuseDatabase(this))
        val sessions = dao.allSessions()
        val analyses = dao.analysesFor(GraphAnalyzers.current.version)

        val paired = sessions.mapNotNull { s -> analyses[s.id]?.let { s to it } }
        adapter.submit(paired)

        emptyState.visibility = if (sessions.isEmpty()) View.VISIBLE else View.GONE
        trendChart.setPoints(
            paired.map { (s, a) ->
                TrendChartView.Point(
                    sessionId = s.id,
                    timeMs = s.orderingTime,
                    score = a.integratedCalmScore,
                    p25 = a.p25,
                    p75 = a.p75
                )
            }
        )
    }

    companion object {
        fun accessibilityServiceEnabled(context: Context): Boolean {
            if (MuseAccessibilityService.instance != null) return true
            val expected = "${context.packageName}/${MuseAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }
    }
}

private class SessionAdapter(
    private val onClick: (SessionRow) -> Unit
) : RecyclerView.Adapter<SessionAdapter.Holder>() {

    private var items: List<Pair<SessionRow, AnalysisRow>> = emptyList()
    private val dateFormat = SimpleDateFormat("EEE d MMM yyyy, h:mm a", Locale.getDefault())

    fun submit(newItems: List<Pair<SessionRow, AnalysisRow>>) {
        items = newItems
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val score: TextView = view.findViewById(R.id.scoreText)
        val title: TextView = view.findViewById(R.id.sessionTitle)
        val subtitle: TextView = view.findViewById(R.id.sessionSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
        LayoutInflater.from(parent.context).inflate(R.layout.item_session, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val (session, analysis) = items[position]
        holder.score.text = "%.1f".format(analysis.integratedCalmScore)
        holder.title.text = dateFormat.format(Date(session.orderingTime))

        val parts = mutableListOf<String>()
        session.durationSeconds?.let { parts += "${it / 60} min" }
        session.calmPercent?.let { parts += "$it% calm" }
        session.musePoints?.let { parts += "$it pts" }
        session.birds?.let { parts += "$it birds" }
        parts += "P25 %.1f / P75 %.1f".format(analysis.p25, analysis.p75)
        if (session.sourceKind == "import") parts += "imported"
        holder.subtitle.text = parts.joinToString(" · ")

        holder.itemView.setOnClickListener { onClick(session) }
    }
}
