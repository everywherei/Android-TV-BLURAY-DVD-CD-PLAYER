package dev.discmount.core

import java.io.Closeable
import java.io.RandomAccessFile

/** Dispositivo de bloques de solo lectura (un disco óptico, normalmente con bloques de 2048 bytes). */
interface BlockDevice : Closeable {
    val blockSize: Int
    val blockCount: Long

    /** Lee [count] bloques consecutivos desde [lba] y los copia en [dst] a partir de [dstOff]. */
    fun readBlocks(lba: Long, count: Int, dst: ByteArray, dstOff: Int = 0)
}

fun BlockDevice.readBlock(lba: Long): ByteArray =
    ByteArray(blockSize).also { readBlocks(lba, 1, it, 0) }

/** Imagen en fichero (.iso / .img). Útil para pruebas sin hardware. */
class FileBlockDevice(path: String, override val blockSize: Int = 2048) : BlockDevice {
    private val raf = RandomAccessFile(path, "r")
    override val blockCount: Long = raf.length() / blockSize

    override fun readBlocks(lba: Long, count: Int, dst: ByteArray, dstOff: Int) {
        if (lba < 0 || lba + count > blockCount) throw java.io.IOException("Lectura fuera de rango: lba=$lba count=$count")
        raf.seek(lba * blockSize)
        raf.readFully(dst, dstOff, count * blockSize)
    }

    override fun close() = raf.close()
}

/** Caché LRU de bloques; las unidades USB son lentas en lecturas pequeñas y los parsers de FS releen mucho. */
class CachedBlockDevice(private val inner: BlockDevice, private val maxBlocks: Int = 2048) : BlockDevice {
    override val blockSize get() = inner.blockSize
    override val blockCount get() = inner.blockCount

    private val cache = object : LinkedHashMap<Long, ByteArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > maxBlocks
    }

    @Synchronized
    override fun readBlocks(lba: Long, count: Int, dst: ByteArray, dstOff: Int) {
        // Lecturas grandes: directas (no ensucian la caché). Pequeñas: bloque a bloque con caché.
        if (count > 16) {
            inner.readBlocks(lba, count, dst, dstOff)
            return
        }
        for (i in 0 until count) {
            val key = lba + i
            val blk = cache.getOrPut(key) { ByteArray(blockSize).also { inner.readBlocks(key, 1, it, 0) } }
            System.arraycopy(blk, 0, dst, dstOff + i * blockSize, blockSize)
        }
    }

    override fun close() = inner.close()
}
