package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
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
    private lateinit var sequence: String

    private var targetPackage: String? = null
    private var label: String = ""
    private var progress = 0
    private var lastTapAt = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)

        tvTitle = findViewById(R.id.tvTitle)
        tvMessage = findViewById(R.id.tvMessage)
        btnPositive = findViewById(R.id.btnPositive)
        sequence = getString(R.string.unlock_sequence)

        tvTitle.setOnClickListener { onSecretTap('T') }
        tvMessage.setOnClickListener { onSecretTap('M') }
        btnPositive.setOnClickListener { goHomeAndFinish() }

        // Last step of the secret: double tap on the black area.
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isArmed()) {
                    BoundaryAccessibilityService.grantSession(targetPackage)
                    finish()
                } else {
                    progress = 0
                }
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                progress = 0
                return true
            }
        })
        findViewById<View>(R.id.root).setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
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

    private fun applyTarget(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        label = resolveLabel(targetPackage)
        progress = 0
        tvTitle.setText(R.string.alert_title)
        tvMessage.text = getString(R.string.alert_message, label)
        btnPositive.setText(R.string.alert_ok)
    }

    /**
     * Secret: tap message/title in the order given by unlock_sequence (M = message, T = title),
     * then double tap the black area. A wrong tap restarts it; max 2 s between taps.
     */
    private fun onSecretTap(symbol: Char) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > TAP_WINDOW_MS || progress >= sequence.length) progress = 0
        lastTapAt = now

        progress = when {
            sequence[progress] == symbol -> progress + 1
            sequence[0] == symbol -> 1
            else -> 0
        }
    }

    private fun isArmed(): Boolean =
        progress == sequence.length && SystemClock.elapsedRealtime() - lastTapAt <= TAP_WINDOW_MS

    private fun resolveLabel(pkg: String?): String {
        if (pkg == null) return getString(R.string.fallback_app_name)
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun goHomeAndFinish() {
        val service = BoundaryAccessibilityService.instance
        if (service != null) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        } else {
            startActivity(
                Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
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
        private const val TAP_WINDOW_MS = 2000L

        fun createIntent(context: Context, targetPackage: String): Intent =
            Intent(context, AlertActivity::class.java)
                .putExtra(EXTRA_PACKAGE, targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}
