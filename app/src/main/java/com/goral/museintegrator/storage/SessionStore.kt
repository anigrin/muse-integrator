package com.goral.museintegrator.storage

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk layout. One directory per session, holding raw capture plus one set of analysis
 * files per algorithm version:
 *
 *   sessions/20260916T050400/
 *       source.png                  lossless, uncropped, exactly as captured
 *       sourcePowerbands.png        optional second capture, unparsed by v1
 *       viewTree.json               full accessibility tree
 *       metadata.json               everything Muse displayed as text
 *       hash.txt                    sha256 + dHash of source.png
 *       analysis-graph-v1.json
 *       trace-graph-v1.csv
 *       bounds-graph-v1.json
 *
 * source.png is never modified or replaced. A new algorithm writes new analysis-*, trace-* and
 * bounds-* files beside the old ones; nothing is overwritten across versions.
 */
object SessionStore {

    const val SOURCE_IMAGE = "source.png"
    const val VIEW_TREE = "viewTree.json"
    const val METADATA = "metadata.json"
    const val HASH = "hash.txt"

    fun root(context: Context): File =
        File(context.filesDir, "sessions").apply { mkdirs() }

    fun sessionDir(context: Context, dirName: String): File =
        File(root(context), dirName).apply { mkdirs() }

    fun allSessionDirs(context: Context): List<File> =
        root(context).listFiles()?.filter { it.isDirectory }?.sortedBy { it.name } ?: emptyList()

    fun analysisFile(dir: File, version: String) = File(dir, "analysis-$version.json")
    fun traceFile(dir: File, version: String) = File(dir, "trace-$version.csv")
    fun boundsFile(dir: File, version: String) = File(dir, "bounds-$version.json")

    /**
     * Directory name from Muse's own session time so the folder listing sorts chronologically
     * by when you meditated, not by when the screenshot happened to be taken.
     */
    fun dirNameFor(sessionEpochMs: Long?, capturedAtEpochMs: Long): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US)
        return if (sessionEpochMs != null) fmt.format(Date(sessionEpochMs))
        else "capture-" + fmt.format(Date(capturedAtEpochMs))
    }

    /** PNG at quality 100: lossless, which JPEG would not be. */
    fun writePng(file: File, bitmap: Bitmap) {
        FileOutputStream(file).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                throw IllegalStateException("PNG encode failed for ${file.name}")
            }
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Difference hash: survives re-encoding and minor rendering differences, so it catches a
     * near-duplicate capture of the same screen that sha256 would miss.
     */
    fun dHash(bitmap: Bitmap): String {
        val w = 9; val h = 8
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        var bits = 0L
        var index = 0
        for (y in 0 until h) {
            for (x in 0 until w - 1) {
                val a = px[y * w + x]; val b = px[y * w + x + 1]
                val la = ((a shr 16 and 0xFF) + (a shr 8 and 0xFF) + (a and 0xFF))
                val lb = ((b shr 16 and 0xFF) + (b shr 8 and 0xFF) + (b and 0xFF))
                if (la > lb) bits = bits or (1L shl index)
                index++
            }
        }
        return "%016x".format(bits)
    }
}
