package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView

class AlertActivity : Activity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnPositive: Button

    private var targetPackage: String? = null
    private var label: String = ""
    private var taps = 0
    private var lastTapAt = 0L
    private var downAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isShowing = true
        setContentView(R.layout.activity_alert)

        tvTitle = findViewById(R.id.tvTitle)
        tvMessage = findViewById(R.id.tvMessage)
        btnPositive = findViewById(R.id.btnPositive)
        btnPositive.setOnClickListener { goHomeAndFinish() }

        // Secret, step 2: after the black-area taps, press and hold the box (not the OK button).
        findViewById<View>(R.id.card).setOnLongClickListener {
            if (isArmed()) {
                BoundaryAccessibilityService.grantSession(targetPackage)
                finish()
                true
            } else {
                taps = 0
                false
            }
        }

        // Secret, step 1: quick taps anywhere on the black area outside the box.
        findViewById<View>(R.id.root).setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> downAt = event.eventTime
                MotionEvent.ACTION_UP ->
                    if (event.eventTime - downAt < TAP_MAX_MS) onBackgroundTap()
            }
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

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goHomeAndFinish()
    }

    override fun onDestroy() {
        isShowing = false
        super.onDestroy()
    }

    private fun applyTarget(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        label = resolveLabel(targetPackage)
        taps = 0
        tvTitle.setText(R.string.alert_title)
        tvMessage.text = getString(R.string.alert_message, label)
        btnPositive.setText(R.string.alert_ok)
    }

    private fun onBackgroundTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > TAP_WINDOW_MS) taps = 0
        lastTapAt = now
        taps++
    }

    private fun isArmed(): Boolean =
        taps >= resources.getInteger(R.integer.unlock_taps) &&
            SystemClock.elapsedRealtime() - lastTapAt <= ARM_WINDOW_MS

    private fun resolveLabel(pkg: String?): String {
        if (pkg == null) return getString(R.string.fallback_app_name)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun goHomeAndFinish() {
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
        private const val TAP_MAX_MS = 350L
        private const val TAP_WINDOW_MS = 2000L
        private const val ARM_WINDOW_MS = 3000L

        /** True while an alert exists, so the service never launches a second one. */
        @Volatile
        var isShowing = false
            private set

        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
