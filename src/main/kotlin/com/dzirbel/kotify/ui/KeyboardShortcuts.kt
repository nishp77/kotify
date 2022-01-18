package com.dzirbel.kotify.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.dzirbel.kotify.Settings

/**
 * Handler for global keyboard shortcuts.
 */
object KeyboardShortcuts {
    // TODO these are invoked even when focusing a text input field
    @ExperimentalComposeUiApi
    fun handle(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyUp) return false

        when (event.key) {
            Key.F12 -> Settings.mutate { copy(debugPanelOpen = !debugPanelOpen) }
            Key.Spacebar -> Player.togglePlayback()
            else -> return false
        }

        return true
    }
}
