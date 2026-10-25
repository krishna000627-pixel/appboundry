package com.appboundary

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.service.quicksettings.TileService
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * The secret vault: PIN gate, then a multi-page home reached from the three-bar header menu.
 * Opened only by long-pressing the Quick Settings tile; re-locks whenever it leaves the screen.
 */
class VaultActivity : Activity() {

    private enum class Mode { UNLOCK, CREATE, CONFIRM }

    private enum class Page(val titleRes: Int) {
        HOME(R.string.vault_nav_home),
        BLOCKED(R.string.vault_nav_blocked),
        ALERT(R.string.vault_nav_alert),
        UNLOCK(R.string.vault_nav_unlock),
        SECURITY(R.string.vault_nav_security)
    }

    // PIN gate
    private lateinit var gate: View
    private lateinit var tvGateTitle: TextView
    private lateinit var tvGateSub: TextView
    private lateinit var dots: LinearLayout
    private lateinit var keypad: LinearLayout
    private lateinit var tvForgot: TextView

    // Vault shell
    private lateinit var vaultRoot: View
    private lateinit var tvPageTitle: TextView
    private lateinit var scrim: View
    private lateinit var drawer: View
    private lateinit var pageViews: Map<Page, View>
    private lateinit var navItems: Map<Page, TextView>

    // Home
    private lateinit var switchFocus: Switch
    private lateinit var tvFocusState: TextView
    private lateinit var cardAccess: View
    private lateinit var tvBlockedCount: TextView
    private lateinit var tvUnlockCount: TextView

    // Blocked apps
    private lateinit var llBlocked: LinearLayout
    private lateinit var tvBlockedEmpty: TextView

    // Alert text
    private lateinit var etTitle: EditText
    private lateinit var etMessage: EditText

    // Unlock methods
    private lateinit var swTapHold: Switch
    private lateinit var swVolume: Switch
    private lateinit var swCorner: Switch
    private lateinit var swTwoFinger: Switch
    private lateinit var tvTaps: TextView
    private lateinit var tvVolumeCode: TextView
    private lateinit var tvCornerCode: TextView
    private lateinit var btnVolumeSet: TextView
    private lateinit var btnCornerSet: TextView
    private lateinit var llCornerPad: View

    // Picker
    private lateinit var pickerLayer: View
    private lateinit var etSearch: EditText
    private lateinit var lvPicker: ListView
    private lateinit var tvPickerEmpty: TextView
    private lateinit var btnPickerAdd: TextView
    private val pickerAdapter = PickerAdapter()
    private val pickerSelected = LinkedHashSet<String>()

    private var mode = Mode.UNLOCK
    private var currentPage = Page.HOME
    private var changingPin = false
    private var drawerOpen = false
    private var rendering = false
    private var firstPin: String? = null
    private val entered = StringBuilder()
    private var lockTimer: CountDownTimer? = null

    private var recordingVolume = false
    private var recordingCorner = false
    private val volumeDraft = StringBuilder()
    private val cornerDraft = StringBuilder()

    // ---- Lifecycle ----------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        bindViews()
        buildDots()
        buildKeypad()
        bindVaultActions()
        bindUnlockActions()
        bindPicker()
        drawer.translationX = -dp(DRAWER_WIDTH_DP).toFloat()
        showGate(if (Prefs.hasPin(this)) Mode.UNLOCK else Mode.CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (vaultRoot.visibility == View.VISIBLE) refreshAll()
    }

