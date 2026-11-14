package com.appboundary

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * One-file encrypted backup of the whole vault, and the way back in.
 *
 * Without loss:
 *  - the vault is only read, never changed, while backing up;
 *  - every file is hashed (SHA-256) while it is read, and after writing the whole backup is read back,
 *    decrypted and compared with those hashes; a backup that does not match is deleted, never kept;
 *  - the file ends with a marked final block, so a cut-off file can never pass as a complete one;
 *  - importing first stages and verifies every file, only then moves them in; nothing already in the
 *    vault is overwritten or removed.
 *
 * Without leak:
 *  - AES-256-GCM, key from the password with PBKDF2-SHA256 (the password is never stored);
 *  - the file has no readable header, name or marker of this app, and no plaintext is ever written
 *    to a temporary file; data goes vault file -> encryption -> the place the user picked;
 *  - the app has no internet permission; where the file goes is the user's choice in the system picker.
 */
object VaultBackup {

    const val STAGE_PREPARE = 0
    const val STAGE_WRITE = 1
    const val STAGE_VERIFY = 2
    const val STAGE_READ = 3

    const val ERR_BAD_PASSWORD = 1
    const val ERR_CORRUPT = 2
    const val ERR_IO = 3
    const val ERR_NO_SPACE = 4
    const val ERR_CANCELLED = 5
    const val ERR_VERIFY = 6
    const val ERR_SOURCE = 7
    const val ERR_EMPTY = 8

    class BackupError(val code: Int, message: String = "") : Exception(message)

    data class BackupResult(val items: Int, val bytes: Long)
    data class RestoreResult(val imported: Int, val skipped: Int, val bytes: Long)

    private const val MAGIC = 0x41424B31
    private const val FORMAT = 1
    private const val ITERATIONS = 210_000
    private const val CHUNK = 1 shl 20
    private const val TAG_BYTES = 16
    private const val SALT_LEN = 16
    private const val PREFIX_LEN = 8
    private const val SAFETY_MARGIN = 32L * 1024 * 1024

    private val ID_OK = Regex("^[A-Za-z0-9-]{1,64}$")
    private val EXT_OK = Regex("^[A-Za-z0-9]{0,10}$")

