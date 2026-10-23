package com.appboundary

import android.content.Context

/** Tiny SharedPreferences wrapper shared by the service, activity and tile. */
object Prefs {
    private const val FILE = "appboundary_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TARGETS = "targets"

    private val DEFAULT_TARGETS = setOf("com.instagram.android")

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun targets(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_TARGETS, DEFAULT_TARGETS) ?: DEFAULT_TARGETS

    fun setTargets(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_TARGETS, packages).apply()
    }
}
