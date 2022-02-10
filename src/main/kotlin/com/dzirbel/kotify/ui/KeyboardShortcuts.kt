package com.dzirbel.kotify.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.dzirbel.kotify.Settings
import com.dzirbel.kotify.ui.player.Player

/**
 * Handler for global keyboard shortcuts.
 */
object KeyboardShortcuts {
    // TODO these are invoked even when focusing a text input field
    @ExperimentalComposeUiApi
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyUp) return false

        when (event.key) {
            Key.F12 -> Settings.debugPanelOpen = !Settings.debugPanelOpen
            Key.Spacebar -> Player.togglePlayback()
            else -> return false
        }

        return true
    }
}
