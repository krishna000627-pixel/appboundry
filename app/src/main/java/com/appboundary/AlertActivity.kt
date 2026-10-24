package com.appboundary

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView

class AlertActivity : Activity() {

    private lateinit var tvTitle: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnNegative: Button
    private lateinit var btnPositive: Button

    private var targetPackage: String? = null
    private var label: String = ""
    private var unlocking = false
    private var progress = 0
    private var lastTapAt = 0L
    private var timer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_alert)

        tvTitle = findViewById(R.id.tvTitle)
        tvMessage = findViewById(R.id.tvMessage)
        btnNegative = findViewById(R.id.btnNegative)
        btnPositive = findViewById(R.id.btnPositive)

        tvTitle.setOnClickListener { onSecretTap('T') }
        tvMessage.setOnClickListener { onSecretTap('M') }
        findViewById<View>(R.id.root).setOnClickListener { progress = 0 }
        applyTarget(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyTarget(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        goHomeAndFinish()
    }

    override fun onDestroy() {
        timer?.cancel()
        super.onDestroy()
    }

    // ---- States -------------------------------------------------------------

    private fun applyTarget(intent: Intent) {
        targetPackage = intent.getStringExtra(EXTRA_PACKAGE)
        label = resolveLabel(targetPackage)
        showAlertState()
    }

    /** Default state: the "app not supported" box with a single OK button. */
    private fun showAlertState() {
        timer?.cancel()
        unlocking = false
        progress = 0

        tvTitle.setText(R.string.alert_title)
        tvMessage.text = getString(R.string.alert_message, label)
        btnNegative.visibility = View.GONE
        btnPositive.setText(R.string.alert_ok)
        setPositiveEnabled(true)
        btnPositive.setOnClickListener { goHomeAndFinish() }
    }

    /** Hidden state, reached by tapping the title: wait, then continue until the app is left. */
    private fun showUnlockState() {
        unlocking = true
        progress = 0

        tvTitle.text = getString(R.string.unlock_title, label)
        tvMessage.text = getString(R.string.unlock_message, label)
        btnNegative.visibility = View.VISIBLE
        btnNegative.setText(R.string.unlock_back)
        btnNegative.setOnClickListener { goHomeAndFinish() }

        setPositiveEnabled(false)
        btnPositive.setOnClickListener {
            BoundaryAccessibilityService.grantSession(targetPackage)
            finish()
        }

        val seconds = resources.getInteger(R.integer.unlock_delay_seconds)
        timer?.cancel()
        timer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisLeft: Long) {
                btnPositive.text = getString(R.string.unlock_wait, ((millisLeft + 999) / 1000).toInt())
            }

            override fun onFinish() {
                btnPositive.setText(R.string.unlock_continue)
                setPositiveEnabled(true)
            }
        }.start()
    }

    /** Secret sequence of taps on the title (T) and the message (M); a wrong tap restarts it. */
    private fun onSecretTap(symbol: Char) {
        if (unlocking) return
        val sequence = getString(R.string.unlock_sequence)
        val now = SystemClock.elapsedRealtime()
        if (now - lastTapAt > TAP_WINDOW_MS) progress = 0
        lastTapAt = now

        progress = when {
            sequence[progress] == symbol -> progress + 1
            sequence[0] == symbol -> 1
            else -> 0
        }
        if (progress == sequence.length) showUnlockState()
    }

    // ---- Helpers ------------------------------------------------------------

    private fun setPositiveEnabled(enabled: Boolean) {
        btnPositive.isEnabled = enabled
        btnPositive.alpha = if (enabled) 1f else 0.4f
    }

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
