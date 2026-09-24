package com.goral.museintegrator.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.goral.museintegrator.R
import com.goral.museintegrator.storage.SessionPipeline
import kotlin.concurrent.thread

/**
 * Runs the same analyzer over screenshots taken before the app existed.
 *
 * These sessions are marked sourceKind=import, because they are weaker data than a live
 * capture: there is no accessibility tree, so Muse's own numbers are unavailable and the band
 * legend cannot supply the classified-time denominator. The score is still computed over the
 * plotted trace, which is the same quantity — only the AUC scaling is approximate.
 */
class ImportActivity : AppCompatActivity() {

    private lateinit var log: TextView

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) importAll(uris) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_import)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        log = findViewById(R.id.importLog)
        findViewById<Button>(R.id.pickImagesButton).setOnClickListener {
            picker.launch(arrayOf("image/*"))
        }
    }

    private fun importAll(uris: List<Uri>) {
        appendLog("Importing ${uris.size} file(s)…\n")
        thread {
            for (uri in uris) {
                val name = displayName(uri)
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: Throwable) {
                    // Not all providers grant persistable access; we only need it for this read.
                }
                val bitmap = decode(uri)
                if (bitmap == null) {
                    appendLog("  $name — could not decode\n")
                    continue
                }
                try {
                    val outcome = SessionPipeline.ingestImported(
                        context = this,
                        bitmap = bitmap,
                        sessionEpochMs = lastModified(uri),
                        durationSeconds = null,
                        classifiedSeconds = null
                    )
                    appendLog("  $name — ${outcome.userMessage}\n")
                } catch (t: Throwable) {
                    appendLog("  $name — failed: ${t.message}\n")
                } finally {
                    bitmap.recycle()
                }
            }
            appendLog("Done.\n")
        }
    }

    private fun decode(uri: Uri): Bitmap? = runCatching {
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(
                input, null,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            )
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && c.moveToFirst()) c.getString(index) else uri.lastPathSegment
        } ?: uri.lastPathSegment
    }.getOrNull() ?: "image"

    /** Best available session time for an imported file; the user can rename folders later. */
    private fun lastModified(uri: Uri): Long? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val index = c.getColumnIndex("last_modified")
            if (index >= 0 && c.moveToFirst()) c.getLong(index) else null
        }
    }.getOrNull()

    private fun appendLog(text: String) {
        runOnUiThread { log.append(text) }
    }
}
