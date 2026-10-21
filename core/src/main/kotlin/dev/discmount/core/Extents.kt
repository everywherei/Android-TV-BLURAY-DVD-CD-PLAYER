package dev.discmount.core

import java.io.IOException

internal fun ByteArray.u8(o: Int) = this[o].toInt() and 0xFF
internal fun ByteArray.u16(o: Int) = u8(o) or (u8(o + 1) shl 8)
internal fun ByteArray.u32(o: Int) = (u16(o).toLong()) or (u16(o + 2).toLong() shl 16)
internal fun ByteArray.u64(o: Int) = u32(o) or (u32(o + 4) shl 32)
internal fun ByteArray.u16be(o: Int) = (u8(o) shl 8) or u8(o + 1)
internal fun ByteArray.u32be(o: Int) = (u16be(o).toLong() shl 16) or u16be(o + 2).toLong()

/**
 * Un tramo contiguo de un fichero. [fileOffset] es la posición dentro del fichero,
 * [lba] el bloque del dispositivo (o -1 si es "sparse"/sin grabar, que se lee como ceros).
 */
class Extent(val fileOffset: Long, val lba: Long, val length: Long)

/** Fichero compuesto por extents sobre un [BlockDevice]. */
class ExtentFile(
    private val dev: BlockDevice,
    private val extents: List<Extent>,
    override val size: Long,
) : DiscFile {

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        while (done < want) {
            val p = pos + done
            val ext = find(p) ?: break
            val inExt = p - ext.fileOffset
            val n = minOf(ext.length - inExt, (want - done).toLong()).toInt()
            if (n <= 0) break
            if (ext.lba < 0) java.util.Arrays.fill(dst, off + done, off + done + n, 0)
            else readRange(ext.lba * dev.blockSize + inExt, dst, off + done, n)
            done += n
        }
        // Si hay un hueco (extents incompletos) rellenamos con ceros hasta el tamaño pedido.
        if (done < want) java.util.Arrays.fill(dst, off + done, off + want, 0)
        return want
    }

    private fun find(p: Long): Extent? {
        var lo = 0
        var hi = extents.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val e = extents[mid]
            when {
                p < e.fileOffset -> hi = mid - 1
                p >= e.fileOffset + e.length -> lo = mid + 1
                else -> return e
            }
        }
        return null
    }

    private fun readRange(byteOff: Long, dst: ByteArray, off: Int, n: Int) {
        val bs = dev.blockSize
        var pos = byteOff
        var o = off
        var rem = n
        val head = (pos % bs).toInt()
        if (head != 0) {
            val blk = dev.readBlock(pos / bs)
            val c = minOf(bs - head, rem)
            System.arraycopy(blk, head, dst, o, c)
            pos += c; o += c; rem -= c
        }
        while (rem >= bs) {
            val blocks = minOf(rem / bs, MAX_CHUNK_BLOCKS)
            dev.readBlocks(pos / bs, blocks, dst, o)
            pos += blocks.toLong() * bs; o += blocks * bs; rem -= blocks * bs
        }
        if (rem > 0) {
            val blk = dev.readBlock(pos / bs)
            System.arraycopy(blk, 0, dst, o, rem)
        }
    }

    private companion object { const val MAX_CHUNK_BLOCKS = 128 }
}

class MemoryFile(private val data: ByteArray) : DiscFile {
    override val size get() = data.size.toLong()
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= data.size) return -1
        val n = minOf(len.toLong(), data.size - pos).toInt()
        System.arraycopy(data, pos.toInt(), dst, off, n)
        return n
    }
}

/** Lee un fichero completo en memoria (para directorios, que son pequeños). */
internal fun DiscFile.readFully(): ByteArray {
    if (size > 256L * 1024 * 1024) throw IOException("Directorio demasiado grande: $size")
    val out = ByteArray(size.toInt())
    var p = 0
    while (p < out.size) {
        val n = read(p.toLong(), out, p, out.size - p)
        if (n <= 0) break
        p += n
    }
    return out
}
