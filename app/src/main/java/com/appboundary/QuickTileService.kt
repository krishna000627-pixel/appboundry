package com.appboundary

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class QuickTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        Prefs.setEnabled(this, !Prefs.isEnabled(this))
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val on = Prefs.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.updateTile()
    }
}
