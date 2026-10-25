package com.appboundary

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** SharedPreferences wrapper shared by the service, alert, tile and vault. */
object Prefs {
    private const val FILE = "appboundary_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGETS = "targets"
    private const val KEY_ALERT_TITLE = "alert_title"
    private const val KEY_ALERT_MESSAGE = "alert_message"
    private const val KEY_UNLOCK_TAPS = "unlock_taps"
    private const val KEY_VOLUME_CODE = "code_volume"
    private const val KEY_CORNER_CODE = "code_corner"
    private const val KEY_PIN_SALT = "pin_salt"
    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_FAILS = "pin_fails"
    private const val KEY_LOCK_UNTIL = "pin_lock_until"
    private const val KEY_BLOCK_SHOTS = "block_screenshots"

    const val MIN_TAPS = 2
    const val MAX_TAPS = 6
    private const val DEFAULT_TAPS = 3
    private const val MAX_FAILS = 5
    private const val LOCK_MS = 30_000L

    // Ways to unlock the blocker screen.
    const val METHOD_TAP_HOLD = "tap_hold"
    const val METHOD_VOLUME = "volume"
    const val METHOD_CORNER = "corner"
    const val METHOD_TWO_FINGER = "two_finger"
    val METHODS = listOf(METHOD_TAP_HOLD, METHOD_VOLUME, METHOD_CORNER, METHOD_TWO_FINGER)

    // U = volume up, D = volume down. Corners: 1 top-left, 2 top-right, 3 bottom-left, 4 bottom-right.
    private const val DEFAULT_VOLUME_CODE = "UDDU"
    private const val DEFAULT_CORNER_CODE = "1432"

    private val DEFAULT_TARGETS = setOf("com.instagram.android")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Vault privacy --------------------------------------------------------

    fun blockShots(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BLOCK_SHOTS, true)

    fun setBlockShots(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BLOCK_SHOTS, on).apply()
    }

    // ---- Monitoring ---------------------------------------------------------

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun targets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TARGETS, DEFAULT_TARGETS) ?: DEFAULT_TARGETS

    fun setTargets(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_TARGETS, HashSet(packages)).apply()
    }

    // ---- Alert text (null = use the built-in text) ---------------------------

    fun alertTitle(context: Context): String? =
        prefs(context).getString(KEY_ALERT_TITLE, null)?.takeIf { it.isNotBlank() }

    fun alertMessage(context: Context): String? =
        prefs(context).getString(KEY_ALERT_MESSAGE, null)?.takeIf { it.isNotBlank() }

    fun setAlertText(context: Context, title: String?, message: String?) {
        val editor = prefs(context).edit()
        if (title.isNullOrBlank()) editor.remove(KEY_ALERT_TITLE) else editor.putString(KEY_ALERT_TITLE, title)
        if (message.isNullOrBlank()) editor.remove(KEY_ALERT_MESSAGE) else editor.putString(KEY_ALERT_MESSAGE, message)
        editor.apply()
    }

    // ---- Unlock methods -------------------------------------------------------

    fun unlockEnabled(context: Context, method: String): Boolean =
        prefs(context).getBoolean("unlock_on_$method", true)

    fun setUnlockEnabled(context: Context, method: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("unlock_on_$method", enabled).apply()
    }

    fun activeUnlockCount(context: Context): Int =
        METHODS.count { unlockEnabled(context, it) }

    fun unlockTaps(context: Context): Int =
        prefs(context).getInt(KEY_UNLOCK_TAPS, DEFAULT_TAPS).coerceIn(MIN_TAPS, MAX_TAPS)

    fun setUnlockTaps(context: Context, taps: Int) {
        prefs(context).edit().putInt(KEY_UNLOCK_TAPS, taps.coerceIn(MIN_TAPS, MAX_TAPS)).apply()
    }

    fun volumeCode(context: Context): String =
        prefs(context).getString(KEY_VOLUME_CODE, null)?.takeIf { it.isNotEmpty() } ?: DEFAULT_VOLUME_CODE

    fun setVolumeCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_VOLUME_CODE, code).apply()
    }

    fun cornerCode(context: Context): String =
        prefs(context).getString(KEY_CORNER_CODE, null)?.takeIf { it.isNotEmpty() } ?: DEFAULT_CORNER_CODE

    fun setCornerCode(context: Context, code: String) {
        prefs(context).edit().putString(KEY_CORNER_CODE, code).apply()
    }

    // ---- Vault PIN -----------------------------------------------------------

    fun hasPin(context: Context): Boolean = prefs(context).contains(KEY_PIN_HASH)

    fun setPin(context: Context, pin: String) {
        val salt = UUID.randomUUID().toString()
        prefs(context).edit()
            .putString(KEY_PIN_SALT, salt)
            .putString(KEY_PIN_HASH, hash(pin, salt))
            .putInt(KEY_FAILS, 0)
            .remove(KEY_LOCK_UNTIL)
            .apply()
    }

    fun checkPin(context: Context, pin: String): Boolean {
        val p = prefs(context)
        val salt = p.getString(KEY_PIN_SALT, null) ?: return false
        val stored = p.getString(KEY_PIN_HASH, null) ?: return false
        return MessageDigest.isEqual(hash(pin, salt).toByteArray(), stored.toByteArray())
    }

    /** Milliseconds left on the wrong-PIN lock, or 0. */
    fun lockRemainingMs(context: Context): Long =
        (prefs(context).getLong(KEY_LOCK_UNTIL, 0L) - System.currentTimeMillis()).coerceAtLeast(0L)

    /** Records a wrong PIN. Returns the lock duration if this attempt triggered a lock, else 0. */
    fun registerFail(context: Context): Long {
        val p = prefs(context)
        val fails = p.getInt(KEY_FAILS, 0) + 1
        return if (fails >= MAX_FAILS) {
            p.edit()
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + LOCK_MS)
                .apply()
            LOCK_MS
        } else {
            p.edit().putInt(KEY_FAILS, fails).apply()
            0L
        }
    }

    fun resetFails(context: Context) {
        prefs(context).edit().putInt(KEY_FAILS, 0).remove(KEY_LOCK_UNTIL).apply()
    }

    private fun hash(pin: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