    /** Leaving the screen always locks the vault again. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && vaultRoot.visibility == View.VISIBLE) {
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
        when {
            pickerLayer.visibility == View.VISIBLE -> closePicker()
            drawerOpen -> closeDrawer(true)
            gate.visibility == View.VISIBLE && changingPin -> {
                changingPin = false
                openVault()
            }
            vaultRoot.visibility == View.VISIBLE && currentPage != Page.HOME -> showPage(Page.HOME)
            else -> super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (recordingVolume && event.repeatCount == 0) {
            val symbol = when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP -> 'U'
                KeyEvent.KEYCODE_VOLUME_DOWN -> 'D'
                else -> null
            }
            if (symbol != null) {
                if (volumeDraft.length < MAX_VOLUME) volumeDraft.append(symbol)
                tvVolumeCode.text = volumeText(volumeDraft.toString())
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (recordingVolume &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun bindViews() {
        gate = findViewById(R.id.gate)
        tvGateTitle = findViewById(R.id.tvGateTitle)
        tvGateSub = findViewById(R.id.tvGateSub)
        dots = findViewById(R.id.dots)
        keypad = findViewById(R.id.keypad)
        tvForgot = findViewById(R.id.tvForgot)

        vaultRoot = findViewById(R.id.vaultRoot)
        tvPageTitle = findViewById(R.id.tvPageTitle)
        scrim = findViewById(R.id.scrim)
        drawer = findViewById(R.id.drawer)
        pageViews = mapOf(
            Page.HOME to findViewById<View>(R.id.pageHome),
            Page.BLOCKED to findViewById<View>(R.id.pageBlocked),
            Page.ALERT to findViewById<View>(R.id.pageAlert),
            Page.UNLOCK to findViewById<View>(R.id.pageUnlock),
            Page.SECURITY to findViewById<View>(R.id.pageSecurity)
        )
        navItems = mapOf(
            Page.HOME to findViewById<TextView>(R.id.navHome),
            Page.BLOCKED to findViewById<TextView>(R.id.navBlocked),
            Page.ALERT to findViewById<TextView>(R.id.navAlert),
            Page.UNLOCK to findViewById<TextView>(R.id.navUnlock),
            Page.SECURITY to findViewById<TextView>(R.id.navSecurity)
        )

        switchFocus = findViewById(R.id.switchFocus)
        tvFocusState = findViewById(R.id.tvFocusState)
        cardAccess = findViewById(R.id.cardAccess)
        tvBlockedCount = findViewById(R.id.tvBlockedCount)
        tvUnlockCount = findViewById(R.id.tvUnlockCount)

        llBlocked = findViewById(R.id.llBlocked)
        tvBlockedEmpty = findViewById(R.id.tvBlockedEmpty)

        etTitle = findViewById(R.id.etTitle)
        etMessage = findViewById(R.id.etMessage)

        swTapHold = findViewById(R.id.swTapHold)
        swVolume = findViewById(R.id.swVolume)
        swCorner = findViewById(R.id.swCorner)
        swTwoFinger = findViewById(R.id.swTwoFinger)
        tvTaps = findViewById(R.id.tvTaps)
        tvVolumeCode = findViewById(R.id.tvVolumeCode)
        tvCornerCode = findViewById(R.id.tvCornerCode)
        btnVolumeSet = findViewById(R.id.btnVolumeSet)
        btnCornerSet = findViewById(R.id.btnCornerSet)
        llCornerPad = findViewById(R.id.llCornerPad)

        pickerLayer = findViewById(R.id.pickerLayer)
        etSearch = findViewById(R.id.etSearch)
        lvPicker = findViewById(R.id.lvPicker)
        tvPickerEmpty = findViewById(R.id.tvPickerEmpty)
        btnPickerAdd = findViewById(R.id.btnPickerAdd)
    }

    // ---- PIN gate -----------------------------------------------------------

    private fun showGate(newMode: Mode) {
        mode = newMode
        entered.clear()
        if (newMode != Mode.CONFIRM) firstPin = null
        stopRecording()
        closeDrawer(false)
        pickerLayer.visibility = View.GONE
        vaultRoot.visibility = View.GONE
        gate.visibility = View.VISIBLE
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

    // ---- Vault shell: pages and drawer --------------------------------------

    private fun openVault() {
        lockTimer?.cancel()
        entered.clear()
        gate.visibility = View.GONE
        vaultRoot.visibility = View.VISIBLE
        vaultRoot.alpha = 0f
        vaultRoot.animate().alpha(1f).setDuration(220).start()
        loadAlertTexts()
        showPage(Page.HOME)
        // Warm the app-name cache in the background so the picker opens instantly.
        AppCache.sync(this) { }
    }

    private fun showPage(page: Page) {
        stopRecording()
        currentPage = page
        for ((p, view) in pageViews) {
            view.visibility = if (p == page) View.VISIBLE else View.GONE
        }
        pageViews[page]?.let {
            it.scrollTo(0, 0)
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(160).start()
        }
        tvPageTitle.setText(page.titleRes)
        for ((p, item) in navItems) {
            val selected = p == page
            item.setBackgroundResource(if (selected) R.drawable.bg_nav_selected else 0)
            item.setTextColor(getColor(if (selected) R.color.vault_accent else R.color.vault_text))
        }
        closeDrawer(true)
        refreshAll()
    }

    private fun openDrawer() {
        if (drawerOpen) return
        drawerOpen = true
        scrim.visibility = View.VISIBLE
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(180).start()
        drawer.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeDrawer(animate: Boolean) {
        val wasOpen = drawerOpen
        drawerOpen = false
        val hidden = -dp(DRAWER_WIDTH_DP).toFloat()
        if (animate && wasOpen) {
            scrim.animate().alpha(0f).setDuration(160).withEndAction { scrim.visibility = View.GONE }.start()
            drawer.animate().translationX(hidden).setDuration(200).start()
        } else {
            scrim.animate().cancel()
            drawer.animate().cancel()
            scrim.visibility = View.GONE
            drawer.translationX = hidden
        }
    }

    private fun bindVaultActions() {
        findViewById<View>(R.id.btnLock).setOnClickListener { finish() }
        findViewById<View>(R.id.btnMenu).setOnClickListener {
            if (drawerOpen) closeDrawer(true) else openDrawer()
        }
        scrim.setOnClickListener { closeDrawer(true) }
        for ((page, item) in navItems) item.setOnClickListener { showPage(page) }

        // Home
        switchFocus.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                Prefs.setEnabled(this, checked)
                renderFocusState(checked)
                TileService.requestListeningState(this, ComponentName(this, QuickTileService::class.java))
            }
        }
        findViewById<View>(R.id.btnAccess).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<View>(R.id.tileBlocked).setOnClickListener { showPage(Page.BLOCKED) }
        findViewById<View>(R.id.tileUnlock).setOnClickListener { showPage(Page.UNLOCK) }

        // Blocked apps
        findViewById<View>(R.id.btnAddApp).setOnClickListener { openPicker() }

        // Alert text
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

        // Security
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

    private fun refreshAll() {
        rendering = true
        switchFocus.isChecked = Prefs.isEnabled(this)
        swTapHold.isChecked = Prefs.unlockEnabled(this, Prefs.METHOD_TAP_HOLD)
        swVolume.isChecked = Prefs.unlockEnabled(this, Prefs.METHOD_VOLUME)
        swCorner.isChecked = Prefs.unlockEnabled(this, Prefs.METHOD_CORNER)
        swTwoFinger.isChecked = Prefs.unlockEnabled(this, Prefs.METHOD_TWO_FINGER)
        rendering = false

        renderFocusState(Prefs.isEnabled(this))
        cardAccess.visibility = if (isServiceEnabled()) View.GONE else View.VISIBLE
        tvBlockedCount.text = Prefs.targets(this).size.toString()
        tvUnlockCount.text = Prefs.activeUnlockCount(this).toString()

        renderBlocked()

        tvTaps.text = Prefs.unlockTaps(this).toString()
        if (!recordingVolume) tvVolumeCode.text = volumeText(Prefs.volumeCode(this))
        if (!recordingCorner) tvCornerCode.text = cornerText(Prefs.cornerCode(this))
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

    // ---- Unlock methods page ------------------------------------------------

    private fun bindUnlockActions() {
        bindUnlockSwitch(swTapHold, Prefs.METHOD_TAP_HOLD)
        bindUnlockSwitch(swVolume, Prefs.METHOD_VOLUME)
        bindUnlockSwitch(swCorner, Prefs.METHOD_CORNER)
        bindUnlockSwitch(swTwoFinger, Prefs.METHOD_TWO_FINGER)

        findViewById<View>(R.id.btnTapsMinus).setOnClickListener { changeTaps(-1) }
        findViewById<View>(R.id.btnTapsPlus).setOnClickListener { changeTaps(1) }

        btnVolumeSet.setOnClickListener {
            if (!recordingVolume) {
                recordingVolume = true
                volumeDraft.clear()
                btnVolumeSet.setText(R.string.vault_save)
                tvVolumeCode.setText(R.string.vault_listening)
            } else if (volumeDraft.length >= MIN_VOLUME) {
                Prefs.setVolumeCode(this, volumeDraft.toString())
                Toast.makeText(this, R.string.vault_code_saved, Toast.LENGTH_SHORT).show()
                stopRecording()
            } else {
                Toast.makeText(this, R.string.vault_need_more, Toast.LENGTH_SHORT).show()
            }
        }

        btnCornerSet.setOnClickListener {
            if (!recordingCorner) {
                recordingCorner = true
                cornerDraft.clear()
                btnCornerSet.setText(R.string.vault_save)
                tvCornerCode.setText(R.string.vault_listening_tap)
                llCornerPad.visibility = View.VISIBLE
            } else if (cornerDraft.length >= MIN_CORNER) {
                Prefs.setCornerCode(this, cornerDraft.toString())
                Toast.makeText(this, R.string.vault_code_saved, Toast.LENGTH_SHORT).show()
                stopRecording()
            } else {
                Toast.makeText(this, R.string.vault_need_more, Toast.LENGTH_SHORT).show()
            }
        }

        val pads = listOf(R.id.pad1 to '1', R.id.pad2 to '2', R.id.pad3 to '3', R.id.pad4 to '4')
        for ((id, symbol) in pads) {
            findViewById<View>(id).setOnClickListener {
                if (recordingCorner && cornerDraft.length < MAX_CORNER) {
                    cornerDraft.append(symbol)
                    tvCornerCode.text = cornerText(cornerDraft.toString())
                }
            }
        }
    }

    private fun bindUnlockSwitch(sw: Switch, method: String) {
        sw.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                Prefs.setUnlockEnabled(this, method, checked)
                tvUnlockCount.text = Prefs.activeUnlockCount(this).toString()
            }
        }
    }

    private fun changeTaps(delta: Int) {
        val value = (Prefs.unlockTaps(this) + delta).coerceIn(Prefs.MIN_TAPS, Prefs.MAX_TAPS)
        Prefs.setUnlockTaps(this, value)
        tvTaps.text = value.toString()
    }

    private fun stopRecording() {
        recordingVolume = false
        recordingCorner = false
        btnVolumeSet.setText(R.string.vault_set_pattern)
        btnCornerSet.setText(R.string.vault_set_pattern)
        llCornerPad.visibility = View.GONE
        tvVolumeCode.text = volumeText(Prefs.volumeCode(this))
        tvCornerCode.text = cornerText(Prefs.cornerCode(this))
    }

    private fun volumeText(code: String): String =
        code.map { if (it == 'U') "\u2191" else "\u2193" }.joinToString("  ")

    private fun cornerText(code: String): String =
        code.map {
            when (it) {
                '1' -> "\u2196"
                '2' -> "\u2197"
                '3' -> "\u2199"
                else -> "\u2198"
            }
        }.joinToString("  ")

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
        val targets = Prefs.targets(this).sortedBy { AppCache.label(this, it).lowercase() }
        tvBlockedEmpty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        for (pkg in targets) {
            val row = layoutInflater.inflate(R.layout.row_app, llBlocked, false)
            row.findViewById<TextView>(R.id.tvLabel).text = AppCache.label(this, pkg)
            bindIcon(row.findViewById(R.id.ivIcon), pkg)
            row.findViewById<View>(R.id.btnRemove).setOnClickListener {
                Prefs.setTargets(this, Prefs.targets(this) - pkg)
                renderBlocked()
                tvBlockedCount.text = Prefs.targets(this).size.toString()
            }
            llBlocked.addView(row)
        }
    }

    /** Shows a cached icon at once; otherwise loads it off the main thread. */
    private fun bindIcon(view: ImageView, pkg: String) {
        view.tag = pkg
        val cached = AppCache.cachedIcon(pkg)
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageDrawable(null)
        AppCache.loadIcon(this, pkg) { bitmap ->
            if (view.tag == pkg && bitmap != null) view.setImageBitmap(bitmap)
        }
    }

