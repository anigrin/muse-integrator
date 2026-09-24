package com.goral.museintegrator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.goral.museintegrator.MuseApp
import com.goral.museintegrator.Prefs
import com.goral.museintegrator.R
import com.goral.museintegrator.analysis.GraphAnalyzers
import com.goral.museintegrator.capture.MuseAccessibilityService
import com.goral.museintegrator.data.MuseDatabase
import com.goral.museintegrator.data.SessionDao
import com.goral.museintegrator.storage.ArchiveManager
import com.goral.museintegrator.storage.SessionPipeline
import kotlin.concurrent.thread

class SettingsActivity : AppCompatActivity() {

    private lateinit var archiveStatus: TextView
    private lateinit var algorithmStatus: TextView

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val uri = result.data?.data ?: return@registerForActivityResult
        try {
            ArchiveManager.rememberFolder(this, uri)
            refreshArchiveStatus()
            thread {
                val count = ArchiveManager.archiveAll(this)
                runOnUiThread {
                    Toast.makeText(this, "Archived $count sessions.", Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            Toast.makeText(this, "Could not keep access to that folder: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        val prefs: Prefs = MuseApp.prefs(this)
        archiveStatus = findViewById(R.id.archiveStatus)
        algorithmStatus = findViewById(R.id.algorithmStatus)

        findViewById<Button>(R.id.enableServiceButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(
                this,
                "If the toggle is greyed out, open App info for Muse Integrator, " +
                    "tap the menu and allow restricted settings first.",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<MaterialSwitch>(R.id.autoCaptureSwitch).apply {
            isChecked = prefs.autoCaptureEnabled
            setOnCheckedChangeListener { _, checked -> prefs.autoCaptureEnabled = checked }
        }
        findViewById<MaterialSwitch>(R.id.powerbandsSwitch).apply {
            isChecked = prefs.capturePowerbands
            setOnCheckedChangeListener { _, checked -> prefs.capturePowerbands = checked }
        }

        findViewById<Button>(R.id.chooseFolderButton).setOnClickListener {
            folderPicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
            )
        }

        findViewById<Button>(R.id.archiveAllButton).setOnClickListener {
            if (!ArchiveManager.hasFolder(this)) {
                Toast.makeText(this, "Choose an archive folder first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            thread {
                val count = ArchiveManager.archiveAll(this)
                runOnUiThread { Toast.makeText(this, "Archived $count sessions.", Toast.LENGTH_LONG).show() }
            }
        }

        findViewById<Button>(R.id.reprocessButton).setOnClickListener { confirmReprocess() }

        findViewById<TextView>(R.id.musePackageHint).text = buildString {
            append("Only events from this package trigger a capture. ")
            val seen = MuseAccessibilityService.lastSeenPackage
            if (seen != null) {
                append("Last app seen by the service: $seen")
                if (seen != prefs.musePackage) append("  — open Muse, then check this again.")
            } else {
                append("No app seen yet. Open Muse with the service enabled, then come back.")
            }
        }
        findViewById<EditText>(R.id.musePackageField).setText(prefs.musePackage)
        findViewById<Button>(R.id.savePackageButton).setOnClickListener {
            val value = findViewById<EditText>(R.id.musePackageField).text.toString().trim()
            if (value.isEmpty()) {
                Toast.makeText(this, "Package name cannot be empty.", Toast.LENGTH_SHORT).show()
            } else {
                prefs.musePackage = value
                Toast.makeText(
                    this,
                    "Saved. Turn the accessibility service off and on again to apply it.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        refreshArchiveStatus()
        refreshAlgorithmStatus()
    }

    private fun confirmReprocess() {
        val dao = SessionDao(MuseDatabase(this))
        val total = dao.allSessions().size
        AlertDialog.Builder(this)
            .setTitle("Reprocess $total sessions?")
            .setMessage(
                "Re-runs ${GraphAnalyzers.current.version} over every preserved source.png. " +
                    "Original screenshots are never modified, and existing analysis files for " +
                    "other algorithm versions are left in place."
            )
            .setPositiveButton("Reprocess") { _, _ -> runReprocess(total) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runReprocess(total: Int) {
        val progress = AlertDialog.Builder(this)
            .setTitle("Reprocessing")
            .setMessage("0 / $total")
            .setCancelable(false)
            .create()
        progress.show()
        thread {
            val (ok, count) = SessionPipeline.reprocessAll(this) { index, size, _ ->
                runOnUiThread { progress.setMessage("$index / $size") }
            }
            runOnUiThread {
                progress.dismiss()
                refreshAlgorithmStatus()
                Toast.makeText(
                    this, "Reprocessed $ok of $count sessions.", Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun refreshArchiveStatus() {
        archiveStatus.text = if (ArchiveManager.hasFolder(this)) {
            "Backing up to: ${ArchiveManager.folderName(this) ?: "chosen folder"}\n" +
                "Each session is written as its own ZIP containing every raw and analysis file."
        } else {
            "No folder chosen. Session history exists only inside this app until you pick one."
        }
    }

    private fun refreshAlgorithmStatus() {
        val dao = SessionDao(MuseDatabase(this))
        val version = GraphAnalyzers.current.version
        val scored = dao.analysesFor(version).size
        val total = dao.allSessions().size
        algorithmStatus.text =
            "Current: $version\nScored with it: $scored of $total sessions\n" +
                "Available: ${GraphAnalyzers.all.joinToString(", ") { it.version }}"
    }
}
