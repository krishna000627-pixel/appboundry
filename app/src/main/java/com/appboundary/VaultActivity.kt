package com.appboundary

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.service.quicksettings.TileService
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The secret vault. Reached only by long-pressing the Quick Settings tile
 * (QS_TILE_PREFERENCES), always behind a 4-digit PIN, and re-locks whenever it leaves the screen.
 */
class VaultActivity : Activity() {

    private enum class Mode { UNLOCK, CREATE, CONFIRM }

    // PIN gate
    private lateinit var gate: View
    private lateinit var tvGateTitle: TextView
    private lateinit var tvGateSub: TextView
    private lateinit var dots: LinearLayout
    private lateinit var keypad: LinearLayout
    private lateinit var tvForgot: TextView

    // Vault
    private lateinit var content: View
    private lateinit var switchFocus: Switch
    private lateinit var tvFocusState: TextView
    private lateinit var cardAccess: View
    private lateinit var llBlocked: LinearLayout
    private lateinit var tvBlockedEmpty: TextView
    private lateinit var etTitle: EditText
    private lateinit var etMessage: EditText
    private lateinit var tvTaps: TextView

    private var mode = Mode.UNLOCK
    private var changingPin = false
    private var firstPin: String? = null
    private val entered = StringBuilder()
    private var lockTimer: CountDownTimer? = null

    private val focusListener = CompoundButton.OnCheckedChangeListener { _, checked ->
        Prefs.setEnabled(this, checked)
        renderFocusState(checked)
        TileService.requestListeningState(this, ComponentName(this, QuickTileService::class.java))
    }

    // ---- Lifecycle ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        bindViews()
        buildDots()
        buildKeypad()
        bindVaultActions()
        showGate(if (Prefs.hasPin(this)) Mode.UNLOCK else Mode.CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (content.visibility == View.VISIBLE) refreshVault()
    }

