package com.goral.museintegrator.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Plain SQLite rather than Room.
 *
 * Room would add an annotation processor and a KSP/Kotlin version pairing that is the single
 * most common reason a fresh Android build fails on CI. The schema here is nine columns and a
 * join; the abstraction would cost more than it saves.
 */
class MuseDatabase(context: Context) : SQLiteOpenHelper(context, NAME, null, VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                dirName TEXT NOT NULL UNIQUE,
                sessionLabel TEXT,
                sessionEpochMs INTEGER,
                capturedAtEpochMs INTEGER NOT NULL,
                durationSeconds INTEGER,
                classifiedSeconds INTEGER,
                calmPercent INTEGER,
                musePoints INTEGER,
                recoveries INTEGER,
                birds INTEGER,
                activeSeconds INTEGER,
                neutralSeconds INTEGER,
                calmSeconds INTEGER,
                imageSha256 TEXT,
                imageDHash TEXT,
                sourceKind TEXT NOT NULL DEFAULT 'capture'
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idxSessionsLabel ON sessions(sessionLabel)")
        db.execSQL("CREATE INDEX idxSessionsTime ON sessions(sessionEpochMs)")
        db.execSQL(
            """
            CREATE TABLE analyses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sessionId INTEGER NOT NULL,
                algorithmVersion TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL,
                integratedCalmScore REAL,
                areaUnderCurve REAL,
                p5 REAL, p25 REAL, p50 REAL, p75 REAL, p95 REAL,
                minimum REAL, maximum REAL,
                bandedScore REAL, bandedAuc REAL,
                bandedP5 REAL, bandedP25 REAL, bandedP50 REAL, bandedP75 REAL, bandedP95 REAL,
                pxPerSecond REAL,
                sampleCount INTEGER,
                interpolatedColumns INTEGER,
                warnings TEXT,
                UNIQUE(sessionId, algorithmVersion) ON CONFLICT REPLACE,
                FOREIGN KEY(sessionId) REFERENCES sessions(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // No destructive migrations: every analysis is reproducible from the preserved
        // source.png, but the raw files themselves must never be at risk from a schema bump.
    }

    companion object {
        const val NAME = "museIntegrator.db"
        const val VERSION = 1
    }
}

data class SessionRow(
    val id: Long,
    val dirName: String,
    val sessionLabel: String?,
    val sessionEpochMs: Long?,
    val capturedAtEpochMs: Long,
    val durationSeconds: Int?,
    val classifiedSeconds: Int?,
    val calmPercent: Int?,
    val musePoints: Int?,
    val recoveries: Int?,
    val birds: Int?,
    val activeSeconds: Int?,
    val neutralSeconds: Int?,
    val calmSeconds: Int?,
    val sourceKind: String
) {
    /** Muse's own session time when we have it, else when we captured. */
    val orderingTime: Long get() = sessionEpochMs ?: capturedAtEpochMs
}

data class AnalysisRow(
    val sessionId: Long,
    val algorithmVersion: String,
    val integratedCalmScore: Double,
    val areaUnderCurve: Double,
    val p5: Double, val p25: Double, val p50: Double, val p75: Double, val p95: Double,
    val minimum: Double, val maximum: Double,
    val bandedScore: Double,
    val pxPerSecond: Double,
    val sampleCount: Int,
    val warnings: String?
)

class SessionDao(private val helper: MuseDatabase) {

    fun insertSession(values: ContentValues): Long =
        helper.writableDatabase.insertWithOnConflict(
            "sessions", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )

    fun insertAnalysis(values: ContentValues): Long =
        helper.writableDatabase.insertWithOnConflict(
            "analyses", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )

    fun existsByLabel(label: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM sessions WHERE sessionLabel = ? LIMIT 1", arrayOf(label)
        ).use { it.moveToFirst() }

    fun existsByHash(sha256: String): Boolean =
        helper.readableDatabase.rawQuery(
            "SELECT 1 FROM sessions WHERE imageSha256 = ? LIMIT 1", arrayOf(sha256)
        ).use { it.moveToFirst() }

    fun allSessions(): List<SessionRow> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM sessions ORDER BY COALESCE(sessionEpochMs, capturedAtEpochMs) DESC", null
        ).use { c -> buildList { while (c.moveToNext()) add(c.toSessionRow()) } }

    fun session(id: Long): SessionRow? =
        helper.readableDatabase.rawQuery("SELECT * FROM sessions WHERE id = ?", arrayOf(id.toString()))
            .use { if (it.moveToFirst()) it.toSessionRow() else null }

    /** The newest analysis per session for the given algorithm version. */
    fun analysesFor(version: String): Map<Long, AnalysisRow> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM analyses WHERE algorithmVersion = ?", arrayOf(version)
        ).use { c ->
            buildMap { while (c.moveToNext()) { val a = c.toAnalysisRow(); put(a.sessionId, a) } }
        }

    fun analysesForSession(sessionId: Long): List<AnalysisRow> =
        helper.readableDatabase.rawQuery(
            "SELECT * FROM analyses WHERE sessionId = ? ORDER BY algorithmVersion", arrayOf(sessionId.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(c.toAnalysisRow()) } }

    private fun Cursor.intOrNull(name: String): Int? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getInt(it) }

    private fun Cursor.longOrNull(name: String): Long? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getLong(it) }

    private fun Cursor.stringOrNull(name: String): String? =
        getColumnIndex(name).let { if (it < 0 || isNull(it)) null else getString(it) }

    private fun Cursor.toSessionRow() = SessionRow(
        id = getLong(getColumnIndexOrThrow("id")),
        dirName = getString(getColumnIndexOrThrow("dirName")),
        sessionLabel = stringOrNull("sessionLabel"),
        sessionEpochMs = longOrNull("sessionEpochMs"),
        capturedAtEpochMs = getLong(getColumnIndexOrThrow("capturedAtEpochMs")),
        durationSeconds = intOrNull("durationSeconds"),
        classifiedSeconds = intOrNull("classifiedSeconds"),
        calmPercent = intOrNull("calmPercent"),
        musePoints = intOrNull("musePoints"),
        recoveries = intOrNull("recoveries"),
        birds = intOrNull("birds"),
        activeSeconds = intOrNull("activeSeconds"),
        neutralSeconds = intOrNull("neutralSeconds"),
        calmSeconds = intOrNull("calmSeconds"),
        sourceKind = stringOrNull("sourceKind") ?: "capture"
    )

    private fun Cursor.toAnalysisRow() = AnalysisRow(
        sessionId = getLong(getColumnIndexOrThrow("sessionId")),
        algorithmVersion = getString(getColumnIndexOrThrow("algorithmVersion")),
        integratedCalmScore = getDouble(getColumnIndexOrThrow("integratedCalmScore")),
        areaUnderCurve = getDouble(getColumnIndexOrThrow("areaUnderCurve")),
        p5 = getDouble(getColumnIndexOrThrow("p5")),
        p25 = getDouble(getColumnIndexOrThrow("p25")),
        p50 = getDouble(getColumnIndexOrThrow("p50")),
        p75 = getDouble(getColumnIndexOrThrow("p75")),
        p95 = getDouble(getColumnIndexOrThrow("p95")),
        minimum = getDouble(getColumnIndexOrThrow("minimum")),
        maximum = getDouble(getColumnIndexOrThrow("maximum")),
        bandedScore = getDouble(getColumnIndexOrThrow("bandedScore")),
        pxPerSecond = getDouble(getColumnIndexOrThrow("pxPerSecond")),
        sampleCount = getInt(getColumnIndexOrThrow("sampleCount")),
        warnings = stringOrNull("warnings")
    )
}
