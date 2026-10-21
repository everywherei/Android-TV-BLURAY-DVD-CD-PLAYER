package dev.discmount.core

import java.io.Closeable
import java.io.IOException
import java.io.InputStream

data class DiscEntry(
    val name: String,
    /** Ruta absoluta con '/', p. ej. "/BDMV/STREAM/00000.m2ts". La raíz es "/". */
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

/** Acceso aleatorio a un fichero del disco. */
interface DiscFile : Closeable {
    val size: Long
    /** Devuelve los bytes leídos, o -1 en EOF. */
    fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int
    override fun close() {}
}

interface DiscFileSystem {
    /** "UDF 2.50", "ISO9660 + Joliet", ... */
    val type: String
    val label: String
    fun list(path: String): List<DiscEntry>
    fun stat(path: String): DiscEntry?
    fun open(path: String): DiscFile

    /** Extents físicos (LBA del dispositivo) de un fichero; vacío si está embebido o no tiene datos. */
    fun locate(path: String): List<Extent>
}

fun DiscFile.asInputStream(): InputStream = object : InputStream() {
    private var pos = 0L
    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xFF
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = this@asInputStream.read(pos, b, off, len)
        if (n > 0) pos += n
        return n
    }
    override fun available(): Int = (size - pos).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    override fun close() = this@asInputStream.close()
}

class UnsupportedFormatException(msg: String) : IOException(msg)

internal fun normalizePath(path: String): List<String> =
    path.split('/').filter { it.isNotEmpty() && it != "." }

internal fun joinPath(parent: String, name: String) = if (parent == "/") "/$name" else "$parent/$name"

object DiscFileSystems {
    /**
     * Detecta y abre el sistema de ficheros. Para discos "bridge" (UDF + ISO9660, típico de DVD-Video y Blu-ray)
     * se prefiere UDF; si falla se recurre a ISO9660.
     */
    fun open(dev: BlockDevice): DiscFileSystem {
        var udfError: Exception? = null
        try {
            return UdfFileSystem.open(dev)
        } catch (e: UnsupportedFormatException) {
            udfError = e
        } catch (e: IOException) {
            udfError = e
        }
        try {
            return Iso9660FileSystem.open(dev)
        } catch (e: UnsupportedFormatException) {
            throw UnsupportedFormatException("No se reconoce el sistema de ficheros (UDF: ${udfError?.message}; ISO9660: ${e.message})")
        }
    }
}
