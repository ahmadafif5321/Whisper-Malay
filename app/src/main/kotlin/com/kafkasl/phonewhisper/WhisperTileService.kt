package com.kafkasl.phonewhisper

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.inputmethod.InputMethodManager

/**
 * Quick Settings tile: from any app, swipe down and tap "Whisper voice" to open the
 * keyboard picker and switch to the Whisper Malay voice keyboard. Android sandboxes
 * input methods (no app can activate one directly), so this shortcut opens the system
 * picker — the fastest always-available way to switch.
 */
class WhisperTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = getString(R.string.tile_label)
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
}
