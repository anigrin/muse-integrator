package com.goral.museintegrator.capture

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

/**
 * Everything Muse displays as text, lifted out of the accessibility tree.
 *
 * [classifiedSeconds] is the one that matters for scoring. Muse plots only the classified part
 * of a session — the headband calibration period at the start is absent from the graph — so
 * normalising by [durationSeconds] would penalise sessions with a slow setup. The band times
 * sum to exactly the plotted span, which makes them the correct denominator and makes the
 * Integrated Calm Score directly comparable with Muse's own Calm %.
 */
data class MuseMetadata(
    val sessionLabel: String?,
    val sessionEpochMs: Long?,
    val durationSeconds: Int?,
    val calmPercent: Int?,
    val musePoints: Int?,
    val recoveries: Int?,
    val birds: Int?,
    val activeSeconds: Int?,
    val neutralSeconds: Int?,
    val calmSeconds: Int?,
    val rawTexts: List<String>
) {
    val classifiedSeconds: Int?
        get() {
            val a = activeSeconds; val n = neutralSeconds; val c = calmSeconds
            return if (a != null && n != null && c != null) a + n + c else null
        }

    /** Seconds Muse recorded but never classified — the calibration period, typically. */
    val unclassifiedSeconds: Int?
        get() {
            val d = durationSeconds; val c = classifiedSeconds
            return if (d != null && c != null) (d - c).coerceAtLeast(0) else null
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("sessionLabel", sessionLabel ?: JSONObject.NULL)
        put("sessionEpochMs", sessionEpochMs ?: JSONObject.NULL)
        put("durationSeconds", durationSeconds ?: JSONObject.NULL)
        put("calmPercent", calmPercent ?: JSONObject.NULL)
        put("musePoints", musePoints ?: JSONObject.NULL)
        put("recoveries", recoveries ?: JSONObject.NULL)
        put("birds", birds ?: JSONObject.NULL)
        put("activeSeconds", activeSeconds ?: JSONObject.NULL)
        put("neutralSeconds", neutralSeconds ?: JSONObject.NULL)
        put("calmSeconds", calmSeconds ?: JSONObject.NULL)
        put("classifiedSeconds", classifiedSeconds ?: JSONObject.NULL)
        put("unclassifiedSeconds", unclassifiedSeconds ?: JSONObject.NULL)
    }
}

object MetadataParser {

