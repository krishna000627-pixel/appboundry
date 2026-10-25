package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView

class AlertActivity : Activity() {

    /** Progress through a secret code (volume or corner). */
    private class Matcher {
        var progress = 0
        var lastAt = 0L
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnPositive: Button

    private var targetPackage: String? = null
    private var label: String = ""

    /** Opened from the vault's Preview button: OK and unlock just close it and return to the vault. */
    private var preview = false

    /** Set when the user ended the alert (OK, Back or an unlock). Otherwise Android closed it for us. */
    private var closedByUser = false

    // Way 1: taps on the black area, then hold the box.
    private var taps = 0
    private var lastTapAt = 0L
    private var downAt = 0L
    private var multiTouch = false

    // Ways 2 and 3: codes.
    private val volumeMatcher = Matcher()
    private val cornerMatcher = Matcher()

    // Way 4: two fingers held on the black area.
    private val handler = Handler(Looper.getMainLooper())
    private val twoFingerUnlock = Runnable { unlockNow() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        setContentView(R.layout.activity_alert)

        tvTitle = findViewById(R.id.tvTitle)
        tvMessage = findViewById(R.id.tvMessage)
        btnPositive = findViewById(R.id.btnPositive)
        btnPositive.setOnClickListener { goHomeAndFinish() }

        // Way 1, part 2: press and hold the box (not the OK button).
        findViewById<View>(R.id.card).setOnLongClickListener {
            if (Prefs.unlockEnabled(this, Prefs.METHOD_TAP_HOLD) && isArmed()) {
                unlockNow()
                true
            } else {
                taps = 0
                false
            }
        }

        findViewById<View>(R.id.root).setOnTouchListener { v, event ->
            handleTouch(v, event)
            true
        }

        applyTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTarget(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemBars()
            // This black screen is now visible, so the instant cover is no longer needed.
            BoundaryAccessibilityService.instance?.hideCover()
        }
    }

    // Way 2: volume code. Volume still works normally while the code is typed.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.repeatCount == 0 && Prefs.unlockEnabled(this, Prefs.METHOD_VOLUME)) {
            val symbol = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 'U'
                KeyEvent.KEYCODE_VOLUME_DOWN -> 'D'
                else -> null
            }
            if (symbol != null && feed(volumeMatcher, Prefs.volumeCode(this), symbol)) {
                unlockNow()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goHomeAndFinish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(twoFingerUnlock)
        isShowing = false
        // Closed by the system (a blocked app opened over us, e.g. from a link): block it again.
        val lost = isFinishing && !closedByUser && !preview
        val target = targetPackage
        super.onDestroy()
        if (lost) BoundaryAccessibilityService.instance?.onAlertLost(target)
    }

    private fun applyTarget(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false)
        label = resolveLabel(targetPackage)
        taps = 0
        volumeMatcher.progress = 0
        cornerMatcher.progress = 0
        tvTitle.text = Prefs.alertTitle(this) ?: getString(R.string.alert_title)
        tvMessage.text = Prefs.alertMessage(this)?.replace("{app}", label)
            ?: getString(R.string.alert_message, label)
        btnPositive.setText(R.string.alert_ok)
    }

    // ---- Touch: tap counting, corner code, two-finger hold ------------------

    private fun handleTouch(v: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                multiTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                if (event.pointerCount == 2 && Prefs.unlockEnabled(this, Prefs.METHOD_TWO_FINGER)) {
                    handler.postDelayed(twoFingerUnlock, TWO_FINGER_HOLD_MS)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> handler.removeCallbacks(twoFingerUnlock)
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(twoFingerUnlock)
                multiTouch = false
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(twoFingerUnlock)
                if (!multiTouch && event.eventTime - downAt < TAP_MAX_MS) onBackgroundTap(v, event)
                multiTouch = false
            }
        }
    }

    private fun onBackgroundTap(v: View, event: MotionEvent) {
        // Way 1: count the taps.
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > TAP_WINDOW_MS) taps = 0
        lastTapAt = now
        taps++

        // Way 3: corner code, by screen quarter.
        if (Prefs.unlockEnabled(this, Prefs.METHOD_CORNER)) {
            val right = event.x >= v.width / 2f
            val bottom = event.y >= v.height / 2f
            val symbol = when {
                !right && !bottom -> '1'
                right && !bottom -> '2'
                !right && bottom -> '3'
                else -> '4'
            }
            if (feed(cornerMatcher, Prefs.cornerCode(this), symbol)) unlockNow()
        }
    }

    private fun isArmed(): Boolean =
        taps >= Prefs.unlockTaps(this) &&
            SystemClock.elapsedRealtime() - lastTapAt <= ARM_WINDOW_MS

    /** Advances a code; returns true when the whole code has been entered. */
    private fun feed(matcher: Matcher, code: String, symbol: Char): Boolean {
        if (code.isEmpty()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - matcher.lastAt > TAP_WINDOW_MS) matcher.progress = 0
        matcher.lastAt = now
        matcher.progress = when {
            code[matcher.progress] == symbol -> matcher.progress + 1
            code[0] == symbol -> 1
            else -> 0
        }
        if (matcher.progress == code.length) {
            matcher.progress = 0
            return true
        }
        return false
    }

    private fun unlockNow() {
        closedByUser = true
        if (preview) {
            returnToVault()
            return
        }
        BoundaryAccessibilityService.grantSession(targetPackage)
        finish()
    }

    /** Preview only: close this screen and bring the vault back instead of going home. */
    private fun returnToVault() {
        finish()
        startActivity(
            Intent(this, VaultActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun resolveLabel(pkg: String?): String {
        if (pkg == null) return getString(R.string.fallback_app_name)
        return AppCache.label(this, pkg)
    }

    private fun goHomeAndFinish() {
        closedByUser = true
        if (preview) {
            returnToVault()
            return
        }
        BoundaryAccessibilityService.markDismissed()
        // Primary: the accessibility HOME action. Backup: a plain home intent, in case the
        // action is dropped while this screen is closing.
        BoundaryAccessibilityService.instance
            ?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    companion object {
        private const val EXTRA_PACKAGE = "extra_package"
        private const val EXTRA_PREVIEW = "extra_preview"
        private const val TAP_MAX_MS = 350L
        private const val TAP_WINDOW_MS = 2000L
        private const val ARM_WINDOW_MS = 3000L
        private const val TWO_FINGER_HOLD_MS = 1500L

        /** True while an alert exists, so the service never launches a second one. */
        @Volatile
        var isShowing = false
            private set

        fun createIntent(context: Context, targetPackage: String, preview: Boolean = false): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .putExtra(EXTRA_PREVIEW, preview)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