    /** Leaving the screen always locks the vault again. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && content.visibility == View.VISIBLE) {
            hideKeyboard()
            showGate(Mode.UNLOCK)
        }
    }

    override fun onDestroy() {
        lockTimer?.cancel()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (gate.visibility == View.VISIBLE && changingPin) {
            changingPin = false
            openVault()
        } else {
            super.onBackPressed()
        }
    }

    private fun bindViews() {
        gate = findViewById(R.id.gate)
        tvGateTitle = findViewById(R.id.tvGateTitle)
        tvGateSub = findViewById(R.id.tvGateSub)
        dots = findViewById(R.id.dots)
        keypad = findViewById(R.id.keypad)
        tvForgot = findViewById(R.id.tvForgot)

        content = findViewById(R.id.content)
        switchFocus = findViewById(R.id.switchFocus)
        tvFocusState = findViewById(R.id.tvFocusState)
        cardAccess = findViewById(R.id.cardAccess)
        llBlocked = findViewById(R.id.llBlocked)
        tvBlockedEmpty = findViewById(R.id.tvBlockedEmpty)
        etTitle = findViewById(R.id.etTitle)
        etMessage = findViewById(R.id.etMessage)
        tvTaps = findViewById(R.id.tvTaps)
    }

    // ---- PIN gate -----------------------------------------------------------

    private fun showGate(newMode: Mode) {
        mode = newMode
        entered.clear()
        if (newMode != Mode.CONFIRM) firstPin = null
        gate.visibility = View.VISIBLE
        content.visibility = View.GONE
        setGateTexts()
        tvForgot.visibility = if (newMode == Mode.UNLOCK) View.VISIBLE else View.INVISIBLE
        updateDots()

        val remaining = Prefs.lockRemainingMs(this)
        if (newMode == Mode.UNLOCK && remaining > 0) startLockTicker(remaining)
    }

    private fun setGateTexts() {
        val texts = when (mode) {
            Mode.UNLOCK -> Pair(R.string.vault_pin_enter_title, R.string.vault_pin_enter_sub)
            Mode.CREATE -> Pair(R.string.vault_pin_create_title, R.string.vault_pin_create_sub)
            Mode.CONFIRM -> Pair(R.string.vault_pin_confirm_title, R.string.vault_pin_confirm_sub)
        }
        tvGateTitle.setText(texts.first)
        setSub(texts.second, false)
    }

    private fun setSub(resId: Int, error: Boolean) {
        tvGateSub.setText(resId)
        tvGateSub.setTextColor(getColor(if (error) R.color.vault_danger else R.color.vault_text_dim))
    }

    private fun buildDots() {
        dots.removeAllViews()
        repeat(PIN_LENGTH) {
            val lp = LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                setMargins(dp(9), 0, dp(9), 0)
            }
            dots.addView(View(this), lp)
        }
    }

    private fun updateDots() {
        for (i in 0 until dots.childCount) {
            dots.getChildAt(i).setBackgroundResource(
                if (i < entered.length) R.drawable.dot_filled else R.drawable.dot_empty
            )
        }
    }

    private fun buildKeypad() {
        keypad.removeAllViews()
        val rows = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("", "0", "<")
        )
        for (row in rows) {
            val rowView = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (key in row) {
                val lp = LinearLayout.LayoutParams(dp(68), dp(68)).apply {
                    setMargins(dp(10), dp(5), dp(10), dp(5))
                }
                when (key) {
                    "" -> rowView.addView(View(this), lp)
                    "<" -> rowView.addView(makeBackspaceKey(), lp)
                    else -> rowView.addView(makeDigitKey(key), lp)
                }
            }
            keypad.addView(rowView)
        }
    }

    private fun makeDigitKey(label: String): View {
        val digit = label.toInt()
        val key = TextView(this)
        key.text = label
        key.textSize = 26f
        key.gravity = Gravity.CENTER
        key.setTextColor(getColor(R.color.vault_text))
        key.setBackgroundResource(R.drawable.bg_key)
        key.isClickable = true
        key.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onDigit(digit)
        }
        return key
    }

    private fun makeBackspaceKey(): View {
        val key = ImageView(this)
        key.setImageResource(R.drawable.ic_backspace)
        key.scaleType = ImageView.ScaleType.CENTER
        key.setBackgroundResource(R.drawable.bg_key_flat)
        key.contentDescription = getString(R.string.vault_backspace)
        key.isClickable = true
        key.setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            if (entered.isNotEmpty()) {
                entered.deleteCharAt(entered.length - 1)
                updateDots()
            }
        }
        key.setOnLongClickListener {
            entered.clear()
            updateDots()
            true
        }
        return key
    }

    private fun onDigit(digit: Int) {
        if (mode == Mode.UNLOCK && Prefs.lockRemainingMs(this) > 0) return
        if (entered.length >= PIN_LENGTH) return
        entered.append(digit)
        updateDots()
        if (entered.length == PIN_LENGTH) dots.postDelayed({ onPinComplete() }, 140)
    }

    private fun onPinComplete() {
        val pin = entered.toString()
        if (pin.length != PIN_LENGTH) return
        when (mode) {
            Mode.UNLOCK -> {
                if (Prefs.checkPin(this, pin)) {
                    Prefs.resetFails(this)
                    openVault()
                } else {
                    val lockMs = Prefs.registerFail(this)
                    reject()
                    if (lockMs > 0) startLockTicker(lockMs) else setSub(R.string.vault_pin_wrong, true)
                }
            }
            Mode.CREATE -> {
                firstPin = pin
                mode = Mode.CONFIRM
                entered.clear()
                setGateTexts()
                updateDots()
            }
            Mode.CONFIRM -> {
                if (pin == firstPin) {
                    Prefs.setPin(this, pin)
                    if (changingPin) {
                        Toast.makeText(this, R.string.vault_pin_changed, Toast.LENGTH_SHORT).show()
                    }
                    changingPin = false
                    openVault()
                } else {
                    reject()
                    firstPin = null
                    mode = Mode.CREATE
                    setGateTexts()
                    setSub(R.string.vault_pin_mismatch, true)
                }
            }
        }
    }

    /** Clears the entry and shakes the dots. */
    private fun reject() {
        entered.clear()
        updateDots()
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        dots.performHapticFeedback(haptic)
        ObjectAnimator.ofFloat(dots, "translationX", 0f, 24f, -24f, 16f, -16f, 8f, -8f, 0f)
            .setDuration(380)
            .start()
    }

    private fun startLockTicker(ms: Long) {
        lockTimer?.cancel()
        lockTimer = object : CountDownTimer(ms, 1000L) {
            override fun onTick(millisLeft: Long) {
                tvGateSub.text = getString(R.string.vault_pin_locked, ((millisLeft + 999) / 1000).toInt())
                tvGateSub.setTextColor(getColor(R.color.vault_danger))
            }

            override fun onFinish() {
                setGateTexts()
            }
        }.start()
    }

    // ---- Vault --------------------------------------------------------------

    private fun openVault() {
        lockTimer?.cancel()
        entered.clear()
        gate.visibility = View.GONE
        content.visibility = View.VISIBLE
        content.alpha = 0f
        content.scrollTo(0, 0)
        content.animate().alpha(1f).setDuration(220).start()
        refreshVault()
        loadAlertTexts()
    }

    private fun bindVaultActions() {
        findViewById<View>(R.id.btnLock).setOnClickListener { finish() }
        switchFocus.setOnCheckedChangeListener(focusListener)

        findViewById<View>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.btnAddApp).setOnClickListener { showAppPicker() }

        findViewById<View>(R.id.btnSave).setOnClickListener { saveTexts(true) }
        findViewById<View>(R.id.btnReset).setOnClickListener {
            Prefs.setAlertText(this, null, null)
            loadAlertTexts()
        }
        findViewById<View>(R.id.btnPreview).setOnClickListener {
            saveTexts(false)
            val target = Prefs.targets(this).firstOrNull() ?: packageName
            startActivity(AlertActivity.createIntent(this, target))
        }