    // ---- App picker ---------------------------------------------------------

    private fun bindPicker() {
        lvPicker.adapter = pickerAdapter
        lvPicker.setOnItemClickListener { _, _, position, _ ->
            val entry = pickerAdapter.items[position]
            if (!pickerSelected.add(entry.pkg)) pickerSelected.remove(entry.pkg)
            pickerAdapter.notifyDataSetChanged()
            updatePickerButton()
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                refreshPicker()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        findViewById<View>(R.id.btnPickerBack).setOnClickListener { closePicker() }
        btnPickerAdd.setOnClickListener {
            if (pickerSelected.isEmpty()) return@setOnClickListener
            Prefs.setTargets(this, Prefs.targets(this) + pickerSelected)
            closePicker()
            renderBlocked()
            tvBlockedCount.text = Prefs.targets(this).size.toString()
        }
    }

    private fun openPicker() {
        pickerSelected.clear()
        etSearch.setText("")
        pickerLayer.visibility = View.VISIBLE
        pickerLayer.alpha = 0f
        pickerLayer.animate().alpha(1f).setDuration(160).start()
        refreshPicker()
        // Only apps installed or removed since last time are touched; the rest comes from the cache.
        AppCache.sync(this) { changed ->
            if (changed && pickerLayer.visibility == View.VISIBLE) refreshPicker()
        }
    }

    private fun closePicker() {
        hideKeyboard()
        pickerLayer.visibility = View.GONE
    }

    private fun refreshPicker() {
        val blocked = Prefs.targets(this)
        val query = etSearch.text.toString().trim().lowercase()
        val list = AppCache.entries(this).filter {
            it.pkg !in blocked && (query.isEmpty() || it.label.lowercase().contains(query))
        }
        pickerAdapter.items = list
        pickerAdapter.notifyDataSetChanged()
        tvPickerEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvPickerEmpty.setText(
            if (AppCache.isEmpty(this)) R.string.vault_picker_loading else R.string.vault_picker_none
        )
        updatePickerButton()
    }

    private fun updatePickerButton() {
        val count = pickerSelected.size
        btnPickerAdd.text = if (count == 0) {
            getString(R.string.vault_select_apps)
        } else {
            getString(R.string.vault_add_n, count)
        }
        btnPickerAdd.alpha = if (count == 0) 0.4f else 1f
    }

    private inner class PickerAdapter : BaseAdapter() {
        var items: List<AppCache.Entry> = emptyList()

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: layoutInflater.inflate(R.layout.row_picker, parent, false)
            val entry = items[position]
            row.findViewById<TextView>(R.id.tvLabel).text = entry.label
            bindIcon(row.findViewById(R.id.ivIcon), entry.pkg)
            row.findViewById<ImageView>(R.id.ivCheck).setImageResource(
                if (entry.pkg in pickerSelected) R.drawable.ic_check_on else R.drawable.ic_check_off
            )
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
        private const val DRAWER_WIDTH_DP = 280
        private const val MIN_VOLUME = 3
        private const val MAX_VOLUME = 6
        private const val MIN_CORNER = 4
        private const val MAX_CORNER = 6
    }
}
