package com.appboundary

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Folders the user makes (numbered "Folder 1", "Folder 2"... by default) and which hidden item sits in which. */
object FolderStore {

    private const val SP = "appboundary_folders"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(SP, Context.MODE_PRIVATE)

    fun names(c: Context): List<String> {
        val arr = try {
            JSONArray(sp(c).getString("names", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun saveNames(c: Context, list: List<String>) {
        sp(c).edit().putString("names", JSONArray(list).toString()).apply()
    }

    fun assignments(c: Context): Map<String, String> {
        val obj = try {
            JSONObject(sp(c).getString("assign", "{}"))
        } catch (e: Exception) {
            JSONObject()
        }
        val out = HashMap<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.getString(k)
        }
        return out
    }

    private fun saveAssign(c: Context, map: Map<String, String>) {
        sp(c).edit().putString("assign", JSONObject(map).toString()).apply()
    }

    private fun remember(c: Context, name: String) {
        val arr = try {
            JSONArray(sp(c).getString("recent", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val list = ArrayList<String>()
        list.add(name)
        for (i in 0 until arr.length()) {
            val old = arr.getString(i)
            if (!old.equals(name, true)) list.add(old)
        }
        sp(c).edit().putString("recent", JSONArray(list.take(15)).toString()).apply()
    }

    /** Names used before (even for folders since deleted) that are free to use again. */
    fun recent(c: Context): List<String> {
        val arr = try {
            JSONArray(sp(c).getString("recent", "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        val have = names(c).map { it.lowercase() }.toSet()
        return (0 until arr.length()).map { arr.getString(it) }.filter { it.lowercase() !in have }
    }

    /** Next free "Folder N". */
    fun nextName(c: Context): String {
        val have = names(c).map { it.lowercase() }.toSet()
        var n = 1
        while ("folder $n" in have) n++
        return "Folder $n"
    }

    fun add(c: Context, name: String): Boolean {
        val clean = name.trim()
        val list = names(c)
        if (clean.isEmpty() || list.any { it.equals(clean, true) }) return false
        saveNames(c, list + clean)
        remember(c, clean)
        return true
    }

    fun rename(c: Context, old: String, new: String): Boolean {
        val clean = new.trim()
        val list = names(c)
        if (clean.isEmpty() || list.any { it.equals(clean, true) && !it.equals(old, true) }) return false
        saveNames(c, list.map { if (it == old) clean else it })
        saveAssign(c, assignments(c).mapValues { if (it.value == old) clean else it.value })
        remember(c, clean)
        return true
    }

    fun remove(c: Context, name: String) {
        saveNames(c, names(c).filter { it != name })
        saveAssign(c, assignments(c).filterValues { it != name })
    }

    /** Puts [ids] into folder [name]; null sends them back to their automatic folder. */
    fun assign(c: Context, ids: Collection<String>, name: String?) {
        val map = HashMap(assignments(c))
        for (id in ids) {
            if (name == null) map.remove(id) else map[id] = name
        }
        saveAssign(c, map)
    }
}