        findViewById<View>(R.id.btnTapsMinus).setOnClickListener { changeTaps(-1) }
        findViewById<View>(R.id.btnTapsPlus).setOnClickListener { changeTaps(1) }

        findViewById<View>(R.id.btnChangePin).setOnClickListener {
            changingPin = true
            showGate(Mode.CREATE)
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) {
            ""
        }
        findViewById<TextView>(R.id.tvVersion).text = getString(R.string.vault_footer, version)
    }

    private fun refreshVault() {
        val enabled = Prefs.isEnabled(this)
        switchFocus.setOnCheckedChangeListener(null)
        switchFocus.isChecked = enabled
        switchFocus.setOnCheckedChangeListener(focusListener)
        renderFocusState(enabled)

        cardAccess.visibility = if (isServiceEnabled()) View.GONE else View.VISIBLE
        renderBlocked()
        tvTaps.text = Prefs.unlockTaps(this).toString()
    }

    private fun renderFocusState(on: Boolean) {
        tvFocusState.setText(if (on) R.string.vault_focus_on else R.string.vault_focus_off)
        tvFocusState.setTextColor(getColor(if (on) R.color.vault_ok else R.color.vault_text_dim))
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val component = ComponentName(this, BoundaryAccessibilityService::class.java)
        return enabled.split(':').any {
            it.equals(component.flattenToString(), true) ||
                it.equals(component.flattenToShortString(), true)
        }
    }

    private fun changeTaps(delta: Int) {
        val value = (Prefs.unlockTaps(this) + delta).coerceIn(Prefs.MIN_TAPS, Prefs.MAX_TAPS)
        Prefs.setUnlockTaps(this, value)
        tvTaps.text = value.toString()
    }

    // ---- Alert text ---------------------------------------------------------

    private fun loadAlertTexts() {
        etTitle.hint = getString(R.string.alert_title)
        etMessage.hint = getString(R.string.alert_message, "{app}")
        etTitle.setText(Prefs.alertTitle(this) ?: "")
        etMessage.setText(Prefs.alertMessage(this) ?: "")
    }

    private fun saveTexts(showToast: Boolean) {
        Prefs.setAlertText(this, etTitle.text.toString().trim(), etMessage.text.toString().trim())
        if (showToast) {
            hideKeyboard()
            Toast.makeText(this, R.string.vault_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Blocked apps -------------------------------------------------------

    private fun renderBlocked() {
        llBlocked.removeAllViews()
        val targets = Prefs.targets(this).sortedBy { labelOf(it).lowercase() }
        tvBlockedEmpty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        for (pkg in targets) {
            val row = layoutInflater.inflate(R.layout.row_app, llBlocked, false)
            row.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(iconOf(pkg))
            row.findViewById<TextView>(R.id.tvLabel).text = labelOf(pkg)
            row.findViewById<TextView>(R.id.tvPackage).text = pkg
            row.findViewById<View>(R.id.btnRemove).setOnClickListener {
                Prefs.setTargets(this, Prefs.targets(this) - pkg)
                renderBlocked()
            }
            llBlocked.addView(row)
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun iconOf(pkg: String): Drawable = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: Exception) {
        getDrawable(android.R.drawable.sym_def_app_icon) ?: ColorDrawable(Color.TRANSPARENT)
    }

    private fun showAppPicker() {
        val pm = packageManager
        val blocked = Prefs.targets(this)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val items = pm.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName && it.activityInfo.packageName !in blocked }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        if (items.isEmpty()) {
            Toast.makeText(this, R.string.vault_no_apps, Toast.LENGTH_SHORT).show()
            return
        }

        val list = ListView(this)
        list.adapter = AppAdapter(items)
        list.divider = null
        list.clipToPadding = false
        list.setPadding(dp(16), dp(8), dp(16), dp(8))

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.vault_add_app)
            .setView(list)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            Prefs.setTargets(this, Prefs.targets(this) + items[position].activityInfo.packageName)
            renderBlocked()
            dialog.dismiss()
        }
        dialog.show()
    }

    private inner class AppAdapter(private val items: List<ResolveInfo>) : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row_app, parent, false)
            val info = items[position]
            row.findViewById<ImageView>(R.id.ivIcon).setImageDrawable(info.loadIcon(packageManager))
            row.findViewById<TextView>(R.id.tvLabel).text = info.loadLabel(packageManager)
            row.findViewById<TextView>(R.id.tvPackage).text = info.activityInfo.packageName
            row.findViewById<View>(R.id.btnRemove).visibility = View.GONE
            return row
        }
    }

    // ---- Helpers ------------------------------------------------------------

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PIN_LENGTH = 4
    }
}
