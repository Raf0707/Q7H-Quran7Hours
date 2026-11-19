package raf.console.quran7hours.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Clear caret/keyboard when tapping an unused area of a form. TextField and
 * other interactive children still receive their original input handlers.
 * Do not use a full-screen overlay, which would block scrolling and dialogs.
 */
@Composable
internal fun Modifier.q7hClearFocusOnBackgroundTap(): Modifier {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val source = remember { MutableInteractionSource() }
    return this.clickable(interactionSource = source, indication = null) {
        focus.clearFocus(force = true)
        keyboard?.hide()
    }
}
