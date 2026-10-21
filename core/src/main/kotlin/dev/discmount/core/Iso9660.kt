package dev.discmount.core

import java.io.FileNotFoundException

/** ISO 9660 con extensión Joliet (nombres largos Unicode). No interpreta Rock Ridge. */
class Iso9660FileSystem private constructor(
    private val dev: BlockDevice,
    private val rootLba: Long,
    private val rootLen: Long,
    private val joliet: Boolean,
    override val label: String,
) : DiscFileSystem {

    override val type = if (joliet) "ISO9660 + Joliet" else "ISO9660"

    private class Rec(val name: String, val isDir: Boolean, val size: Long, val extents: List<Extent>)

    private val dirCache = HashMap<String, List<Rec>>()
    private val rootRec = Rec("", true, rootLen, listOf(Extent(0, rootLba, rootLen)))

    private fun parseDir(dir: Rec): List<Rec> {
        val raw = ExtentFile(dev, dir.extents, dir.size).readFully()
        val out = ArrayList<Rec>()
        var pos = 0
        var pending: MutableList<Extent>? = null
        var pendingSize = 0L
        while (pos < raw.size) {
            val len = raw.u8(pos)
            if (len == 0) { // relleno hasta el final del sector
                pos = (pos / 2048 + 1) * 2048
                continue
            }
            if (pos + len > raw.size || len < 34) break
            val extLba = raw.u32(pos + 2)
            val dataLen = raw.u32(pos + 10)
            val flags = raw.u8(pos + 25)
            val nameLen = raw.u8(pos + 32)
            val nameOff = pos + 33
            val nameBytes = raw.copyOfRange(nameOff, nameOff + nameLen)
            pos += len
            if (nameLen == 1 && (nameBytes[0].toInt() == 0 || nameBytes[0].toInt() == 1)) continue // "." y ".."

            val isDir = flags and 0x02 != 0
            val multi = flags and 0x80 != 0
            val name = decodeName(nameBytes, isDir)

            if (pending == null) {
                pending = mutableListOf(); pendingSize = 0
            }
            pending.add(Extent(pendingSize, extLba, dataLen))
            pendingSize += dataLen
            if (multi) continue // el nombre se repite en el siguiente registro; esperamos al último extent
            out.add(Rec(name, isDir, pendingSize, pending))
            pending = null
        }
        return out
    }

    private fun decodeName(b: ByteArray, isDir: Boolean): String {
        var s = if (joliet) String(b, Charsets.UTF_16BE) else String(b, Charsets.ISO_8859_1)
        if (!isDir) {
            val semi = s.lastIndexOf(';')
            if (semi >= 0) s = s.substring(0, semi)
            if (!joliet && s.endsWith(".")) s = s.dropLast(1)
        }
        return s
    }

    private fun children(path: String, rec: Rec): List<Rec> = dirCache.getOrPut(path) { parseDir(rec) }

    private fun resolve(path: String): Rec? {
        var cur = rootRec
        var curPath = "/"
        for (part in normalizePath(path)) {
            if (!cur.isDir) return null
            val next = children(curPath, cur).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            curPath = joinPath(curPath, next.name)
            cur = next
        }
        return cur
    }

    private fun canonical(path: String): String =
        if (normalizePath(path).isEmpty()) "/" else "/" + normalizePath(path).joinToString("/")

    @Synchronized
    override fun list(path: String): List<DiscEntry> {
        val rec = resolve(path) ?: throw FileNotFoundException(path)
        if (!rec.isDir) throw FileNotFoundException("$path no es un directorio")
        val base = canonical(path)
        return children(base, rec).map { DiscEntry(it.name, joinPath(base, it.name), it.isDir, it.size) }
    }

    @Synchronized
    override fun stat(path: String): DiscEntry? {
        val rec = resolve(path) ?: return null
        val c = canonical(path)
        return DiscEntry(rec.name.ifEmpty { "/" }, c, rec.isDir, rec.size)
    }

    @Synchronized
    override fun open(path: String): DiscFile {
        val rec = resolve(path) ?: throw FileNotFoundException(path)
        if (rec.isDir) throw FileNotFoundException("$path es un directorio")
        return ExtentFile(dev, rec.extents, rec.size)
    }

    @Synchronized
    override fun locate(path: String): List<Extent> {
        val rec = resolve(path) ?: throw FileNotFoundException(path)
        return rec.extents
    }

    companion object {
        fun open(dev: BlockDevice): Iso9660FileSystem {
            if (dev.blockSize != 2048) throw UnsupportedFormatException("ISO9660 requiere bloques de 2048 bytes")
            var pvd: ByteArray? = null
            var svd: ByteArray? = null
            for (lba in 16L until minOf(16L + 32, dev.blockCount)) {
                val b = dev.readBlock(lba)
                if (String(b, 1, 5, Charsets.ISO_8859_1) != "CD001") break
                when (b.u8(0)) {
                    1 -> if (pvd == null) pvd = b
                    2 -> {
                        val esc = String(b, 88, 3, Charsets.ISO_8859_1)
                        if (esc == "%/@" || esc == "%/C" || esc == "%/E") svd = b
                    }
                    255 -> break
                }
            }
            if (pvd == null) throw UnsupportedFormatException("No hay descriptor ISO9660")
            val useJoliet = svd != null
            val d = svd ?: pvd
            if (d.u16(128) != 2048) throw UnsupportedFormatException("Tamaño de bloque lógico ISO no soportado: ${d.u16(128)}")
            val label = if (useJoliet) String(d, 40, 32, Charsets.UTF_16BE).trim()
            else String(pvd, 40, 32, Charsets.ISO_8859_1).trim()
            val rootOff = 156
            return Iso9660FileSystem(dev, d.u32(rootOff + 2), d.u32(rootOff + 10), useJoliet, label)
        }
    }
}
