package com.goral.museintegrator

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class MuseApp : Application() {

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        lateinit var appContext: Context
            private set

        fun prefs(context: Context): Prefs = Prefs(
            context.applicationContext.getSharedPreferences("museIntegrator", Context.MODE_PRIVATE)
        )
    }
}

class Prefs(private val sp: SharedPreferences) {

    /** Settable so a Muse package rename does not require a new build. */
    var musePackage: String
        get() = sp.getString(KEY_MUSE_PACKAGE, DEFAULT_MUSE_PACKAGE) ?: DEFAULT_MUSE_PACKAGE
        set(v) = sp.edit().putString(KEY_MUSE_PACKAGE, v).apply()

    var autoCaptureEnabled: Boolean
        get() = sp.getBoolean(KEY_AUTO_CAPTURE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_CAPTURE, v).apply()

    var capturePowerbands: Boolean
        get() = sp.getBoolean(KEY_POWERBANDS, true)
        set(v) = sp.edit().putBoolean(KEY_POWERBANDS, v).apply()

    var archiveTreeUri: String?
        get() = sp.getString(KEY_ARCHIVE_URI, null)
        set(v) = sp.edit().putString(KEY_ARCHIVE_URI, v).apply()

    val archiveEnabled: Boolean get() = archiveTreeUri != null

    companion object {
        const val DEFAULT_MUSE_PACKAGE = "com.interaxon.muse"
        private const val KEY_MUSE_PACKAGE = "musePackage"
        private const val KEY_AUTO_CAPTURE = "autoCaptureEnabled"
        private const val KEY_POWERBANDS = "capturePowerbands"
        private const val KEY_ARCHIVE_URI = "archiveTreeUri"
    }
}
