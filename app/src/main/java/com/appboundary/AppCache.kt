package com.appboundary

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Remembers the names of launchable apps so the picker opens instantly.
 * The system is asked for a name only when an app is new since the last sync; icons are
 * loaded off the main thread and kept in a small memory cache.
 */
object AppCache {

    data class Entry(val pkg: String, val label: String)

    private const val FILE = "appboundary_apps"
    private const val KEY_LABELS = "labels"
    private const val ICON_PX = 96

    private val labels = LinkedHashMap<String, String>()
    private var loaded = false

    private val executor = Executors.newSingleThreadExecutor()
    private val iconExecutor = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val icons = object : LruCache<String, Bitmap>(4 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true
        val json = context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_LABELS, null) ?: return
        try {
            val obj = JSONObject(json)
            for (key in obj.keys()) labels[key] = obj.getString(key)
        } catch (e: Exception) {
            labels.clear()
        }
    }

    @Synchronized
    private fun persist(context: Context) {
        context.applicationContext
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LABELS, JSONObject(labels as Map<*, *>).toString())
            .apply()
    }

    /** Cached apps sorted by name, without this app. */
    @Synchronized
    fun entries(context: Context): List<Entry> {
        ensureLoaded(context)
        return labels
            .filterKeys { it != context.packageName }
            .map { Entry(it.key, it.value) }
            .sortedBy { it.label.lowercase() }
    }

    @Synchronized
    fun isEmpty(context: Context): Boolean {
        ensureLoaded(context)
        return labels.isEmpty()
    }

    /** Name for any package: from the cache, else asked from the system. */
    fun label(context: Context, pkg: String): String {
        synchronized(this) {
            ensureLoaded(context)
            labels[pkg]?.let { return it }
        }
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    /**
     * Adds apps installed since the last sync and drops removed ones, in the background.
     * Names are loaded only for new apps. [onDone] runs on the main thread, true if anything changed.
     */
    fun sync(context: Context, onDone: (Boolean) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            ensureLoaded(app)
            val pm = app.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val installed = pm.queryIntentActivities(launcher, 0)
                .distinctBy { it.activityInfo.packageName }
            val installedPkgs = installed.map { it.activityInfo.packageName }.toSet()

            var changed = false
            synchronized(this) {
                val gone = labels.keys.filter { it !in installedPkgs }
                for (pkg in gone) {
                    labels.remove(pkg)
                    icons.remove(pkg)
                    changed = true
                }
            }
            for (info in installed) {
                val pkg = info.activityInfo.packageName
                val known = synchronized(this) { labels.containsKey(pkg) }
                if (!known) {
                    val name = info.loadLabel(pm).toString()
                    synchronized(this) { labels[pkg] = name }
                    changed = true
                }
            }
            if (changed) persist(app)
            main.post { onDone(changed) }
        }
    }

    /** A new app was installed: add just that one. */
    fun onInstalled(context: Context, pkg: String) {
        val app = context.applicationContext
        executor.execute {
            ensureLoaded(app)
            val pm = app.packageManager
            if (pm.getLaunchIntentForPackage(pkg) == null) return@execute
            val name = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                return@execute
            }
            synchronized(this) { labels[pkg] = name }
            persist(app)
        }
    }

    /** An app was uninstalled: take it off the blocked list right away, then clean the cache. */
    fun onRemoved(context: Context, pkg: String) {
        val app = context.applicationContext
        Prefs.setTargets(app, Prefs.targets(app) - pkg)
        executor.execute {
            ensureLoaded(app)
            val had = synchronized(this) {
                icons.remove(pkg)
                labels.remove(pkg) != null
            }
            if (had) persist(app)
        }
    }

    fun cachedIcon(pkg: String): Bitmap? = icons.get(pkg)

    /** Loads an icon in the background; [onLoaded] runs on the main thread. */
    fun loadIcon(context: Context, pkg: String, onLoaded: (Bitmap?) -> Unit) {
        val app = context.applicationContext
        iconExecutor.execute {
            var bitmap: Bitmap? = icons.get(pkg)
            if (bitmap == null) {
                bitmap = try {
                    val drawable = app.packageManager.getApplicationIcon(pkg)
                    val created = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                    drawable.draw(Canvas(created))
                    icons.put(pkg, created)
                    created
                } catch (e: Exception) {
                    null
                }
            }
            val result = bitmap
            main.post { onLoaded(result) }
        }
    }
}
