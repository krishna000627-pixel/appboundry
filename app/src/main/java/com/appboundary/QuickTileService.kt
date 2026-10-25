package com.appboundary

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Tap = camouflage on/off. Long press = Android opens VaultActivity
 * (it declares the QS_TILE_PREFERENCES action in the manifest).
 */
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
        tile.icon = Icon.createWithResource(this, Prefs.tileIconRes(this))
        tile.label = Prefs.tileName(this) ?: getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(if (on) R.string.tile_on else R.string.tile_off)
        }
        tile.updateTile()
    }
}
