package dev.discmount.app

import dev.discmount.core.DiscFile
import dev.discmount.core.Extent
import dev.discmount.core.scsi.DataDirection
import dev.discmount.core.scsi.ScsiDrive
import java.io.IOException

/**
 * Lectura de DVD-Video con CSS. Usa libdvdcss (autenticación con la unidad, clave de disco y de título)
 * sobre la unidad USB. Solo se usa con discos que el usuario posee, como hace VLC con libdvdcss en PC.
 */
class CssDisc private constructor(private val drive: ScsiDrive) : CssBridge {
    private var handle = 0L
    private var keyedTitle = -1L
    private var nextLba = -1L

    val isScrambled: Boolean get() = handle != 0L && CssNative.nativeIsScrambled(handle)
    val lastError: String get() = if (handle != 0L) CssNative.nativeError(handle) else "sin handle"

    override fun readBlocks(lba: Long, count: Int, dst: ByteArray): Int {
        val n = minOf(count.toLong(), drive.blockCount - lba).toInt()
        if (n <= 0) return 0
        return try {
            val tmp = if (n == count) dst else ByteArray(n * 2048)
            drive.readBlocks(lba, n, tmp, 0)
            if (tmp !== dst) System.arraycopy(tmp, 0, dst, 0, tmp.size)
            n
        } catch (e: IOException) { -1 }
    }

    override fun scsi(cdb: ByteArray, dir: Int, buf: ByteArray, len: Int): Int {
        val d = when (dir) { 1 -> DataDirection.IN; 2 -> DataDirection.OUT; else -> DataDirection.NONE }
        return drive.execRaw(cdb, d, if (len > 0) buf else null, len)
    }

    /**
     * Lee [count] sectores descifrados desde [lba]. [titleStart] es el primer sector del VOB (o conjunto de VOB)
     * al que pertenece, que es donde libdvdcss pide la clave de título a la unidad.
     */
    @Synchronized
    fun readSectors(titleStart: Long, lba: Long, count: Int, dst: ByteArray): Int {
        if (handle == 0L) return -1
        if (keyedTitle != titleStart) {
            if (CssNative.nativeSeek(handle, titleStart.toInt(), CssNative.SEEK_KEY) < 0) return -1
            keyedTitle = titleStart
            nextLba = titleStart
        }
        if (nextLba != lba) {
            if (CssNative.nativeSeek(handle, lba.toInt(), 0) < 0) { nextLba = -1; return -1 }
        }
        val n = CssNative.nativeRead(handle, dst, count, CssNative.READ_DECRYPT)
        nextLba = if (n > 0) lba + n else -1
        return n
    }

    @Synchronized
    fun close() {
        if (handle != 0L) { CssNative.nativeClose(handle); handle = 0 }
    }

    companion object {
        /** Devuelve null si libdvdcss no puede abrir la unidad. */
        fun open(drive: ScsiDrive): CssDisc? {
            CssNative.load()
            val d = CssDisc(drive)
            d.handle = CssNative.nativeOpen(d)
            return if (d.handle == 0L) null else d
        }
    }
}

/** Un VOB del disco leído a través de CSS (descifrando los sectores cifrados). */
class CssPartFile(private val css: CssDisc, private val extents: List<Extent>, override val size: Long) : DiscFile {
    private val titleStart = extents.first().lba

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        val tmp = ByteArray(MAX_SECTORS * 2048)
        var done = 0
        while (done < want) {
            val p = pos + done
            val ext = extents.firstOrNull { p >= it.fileOffset && p < it.fileOffset + it.length } ?: break
            val inExt = p - ext.fileOffset
            val skip = (inExt % 2048).toInt()
            val remainInExt = ext.length - inExt
            val sectors = minOf(MAX_SECTORS.toLong(), (skip + minOf(remainInExt, (want - done).toLong()) + 2047) / 2048).toInt()
            val got = css.readSectors(titleStart, ext.lba + inExt / 2048, sectors, tmp)
            if (got <= 0) break
            val n = minOf(got * 2048 - skip, want - done, remainInExt.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            System.arraycopy(tmp, skip, dst, off + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    private companion object { const val MAX_SECTORS = 64 }
}
