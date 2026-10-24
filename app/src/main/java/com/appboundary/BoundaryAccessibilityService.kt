package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager

class BoundaryAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val coverTimeout = Runnable { hideCover() }
    private var cover: View? = null
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
            hideCover()
            return
        }

        // An unlock session lasts until the user leaves the unlocked app once.
        val unlocked = unlockedPackage
        if (unlocked != null) {
            if (pkg == unlocked || isTransientWindow(pkg)) return
            unlockedPackage = null
        }

        if (pkg !in Prefs.targets(this)) return

        // Never stack a second alert: skip if one is on screen or was just dismissed.
        val now = SystemClock.elapsedRealtime()
        if (AlertActivity.isShowing || now < cooldownUntil) return

        // Debounce: one app launch can emit several window-state events.
        if (now - lastTriggerAt < DEBOUNCE_MS) return
        lastTriggerAt = now

        // 1) Cover the screen right away (a plain view is much faster than starting an activity),
        // 2) then bring up the alert activity, which removes the cover once it is on screen.
        showCover()
        try {
            startActivity(AlertActivity.createIntent(this, pkg))
        } catch (e: Exception) {
            hideCover()
        }
    }

    private fun showCover() {
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, COVER_TIMEOUT_MS) // safety net: never get stuck on black
        if (cover != null) return

        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = View(this).apply { setBackgroundColor(Color.BLACK) }
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(view, params)
            cover = view
        } catch (e: Exception) {
            // No cover available: the alert activity alone still works.
        }
    }

    /** Called by AlertActivity once it is visible (it is black too, so the swap is invisible). */
    fun hideCover() {
        handler.removeCallbacks(coverTimeout)
        val view = cover ?: return
        cover = null
        try {
            getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (e: Exception) {
            // Already gone.
        }
    }

    /** Notification shade and keyboard windows must not count as "leaving" the app. */
    private fun isTransientWindow(pkg: String): Boolean {
        if (pkg == SYSTEM_UI) return true
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        return imm.enabledInputMethodList.any { it.packageName == pkg }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        hideCover()
        instance = null
        unlockedPackage = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideCover()
        instance = null
        unlockedPackage = null
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 1500L
        private const val COOLDOWN_MS = 1000L
        private const val COVER_TIMEOUT_MS = 2500L
        private const val SYSTEM_UI = "com.android.systemui"

        @Volatile
        var instance: BoundaryAccessibilityService? = null

        /** Package that was unlocked via the alert; null means the next open is blocked. */
        @Volatile
        var unlockedPackage: String? = null
            private set

        @Volatile
        private var cooldownUntil = 0L

        fun grantSession(pkg: String?) {
            unlockedPackage = pkg
        }

        /** Called when the alert is dismissed with OK/Back: ignore stray events for a moment. */
        fun markDismissed() {
            cooldownUntil = SystemClock.elapsedRealtime() + COOLDOWN_MS
        }
    }
}
