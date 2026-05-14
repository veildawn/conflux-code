package com.claudemobile.feature.chat

import android.content.ClipData
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import android.content.ClipboardManager as SystemClipboardManager

/**
 * Android implementation of [ClipboardManager] backed by the system clipboard service.
 */
internal class AndroidClipboardManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : ClipboardManager {

    override fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as SystemClipboardManager
        val label = context.getString(R.string.chat_clipboard_label)
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
    }
}