    // ---- Key ------------------------------------------------------------------------------------

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).array()

    // ---- Chunked authenticated encryption -----------------------------------------------------
    // Each block: [flag 0/1][int length][ciphertext + tag]. The flag is authenticated, and only the
    // last block has flag 1, so removing the end of the file is detected.

    private class ChunkOut(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray
    ) : OutputStream() {
        private val buf = ByteArray(CHUNK)
        private var n = 0
        private var counter = 0
        private var finished = false

        override fun write(b: Int) {
            if (n == CHUNK) emit(false)
            buf[n++] = b.toByte()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var l = len
            while (l > 0) {
                if (n == CHUNK) emit(false)
                val c = minOf(l, CHUNK - n)
                System.arraycopy(b, o, buf, n, c)
                n += c
                o += c
                l -= c
            }
        }

        private fun emit(last: Boolean) {
            val flag: Byte = if (last) 1 else 0
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(byteArrayOf(flag))
            val ct = cipher.doFinal(buf, 0, n)
            out.write(flag.toInt())
            out.write(ByteBuffer.allocate(4).putInt(ct.size).array())
            out.write(ct)
            counter++
            n = 0
        }

        fun finish() {
            if (!finished) {
                emit(true)
                finished = true
            }
        }
    }

    private class ChunkIn(
        private val inp: InputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray
    ) : InputStream() {
        private var buf = ByteArray(0)
        private var pos = 0
        private var counter = 0
        private var sawFinal = false

        /** Loads the next block. Returns false when the final block was already used up. */
        private fun fill(): Boolean {
            if (sawFinal) return false
            val flag = inp.read()
            if (flag < 0) throw BackupError(ERR_CORRUPT, "incomplete")
            if (flag != 0 && flag != 1) throw BackupError(if (counter == 0) ERR_BAD_PASSWORD else ERR_CORRUPT)
            val lenBytes = ByteArray(4)
            readFully(inp, lenBytes)
            val len = ByteBuffer.wrap(lenBytes).int
            if (len < TAG_BYTES || len > CHUNK + TAG_BYTES) {
                throw BackupError(if (counter == 0) ERR_BAD_PASSWORD else ERR_CORRUPT)
            }
            val ct = ByteArray(len)
            readFully(inp, ct)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
                cipher.updateAAD(byteArrayOf(flag.toByte()))
                buf = cipher.doFinal(ct)
            } catch (e: GeneralSecurityException) {
                // The very first block failing means the key is wrong (or this is not a backup).
                throw BackupError(if (counter == 0) ERR_BAD_PASSWORD else ERR_CORRUPT)
            }
            pos = 0
            counter++
            if (flag == 1) sawFinal = true
            return true
        }

        override fun read(): Int {
            while (pos >= buf.size) if (!fill()) return -1
            return buf[pos++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos >= buf.size) if (!fill()) return -1
            val c = minOf(len, buf.size - pos)
            System.arraycopy(buf, pos, b, off, c)
            pos += c
            return c
        }

        /** After the last record: the final block must have been reached and nothing may be left. */
        fun verifyEnd() {
            while (true) {
                if (pos < buf.size) throw BackupError(ERR_CORRUPT, "trailing data")
                if (sawFinal) return
                fill()
            }
        }

        private fun readFully(s: InputStream, dst: ByteArray) {
            var got = 0
            while (got < dst.size) {
                val n = s.read(dst, got, dst.size - got)
                if (n < 0) throw BackupError(ERR_CORRUPT, "incomplete")
                got += n
            }
        }
    }

    // ---- Shared reading -------------------------------------------------------------------------

    private class Header(val count: Int, val total: Long, val manifest: JSONObject)

    private fun readHeader(dis: DataInputStream): Header {
        if (dis.readInt() != MAGIC) throw BackupError(ERR_BAD_PASSWORD)
        if (dis.readInt() != FORMAT) throw BackupError(ERR_CORRUPT, "format")
        val count = dis.readInt()
        val total = dis.readLong()
        val len = dis.readInt()
        if (count < 0 || count > 2_000_000 || total < 0 || len < 2 || len > 256 * 1024 * 1024) {
            throw BackupError(ERR_CORRUPT, "header")
        }
        val mb = ByteArray(len)
        dis.readFully(mb)
        val manifest = try {
            JSONObject(String(mb, Charsets.UTF_8))
        } catch (e: Exception) {
            throw BackupError(ERR_CORRUPT, "manifest")
        }
        if (manifest.optJSONArray("items")?.length() != count) throw BackupError(ERR_CORRUPT, "manifest")
        return Header(count, total, manifest)
    }

    /** Reads [size] bytes into [sink] (or discards them), then checks them against the stored SHA-256. */
    private fun pump(
        dis: DataInputStream,
        size: Long,
        sink: OutputStream?,
        buf: ByteArray,
        onBytes: (Int) -> Unit,
        cancelled: () -> Boolean
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        var left = size
        while (left > 0) {
            if (cancelled()) throw BackupError(ERR_CANCELLED)
            val n = dis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw BackupError(ERR_CORRUPT, "incomplete")
            md.update(buf, 0, n)
            sink?.write(buf, 0, n)
            left -= n
            onBytes(n)
        }
        val stored = ByteArray(32)
        dis.readFully(stored)
        val got = md.digest()
        if (!MessageDigest.isEqual(got, stored)) throw BackupError(ERR_CORRUPT, "hash")
        return got
    }

    private fun isNoSpace(e: IOException): Boolean {
        val m = e.message ?: return false
        return m.contains("ENOSPC") || m.contains("No space", true)
    }

    // ---- Backup ----------------------------------------------------------------------------------

    fun backup(
        context: Context,
        dest: Uri,
        password: CharArray,
        onProgress: (stage: Int, done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ): BackupResult {
        val app = context.applicationContext
        val cr = app.contentResolver
        val items = MediaVault.list(app).sortedBy { it.added }
        if (items.isEmpty()) throw BackupError(ERR_EMPTY)
        val sizes = LongArray(items.size) { items[it].file.length() }
        val total = sizes.sum()

        val ids = items.map { it.id }.toSet()
        val manifest = JSONObject()
        val arr = JSONArray()
        for ((i, item) in items.withIndex()) {
            arr.put(
                JSONObject()
                    .put("id", item.id)
                    .put("ext", item.file.extension)
                    .put("video", item.video)
                    .put("name", item.name)
                    .put("mime", item.mime)
                    .put("added", item.added)
                    .put("size", sizes[i])
            )
        }
        manifest.put("items", arr)
        manifest.put("folders", JSONArray(FolderStore.names(app)))
        val assign = JSONObject()
        for ((id, folder) in FolderStore.assignments(app)) if (id in ids) assign.put(id, folder)
        manifest.put("assign", assign)

        onProgress(STAGE_PREPARE, 0, 0)
        val rnd = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
        val prefix = ByteArray(PREFIX_LEN).also { rnd.nextBytes(it) }
        val key = deriveKey(password, salt)

        var ok = false
        val hashes = ArrayList<ByteArray>(items.size)
        try {
            val os = try {
                cr.openOutputStream(dest, "wt")
            } catch (e: Exception) {
                null
            } ?: throw BackupError(ERR_IO, "cannot open")
            try {
                val raw = BufferedOutputStream(os, 256 * 1024)
                raw.write(salt)
                raw.write(prefix)
                val enc = ChunkOut(raw, key, prefix)
                val dos = DataOutputStream(enc)
                dos.writeInt(MAGIC)
                dos.writeInt(FORMAT)
                dos.writeInt(items.size)
                dos.writeLong(total)
                val mb = manifest.toString().toByteArray(Charsets.UTF_8)
                dos.writeInt(mb.size)
                dos.write(mb)

                val buf = ByteArray(1 shl 20)
                var done = 0L
                for ((i, item) in items.withIndex()) {
                    dos.writeByte(1)
                    dos.writeUTF(item.id)
                    dos.writeLong(sizes[i])
                    val md = MessageDigest.getInstance("SHA-256")
                    var left = sizes[i]
                    FileInputStream(item.file).use { fin ->
                        while (left > 0) {
                            if (cancelled()) throw BackupError(ERR_CANCELLED)
                            val n = fin.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                            if (n < 0) throw BackupError(ERR_SOURCE, "source shrank")
                            md.update(buf, 0, n)
                            dos.write(buf, 0, n)
                            left -= n
                            done += n
                            onProgress(STAGE_WRITE, done, total)
                        }
                    }
                    val h = md.digest()
                    dos.write(h)
                    hashes.add(h)
                }
                dos.writeByte(0)
                dos.flush()
                enc.finish()
                raw.flush()
                try {
                    (os as? FileOutputStream)?.fd?.sync()
                } catch (e: Exception) {
                }
            } finally {
                try {
                    os.close()
                } catch (e: Exception) {
                }
            }

            // Read the finished file back and compare it with what was read from the vault.
            verify(cr, dest, key, salt, items.map { it.id }, sizes, total, hashes, onProgress, cancelled)
            ok = true
        } catch (e: BackupError) {
            throw e
        } catch (e: IOException) {
            throw BackupError(if (isNoSpace(e)) ERR_NO_SPACE else ERR_IO, e.message ?: "")
        } finally {
            if (!ok) deleteQuietly(cr, dest)
        }
        Prefs.setLastBackup(app, System.currentTimeMillis(), items.size)
        return BackupResult(items.size, total)
    }

    private fun verify(
        cr: android.content.ContentResolver,
        src: Uri,
        key: SecretKeySpec,
        salt: ByteArray,
        ids: List<String>,
        sizes: LongArray,
        total: Long,
        hashes: List<ByteArray>,
        onProgress: (Int, Long, Long) -> Unit,
        cancelled: () -> Boolean
    ) {
        val raw = cr.openInputStream(src) ?: throw BackupError(ERR_VERIFY, "cannot reopen")
        raw.use {
            val bin = BufferedInputStream(it, 256 * 1024)
            val fileSalt = ByteArray(SALT_LEN)
            val prefix = ByteArray(PREFIX_LEN)
            try {
                DataInputStream(bin).apply {
                    readFully(fileSalt)
                    readFully(prefix)
                }
            } catch (e: EOFException) {
                throw BackupError(ERR_VERIFY, "short")
            }
            if (!MessageDigest.isEqual(fileSalt, salt)) throw BackupError(ERR_VERIFY, "salt")
            val chunks = ChunkIn(bin, key, prefix)
            val dis = DataInputStream(chunks)
            try {
                val header = readHeader(dis)
                if (header.count != ids.size || header.total != total) throw BackupError(ERR_VERIFY, "header")
                val buf = ByteArray(1 shl 20)
                var done = 0L
                for (i in ids.indices) {
                    if (dis.readByte().toInt() != 1) throw BackupError(ERR_VERIFY, "tag")
                    if (dis.readUTF() != ids[i] || dis.readLong() != sizes[i]) throw BackupError(ERR_VERIFY, "entry")
                    val got = pump(dis, sizes[i], null, buf, { n ->
                        done += n
                        onProgress(STAGE_VERIFY, done, total)
                    }, cancelled)
                    if (!MessageDigest.isEqual(got, hashes[i])) throw BackupError(ERR_VERIFY, "content")
                }
                if (dis.readByte().toInt() != 0) throw BackupError(ERR_VERIFY, "end")
                chunks.verifyEnd()
            } catch (e: BackupError) {
                if (e.code == ERR_CANCELLED) throw e
                throw BackupError(ERR_VERIFY, e.message ?: "")
            } catch (e: EOFException) {
                throw BackupError(ERR_VERIFY, "short")
            }
        }
    }

    private fun deleteQuietly(cr: android.content.ContentResolver, uri: Uri) {
        try {
            if (DocumentsContract.deleteDocument(cr, uri)) return
        } catch (e: Exception) {
        }
        try {
            cr.delete(uri, null, null)
        } catch (e: Exception) {
        }
    }

    // ---- Import ----------------------------------------------------------------------------------

    fun restore(
        context: Context,
        src: Uri,
        password: CharArray,
        onProgress: (stage: Int, done: Long, total: Long) -> Unit,
        cancelled: () -> Boolean
    ): RestoreResult {
        val app = context.applicationContext
        val cr = app.contentResolver
        val raw = try {
            cr.openInputStream(src)
        } catch (e: Exception) {
            null
        } ?: throw BackupError(ERR_IO, "cannot open")
        MediaVault.clearStaging(app)
        val staged = ArrayList<MediaVault.Imported>()
        var folders: List<String> = emptyList()
        var assign: Map<String, String> = emptyMap()
        var skipped = 0
        var newBytes = 0L
        var committed = false
        try {
            raw.use {
                onProgress(STAGE_PREPARE, 0, 0)
                val bin = BufferedInputStream(it, 256 * 1024)
                val head = DataInputStream(bin)
                val salt = ByteArray(SALT_LEN)
                val prefix = ByteArray(PREFIX_LEN)
                try {
                    head.readFully(salt)
                    head.readFully(prefix)
                } catch (e: EOFException) {
                    throw BackupError(ERR_BAD_PASSWORD)
                }
                val key = deriveKey(password, salt)
                val chunks = ChunkIn(bin, key, prefix)
                val dis = DataInputStream(chunks)
                val header = readHeader(dis)

                val list = header.manifest.getJSONArray("items")
                val have = MediaVault.list(app).map { item -> item.id }.toHashSet()
                var need = 0L
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val id = o.getString("id")
                    if (!ID_OK.matches(id) || !EXT_OK.matches(o.optString("ext"))) throw BackupError(ERR_CORRUPT, "name")
                    if (id !in have) need += o.getLong("size")
                }
                if (need + SAFETY_MARGIN > MediaVault.freeBytes(app)) throw BackupError(ERR_NO_SPACE, "local")

                val buf = ByteArray(1 shl 20)
                var done = 0L
                for (i in 0 until list.length()) {
                    val o = list.getJSONObject(i)
                    val id = o.getString("id")
                    val size = o.getLong("size")
                    if (dis.readByte().toInt() != 1) throw BackupError(ERR_CORRUPT, "tag")
                    if (dis.readUTF() != id || dis.readLong() != size) throw BackupError(ERR_CORRUPT, "entry")
                    val isNew = id !in have
                    val ext = o.optString("ext")
                    val fileName = if (ext.isNotEmpty()) "$id.$ext" else id
                    val onBytes = { n: Int ->
                        done += n
                        onProgress(STAGE_READ, done, header.total)
                    }
                    if (isNew) {
                        val part = MediaVault.stagingFile(app, fileName)
                        FileOutputStream(part).use { fo ->
                            val bo = BufferedOutputStream(fo, 256 * 1024)
                            pump(dis, size, bo, buf, onBytes, cancelled)
                            bo.flush()
                            try {
                                fo.fd.sync()
                            } catch (e: Exception) {
                            }
                        }
                        if (part.length() != size) throw BackupError(ERR_CORRUPT, "size")
                        staged.add(
                            MediaVault.Imported(
                                id, fileName, o.optBoolean("video"),
                                o.optString("name"), o.optString("mime"), o.optLong("added")
                            )
                        )
                        newBytes += size
                    } else {
                        pump(dis, size, null, buf, onBytes, cancelled)
                        skipped++
                    }
                }
                if (dis.readByte().toInt() != 0) throw BackupError(ERR_CORRUPT, "end")
                chunks.verifyEnd()

                val fa = header.manifest.optJSONArray("folders") ?: JSONArray()
                folders = (0 until fa.length()).map { n -> fa.getString(n) }
                val aj = header.manifest.optJSONObject("assign") ?: JSONObject()
                val m = HashMap<String, String>()
                val keys = aj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    m[k] = aj.getString(k)
                }
                assign = m
            }
            // Everything arrived intact: now move it in.
            MediaVault.commitImported(app, staged)
            committed = true
        } catch (e: BackupError) {
            throw e
        } catch (e: EOFException) {
            throw BackupError(ERR_CORRUPT, "short")
        } catch (e: IOException) {
            throw BackupError(if (isNoSpace(e)) ERR_NO_SPACE else ERR_IO, e.message ?: "")
        } catch (e: org.json.JSONException) {
            throw BackupError(ERR_CORRUPT, "manifest")
        } finally {
            if (!committed) MediaVault.clearStaging(app)
        }

        // Folders and which picture sits in which.
        for (name in folders) FolderStore.add(app, name)
        val have = FolderStore.names(app).toHashSet()
        val byFolder = HashMap<String, MutableList<String>>()
        for (item in staged) {
            val f = assign[item.id] ?: continue
            if (f in have) byFolder.getOrPut(f) { ArrayList() }.add(item.id)
        }
        for ((f, ids) in byFolder) FolderStore.assign(app, ids, f)
        return RestoreResult(staged.size, skipped, newBytes)
    }
}
