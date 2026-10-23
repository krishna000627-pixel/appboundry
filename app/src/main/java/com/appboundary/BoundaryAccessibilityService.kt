package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

class BoundaryAccessibilityService : AccessibilityService() {

    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!Prefs.isEnabled(this)) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg !in Prefs.targets(this)) return

        // Debounce: one app launch can emit several window-state events.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        startActivity(AlertActivity.createIntent(this, pkg))
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L

        @Volatile
        var instance: BoundaryAccessibilityService? = null
    }
}
