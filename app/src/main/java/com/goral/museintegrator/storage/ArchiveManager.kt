package com.goral.museintegrator.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.goral.museintegrator.MuseApp
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Optional external backup: one ZIP per session in a folder you pick once.
 *
 * The point is that the raw history does not live solely inside the app's private storage,
 * where an uninstall or a wiped device would take it with them.
 */
object ArchiveManager {

    private const val TAG = "MuseArchive"

    fun hasFolder(context: Context): Boolean = MuseApp.prefs(context).archiveTreeUri != null

    fun folderName(context: Context): String? {
        val uri = MuseApp.prefs(context).archiveTreeUri ?: return null
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name
        }.getOrNull()
    }

    /**
     * Persist read/write access across reboots. Without takePersistableUriPermission the
     * folder grant dies with the process and the backup silently stops working.
     */
    fun rememberFolder(context: Context, treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        MuseApp.prefs(context).archiveTreeUri = treeUri.toString()
    }

    fun forgetFolder(context: Context) {
        MuseApp.prefs(context).archiveTreeUri = null
    }

    /**
     * Rewrites the session's ZIP from whatever is currently in its directory, so reprocessing
     * with a new algorithm refreshes the archive to include the new analysis files.
     */
    fun archiveSession(context: Context, sessionDir: File): Boolean {
        val treeUriString = MuseApp.prefs(context).archiveTreeUri ?: return false
        return try {
            val tree = DocumentFile.fromTreeUri(context, Uri.parse(treeUriString))
                ?: return false
            if (!tree.canWrite()) {
                Log.w(TAG, "archive folder is not writable")
                return false
            }
            val name = "${sessionDir.name}.zip"
            tree.findFile(name)?.delete()
            val target = tree.createFile("application/zip", name) ?: return false

            context.contentResolver.openOutputStream(target.uri, "w").use { raw ->
                if (raw == null) return false
                ZipOutputStream(raw.buffered()).use { zip ->
                    sessionDir.listFiles()?.sortedBy { it.name }?.forEach { file ->
                        if (!file.isFile) return@forEach
                        zip.putNextEntry(ZipEntry("${sessionDir.name}/${file.name}"))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            true
        } catch (t: Throwable) {
            Log.e(TAG, "archive failed for ${sessionDir.name}", t)
            false
        }
    }

    fun archiveAll(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val dirs = SessionStore.allSessionDirs(context)
        var ok = 0
        dirs.forEachIndexed { index, dir ->
            if (archiveSession(context, dir)) ok++
            onProgress(index + 1, dirs.size)
        }
        return ok
    }
}
