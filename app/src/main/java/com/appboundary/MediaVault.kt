package com.appboundary

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.LruCache
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Hidden photos and videos. Files are copied into this app's private storage (not readable by
 * the gallery or any other app) and the originals are removed by the caller.
 */
object MediaVault {

    data class Item(
        val id: String,
        val file: File,
        val video: Boolean,
        val name: String,
        val mime: String,
        val added: Long
    )

    private const val DIR = "media"
    private const val DIR_DECOY = "media_decoy"

    /** True while the decoy vault is open: everything below then reads and writes a separate, empty store. */
    @Volatile
    var decoy = false
    private const val INDEX = "index.json"

    private val lock = Any()
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    private val thumbs = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun dir(context: Context): File =
        File(context.applicationContext.filesDir, if (decoy) DIR_DECOY else DIR).also { it.mkdirs() }

    // ---- Index --------------------------------------------------------------

    private fun readIndex(context: Context): JSONArray {
        val f = File(dir(context), INDEX)
        if (!f.exists()) return JSONArray()
        return try {
            JSONArray(f.readText())
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun writeIndex(context: Context, arr: JSONArray) {
        val d = dir(context)
        val tmp = File(d, "$INDEX.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(d, INDEX))
    }

    /** Newest first. Entries whose file went missing are skipped. */
    fun list(context: Context): List<Item> = synchronized(lock) {
        val d = dir(context)
        val arr = readIndex(context)
        val out = ArrayList<Item>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val file = File(d, o.optString("file"))
            if (!file.exists()) continue
            out.add(
                Item(
                    id = o.optString("id"),
                    file = file,
                    video = o.optBoolean("video"),
                    name = o.optString("name"),
                    mime = o.optString("mime"),
                    added = o.optLong("added")
                )
            )
        }
        out.sortedByDescending { it.added }
    }

    fun count(context: Context): Int = list(context).size

    // ---- Hide / restore / delete -------------------------------------------

    /** Copies [uri] into the vault. Returns null if the copy failed. */
    /** Kernel-level copy (no byte buffer in the app); false means "try something else". */
    private fun copyChannel(cr: android.content.ContentResolver, uri: Uri?, target: File): Boolean {
        if (uri == null) return false
        return try {
            cr.openFileDescriptor(uri, "r")?.use { pfd ->
                val size = pfd.statSize
                java.io.FileInputStream(pfd.fileDescriptor).channel.use { src ->
                    FileOutputStream(target).channel.use { dst ->
                        val total = if (size > 0) size else src.size()
                        var pos = 0L
                        while (pos < total) {
                            val n = src.transferTo(pos, total - pos, dst)
                            if (n <= 0) break
                            pos += n
                        }
                        pos == total && total > 0
                    }
                }
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun hide(context: Context, uri: Uri): Item? {
        val app = context.applicationContext
        val cr = app.contentResolver
        var name = "file"
        try {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { name = it }
            }
        } catch (e: Exception) {
        }
        var mime = cr.getType(uri) ?: ""
        var ext = name.substringAfterLast('.', "")
        if (mime.isEmpty() && ext.isNotEmpty()) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.lowercase()) ?: ""
        }
        val video = mime.startsWith("video/")
        if (!video && !mime.startsWith("image/")) mime = "image/jpeg"
        if (ext.isEmpty()) {
            ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: ""
        }

        val id = UUID.randomUUID().toString()
        val fileName = if (ext.isNotEmpty()) "$id.$ext" else id
        val target = File(dir(app), fileName)
        try {
            // Reading straight from the media store is faster than through the photo picker's proxy.
            val direct = try { resolveMediaUri(app, uri) } catch (e: Exception) { null }
            if (!copyChannel(cr, direct, target) && !copyChannel(cr, uri, target)) {
                target.delete()
                val input = cr.openInputStream(uri) ?: return null
                input.use { src ->
                    FileOutputStream(target).use { dst ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            dst.write(buf, 0, n)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            target.delete()
            return null
        }

        val now = System.currentTimeMillis()
        synchronized(lock) {
            val arr = readIndex(app)
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("file", fileName)
                    .put("video", video)
                    .put("name", name)
                    .put("mime", mime)
                    .put("added", now)
            )
            writeIndex(app, arr)
        }
        return Item(id, target, video, name, mime, now)
    }

    /** Puts the file back into the gallery (Pictures/Restored or Movies/Restored) and removes it from the vault. */
    fun restore(context: Context, item: Item): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val app = context.applicationContext
        val cr = app.contentResolver
        return try {
            val collection = if (item.video) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val base = if (item.video) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/Restored")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val dest = cr.insert(collection, values) ?: return false
            cr.openOutputStream(dest)?.use { out ->
                item.file.inputStream().use { it.copyTo(out) }
            } ?: return false
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            cr.update(dest, done, null, null)
            delete(app, item)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun delete(context: Context, item: Item) {
        val app = context.applicationContext
        item.file.delete()
        synchronized(lock) {
            val arr = readIndex(app)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != item.id) kept.put(o)
            }
            writeIndex(app, kept)
        }
        thumbs.snapshot().keys.filter { it.startsWith(item.id) }.forEach { thumbs.remove(it) }
    }


    fun totalBytes(items: List<Item>): Long {
        var total = 0L
        for (i in items) total += i.file.length()
        return total
    }

    /** Erases everything in the vault. */
    fun deleteAll(context: Context) {
        for (item in list(context)) delete(context, item)
    }

    /**
     * Removes the original of a picked file from the gallery. Tries the document provider first;
     * with all-files access it can also delete through MediaStore or the file path directly.
     */
    /** Gallery-picker links point at the same id as the MediaStore row; this finds that row. */
    fun resolveMediaUri(context: Context, uri: Uri): Uri? {
        if (uri.authority != "media") return null
        if (!uri.pathSegments.contains("picker")) return uri
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        val type = try {
            context.applicationContext.contentResolver.getType(uri) ?: ""
        } catch (e: Exception) {
            ""
        }
        val base = if (type.startsWith("video/")) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return ContentUris.withAppendedId(base, id)
    }

    /** Gallery files that are still there although the vault holds a copy (same name and same size). */
    fun findLeftovers(context: Context): List<Uri> {
        val app = context.applicationContext
        val cr = app.contentResolver
        val keys = HashSet<String>()
        for (item in list(app)) keys.add(item.name + "|" + item.file.length())
        if (keys.isEmpty()) return emptyList()
        val out = ArrayList<Uri>()
        for (base in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)) {
            try {
                cr.query(
                    base,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                    null, null, null
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if ((name + "|" + c.getLong(2)) in keys) out.add(ContentUris.withAppendedId(base, c.getLong(0)))
                    }
                }
            } catch (e: Exception) {
            }
        }
        return out
    }

    fun deleteOriginal(context: Context, uri: Uri, allFiles: Boolean): Boolean {
        val app = context.applicationContext
        val cr = app.contentResolver
        val mediaUri = resolveMediaUri(app, uri)
        if (mediaUri != null) {
            try {
                if (cr.delete(mediaUri, null, null) > 0) return true
            } catch (e: Exception) {
            }
            // The media store said no: with "All files access" remove the real file and tell the gallery.
            if (allFiles) {
                try {
                    var path: String? = null
                    cr.query(mediaUri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
                        if (c.moveToFirst()) path = c.getString(0)
                    }
                    val f = path?.let { File(it) }
                    if (f != null && f.exists()) {
                        val gone = f.delete() || !f.exists()
                        MediaScannerConnection.scanFile(app, arrayOf(f.absolutePath), null, null)
                        if (gone) return true
                    }
                } catch (e: Exception) {
                }
            }
        }
        try {
            if (DocumentsContract.deleteDocument(cr, uri)) return true
        } catch (e: Exception) {
        }
        if (!allFiles) return false
        try {
            if (!DocumentsContract.isDocumentUri(app, uri)) return false
            val docId = DocumentsContract.getDocumentId(uri)
            val authority = uri.authority ?: ""
            if (authority == "com.android.providers.media.documents") {
                val id = docId.substringAfter(':').toLongOrNull() ?: return false
                val base = when (docId.substringBefore(':')) {
                    "video" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    "image" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    else -> return false
                }
                return cr.delete(ContentUris.withAppendedId(base, id), null, null) > 0
            }
            val file: File? = when {
                docId.startsWith("raw:") -> File(docId.removePrefix("raw:"))
                authority == "com.android.externalstorage.documents" && docId.startsWith("primary:") ->
                    File(Environment.getExternalStorageDirectory(), docId.removePrefix("primary:"))
                else -> null
            }
            if (file == null) return false
            val gone = file.delete()
            if (gone) MediaScannerConnection.scanFile(app, arrayOf(file.absolutePath), null, null)
            return gone
        } catch (e: Exception) {
            return false
        }
    }

    // ---- Thumbnails and full-size decode -----------------------------------

    /** Calls [cb] on the main thread with a square-ish thumbnail (or null if it could not be made). */
    fun thumb(item: Item, px: Int, cb: (Bitmap?) -> Unit) {
        val key = "${item.id}:$px"
        thumbs.get(key)?.let {
            cb(it)
            return
        }
        io.execute {
            val bmp = try {
                if (item.video) videoFrame(item.file, px) else decode(item.file, px)
            } catch (e: Exception) {
                null
            }
            if (bmp != null) thumbs.put(key, bmp)
            main.post { cb(bmp) }
        }
    }

    private val durations = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Calls [cb] on the main thread with the video length as mm:ss. */
    fun duration(item: Item, cb: (String) -> Unit) {
        durations[item.id]?.let {
            cb(it)
            return
        }
        io.execute {
            var text = ""
            try {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(item.file.absolutePath)
                    val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    val sec = (ms + 500) / 1000
                    text = String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60)
                } finally {
                    r.release()
                }
            } catch (e: Exception) {
            }
            if (text.isNotEmpty()) durations[item.id] = text
            main.post { if (text.isNotEmpty()) cb(text) }
        }
    }

    /** Full-screen photo, scaled down to roughly [maxPx]. Call off the main thread. */
    fun decode(item: Item, maxPx: Int): Bitmap? = decode(item.file, maxPx)

    private fun decode(file: File, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample * 2) >= maxPx) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return null
        val degrees = try {
            when (ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) {
            0f
        }
        if (degrees == 0f) return bmp
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    private fun videoFrame(file: File, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            val frame = r.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val scale = px.toFloat() / maxOf(frame.width, frame.height)
            if (scale >= 1f) {
                frame
            } else {
                Bitmap.createScaledBitmap(
                    frame,
                    (frame.width * scale).toInt().coerceAtLeast(1),
                    (frame.height * scale).toInt().coerceAtLeast(1),
                    true
                )
            }
        } catch (e: Exception) {
            null
        } finally {
            try {
                r.release()
            } catch (e: Exception) {
            }
        }
    }
}
