package com.appboundary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A short history of every time the blocker screen was unlocked: when, which app, which method. */
object UnlockLog {

    data class Entry(val time: Long, val pkg: String, val method: String)

    private const val SP = "appboundary_unlock_log"
    private const val KEY = "entries"
    private const val MAX = 200

    private val lock = Any()

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun read(c: Context): JSONArray = try {
        JSONArray(sp(c).getString(KEY, "[]"))
    } catch (e: Exception) {
        JSONArray()
    }

    fun add(c: Context, pkg: String, method: String) = synchronized(lock) {
        val old = read(c)
        val out = JSONArray()
        out.put(
            JSONObject()
                .put("t", System.currentTimeMillis())
                .put("p", pkg)
                .put("m", method)
        )
        var i = 0
        while (i < old.length() && out.length() < MAX) {
            out.put(old.get(i))
            i++
        }
        sp(c).edit().putString(KEY, out.toString()).apply()
    }

    /** Newest first. */
    fun list(c: Context): List<Entry> = synchronized(lock) {
        val arr = read(c)
        val out = ArrayList<Entry>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(Entry(o.optLong("t"), o.optString("p"), o.optString("m")))
        }
        out
    }

    fun clear(c: Context) = synchronized(lock) {
        sp(c).edit().remove(KEY).apply()
    }
}
