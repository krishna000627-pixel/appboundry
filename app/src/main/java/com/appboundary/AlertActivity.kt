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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var btnNeutral: Button
    private lateinit var ivArt: ImageView

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

    // Gestures only count when the whole touch happened on this screen, after it settled.
    private var readyAt = 0L
    private var gestureOk = false

    // Ways 2 and 3: codes.
    private val volumeMatcher = Matcher()
    private val cornerMatcher = Matcher()

    // Way 4: two fingers held on the black area.
    private val handler = Handler(Looper.getMainLooper())
    private val twoFingerUnlock = Runnable {
        if (isReady() && !isThrottled() && gestureOk && multiTouch) unlockNow(Prefs.METHOD_TWO_FINGER)
    }
    private val fingerX = FloatArray(2)
    private val fingerY = FloatArray(2)
    private var fingersTracked = false

    // Guessing guard: lots of wrong input in a short time switches every secret method off for a while.
    private val misses = ArrayDeque<Long>()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        setContentView(R.layout.activity_alert)

        tvTitle = findViewById(R.id.tvTitle)
        tvMessage = findViewById(R.id.tvMessage)
        btnPositive = findViewById(R.id.btnPositive)
        btnNeutral = findViewById(R.id.btnNeutral)
        ivArt = findViewById(R.id.ivArt)
        btnPositive.setOnClickListener { goHomeAndFinish() }
        btnNeutral.setOnClickListener { goHomeAndFinish() }

        // Way 1, part 2: press and hold the box (not the OK button).
        findViewById<View>(R.id.card).setOnLongClickListener {
            if (isReady() && !isThrottled() && Prefs.unlockEnabled(this, Prefs.METHOD_TAP_HOLD) && isArmed()) {
                unlockNow(Prefs.METHOD_TAP_HOLD)
                true
            } else {
                if (taps > 0) noteMiss()
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
            readyAt = SystemClock.elapsedRealtime() + SETTLE_MS
            hideSystemBars()
            // This black screen is now visible, so the instant cover is no longer needed.
            BoundaryAccessibilityService.instance?.hideCover()
        }
    }

    // Way 2: volume code. While this method is on, the volume keys are swallowed here, so the
    // system volume bar never appears and the volume never changes on the blocker screen.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val symbol = when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> 'U'
            KeyEvent.KEYCODE_VOLUME_DOWN -> 'D'
            else -> null
        }
        if (symbol != null && Prefs.unlockEnabled(this, Prefs.METHOD_VOLUME)) {
            if (event.repeatCount == 0 && isReady() &&
                feed(volumeMatcher, Prefs.volumeCode(this), symbol)
            ) {
                unlockNow(Prefs.METHOD_VOLUME)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) &&
            Prefs.unlockEnabled(this, Prefs.METHOD_VOLUME)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goHomeAndFinish()
    }

    /** Covered or left: nothing started here may finish later (a held finger must never unlock). */
    override fun onStop() {
        resetGestures()
        super.onStop()
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
        val disguiseOverride = intent.getIntExtra(EXTRA_DISGUISE, -1)
        label = resolveLabel(targetPackage)
        resetGestures()
        readyAt = SystemClock.elapsedRealtime() + SETTLE_MS
        applyDisguise(
            if (disguiseOverride >= 0) disguiseOverride else Prefs.disguise(this, targetPackage)
        )
    }

    /** 0 = "not supported" (the vault's custom text applies), 1 = keeps stopping, 2 = not available, 3 = can't open, 4 = under development. */
    private fun applyDisguise(kind: Int) {
        tvMessage.visibility = View.VISIBLE
        ivArt.visibility = View.GONE
        btnNeutral.visibility = View.GONE
        btnPositive.setText(R.string.alert_ok)
        when (kind) {
            1 -> {
                tvTitle.text = getString(R.string.dg_stop_title, label)
                tvMessage.visibility = View.GONE
                btnNeutral.setText(R.string.dg_app_info)
                btnNeutral.visibility = View.VISIBLE
                btnPositive.setText(R.string.dg_close_app)
            }
            2 -> {
                tvTitle.setText(R.string.dg_na_title)
                tvMessage.text = getString(R.string.dg_na_msg, label)
            }
            3 -> {
                tvTitle.text = getString(R.string.dg_cant_title, label)
                tvMessage.setText(R.string.dg_cant_msg)
            }
            4 -> {
                ivArt.visibility = View.VISIBLE
                tvTitle.setText(R.string.dg_dev_title)
                tvMessage.text = getString(R.string.dg_dev_msg, label)
            }
            else -> {
                tvTitle.text = Prefs.alertTitle(this) ?: getString(R.string.alert_title)
                tvMessage.text = Prefs.alertMessage(this)?.replace("{app}", label)
                    ?: getString(R.string.alert_message, label)
            }
        }
    }

    private fun resetGestures() {
        handler.removeCallbacks(twoFingerUnlock)
        taps = 0
        multiTouch = false
        gestureOk = false
        fingersTracked = false
        volumeMatcher.progress = 0
        cornerMatcher.progress = 0
    }

    /** False for a moment after the screen appears, so touches left over from opening the app are ignored. */
    private fun isReady(): Boolean = SystemClock.elapsedRealtime() >= readyAt

    // ---- Touch: tap counting, corner code, two-finger hold ------------------

    private fun handleTouch(v: View, event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                multiTouch = false
                gestureOk = isReady() // a touch that starts too early is ignored until all fingers lift
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiTouch = true
                if (gestureOk && event.pointerCount == 2 && !isThrottled() &&
                    Prefs.unlockEnabled(this, Prefs.METHOD_TWO_FINGER)
                ) {
                    for (i in 0..1) {
                        fingerX[i] = event.getX(i)
                        fingerY[i] = event.getY(i)
                    }
                    fingersTracked = true
                    handler.postDelayed(twoFingerUnlock, TWO_FINGER_HOLD_MS)
                }
            }
            // Two fingers must stay put: swiping or dragging cancels the hold.
            MotionEvent.ACTION_MOVE -> if (fingersTracked && event.pointerCount >= 2) {
                val slop = v.resources.displayMetrics.density * FINGER_SLOP_DP
                for (i in 0..1) {
                    val dx = event.getX(i) - fingerX[i]
                    val dy = event.getY(i) - fingerY[i]
                    if (dx * dx + dy * dy > slop * slop) {
                        handler.removeCallbacks(twoFingerUnlock)
                        fingersTracked = false
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                handler.removeCallbacks(twoFingerUnlock)
                fingersTracked = false
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(twoFingerUnlock)
                multiTouch = false
                gestureOk = false
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(twoFingerUnlock)
                if (gestureOk && !multiTouch && event.eventTime - downAt < TAP_MAX_MS) onBackgroundTap(v, event)
                multiTouch = false
                gestureOk = false
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
            if (feed(cornerMatcher, Prefs.cornerCode(this), symbol)) unlockNow(Prefs.METHOD_CORNER)
        }
    }

    private fun isArmed(): Boolean =
        taps >= Prefs.unlockTaps(this) &&
            SystemClock.elapsedRealtime() - lastTapAt <= ARM_WINDOW_MS

    /** Advances a code; returns true when the whole code has been entered. */
    private fun feed(matcher: Matcher, code: String, symbol: Char): Boolean {
        if (code.isEmpty() || isThrottled()) return false
        val now = SystemClock.elapsedRealtime()
        if (now - matcher.lastAt > TAP_WINDOW_MS) matcher.progress = 0
        matcher.lastAt = now
        matcher.progress = when {
            code[matcher.progress] == symbol -> matcher.progress + 1
            code[0] == symbol -> {
                noteMiss()
                1
            }
            else -> {
                noteMiss()
                0
            }
        }
        if (matcher.progress == code.length) {
            matcher.progress = 0
            return true
        }
        return false
    }

    private fun isThrottled(): Boolean = SystemClock.elapsedRealtime() < throttleUntil

    /** Counts a wrong input; too many in a short time pauses all secret unlock methods for a while. */
    private fun noteMiss() {
        val now = SystemClock.elapsedRealtime()
        misses.addLast(now)
        while (misses.isNotEmpty() && now - misses.first() > MISS_WINDOW_MS) misses.removeFirst()
        if (misses.size >= MAX_MISSES) {
            throttleUntil = now + THROTTLE_MS
            misses.clear()
            resetGestures()
        }
    }

    private fun unlockNow(method: String) {
        closedByUser = true
        if (!preview) targetPackage?.let { UnlockLog.add(this, it, method) }
        vibrateUnlock() // every unlock method ends here, so each one gets the same buzz
        if (preview) {
            returnToVault()
            return
        }
        BoundaryAccessibilityService.grantSession(targetPackage)
        finish()
    }

    private fun vibrateUnlock() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(
                VibrationEffect.createOneShot(UNLOCK_VIBRATION_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (e: Exception) {
            // No vibrator or not allowed: the unlock still works.
        }
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
        private const val EXTRA_DISGUISE = "extra_disguise"
        private const val TAP_MAX_MS = 350L
        private const val TAP_WINDOW_MS = 2000L
        private const val ARM_WINDOW_MS = 3000L
        private const val TWO_FINGER_HOLD_MS = 1500L
        private const val SETTLE_MS = 500L
        private const val UNLOCK_VIBRATION_MS = 60L
        private const val MISS_WINDOW_MS = 10_000L
        private const val MAX_MISSES = 10
        private const val THROTTLE_MS = 30_000L
        private const val FINGER_SLOP_DP = 40f

        /** Kept for the whole process, so closing and reopening the blocker does not reset the guard. */
        @Volatile
        private var throttleUntil = 0L

        /** True while an alert exists, so the service never launches a second one. */
        @Volatile
        var isShowing = false
            private set

        fun createIntent(
            context: Context,
            targetPackage: String,
            preview: Boolean = false,
            disguise: Int = -1
        ): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .putExtra(EXTRA_PREVIEW, preview)
                .putExtra(EXTRA_DISGUISE, disguise)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
