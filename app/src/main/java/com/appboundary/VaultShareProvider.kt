package com.appboundary

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets the vault share a hidden photo or video straight from its private storage.
 *
 * - Original bytes: the file is streamed as it is, nothing is re-encoded, so no quality is lost.
 * - No copies: nothing is written to the gallery, the cache or any public folder.
 * - Not guessable and not lasting: every link has a random token, is read-only, works only for the
 *   app the user picked in the share sheet, and stops working after [TTL_MS] (or when the file is gone).
 */
class VaultShareProvider : ContentProvider() {

    private class Grant(val file: File, val name: String, val mime: String, val expires: Long)

    override fun onCreate(): Boolean = true

    private fun grantFor(uri: Uri): Grant? {
        val token = uri.pathSegments.firstOrNull() ?: return null
        val g = grants[token] ?: return null
        if (System.currentTimeMillis() > g.expires || !g.file.exists()) {
            grants.remove(token)
            return null
        }
        return g
    }

    override fun getType(uri: Uri): String? = grantFor(uri)?.mime

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val g = grantFor(uri) ?: return null
        val cols: Array<String> = projection?.let { p -> Array(p.size) { p[it] } }
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols, 1)
        val row = cursor.newRow()
        for (c in cols) {
            when (c) {
                OpenableColumns.DISPLAY_NAME -> row.add(g.name)
                OpenableColumns.SIZE -> row.add(g.file.length())
                else -> row.add(null)
            }
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val g = grantFor(uri) ?: throw FileNotFoundException()
        return ParcelFileDescriptor.open(g.file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException()

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY = "com.appboundary.share"
        private const val TTL_MS = 10 * 60 * 1000L
        private val grants = ConcurrentHashMap<String, Grant>()

        /** A one-use style link for [item]; hand it to the share sheet. */
        fun grant(item: MediaVault.Item): Uri {
            purge()
            val token = UUID.randomUUID().toString().replace("-", "")
            var name = item.name.replace('/', '_').replace('\\', '_').ifBlank { "file" }
            val ext = item.file.extension
            if (ext.isNotEmpty() && !name.contains('.')) name = "$name.$ext"
            val mime = item.mime.ifBlank { "application/octet-stream" }
            grants[token] = Grant(item.file, name, mime, System.currentTimeMillis() + TTL_MS)
            return Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath(token)
                .appendPath(name)
                .build()
        }

        private fun purge() {
            val now = System.currentTimeMillis()
            val it = grants.entries.iterator()
            while (it.hasNext()) if (now > it.next().value.expires) it.remove()
        }

        /** Ends every share link at once (also tells the system to drop the permissions it handed out). */
        fun revokeAll(context: Context) {
            grants.clear()
            try {
                context.applicationContext.revokeUriPermission(
                    Uri.parse("content://$AUTHORITY"),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
        }
    }
}
