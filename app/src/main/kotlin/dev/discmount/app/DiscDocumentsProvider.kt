package dev.discmount.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import android.content.Context
import android.net.Uri
import android.system.ErrnoException
import android.system.OsConstants
import dev.discmount.core.DiscFile
import dev.discmount.core.DiscEntry
import java.io.FileNotFoundException

/**
 * Publica el disco montado como un "almacenamiento" más de Android: cualquier app (VLC incluido) puede
 * abrirlo desde su selector de archivos. El id de documento es la ruta dentro del disco ("/" = raíz).
 */
class DiscDocumentsProvider : DocumentsProvider() {

    private val thread = HandlerThread("disc-io").apply { start() }
    private val handler by lazy { Handler(thread.looper) }

    override fun onCreate(): Boolean {
        DiscSession.listeners.add {
            val ctx = context ?: return@add
            ctx.contentResolver.notifyChange(DocumentsContractRoots.rootsUri(AUTHORITY), null)
            ctx.contentResolver.notifyChange(Uri.parse("content://$AUTHORITY"), null)
        }
        return true
    }

    private val rootProjection = arrayOf(
        Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID, Root.COLUMN_ICON, Root.COLUMN_MIME_TYPES,
    )
    private val docProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED,
    )

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: rootProjection)
        val fs = DiscSession.fs ?: return c
        c.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            add(Root.COLUMN_TITLE, fs.label.ifBlank { "Disco" })
            add(Root.COLUMN_SUMMARY, "${DiscSession.profile.label} · ${fs.type}")
            add(Root.COLUMN_DOCUMENT_ID, "/")
            add(Root.COLUMN_ICON, R_ICON)
            add(Root.COLUMN_MIME_TYPES, "*/*")
        }
        return c
    }

    private fun fs() = DiscSession.fs ?: throw FileNotFoundException("No hay ningún disco montado")

    private fun addRow(c: MatrixCursor, e: DiscEntry) {
        c.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, e.path)
            add(Document.COLUMN_DISPLAY_NAME, if (e.path == "/") (DiscSession.fs?.label ?: "Disco") else e.name)
            add(Document.COLUMN_MIME_TYPE, if (e.isDirectory) Document.MIME_TYPE_DIR else mimeOf(e.name))
            add(Document.COLUMN_SIZE, if (e.isDirectory) null else e.size)
            add(Document.COLUMN_FLAGS, 0)
            add(Document.COLUMN_LAST_MODIFIED, null)
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val c = MatrixCursor(projection ?: docProjection)
        val e = fs().stat(documentId) ?: throw FileNotFoundException(documentId)
        addRow(c, e)
        return c
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor {
        val c = MatrixCursor(projection ?: docProjection)
        try {
            fs().list(parentDocumentId).forEach { addRow(c, it) }
        } catch (e: java.io.IOException) {
            throw FileNotFoundException(e.message)
        }
        return c
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val p = if (parentDocumentId == "/") "/" else "$parentDocumentId/"
        return documentId == parentDocumentId || documentId.startsWith(p)
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        if (mode.any { it == 'w' || it == 'a' }) throw FileNotFoundException("El disco es de solo lectura")
        val file = try { fs().open(documentId) } catch (e: java.io.IOException) { throw FileNotFoundException(e.message) }
        val sm = context!!.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, Proxy(file), handler)
    }

    private class Proxy(private val f: DiscFile) : ProxyFileDescriptorCallback() {
        override fun onGetSize(): Long = f.size
        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            try {
                var done = 0
                while (done < size) {
                    val n = f.read(offset + done, data, done, size - done)
                    if (n <= 0) break
                    done += n
                }
                return done
            } catch (e: java.io.IOException) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }
        override fun onRelease() { f.close() }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val fromMap = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return fromMap ?: when (ext) {
            "m2ts", "mts" -> "video/mp2t"
            "vob" -> "video/mpeg"
            "mkv" -> "video/x-matroska"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val AUTHORITY = "dev.discmount.documents"
        const val ROOT_ID = "disc"
        val R_ICON = R.drawable.ic_disc
    }
}

private object DocumentsContractRoots {
    fun rootsUri(authority: String): Uri = android.provider.DocumentsContract.buildRootsUri(authority)
}
