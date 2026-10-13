package com.danielealbano.androidremotecontrolmcp.ui.components

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

/** Label compose-ui's deprecated `ClipboardManager.setText` used; kept so copied clips look the same to other apps. */
internal const val PLAIN_TEXT_CLIP_LABEL = "plain text"

/** Copies [text] as a plain-text clip: the suspend replacement for `ClipboardManager.setText`. */
internal suspend fun Clipboard.setPlainText(text: String) {
    setClipEntry(ClipEntry(ClipData.newPlainText(PLAIN_TEXT_CLIP_LABEL, text)))
}
