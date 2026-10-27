package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
    private val coverTimeout = Runnable {
        hideCover()
        // The alert never came up: do not leave the blocked app open, send the user home instead.
        val front = lastPkg
        if (!AlertActivity.isShowing && Prefs.isEnabled(this) && front != null &&
            front in Prefs.targets(this) && front != unlockedPackage
        ) {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }
    private var cover: View? = null
    private var lastTriggerAt = 0L

    /** Last real app that came to the front (our own alert, the keyboard and the shade don't count). */
    private var lastPkg: String? = null
    private var relaunchCount = 0
    private var relaunchWindowStart = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        unlockedPackage = null
        registerPackageReceiver()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // our own alert window

        // Check the blocked list first: asking the system about keyboards is slow and a blocked
        // app never needs it, so the cover can go up sooner.
        val isTarget = pkg in Prefs.targets(this)
        if (isTarget || !isTransientWindow(pkg)) lastPkg = pkg

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

        if (!isTarget) return

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

    /**
     * The alert closed by itself without OK or an unlock. This happens when a blocked app is
     * opened from a link: Android removes the alert as soon as the app's own screen opens on
     * top of it, which used to leave the app wide open. If that app is still in front, block it again.
     */
    fun onAlertLost(target: String?) {
        if (target == null) return
        lastTriggerAt = 0L // the old debounce must not swallow the next event
        // Cover the app at once; waiting for the delay below used to show it for a moment.
        if (Prefs.isEnabled(this) && target in Prefs.targets(this) &&
            unlockedPackage != target
        ) {
            showCover()
        }
        handler.postDelayed({
            if (!Prefs.isEnabled(this) || target !in Prefs.targets(this)) {
                hideCover()
                return@postDelayed
            }
            if (unlockedPackage == target || lastPkg != target || AlertActivity.isShowing) {
                if (!AlertActivity.isShowing) hideCover()
                return@postDelayed
            }

            // Safety: never fight an app that keeps reopening itself over the alert.
            val now = SystemClock.elapsedRealtime()
            if (now - relaunchWindowStart > RELAUNCH_WINDOW_MS) {
                relaunchWindowStart = now
                relaunchCount = 0
            }
            if (++relaunchCount > MAX_RELAUNCHES) {
                // The app keeps pushing the alert away. Never leave it open: send the user home.
                hideCover()
                performGlobalAction(GLOBAL_ACTION_HOME)
                return@postDelayed
            }

            lastTriggerAt = now
            showCover()
            try {
                startActivity(AlertActivity.createIntent(this, target))
            } catch (e: Exception) {
                hideCover()
            }
        }, RELAUNCH_DELAY_MS)
    }

    private fun showCover() {
        handler.removeCallbacks(coverTimeout)
        handler.postDelayed(coverTimeout, COVER_TIMEOUT_MS) // safety net: never get stuck on black
        if (cover != null) return

        val wm = getSystemService(WindowManager::class.java) ?: return
        val view = View(this).apply { setBackgroundColor(getColor(R.color.alert_bg)) }
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

    /** Called by AlertActivity once it is visible (it has the same background, so the swap is invisible). */
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

    private var packageReceiver: BroadcastReceiver? = null

    /**
     * Reacts to installs and uninstalls the moment they happen: new apps are added to the app
     * list cache, and an uninstalled app is removed from the blocked list immediately.
     */
    private fun registerPackageReceiver() {
        if (packageReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> AppCache.onInstalled(context, pkg)
                    Intent.ACTION_PACKAGE_REMOVED -> {
                        // An update also sends "removed"; only a real uninstall counts.
                        if (!intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) {
                            AppCache.onRemoved(context, pkg)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        packageReceiver = receiver
    }

    private fun unregisterPackageReceiver() {
        val receiver = packageReceiver ?: return
        packageReceiver = null
        try {
            unregisterReceiver(receiver)
        } catch (e: Exception) {
            // Already unregistered.
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        hideCover()
        handler.removeCallbacksAndMessages(null)
        unregisterPackageReceiver()
        instance = null
        unlockedPackage = null
        lastPkg = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        hideCover()
        unregisterPackageReceiver()
        instance = null
        unlockedPackage = null
        super.onDestroy()
    }

    companion object {
        private const val DEBOUNCE_MS = 400L
        private const val COOLDOWN_MS = 1000L
        private const val COVER_TIMEOUT_MS = 2500L
        private const val RELAUNCH_DELAY_MS = 250L
        private const val RELAUNCH_WINDOW_MS = 5000L
        private const val MAX_RELAUNCHES = 3
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
