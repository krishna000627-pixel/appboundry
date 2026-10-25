package com.appboundary

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.WindowManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import android.widget.AbsListView
import android.widget.GridView
import android.widget.MediaController
import android.widget.VideoView
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.text.format.Formatter
import java.text.DateFormat
import java.util.Date
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import android.widget.FrameLayout
import android.view.animation.DecelerateInterpolator
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.graphics.Color
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
        PHOTOS(R.string.vault_nav_photos),
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

    private lateinit var cardMedia: View
    private lateinit var tvHomeMediaCount: TextView
    private lateinit var llHomeMedia: LinearLayout

    // Home status
    private lateinit var tvStatusAccess: TextView
    private lateinit var tvStatusFiles: TextView
    private lateinit var tvStatusStorage: TextView

    // Photos and videos
    private enum class Folder(val labelRes: Int) {
        SCREENSHOTS(R.string.vault_folder_screenshots),
        CAMERA(R.string.vault_folder_camera),
        WHATSAPP(R.string.vault_folder_whatsapp),
        OTHER(R.string.vault_folder_other),
        VIDEOS(R.string.vault_folder_videos)
    }

    private lateinit var gvMedia: GridView
    private lateinit var tvMediaEmpty: TextView
    private lateinit var tvMediaCount: TextView
    private lateinit var btnHideMedia: TextView
    private lateinit var cardFiles: View
    private lateinit var tabAll: TextView
    private lateinit var tabPhotos: TextView
    private lateinit var tabVideos: TextView
    private lateinit var btnSort: TextView
    private lateinit var llSelectBar: View
    private lateinit var tvSelCount: TextView
    private lateinit var viewerLayer: View
    private lateinit var pager: MediaPager
    private lateinit var viewerTop: View
    private lateinit var viewerBottom: View
    private lateinit var btnPlay: TextView
    private lateinit var llVideoBar: View
    private lateinit var seekVideo: SeekBar
    private lateinit var tvVideoTime: TextView
    private lateinit var cardFocus: View
    private lateinit var sheetLayer: View
    private lateinit var sheetPanel: View
    private lateinit var llSheetList: LinearLayout
    private var controlsVisible = true
    private var viewerCount = 0
    private var seeking = false
    private val ui = Handler(Looper.getMainLooper())
    private val progressTick = object : Runnable {
        override fun run() {
            updateVideoProgress()
            ui.postDelayed(this, 250)
        }
    }
    private val autoHide = Runnable { if (controlsVisible) setControls(false) }
    private lateinit var tvViewerTitle: TextView
    private lateinit var tvViewerInfo: TextView
    private lateinit var tvViewerSub: TextView
    private val mediaAdapter = MediaAdapter()
    private var allMedia: List<MediaVault.Item> = emptyList()
    private var mediaItems: List<MediaVault.Item> = emptyList()
    private data class FolderEntry(
        val key: String,
        val label: String,
        val items: List<MediaVault.Item>,
        val custom: Boolean
    )

    private var openFolder: String? = null
    private var folderList: List<FolderEntry> = emptyList()
    private var gridShowsFolders = true
    private var folderCellH = 0
    private val folderAdapter = FolderAdapter()
    private var newestFirst = true
    private var searchQuery = ""
    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    private var viewing: MediaVault.Item? = null
    private var viewIndex = -1
    private var cellPx = 0
    private var holdLockUntil = 0L

    // Page extras
    private lateinit var tvBlockedSummary: TextView
    private lateinit var tvPrevTitle: TextView
    private lateinit var tvPrevMsg: TextView
    private lateinit var tvAlertCounts: TextView
    private lateinit var tvUnlockSummary: TextView
    private lateinit var tvSecAccess: TextView
    private lateinit var tvSecFiles: TextView
    private lateinit var swShots: Switch

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

    override fun attachBaseContext(base: Context) {
        val cfg = Configuration(base.resources.configuration)
        val night = if (Prefs.isDark(base)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cfg.uiMode = (cfg.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        super.attachBaseContext(base.createConfigurationContext(cfg))
    }

    /** Dark or light status/navigation bar icons. */
    private fun setBarIcons(lightBars: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightBars) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            var f = window.decorView.systemUiVisibility
            f = if (lightBars) f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                f = if (lightBars) f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Hidden photos must not show up in screenshots or the recents preview.
        applyShotFlag()
        setContentView(R.layout.activity_vault)
        setBarIcons(!Prefs.isDark(this))
        bindViews()
        buildDots()
        buildKeypad()
        bindVaultActions()
        bindUnlockActions()
        bindPicker()
        bindMedia()
        bindExtras()
        drawer.translationX = -dp(DRAWER_WIDTH_DP).toFloat()
        showGate(if (Prefs.hasPin(this)) Mode.UNLOCK else Mode.CREATE)
    }

    override fun onResume() {
        super.onResume()
        if (holdLockUntil != 0L) {
            val expired = System.currentTimeMillis() > holdLockUntil
            holdLockUntil = 0L
            if (expired && vaultRoot.visibility == View.VISIBLE) {
                showGate(Mode.UNLOCK)
                return
            }
        }
        if (vaultRoot.visibility == View.VISIBLE) refreshAll()
    }

    /** Leaving the screen always locks the vault again. */
    override fun onStop() {
        super.onStop()
        if (System.currentTimeMillis() < holdLockUntil) return
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
            sheetLayer.visibility == View.VISIBLE -> closeSheet()
            viewerLayer.visibility == View.VISIBLE -> closeViewer()
            selectMode -> exitSelect()
            currentPage == Page.PHOTOS && openFolder != null -> closeFolder()
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
            Page.PHOTOS to findViewById<View>(R.id.pagePhotos),
            Page.BLOCKED to findViewById<View>(R.id.pageBlocked),
            Page.ALERT to findViewById<View>(R.id.pageAlert),
            Page.UNLOCK to findViewById<View>(R.id.pageUnlock),
            Page.SECURITY to findViewById<View>(R.id.pageSecurity)
        )
        navItems = mapOf(
            Page.HOME to findViewById<TextView>(R.id.navHome),
            Page.PHOTOS to findViewById<TextView>(R.id.navPhotos),
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
        tvStatusAccess = findViewById(R.id.tvStatusAccess)
        tvStatusFiles = findViewById(R.id.tvStatusFiles)
        tvStatusStorage = findViewById(R.id.tvStatusStorage)
        cardFiles = findViewById(R.id.cardFiles)
        tabAll = findViewById(R.id.tabAll)
        tabPhotos = findViewById(R.id.tabPhotos)
        tabVideos = findViewById(R.id.tabVideos)
        btnSort = findViewById(R.id.btnSort)
        llSelectBar = findViewById(R.id.llSelectBar)
        tvSelCount = findViewById(R.id.tvSelCount)
        tvViewerInfo = findViewById(R.id.tvViewerInfo)
        tvBlockedSummary = findViewById(R.id.tvBlockedSummary)
        tvPrevTitle = findViewById(R.id.tvPrevTitle)
        tvPrevMsg = findViewById(R.id.tvPrevMsg)
        tvAlertCounts = findViewById(R.id.tvAlertCounts)
        tvUnlockSummary = findViewById(R.id.tvUnlockSummary)
        tvSecAccess = findViewById(R.id.tvSecAccess)
        tvSecFiles = findViewById(R.id.tvSecFiles)
        swShots = findViewById(R.id.swShots)
        cardMedia = findViewById(R.id.cardMedia)
        tvHomeMediaCount = findViewById(R.id.tvHomeMediaCount)
        llHomeMedia = findViewById(R.id.llHomeMedia)

        gvMedia = findViewById(R.id.gvMedia)
        tvMediaEmpty = findViewById(R.id.tvMediaEmpty)
        tvMediaCount = findViewById(R.id.tvMediaCount)
        btnHideMedia = findViewById(R.id.btnHideMedia)
        viewerLayer = findViewById(R.id.viewerLayer)
        pager = findViewById(R.id.viewerPager)
        viewerTop = findViewById(R.id.viewerTop)
        viewerBottom = findViewById(R.id.viewerBottom)
        btnPlay = findViewById(R.id.btnPlay)
        llVideoBar = findViewById(R.id.llVideoBar)
        seekVideo = findViewById(R.id.seekVideo)
        tvVideoTime = findViewById(R.id.tvVideoTime)
        cardFocus = findViewById(R.id.cardFocus)
        sheetLayer = findViewById(R.id.sheetLayer)
        sheetPanel = findViewById(R.id.sheetPanel)
        llSheetList = findViewById(R.id.llSheetList)
        tvViewerTitle = findViewById(R.id.tvViewerTitle)
        tvViewerSub = findViewById(R.id.tvViewerSub)

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
        closeViewer()
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


    // ---- Permissions and status ---------------------------------------------

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun requestAllFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        holdLockUntil = System.currentTimeMillis() + HOLD_LOCK_MS
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (e2: Exception) {
                holdLockUntil = 0L
            }
        }
    }

    /** Asks once, the first time the Photos page opens without all-files access. */
    private fun maybeAskAllFiles() {
        if (hasAllFiles()) return
        val sp = getSharedPreferences("appboundary_media", MODE_PRIVATE)
        if (sp.getBoolean("asked_all_files", false)) return
        sp.edit().putBoolean("asked_all_files", true).apply()
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(R.string.vault_files_ask_title)
            .setMessage(R.string.vault_files_desc)
            .setNegativeButton(R.string.vault_files_later, null)
            .setPositiveButton(R.string.vault_files_grant) { _, _ -> requestAllFiles() }
            .show()
    }

    private fun applyShotFlag() {
        if (Prefs.blockShots(this)) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun setStatus(tv: TextView, ok: Boolean) {
        tv.setText(if (ok) R.string.vault_status_on else R.string.vault_status_off)
        tv.setTextColor(getColor(if (ok) R.color.vault_ok else R.color.vault_danger))
    }

    private fun renderStatus() {
        val access = isServiceEnabled()
        val files = hasAllFiles()
        setStatus(tvStatusAccess, access)
        setStatus(tvStatusFiles, files)
        setStatus(tvSecAccess, access)
        setStatus(tvSecFiles, files)
        cardFiles.visibility = if (files) View.GONE else View.VISIBLE
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun bindExtras() {
        // Home
        findViewById<View>(R.id.btnQuickApps).setOnClickListener {
            showPage(Page.BLOCKED)
            openPicker()
        }
        findViewById<View>(R.id.rowStatusAccess).setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.rowStatusFiles).setOnClickListener { requestAllFiles() }

        // Blocked apps
        findViewById<View>(R.id.btnRemoveAll).setOnClickListener {
            if (Prefs.targets(this).isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this, R.style.Vault_Dialog)
                .setTitle(R.string.vault_remove_all_title)
                .setMessage(R.string.vault_remove_all_msg)
                .setNegativeButton(R.string.vault_media_cancel, null)
                .setPositiveButton(R.string.vault_remove_all) { _, _ ->
                    Prefs.setTargets(this, emptySet())
                    refreshAll()
                }
                .show()
        }

        // Alert preview
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updateAlertPreview()
            }
        }
        etTitle.addTextChangedListener(watcher)
        etMessage.addTextChangedListener(watcher)

        // Security
        findViewById<View>(R.id.rowSecAccess).setOnClickListener { openAccessibilitySettings() }
        findViewById<View>(R.id.rowSecFiles).setOnClickListener { requestAllFiles() }
        swShots.isChecked = Prefs.blockShots(this)
        swShots.setOnCheckedChangeListener { _, checked ->
            if (!rendering) {
                Prefs.setBlockShots(this, checked)
                applyShotFlag()
            }
        }
        findViewById<View>(R.id.btnWipe).setOnClickListener {
            val n = MediaVault.count(this)
            if (n == 0) {
                Toast.makeText(this, R.string.vault_media_empty_short, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            AlertDialog.Builder(this, R.style.Vault_Dialog)
                .setTitle(R.string.vault_wipe_title)
                .setMessage(getString(R.string.vault_wipe_msg, n))
                .setNegativeButton(R.string.vault_media_cancel, null)
                .setPositiveButton(R.string.vault_media_delete) { _, _ ->
                    MediaVault.deleteAll(this)
                    exitSelect()
                    refreshMedia()
                }
                .show()
        }
    }

    private fun updateAlertPreview() {
        val app = Prefs.targets(this).firstOrNull()?.let { AppCache.label(this, it) }
            ?: getString(R.string.fallback_app_name)
        val rawTitle = etTitle.text.toString().trim()
        val rawMsg = etMessage.text.toString().trim()
        tvPrevTitle.text = if (rawTitle.isEmpty()) getString(R.string.alert_title) else rawTitle
        tvPrevMsg.text = if (rawMsg.isEmpty()) {
            getString(R.string.alert_message, app)
        } else {
            rawMsg.replace("{app}", app)
        }
        tvAlertCounts.text = getString(R.string.vault_alert_counts, rawTitle.length, rawMsg.length)
    }

    // ---- Photos and videos --------------------------------------------------

    private fun bindMedia() {
        cellPx = (resources.displayMetrics.widthPixels - dp(3)) / 4
        folderCellH = (((resources.displayMetrics.widthPixels - dp(40) - dp(6)) / 2) * 0.8f).toInt()
        gvMedia.numColumns = 2
        gvMedia.adapter = folderAdapter
        gvMedia.setOnItemClickListener { _, _, position, _ ->
            if (openFolder == null) {
                if (position == folderList.size) {
                    newFolderDialog(null)
                } else {
                    folderList.getOrNull(position)?.let {
                        openFolder = it.key
                        refreshMedia()
                    }
                }
                return@setOnItemClickListener
            }
            val item = mediaItems.getOrNull(position) ?: return@setOnItemClickListener
            if (selectMode) toggleSelected(item) else openViewer(item)
        }
        gvMedia.setOnItemLongClickListener { _, _, position, _ ->
            if (openFolder == null) {
                folderList.getOrNull(position)?.takeIf { it.custom }?.let { folderOptions(it) }
                return@setOnItemLongClickListener true
            }
            val item = mediaItems.getOrNull(position)
            if (item != null) {
                selectMode = true
                toggleSelected(item)
            }
            true
        }
        btnHideMedia.setOnClickListener { addDialog() }
        findViewById<View>(R.id.btnCleanOriginals).setOnClickListener { cleanOriginals() }
        tabAll.setOnClickListener { closeFolder() }
        findViewById<View>(R.id.btnFolderBack).setOnClickListener { closeFolder() }
        findViewById<View>(R.id.btnSearch).setOnClickListener {
            nameDialog(R.string.vault_search_title, searchQuery, android.R.string.ok) { q ->
                searchQuery = q
                refreshMedia()
            }
        }
        findViewById<View>(R.id.btnMore).setOnClickListener {
            val sort = getString(if (newestFirst) R.string.vault_more_sort_old else R.string.vault_more_sort_new)
            AlertDialog.Builder(this, R.style.Vault_Dialog)
                .setItems(arrayOf(getString(R.string.vault_more_select), sort)) { _, which ->
                    if (which == 0) {
                        if (mediaItems.isNotEmpty()) {
                            selectMode = true
                            renderSelection()
                        }
                    } else {
                        btnSort.performClick()
                    }
                }
                .show()
        }
        btnSort.setOnClickListener {
            newestFirst = !newestFirst
            refreshMedia()
        }
        findViewById<View>(R.id.btnGrantFiles).setOnClickListener { requestAllFiles() }

        findViewById<View>(R.id.btnSelAll).setOnClickListener {
            selected.addAll(mediaItems.map { it.id })
            renderSelection()
        }
        findViewById<View>(R.id.btnSelCancel).setOnClickListener { exitSelect() }
        findViewById<View>(R.id.btnSelRestore).setOnClickListener { restoreSelected() }
        findViewById<View>(R.id.btnSelMove).setOnClickListener { moveSelected() }
        findViewById<View>(R.id.btnSelDelete).setOnClickListener { confirmDeleteSelected() }

        findViewById<View>(R.id.btnViewerBack).setOnClickListener { closeViewer() }
        findViewById<View>(R.id.btnViewerRestore).setOnClickListener { restoreViewing() }
        findViewById<View>(R.id.btnViewerDelete).setOnClickListener { confirmDeleteViewing() }
        pager.host = pagerHost
        btnPlay.setOnClickListener { toggleVideo() }
        findViewById<View>(R.id.btnViewerFolder).setOnClickListener { openSheet() }
        findViewById<View>(R.id.btnViewerInfo).setOnClickListener { showViewerInfo() }
        sheetLayer.setOnClickListener { closeSheet() }
        seekVideo.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                val page = pager.currentPage()
                if (fromUser && page.videoReady) page.video.seekTo(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
                seeking = true
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                seeking = false
            }
        })
    }

    private fun closeFolder() {
        searchQuery = ""
        exitSelect()
        openFolder = null
        refreshMedia()
    }

    /** Sorts a hidden item into a folder by what it is, judging by its original file name. */
    private fun folderOf(item: MediaVault.Item): Folder {
        if (item.video) return Folder.VIDEOS
        val n = item.name.lowercase()
        return when {
            n.contains("screenshot") || n.contains("screen_shot") || n.startsWith("screen") -> Folder.SCREENSHOTS
            n.contains("-wa0") || n.contains("whatsapp") -> Folder.WHATSAPP
            n.startsWith("img_") || n.startsWith("pxl_") || n.startsWith("dsc") ||
                n.startsWith("mvimg") || n.matches(Regex("^\\d{8}[_-]\\d{6}.*")) -> Folder.CAMERA
            else -> Folder.OTHER
        }
    }

    /** Folder list on top level; thumbnails of one folder once it is opened. */
    private fun renderMediaMode() {
        val inFolder = openFolder != null
        if (inFolder == gridShowsFolders) {
            gridShowsFolders = !inFolder
            if (inFolder) {
                gvMedia.numColumns = 4
                gvMedia.horizontalSpacing = dp(1)
                gvMedia.verticalSpacing = dp(1)
                gvMedia.setPadding(0, 0, 0, dp(20))
                gvMedia.adapter = mediaAdapter
            } else {
                gvMedia.numColumns = 2
                gvMedia.horizontalSpacing = dp(6)
                gvMedia.verticalSpacing = dp(6)
                gvMedia.setPadding(dp(20), 0, dp(20), dp(20))
                gvMedia.adapter = folderAdapter
            }
        }
        folderAdapter.notifyDataSetChanged()
        val vis = if (inFolder) View.VISIBLE else View.GONE
        findViewById<View>(R.id.llFolderHeader).visibility = vis
        updateTopBar()
        findViewById<ImageView>(R.id.btnSearch).let {
            if (searchQuery.isEmpty()) it.clearColorFilter() else it.setColorFilter(getColor(R.color.vault_accent))
        }
        tvMediaCount.visibility = if (inFolder) View.GONE else View.VISIBLE
        tabAll.visibility = vis
        tabPhotos.visibility = View.GONE
        tabVideos.visibility = View.GONE
        btnSort.visibility = vis
        findViewById<View>(R.id.tvMediaHint).visibility = vis
        val f = folderList.firstOrNull { it.key == openFolder }
        if (f != null) {
            tabAll.text = f.label
            findViewById<TextView>(R.id.tvMediaHint).text = getString(R.string.vault_items_n, mediaItems.size)
        }
    }

    private fun styleTab(tv: TextView, on: Boolean) {
        tv.setBackgroundResource(if (on) R.drawable.bg_nav_selected else 0)
        tv.setTextColor(getColor(if (on) R.color.vault_accent else R.color.vault_text_dim))
    }

    private fun refreshMedia() {
        allMedia = MediaVault.list(this)
        folderList = buildFolders()
        if (openFolder != null && folderList.none { it.key == openFolder }) openFolder = null
        val cur = openFolder
        mediaItems = if (cur == null) {
            emptyList()
        } else {
            val l = folderList.first { it.key == cur }.items
                .filter { searchQuery.isEmpty() || it.name.contains(searchQuery, true) }
            if (newestFirst) l.sortedByDescending { it.added } else l.sortedBy { it.added }
        }
        btnSort.setText(if (newestFirst) R.string.vault_sort_newest else R.string.vault_sort_oldest)
        renderMediaMode()
        mediaAdapter.notifyDataSetChanged()
        selected.retainAll(allMedia.map { it.id }.toSet())
        syncViewer()

        val size = Formatter.formatFileSize(this, MediaVault.totalBytes(allMedia))
        tvMediaCount.text = getString(R.string.vault_media_summary, allMedia.size, size)
        tvStatusStorage.text = size
        val folderEmpty = openFolder != null && mediaItems.isEmpty()
        tvMediaEmpty.visibility = if (allMedia.isEmpty() || folderEmpty) View.VISIBLE else View.GONE
        tvMediaEmpty.setText(if (allMedia.isEmpty()) R.string.vault_media_empty else R.string.vault_folder_empty)
        tvHomeMediaCount.text = allMedia.size.toString()
        renderSelection()

        llHomeMedia.removeAllViews()
        if (allMedia.isEmpty()) {
            llHomeMedia.visibility = View.GONE
            return
        }
        llHomeMedia.visibility = View.VISIBLE
        val thumb = (resources.displayMetrics.widthPixels - dp(76) - dp(24)) / 4
        val shown = if (allMedia.size > 4) allMedia.take(3) else allMedia
        for (item in shown) {
            val iv = ImageView(this)
            iv.scaleType = ImageView.ScaleType.CENTER_CROP
            iv.setBackgroundResource(R.drawable.bg_thumb)
            iv.clipToOutline = true
            val lp = LinearLayout.LayoutParams(thumb, thumb)
            lp.marginEnd = dp(8)
            llHomeMedia.addView(iv, lp)
            iv.tag = item.id
            MediaVault.thumb(item, thumb * 2) { bmp ->
                if (iv.tag == item.id && bmp != null) iv.setImageBitmap(bmp)
            }
        }
        if (allMedia.size > 4) {
            val more = TextView(this)
            more.text = "+" + (allMedia.size - 3)
            more.gravity = Gravity.CENTER
            more.textSize = 18f
            more.setTypeface(null, android.graphics.Typeface.BOLD)
            more.setTextColor(getColor(R.color.vault_accent))
            more.setBackgroundResource(R.drawable.bg_thumb)
            llHomeMedia.addView(more, LinearLayout.LayoutParams(thumb, thumb))
        }
    }

    // -- selection --

    private fun toggleSelected(item: MediaVault.Item) {
        if (!selected.add(item.id)) selected.remove(item.id)
        renderSelection()
    }

    private fun renderSelection() {
        llSelectBar.visibility = if (selectMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.llSelectTop).visibility = if (selectMode) View.VISIBLE else View.GONE
        findViewById<View>(R.id.llFolderHeader).visibility =
            if (openFolder != null && !selectMode) View.VISIBLE else View.GONE
        tvSelCount.text = when (selected.size) {
            0 -> getString(R.string.vault_sel_none)
            1 -> getString(R.string.vault_sel_one)
            else -> getString(R.string.vault_sel_many, selected.size)
        }
        syncVisibleCells()
    }

    /** Only repaints the check marks of the cells on screen; thumbnails are never touched. */
    private fun syncVisibleCells() {
        if (openFolder == null) return
        val first = gvMedia.firstVisiblePosition
        for (i in 0 until gvMedia.childCount) {
            val item = mediaItems.getOrNull(first + i) ?: continue
            bindCellState(gvMedia.getChildAt(i), item, true)
        }
    }

    private fun bindCellState(v: View, item: MediaVault.Item, animate: Boolean) {
        val picked = selectMode && selected.contains(item.id)
        v.findViewById<View>(R.id.vSelect).visibility = if (picked) View.VISIBLE else View.GONE
        val check = v.findViewById<TextView>(R.id.tvCheck)
        check.visibility = if (selectMode) View.VISIBLE else View.GONE
        check.setBackgroundResource(if (picked) R.drawable.bg_check_circle else R.drawable.bg_check_ring)
        check.text = if (picked) getString(R.string.vault_media_check) else ""
        val sc = 1f
        if (animate) v.animate().scaleX(sc).scaleY(sc).setDuration(90).start() else {
            v.animate().cancel()
            v.scaleX = sc
            v.scaleY = sc
        }
    }

    private fun exitSelect() {
        selectMode = false
        selected.clear()
        renderSelection()
    }

    private fun restoreSelected() {
        val items = allMedia.filter { it.id in selected }
        if (items.isEmpty()) return
        val app = applicationContext
        Thread {
            var ok = 0
            for (item in items) {
                if (MediaVault.restore(app, item)) ok++
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                exitSelect()
                refreshMedia()
                if (ok > 0) {
                    Toast.makeText(this, getString(R.string.vault_media_restored_n, ok), Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.vault_media_restore_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDeleteSelected() {
        val items = allMedia.filter { it.id in selected }
        if (items.isEmpty()) return
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(R.string.vault_media_delete_title)
            .setMessage(R.string.vault_media_delete_msg)
            .setNegativeButton(R.string.vault_media_cancel, null)
            .setPositiveButton(R.string.vault_media_delete) { _, _ ->
                for (item in items) MediaVault.delete(this, item)
                exitSelect()
                refreshMedia()
                Toast.makeText(this, getString(R.string.vault_media_deleted_n, items.size), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // -- hiding --

    /** Finds gallery copies of things already hidden and deletes them after a confirmation. */
    private fun cleanOriginals() {
        val app = applicationContext
        Toast.makeText(this, R.string.vault_clean_searching, Toast.LENGTH_SHORT).show()
        Thread {
            val found = MediaVault.findLeftovers(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (found.isEmpty()) {
                    Toast.makeText(this, R.string.vault_clean_none, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                AlertDialog.Builder(this, R.style.Vault_Dialog)
                    .setTitle(R.string.vault_clean_title)
                    .setMessage(getString(R.string.vault_clean_msg, found.size))
                    .setNegativeButton(R.string.vault_media_cancel, null)
                    .setPositiveButton(R.string.vault_media_delete) { _, _ ->
                        val allFiles = hasAllFiles()
                        Thread {
                            val stuck = ArrayList<Uri>()
                            for (u in found) if (!MediaVault.deleteOriginal(app, u, allFiles)) stuck.add(u)
                            runOnUiThread {
                                if (isDestroyed) return@runOnUiThread
                                Toast.makeText(this, getString(R.string.vault_clean_done, found.size - stuck.size), Toast.LENGTH_LONG).show()
                                if (stuck.isNotEmpty()) askDeleteOriginals(stuck)
                            }
                        }.start()
                    }
                    .show()
            }
        }.start()
    }

    /** "Add" button: choose between the gallery picker and the file browser; both allow many at once. */
    private fun addDialog() {
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(R.string.vault_add_title)
            .setItems(arrayOf(getString(R.string.vault_add_gallery), getString(R.string.vault_add_files))) { _, which ->
                if (which == 0) pickMedia() else pickFiles()
            }
            .show()
    }

    private fun pickFiles() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        holdLockUntil = System.currentTimeMillis() + HOLD_LOCK_MS
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            holdLockUntil = 0L
            Toast.makeText(this, R.string.vault_no_gallery, Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens the gallery photo picker (not the Files app); older phones fall back to the gallery app. */
    private fun pickMedia() {
        val options = ArrayList<Intent>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            options.add(Intent("android.provider.action.PICK_IMAGES").apply {
                val max = if (Build.VERSION.SDK_INT >= 33) MediaStore.getPickImagesMaxLimit() else 100
                putExtra("android.provider.extra.PICK_IMAGES_MAX", max)
            })
        }
        options.add(Intent(Intent.ACTION_PICK).apply {
            setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
        options.add(Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        })
        // The picker takes the screen; do not re-lock while it is open.
        holdLockUntil = System.currentTimeMillis() + HOLD_LOCK_MS
        for (intent in options) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_PICK)
                return
            } catch (e: Exception) {
            }
        }
        holdLockUntil = 0L
        Toast.makeText(this, R.string.vault_no_gallery, Toast.LENGTH_SHORT).show()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val expired = holdLockUntil != 0L && System.currentTimeMillis() > holdLockUntil
        holdLockUntil = 0L
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
            } else {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty() && !(expired && vaultRoot.visibility == View.VISIBLE)) {
                chooseFolder(R.string.vault_folder_add_to) { target -> hideUris(uris, target) }
            }
        } else if (requestCode == REQ_DELETE) {
            Toast.makeText(this, R.string.vault_media_originals_done, Toast.LENGTH_SHORT).show()
        }
        if (expired && vaultRoot.visibility == View.VISIBLE) showGate(Mode.UNLOCK)
    }

    private fun hideUris(uris: List<Uri>, target: String? = null) {
        if (uris.isEmpty()) return
        btnHideMedia.isEnabled = false
        btnHideMedia.setText(R.string.vault_media_hiding)
        val app = applicationContext
        val allFiles = hasAllFiles()
        Thread {
            val copied = ArrayList<Uri>()
            val newIds = ArrayList<String>()
            val results = arrayOfNulls<MediaVault.Item>(uris.size)
            val pool = Executors.newFixedThreadPool(3)
            val done = AtomicInteger(0)
            val latch = CountDownLatch(uris.size)
            uris.forEachIndexed { i, u ->
                pool.execute {
                    try {
                        results[i] = MediaVault.hide(app, u)
                    } catch (e: Exception) {
                    } finally {
                        val n = done.incrementAndGet()
                        runOnUiThread {
                            if (!isDestroyed) btnHideMedia.text = getString(R.string.vault_media_hiding_n, n, uris.size)
                        }
                        latch.countDown()
                    }
                }
            }
            latch.await()
            pool.shutdown()
            for (i in uris.indices) {
                val hidden = results[i] ?: continue
                copied.add(uris[i])
                newIds.add(hidden.id)
            }
            if (target != null) FolderStore.assign(app, newIds, target)
            // Remove the originals; whatever the system refuses is handled below.
            val stuck = ArrayList<Uri>()
            for (u in copied) {
                if (!MediaVault.deleteOriginal(app, u, allFiles)) stuck.add(u)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                btnHideMedia.isEnabled = true
                btnHideMedia.setText(R.string.vault_media_hide)
                refreshMedia()
                if (copied.size < uris.size) {
                    Toast.makeText(this, R.string.vault_media_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, getString(R.string.vault_media_hidden_n, copied.size), Toast.LENGTH_SHORT).show()
                }
                if (stuck.isNotEmpty()) askDeleteOriginals(stuck)
            }
        }.start()
    }

    /** Asks the system (one confirmation dialog) to delete originals it would not let us remove directly. */
    private fun askDeleteOriginals(stuck: List<Uri>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mediaUris = ArrayList<Uri>()
            for (u in stuck) {
                try {
                    (MediaVault.resolveMediaUri(this, u) ?: MediaStore.getMediaUri(this, u))?.let { mediaUris.add(it) }
                } catch (e: Exception) {
                }
            }
            if (mediaUris.isNotEmpty()) {
                try {
                    val pi = MediaStore.createDeleteRequest(contentResolver, mediaUris)
                    holdLockUntil = System.currentTimeMillis() + HOLD_LOCK_MS
                    startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
                    return
                } catch (e: Exception) {
                    holdLockUntil = 0L
                }
            }
        }
        Toast.makeText(this, R.string.vault_media_originals, Toast.LENGTH_LONG).show()
    }

    // -- viewer --

    private val pagerHost = object : MediaPager.Host {
        override fun count(): Int = mediaItems.size
        override fun bind(page: MediaPager.Page, index: Int) = bindViewerPage(page, index)
        override fun onTap() = setControls(!controlsVisible)
        override fun onSettled(index: Int) = onViewerSettled(index)
        override fun onDragProgress(progress: Float) {
            viewerLayer.alpha = 1f - 0.75f * progress
        }

        override fun onDismiss() = closeViewer()
    }

    private fun openViewer(item: MediaVault.Item) {
        val idx = mediaItems.indexOfFirst { it.id == item.id }
        if (idx < 0) return
        viewing = item
        viewIndex = idx
        viewerCount = mediaItems.size
        viewerLayer.alpha = 1f
        viewerLayer.visibility = View.VISIBLE
        enterFullScreen()
        setControls(true, false)
        pager.open(idx)
    }

    private fun bindViewerPage(page: MediaPager.Page, index: Int) {
        val item = mediaItems.getOrNull(index) ?: return
        page.resetState()
        page.token = item.id
        page.isVideo = item.video
        page.image.setImageDrawable(null)
        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        if (item.video) {
            MediaVault.thumb(item, maxPx / 2) { bmp: Bitmap? ->
                if (page.token == item.id && bmp != null) page.image.setImageBitmap(bmp)
            }
        } else {
            Thread {
                val bmp = MediaVault.decode(item, maxPx)
                runOnUiThread {
                    if (!isDestroyed && page.token == item.id) page.image.setImageBitmap(bmp)
                }
            }.start()
        }
    }

    private fun onViewerSettled(index: Int) {
        val item = mediaItems.getOrNull(index) ?: return
        viewIndex = index
        viewing = item
        stopVideoUi()
        tvViewerTitle.text = when {
            android.text.format.DateUtils.isToday(item.added) -> getString(R.string.vault_day_today)
            android.text.format.DateUtils.isToday(item.added + 86400000L) -> getString(R.string.vault_day_yesterday)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.added))
        }
        tvViewerSub.text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(item.added))
        val type = getString(if (item.video) R.string.vault_type_video else R.string.vault_type_photo)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.added))
        tvViewerInfo.text = getString(
            R.string.vault_viewer_info,
            type,
            Formatter.formatFileSize(this, item.file.length()),
            date,
            index + 1,
            mediaItems.size
        )
        llVideoBar.visibility = if (item.video) View.VISIBLE else View.GONE
        btnPlay.animate().cancel()
        btnPlay.alpha = 1f
        btnPlay.visibility = if (item.video && controlsVisible) View.VISIBLE else View.GONE
        setPlayIcon(false)
        seekVideo.progress = 0
        tvVideoTime.text = ""
        viewerLayer.post { fitPager(false) }
    }

    /** Tap on the picture: hide or show the title bar, buttons and play control. */
    private fun setControls(show: Boolean, animate: Boolean = true) {
        controlsVisible = show
        applyBars(show)
        ui.removeCallbacks(autoHide)
        val isVideo = viewing?.video == true
        for (v in listOf(viewerTop, viewerBottom, btnPlay)) {
            v.animate().cancel()
            val allowed = v !== btnPlay || isVideo
            if (show && allowed) {
                v.visibility = View.VISIBLE
                if (animate) v.animate().alpha(1f).setDuration(160).start() else v.alpha = 1f
            } else if (animate && v.visibility == View.VISIBLE) {
                v.animate().alpha(0f).setDuration(160).withEndAction {
                    if (!controlsVisible || !allowed) v.visibility = View.GONE
                }.start()
            } else {
                v.alpha = if (show) 1f else 0f
                v.visibility = View.GONE
            }
        }
        if (show && isPlayingVideo()) ui.postDelayed(autoHide, 2500)
        fitPager(animate)
    }

    /** Like the gallery: the picture fills the page and the bars float over it. */
    private fun fitPager(animate: Boolean, retry: Boolean = true) {
        pager.animate().cancel()
        pager.scaleX = 1f
        pager.scaleY = 1f
        pager.translationY = 0f
        btnPlay.translationY = 0f
    }

    private fun isPlayingVideo(): Boolean {
        val p = pager.currentPage()
        return p.isVideo && p.videoReady && p.video.isPlaying
    }

    private fun setPlayIcon(playing: Boolean) {
        btnPlay.setText(if (playing) R.string.vault_media_pause else R.string.vault_media_play)
    }

    private fun toggleVideo() {
        val page = pager.currentPage()
        val item = viewing
        if (!page.isVideo || item == null) return
        val v = page.video
        if (page.videoReady) {
            if (v.isPlaying) {
                v.pause()
                setPlayIcon(false)
                ui.removeCallbacks(autoHide)
            } else {
                v.start()
                setPlayIcon(true)
                ui.postDelayed(autoHide, 2500)
            }
            return
        }
        v.visibility = View.VISIBLE
        v.setOnPreparedListener {
            page.videoReady = true
            page.image.visibility = View.INVISIBLE
            seekVideo.max = v.duration
            v.start()
            setPlayIcon(true)
            ui.removeCallbacks(progressTick)
            ui.post(progressTick)
            ui.postDelayed(autoHide, 2500)
        }
        v.setOnCompletionListener {
            setPlayIcon(false)
            ui.removeCallbacks(autoHide)
            updateVideoProgress()
            setControls(true)
        }
        v.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, R.string.vault_media_restore_failed, Toast.LENGTH_SHORT).show()
            true
        }
        v.setVideoPath(item.file.absolutePath)
    }

    private fun updateVideoProgress() {
        val page = pager.currentPage()
        if (!page.isVideo || !page.videoReady) return
        val pos = page.video.currentPosition
        val dur = page.video.duration
        if (!seeking) seekVideo.progress = pos
        tvVideoTime.text = clock(pos) + " / " + clock(dur)
    }

    private fun clock(ms: Int): String {
        val sec = ms / 1000
        return String.format(java.util.Locale.US, "%d:%02d", sec / 60, sec % 60)
    }

    private fun stopVideoUi() {
        ui.removeCallbacks(progressTick)
        ui.removeCallbacks(autoHide)
    }

    /** Keeps the open viewer in step after items were restored, deleted or re-sorted. */
    private fun syncViewer() {
        if (viewerLayer.visibility != View.VISIBLE) return
        if (mediaItems.isEmpty()) {
            closeViewer()
            return
        }
        val cur = viewing
        val idx = if (cur == null) -1 else mediaItems.indexOfFirst { it.id == cur.id }
        if (idx >= 0 && idx == pager.index && mediaItems.size == viewerCount) return
        viewerCount = mediaItems.size
        viewIndex = if (idx >= 0) idx else viewIndex.coerceIn(0, mediaItems.size - 1)
        pager.open(viewIndex)
    }

    private fun closeViewer() {
        if (!::viewerLayer.isInitialized) return
        stopVideoUi()
        viewerLayer.animate().cancel()
        viewing = null
        viewIndex = -1
        pager.release()
        sheetLayer.visibility = View.GONE
        viewerLayer.visibility = View.GONE
        viewerLayer.alpha = 1f
        pager.scaleX = 1f
        pager.scaleY = 1f
        pager.translationY = 0f
        btnPlay.translationY = 0f
        exitFullScreen()
    }

    // Full-screen viewer: draw behind the system bars and hide them together with the controls.
    private var fullScreen = false

    private fun enterFullScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || fullScreen) return
        fullScreen = true
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        viewerLayer.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            viewerTop.layoutParams = viewerTop.layoutParams.also { it.height = dp(70) + bars.top }
            viewerTop.setPadding(dp(4), dp(20) + bars.top, dp(16), 0)
            viewerBottom.setPadding(0, dp(6), 0, bars.bottom)
            sheetPanel.setPadding(dp(8), dp(10), dp(8), dp(16) + bars.bottom)
            viewerLayer.post { fitPager(false) }
            insets
        }
        viewerLayer.requestApplyInsets()
        setBarIcons(false)
    }

    private fun exitFullScreen() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !fullScreen) return
        fullScreen = false
        viewerLayer.setOnApplyWindowInsetsListener(null)
        window.insetsController?.show(WindowInsets.Type.systemBars())
        window.setDecorFitsSystemWindows(true)
        window.statusBarColor = getColor(R.color.vault_bg)
        window.navigationBarColor = getColor(R.color.vault_bg)
        setBarIcons(!Prefs.isDark(this))
    }

    private fun applyBars(show: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !fullScreen) return
        val c = window.insetsController ?: return
        if (show) {
            c.show(WindowInsets.Type.systemBars())
        } else {
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsets.Type.systemBars())
        }
    }

    private fun showViewerInfo() {
        val item = viewing ?: return
        val type = getString(if (item.video) R.string.vault_type_video else R.string.vault_type_photo)
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.added))
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(R.string.vault_viewer_info_title)
            .setMessage(
                getString(
                    R.string.vault_viewer_info_body,
                    item.name, type, Formatter.formatFileSize(this, item.file.length()),
                    date, viewIndex + 1, mediaItems.size
                )
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun restoreViewing() {
        val item = viewing ?: return
        val app = applicationContext
        Thread {
            val ok = MediaVault.restore(app, item)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (ok) {
                    refreshMedia()
                    Toast.makeText(this, R.string.vault_media_restored, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, R.string.vault_media_restore_failed, Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun confirmDeleteViewing() {
        val item = viewing ?: return
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(R.string.vault_media_delete_title)
            .setMessage(R.string.vault_media_delete_msg)
            .setNegativeButton(R.string.vault_media_cancel, null)
            .setPositiveButton(R.string.vault_media_delete) { _, _ ->
                MediaVault.delete(this, item)
                refreshMedia()
            }
            .show()
    }

    private fun buildFolders(): List<FolderEntry> {
        val assign = FolderStore.assignments(this)
        val customNames = FolderStore.names(this)
        val customItems = customNames.associateWith { ArrayList<MediaVault.Item>() }
        val autoItems = Folder.values().associateWith { ArrayList<MediaVault.Item>() }
        for (item in allMedia) {
            val mine = assign[item.id]?.let { customItems[it] }
            if (mine != null) mine.add(item) else autoItems.getValue(folderOf(item)).add(item)
        }
        val out = ArrayList<FolderEntry>()
        for (n in customNames) out.add(FolderEntry("c:$n", n, customItems.getValue(n), true))
        for (f in Folder.values()) {
            val list = autoItems.getValue(f)
            if (list.isNotEmpty()) out.add(FolderEntry("a:" + f.name, getString(f.labelRes), list, false))
        }
        return out
    }

    private fun nameDialog(titleRes: Int, start: String, okRes: Int = R.string.vault_folder_create, onOk: (String) -> Unit) {
        val input = EditText(this)
        input.setText(start)
        input.setSingleLine()
        input.selectAll()
        input.setBackgroundResource(R.drawable.bg_input)
        input.setTextColor(getColor(R.color.vault_text))
        input.setHintTextColor(getColor(R.color.vault_text_dim))
        input.textSize = 16f
        input.setPadding(dp(16), dp(14), dp(16), dp(14))
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(22), dp(8), dp(22), dp(4))
        box.addView(input)
        val dlg = AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(titleRes)
            .setView(box)
            .setNegativeButton(R.string.vault_media_cancel, null)
            .setPositiveButton(okRes) { _, _ -> onOk(input.text.toString().trim()) }
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
        input.requestFocus()
    }

    // -- "Add to folder" sheet on the picture page --

    private fun openSheet() {
        val item = viewing ?: return
        val current = FolderStore.assignments(this)[item.id]
        llSheetList.removeAllViews()
        fun addRow(label: String, iconRes: Int, checked: Boolean, onClick: () -> Unit) {
            val row = layoutInflater.inflate(R.layout.row_sheet, llSheetList, false)
            row.findViewById<TextView>(R.id.tvSheetName).text = label
            row.findViewById<ImageView>(R.id.ivSheetIcon).setImageResource(iconRes)
            row.findViewById<View>(R.id.tvSheetCheck).visibility = if (checked) View.VISIBLE else View.INVISIBLE
            row.setOnClickListener { onClick() }
            llSheetList.addView(row)
        }
        addRow(getString(R.string.vault_folder_auto), R.drawable.ic_photos, current == null) {
            assignViewing(null)
        }
        for (name in FolderStore.names(this)) {
            addRow(name, R.drawable.ic_folder, current == name) { assignViewing(name) }
        }
        addRow(getString(R.string.vault_folder_new), R.drawable.ic_folder_add, false) {
            newFolderDialog { created -> assignViewing(created) }
        }
        val scroll = findViewById<View>(R.id.sheetScroll)
        val maxH = (resources.displayMetrics.heightPixels * 0.5f).toInt()
        scroll.layoutParams = scroll.layoutParams.also {
            it.height = if (llSheetList.childCount * dp(52) > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        sheetLayer.animate().cancel()
        sheetPanel.animate().cancel()
        sheetLayer.alpha = 0f
        sheetLayer.visibility = View.VISIBLE
        sheetLayer.animate().alpha(1f).setDuration(180).start()
        sheetPanel.translationY = dp(500).toFloat()
        sheetPanel.animate().translationY(0f).setDuration(240).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun closeSheet() {
        if (sheetLayer.visibility != View.VISIBLE) return
        sheetLayer.animate().alpha(0f).setDuration(160).start()
        sheetPanel.animate().translationY(sheetPanel.height.toFloat()).setDuration(200)
            .withEndAction { sheetLayer.visibility = View.GONE }
            .start()
    }

    private fun assignViewing(target: String?) {
        val item = viewing ?: return
        FolderStore.assign(this, listOf(item.id), target)
        closeSheet()
        Toast.makeText(
            this,
            getString(R.string.vault_folder_moved_to, target ?: getString(R.string.vault_folder_auto)),
            Toast.LENGTH_SHORT
        ).show()
        refreshMedia()
    }

    /** Makes a folder named "Folder N" (the number is filled in, the name can be changed). */
    private fun newFolderDialog(onCreated: ((String) -> Unit)?) {
        nameDialog(R.string.vault_folder_new, FolderStore.nextName(this)) { name ->
            if (FolderStore.add(this, name)) {
                refreshMedia()
                onCreated?.invoke(name)
            } else {
                Toast.makeText(this, R.string.vault_folder_exists, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun folderOptions(entry: FolderEntry) {
        val name = entry.label
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(name)
            .setItems(
                arrayOf(getString(R.string.vault_folder_rename), getString(R.string.vault_folder_remove))
            ) { _, which ->
                if (which == 0) {
                    nameDialog(R.string.vault_folder_rename, name) { newName ->
                        if (FolderStore.rename(this, name, newName)) {
                            if (openFolder == entry.key) openFolder = "c:$newName"
                            refreshMedia()
                        } else {
                            Toast.makeText(this, R.string.vault_folder_exists, Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    AlertDialog.Builder(this, R.style.Vault_Dialog)
                        .setTitle(R.string.vault_folder_remove_title)
                        .setMessage(R.string.vault_folder_remove_msg)
                        .setNegativeButton(R.string.vault_media_cancel, null)
                        .setPositiveButton(R.string.vault_folder_remove) { _, _ ->
                            FolderStore.remove(this, name)
                            if (openFolder == entry.key) openFolder = null
                            refreshMedia()
                        }
                        .show()
                }
            }
            .show()
    }

    /** Lists the user's folders (and "new folder"); [onPick] gets the folder name, or null for automatic. */
    private fun chooseFolder(titleRes: Int, onPick: (String?) -> Unit) {
        val names = FolderStore.names(this)
        val labels = ArrayList<String>()
        labels.add(getString(R.string.vault_folder_auto))
        labels.addAll(names)
        labels.add(getString(R.string.vault_folder_new_item))
        AlertDialog.Builder(this, R.style.Vault_Dialog)
            .setTitle(titleRes)
            .setItems(labels.toTypedArray()) { _, which ->
                when {
                    which == 0 -> onPick(null)
                    which <= names.size -> onPick(names[which - 1])
                    else -> newFolderDialog { created -> onPick(created) }
                }
            }
            .show()
    }

    private fun moveSelected() {
        val ids = selected.toList()
        if (ids.isEmpty()) return
        chooseFolder(R.string.vault_folder_move_to) { target ->
            FolderStore.assign(this, ids, target)
            exitSelect()
            refreshMedia()
            Toast.makeText(this, getString(R.string.vault_folder_moved, ids.size), Toast.LENGTH_SHORT).show()
        }
    }

    private inner class FolderAdapter : BaseAdapter() {
        override fun getCount(): Int = folderList.size + 1
        override fun getItem(position: Int): Any = position
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.cell_folder, parent, false)
            v.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, folderCellH)
            val iv = v.findViewById<ImageView>(R.id.ivFolderThumb)
            val tvName = v.findViewById<TextView>(R.id.tvFolderName)
            val tvCount = v.findViewById<TextView>(R.id.tvFolderCount)
            iv.setImageDrawable(null)
            val icon = v.findViewById<ImageView>(R.id.ivFolderIcon)
            icon.visibility = View.GONE
            val entry = folderList.getOrNull(position)
            if (entry == null) {
                icon.setImageResource(R.drawable.ic_folder_add)
                icon.visibility = View.VISIBLE
                iv.tag = ""
                tvName.text = "+  " + getString(R.string.vault_folder_new)
                tvCount.setText(R.string.vault_folder_new_hint)
                return v
            }
            tvName.text = entry.label
            val first = entry.items.maxByOrNull { it.added }
            if (first == null) {
                icon.setImageResource(R.drawable.ic_folder)
                icon.visibility = View.VISIBLE
                iv.tag = ""
                tvCount.setText(R.string.vault_folder_empty_count)
                return v
            }
            iv.tag = first.id
            tvCount.text = entry.items.size.toString() + "  \u00B7  " +
                Formatter.formatFileSize(this@VaultActivity, MediaVault.totalBytes(entry.items))
            MediaVault.thumb(first, folderCellH * 2) { bmp: Bitmap? ->
                if (iv.tag == first.id && bmp != null) iv.setImageBitmap(bmp)
            }
            return v
        }
    }

    private inner class MediaAdapter : BaseAdapter() {
        override fun getCount(): Int = mediaItems.size
        override fun getItem(position: Int): Any = mediaItems[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: layoutInflater.inflate(R.layout.cell_media, parent, false)
            val lp = v.layoutParams
            if (lp == null || lp.height != cellPx) {
                v.layoutParams = AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, cellPx)
            }
            val iv = v.findViewById<ImageView>(R.id.ivThumb)
            val item = mediaItems[position]
            val badge = v.findViewById<TextView>(R.id.tvPlay)
            badge.visibility = if (item.video) View.VISIBLE else View.GONE
            if (item.video) {
                badge.tag = item.id
                MediaVault.duration(item) { t -> if (badge.tag == item.id) badge.text = t }
            }
            bindCellState(v, item, false)
            if (!(iv.tag == item.id && iv.drawable != null)) {
                iv.tag = item.id
                iv.setImageDrawable(null)
                MediaVault.thumb(item, cellPx) { bmp: Bitmap? ->
                    if (iv.tag == item.id && bmp != null) iv.setImageBitmap(bmp)
                }
            }
            return v
        }
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

    private fun updateTopBar() {
        findViewById<View>(R.id.vaultTopBar).visibility =
            if (currentPage == Page.PHOTOS && openFolder != null) View.GONE else View.VISIBLE
    }

    private fun showPage(page: Page) {
        stopRecording()
        if (page != Page.PHOTOS && selectMode) exitSelect()
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
        updateTopBar()
        closeDrawer(true)
        refreshAll()
        if (page == Page.PHOTOS) maybeAskAllFiles()
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
        cardMedia.setOnClickListener { showPage(Page.PHOTOS) }
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
            // The preview takes the screen; keep the vault unlocked so OK can return here.
            holdLockUntil = System.currentTimeMillis() + HOLD_LOCK_MS
            startActivity(AlertActivity.createIntent(this, target, true))
        }

        // Security
        val btnLight = findViewById<TextView>(R.id.btnThemeLight)
        val btnDark = findViewById<TextView>(R.id.btnThemeDark)
        val darkNow = Prefs.isDark(this)
        btnLight.setBackgroundResource(if (!darkNow) R.drawable.bg_btn_primary else R.drawable.bg_btn_tonal)
        btnDark.setBackgroundResource(if (darkNow) R.drawable.bg_btn_primary else R.drawable.bg_btn_tonal)
        btnLight.setTextColor(if (!darkNow) Color.WHITE else getColor(R.color.vault_text))
        btnDark.setTextColor(if (darkNow) Color.WHITE else getColor(R.color.vault_text))
        btnLight.setOnClickListener { if (Prefs.isDark(this)) { Prefs.setDark(this, false); recreate() } }
        btnDark.setOnClickListener { if (!Prefs.isDark(this)) { Prefs.setDark(this, true); recreate() } }
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
        refreshMedia()
        renderStatus()
        tvUnlockSummary.text = getString(R.string.vault_unlock_summary, Prefs.activeUnlockCount(this), Prefs.METHODS.size)
        updateAlertPreview()

        tvTaps.text = Prefs.unlockTaps(this).toString()
        if (!recordingVolume) tvVolumeCode.text = volumeText(Prefs.volumeCode(this))
        if (!recordingCorner) tvCornerCode.text = cornerText(Prefs.cornerCode(this))
    }

    private fun renderFocusState(on: Boolean) {
        tvFocusState.setText(if (on) R.string.vault_focus_on else R.string.vault_focus_off)
        tvFocusState.setTextColor(getColor(if (on) R.color.vault_ok else R.color.vault_text_dim))
        cardFocus.setBackgroundResource(if (on) R.drawable.bg_hero_on else R.drawable.bg_vault_card)
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
        tvBlockedSummary.text = getString(
            R.string.vault_blocked_summary,
            targets.size,
            getString(if (Prefs.isEnabled(this)) R.string.vault_word_on else R.string.vault_word_off)
        )
        for (pkg in targets) {
            val row = layoutInflater.inflate(R.layout.row_app, llBlocked, false)
            row.findViewById<TextView>(R.id.tvLabel).text = AppCache.label(this, pkg)
            row.findViewById<TextView>(R.id.tvPkg).text = pkg
            bindIcon(row.findViewById(R.id.ivIcon), pkg)
            row.findViewById<View>(R.id.btnRemove).setOnClickListener {
                Prefs.setTargets(this, Prefs.targets(this) - pkg)
                renderBlocked()
                tvBlockedCount.text = Prefs.targets(this).size.toString()
                updateAlertPreview()
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
        private const val REQ_PICK = 41
        private const val REQ_DELETE = 42
        private const val HOLD_LOCK_MS = 3 * 60 * 1000L
        private const val MIN_CORNER = 4
        private const val MAX_CORNER = 6
    }
}
