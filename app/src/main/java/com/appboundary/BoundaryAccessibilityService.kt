package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

class BoundaryAccessibilityService : AccessibilityService() {

    private var lastTriggerAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        unlockedPackage = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // our own alert window

        if (!Prefs.isEnabled(this)) {
            unlockedPackage = null
            return
        }

        // An unlock session lasts until the user leaves the unlocked app once.
        val unlocked = unlockedPackage
        if (unlocked != null) {
            if (pkg == unlocked || isTransientWindow(pkg)) return
            unlockedPackage = null
        }

        if (pkg !in Prefs.targets(this)) return

        // Debounce: one app launch can emit several window-state events.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        startActivity(AlertActivity.createIntent(this, pkg))
    }

    /** Notification shade and keyboard windows must not count as "leaving" the app. */
    private fun isTransientWindow(pkg: String): Boolean {
        if (pkg == SYSTEM_UI) return true
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        unlockedPackage = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        unlockedPackage = null
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L
        private const val SYSTEM_UI = "com.android.systemui"

        @Volatile
        var instance: BoundaryAccessibilityService? = null

        /** Package that was unlocked via the alert; null means the next open is blocked. */
        @Volatile
        var unlockedPackage: String? = null
            private set

        fun grantSession(pkg: String?) {
            unlockedPackage = pkg
        }
    }
}
