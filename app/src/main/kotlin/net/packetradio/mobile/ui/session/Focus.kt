package net.packetradio.mobile.ui.session

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager

/**
 * Tapping empty space clears whatever text field currently has focus,
 * dismissing its keyboard — the usual "tap away to deselect" gesture. A tap
 * that lands directly on a focusable child (a text field, a button) is
 * consumed by that child first and never reaches this handler, so it only
 * fires for taps that don't hit anything else.
 */
fun Modifier.clearFocusOnTapOutside(): Modifier = composed {
    val focusManager = LocalFocusManager.current
    pointerInput(Unit) { detectTapGestures(onTap = { focusManager.clearFocus() }) }
}

/**
 * Without this, dismissing the keyboard by any means other than tapping
 * away (the system back gesture, swiping it down) leaves the field that
 * triggered it logically focused — visually "selected" — even though
 * there's no keyboard for it anymore. Watches IME visibility and clears
 * focus the moment it goes from shown to hidden.
 */
@Composable
fun ClearFocusWhenKeyboardHides() {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focusManager = LocalFocusManager.current
    var wasVisible by remember { mutableStateOf(imeVisible) }
    LaunchedEffect(imeVisible) {
        if (wasVisible && !imeVisible) focusManager.clearFocus()
        wasVisible = imeVisible
    }
}
