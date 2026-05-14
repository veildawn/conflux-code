package com.claudemobile.feature.chat

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Android implementation of [AccessibilityStateProvider] backed by the system
 * AccessibilityManager service.
 *
 * Provides TalkBack state detection and announcement dispatch via the
 * AccessibilityEvent framework. Announcements are rate-limited by the caller
 * (ChatViewModel) to at most once every 2 seconds during streaming.
 */
internal class AndroidAccessibilityStateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : AccessibilityStateProvider {

    private val accessibilityManager: AccessibilityManager? by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }

    override fun isTalkBackEnabled(): Boolean {
        return accessibilityManager?.isTouchExplorationEnabled == true
    }

    override fun announce(text: String) {
        val manager = accessibilityManager ?: return
        if (!manager.isEnabled) return

        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT).apply {
            this.text.add(text)
            this.className = this@AndroidAccessibilityStateProvider.javaClass.name
            this.packageName = context.packageName
        }
        manager.sendAccessibilityEvent(event)
    }
}
