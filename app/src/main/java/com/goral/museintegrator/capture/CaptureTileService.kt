package com.goral.museintegrator.capture

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast

/**
 * Manual capture, for when Muse changes its layout and automatic detection stops firing.
 *
 * Screen detection runs against another app's UI, so it will break eventually; having a manual
 * path means a Muse update costs you one extra tap rather than your whole capture pipeline.
 */
class CaptureTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = if (MuseAccessibilityService.instance != null) Tile.STATE_INACTIVE else Tile.STATE_UNAVAILABLE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val service = MuseAccessibilityService.instance
        if (service == null) {
            Toast.makeText(
                this,
                "Muse Integrator's accessibility service is not enabled.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        service.requestManualCapture()
    }
}