    private val DURATION = Regex("(\\d+)\\s*(hrs?|hours?|h|mins?|minutes?|m|secs?|seconds?|s)(?![a-z])", RegexOption.IGNORE_CASE)
    private val CLOCK = Regex("^\\s*(\\d{1,2}):(\\d{2})\\s*(am|pm)\\s*$", RegexOption.IGNORE_CASE)
    private val MONTH_DAY = Regex("^\\s*([A-Za-z]{3,9})\\s+(\\d{1,2})\\s*$")
    private val YEAR = Regex("^\\s*(\\d{4})\\s*$")
    private val PERCENT = Regex("^\\s*(\\d{1,3})\\s*%\\s*$")
    private val INTEGER = Regex("^\\s*([\\d,]{1,9})\\s*$")

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december"
    )

    /** Signature of the Muse session results screen. */
    fun looksLikeResultsScreen(nodes: List<FlatNode>): Boolean {
        val texts = nodes.map { it.text.lowercase(Locale.US) }
        val hasSessionHeader = texts.any { it.contains("session") }
        val hasMetrics = texts.any { it.contains("muse points") } ||
            (texts.any { it == "birds" } && texts.any { it == "recoveries" })
        return hasSessionHeader && hasMetrics
    }

    /** The band legend only renders when the Mind card is expanded — a reliable expansion probe. */
    fun mindGraphIsExpanded(nodes: List<FlatNode>): Boolean {
        val texts = nodes.map { it.text.trim().lowercase(Locale.US) }.toSet()
        return texts.contains("active") && texts.contains("neutral") && texts.contains("calm")
    }

    fun parseDurationSeconds(s: String): Int? {
        var total = 0
        var found = false
        for (m in DURATION.findAll(s)) {
            val n = m.groupValues[1].toIntOrNull() ?: continue
            val u = m.groupValues[2].lowercase(Locale.US)
            found = true
            total += when {
                u.startsWith("h") -> n * 3600
                u.startsWith("m") -> n * 60
                else -> n
            }
        }
        return if (found) total else null
    }

    fun parse(nodes: List<FlatNode>): MuseMetadata {
        val texts = nodes.map { it.text }.filter { it.isNotBlank() }

        // ---- header: "5:04am, September 16, 2026, 10 mins"
        var label: String? = null
        var epochMs: Long? = null
        var duration: Int? = null
        for (t in texts) {
            val parts = t.split(",").map { it.trim() }
            if (parts.size < 3) continue
            val clock = parts.firstOrNull { CLOCK.matches(it) } ?: continue
            val monthDay = parts.firstOrNull { MONTH_DAY.matches(it) } ?: continue
            val year = parts.firstOrNull { YEAR.matches(it) } ?: continue
            val durPart = parts.firstOrNull { parseDurationSeconds(it) != null && !YEAR.matches(it) }
            label = t
            duration = durPart?.let { parseDurationSeconds(it) }
            epochMs = toEpochMs(clock, monthDay, year)
            break
        }

        // ---- "98%" on the Mind card header
        val calmPercent = texts.firstNotNullOfOrNull { t ->
            PERCENT.find(t)?.groupValues?.get(1)?.toIntOrNull()
        }

        // ---- labelled counters: value sits directly above its label
        val musePoints = numberAboveLabel(nodes, "muse points")
        val recoveries = numberAboveLabel(nodes, "recoveries")
        val birds = numberAboveLabel(nodes, "birds")

        // ---- band legend: value sits directly below its label
        val activeSeconds = durationBelowLabel(nodes, "active")
        val neutralSeconds = durationBelowLabel(nodes, "neutral")
        val calmSeconds = durationBelowLabel(nodes, "calm")

        return MuseMetadata(
            sessionLabel = label,
            sessionEpochMs = epochMs,
            durationSeconds = duration,
            calmPercent = calmPercent,
            musePoints = musePoints,
            recoveries = recoveries,
            birds = birds,
            activeSeconds = activeSeconds,
            neutralSeconds = neutralSeconds,
            calmSeconds = calmSeconds,
            rawTexts = texts
        )
    }

    private fun toEpochMs(clock: String, monthDay: String, year: String): Long? {
        val cm = CLOCK.find(clock) ?: return null
        val md = MONTH_DAY.find(monthDay) ?: return null
        val y = YEAR.find(year)?.groupValues?.get(1)?.toIntOrNull() ?: return null

        var hour = cm.groupValues[1].toIntOrNull() ?: return null
        val minute = cm.groupValues[2].toIntOrNull() ?: return null
        val meridiem = cm.groupValues[3].lowercase(Locale.US)
        if (meridiem == "pm" && hour != 12) hour += 12
        if (meridiem == "am" && hour == 12) hour = 0

        val monthName = md.groupValues[1].lowercase(Locale.US)
        val month = MONTHS.indexOfFirst { it.startsWith(monthName.take(3)) }
        if (month < 0) return null
        val day = md.groupValues[2].toIntOrNull() ?: return null

        // Muse renders session times in the device's local zone, so the default calendar is right.
        return Calendar.getInstance().apply {
            clear()
            set(y, month, day, hour, minute, 0)
        }.timeInMillis
    }

    /**
     * Counters render as value-over-label. Match on horizontal alignment and pick the nearest
     * numeric node above, so the three circles can be reordered without breaking this.
     */
    private fun numberAboveLabel(nodes: List<FlatNode>, label: String): Int? {
        val labelNode = nodes.firstOrNull { it.text.trim().equals(label, ignoreCase = true) } ?: return null
        return nodes
            .filter { INTEGER.matches(it.text) }
            .filter { it.centerY < labelNode.centerY && labelNode.centerY - it.centerY < 260 }
            .filter { abs(it.centerX - labelNode.centerX) < 140 }
            .minByOrNull { abs(it.centerX - labelNode.centerX) + (labelNode.centerY - it.centerY) }
            ?.text?.replace(",", "")?.trim()?.toIntOrNull()
    }

    /**
     * The band legend renders as label-over-duration. "Calm" also appears in the card header,
     * so require an actual duration node below and take the closest pairing.
     */
    private fun durationBelowLabel(nodes: List<FlatNode>, label: String): Int? {
        val candidates = nodes.filter { it.text.trim().equals(label, ignoreCase = true) }
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE
        for (labelNode in candidates) {
            val match = nodes
                .filter { it.centerY > labelNode.centerY && it.centerY - labelNode.centerY < 200 }
                .filter { abs(it.centerX - labelNode.centerX) < 160 }
                .filter { !PERCENT.matches(it.text) && parseDurationSeconds(it.text) != null }
                .minByOrNull { it.centerY - labelNode.centerY }
                ?: continue
            val distance = match.centerY - labelNode.centerY
            if (distance < bestDistance) {
                bestDistance = distance
                best = parseDurationSeconds(match.text)
            }
        }
        return best
    }
}
