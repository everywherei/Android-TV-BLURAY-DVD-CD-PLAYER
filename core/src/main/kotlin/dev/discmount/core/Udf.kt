package dev.discmount.core

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Lector UDF 1.02 – 2.60 de solo lectura (DVD-Video, Blu-ray, discos de datos).
 * Soporta particiones tipo 1, sparable (sin remapeo de defectos) y la partición de metadatos de UDF 2.50.
 * No soporta particiones virtuales (VAT, discos grabables secuenciales).
 */
class UdfFileSystem private constructor(
    private val dev: BlockDevice,
    private val maps: List<PartMap>,
    private val partitions: Map<Int, PartitionDesc>,
    private val fsdPartRef: Int,
    private val fsdLbn: Long,
    override val label: String,
    revision: Int,
) : DiscFileSystem {

    override val type = String.format("UDF %x.%02x", revision shr 8, revision and 0xFF)

    private val bs = dev.blockSize

    private enum class Kind { PHYSICAL, METADATA, VIRTUAL }

    private class PartMap(
        val kind: Kind,
        val partNum: Int,
        val metaLoc: Long = 0,
        val metaMirrorLoc: Long = 0,
    ) {
        /** Extents del fichero de metadatos (offset en la partición lógica de metadatos -> LBA físico). */
        var metaExtents: List<Extent>? = null
    }

    private class PartitionDesc(val start: Long, val length: Long)

    private class Icb(
        val fileType: Int,
        val size: Long,
        val partRef: Int,
        val embedded: ByteArray?,
        val extents: List<Extent>,
    )

    private class Child(val name: String, val isDir: Boolean, val partRef: Int, val lbn: Long)

    private var root: Icb? = null
    private val dirCache = HashMap<String, List<Child>>()
    private val icbCache = HashMap<Long, Icb>()

    // ---------- Mapeo de bloques lógicos -> LBA físico ----------

    private fun physicalStart(partNum: Int): Long =
        partitions[partNum]?.start ?: throw IOException("Partición UDF $partNum no encontrada")

    /**
     * Traduce un tramo [lbn, lbn + byteLen) de la partición [partRef] a tramos físicos (lba, bytes).
     * Para la partición de metadatos puede devolver varios si el fichero de metadatos está fragmentado.
     */
    private fun mapRange(partRef: Int, lbn: Long, byteLen: Long): List<Pair<Long, Long>> {
        val map = maps.getOrNull(partRef) ?: throw IOException("Referencia de partición inválida: $partRef")
        return when (map.kind) {
            Kind.PHYSICAL -> listOf((physicalStart(map.partNum) + lbn) to byteLen)
            Kind.VIRTUAL -> throw UnsupportedFormatException("Particiones UDF virtuales (VAT) no soportadas")
            Kind.METADATA -> {
                val exts = metaExtents(map)
                val out = ArrayList<Pair<Long, Long>>()
                var off = lbn * bs
                var rem = byteLen
                while (rem > 0) {
                    val e = exts.firstOrNull { off >= it.fileOffset && off < it.fileOffset + it.length }
                        ?: throw IOException("Bloque fuera del fichero de metadatos: $lbn")
                    val inExt = off - e.fileOffset
                    val n = minOf(rem, e.length - inExt)
                    out.add((e.lba + inExt / bs) to n)
                    off += n; rem -= n
                }
                out
            }
        }
    }

    private fun metaExtents(map: PartMap): List<Extent> {
        map.metaExtents?.let { return it }
        // El fichero de metadatos vive en la partición física asociada.
        val physRef = maps.indexOfFirst { it.kind == Kind.PHYSICAL && it.partNum == map.partNum }
        if (physRef < 0) throw IOException("Partición física de metadatos no encontrada")
        val exts = try {
            readIcbAt(physRef, map.metaLoc).extents
        } catch (e: IOException) {
            // Se intenta el espejo del fichero de metadatos.
            readIcbAt(physRef, map.metaMirrorLoc).extents
        }
        map.metaExtents = exts
        return exts
    }

    private fun readLogicalBlock(partRef: Int, lbn: Long): ByteArray {
        val (lba, _) = mapRange(partRef, lbn, bs.toLong()).first()
        return dev.readBlock(lba)
    }

    // ---------- ICB / File Entry ----------

    private fun readIcbAt(partRef: Int, lbn: Long): Icb {
        val key = (partRef.toLong() shl 40) or lbn
        icbCache[key]?.let { return it }
        var curRef = partRef
        var curLbn = lbn
        var b = readLogicalBlock(curRef, curLbn)
        var hops = 0
        while (b.u16(0) == TAG_INDIRECT && hops++ < 4) { // Indirect Entry: sigue al ICB real
            curLbn = b.u32(24)
            curRef = b.u16(28)
            b = readLogicalBlock(curRef, curLbn)
        }
        val tag = b.u16(0)
        if (tag != TAG_FILE_ENTRY && tag != TAG_EXT_FILE_ENTRY) throw IOException("Se esperaba File Entry en $lbn, tag=$tag")
        val ext = tag == TAG_EXT_FILE_ENTRY
        val fileType = b.u8(27)
        val flags = b.u16(34)
        val allocType = flags and 7
        val infoLen = b.u64(56)
        val lEA = b.u32(if (ext) 208 else 168).toInt()
        val lAD = b.u32(if (ext) 212 else 172).toInt()
        val adOff = (if (ext) 216 else 176) + lEA
        if (adOff + lAD > b.size) throw IOException("File Entry corrupto en $lbn")

        val icb = if (allocType == 3) {
            Icb(fileType, infoLen, partRef, b.copyOfRange(adOff, adOff + minOf(lAD.toLong(), infoLen).toInt()), emptyList())
        } else {
            Icb(fileType, infoLen, partRef, null, buildExtents(b, adOff, lAD, allocType, partRef, fileType, infoLen))
        }
        icbCache[key] = icb
        return icb
    }

    /** Referencia de partición donde residen los *datos* descritos por short_ads. */
    private fun dataPartRef(icbPartRef: Int, fileType: Int): Int {
        val m = maps.getOrNull(icbPartRef) ?: return icbPartRef
        // UDF 2.50: los File Entry y directorios viven en la partición de metadatos, pero el contenido
        // de los ficheros normales está en la partición física asociada.
        if (m.kind == Kind.METADATA && fileType != FT_DIRECTORY && fileType != FT_METADATA && fileType != FT_METADATA_MIRROR) {
            val phys = maps.indexOfFirst { it.kind == Kind.PHYSICAL && it.partNum == m.partNum }
            if (phys >= 0) return phys
        }
        return icbPartRef
    }

    private class Ad(val type: Int, val length: Long, val lbn: Long, val partRef: Int?)

    private fun parseAds(buf: ByteArray, off: Int, len: Int, allocType: Int): List<Ad> {
        val out = ArrayList<Ad>()
        var p = off
        val step = when (allocType) { 0 -> 8; 1 -> 16; 2 -> 20; else -> throw UnsupportedFormatException("Tipo de AD $allocType") }
        while (p + step <= off + len) {
            val raw = buf.u32(p)
            val l = raw and 0x3FFFFFFFL
            val t = (raw shr 30).toInt()
            if (l == 0L && t == 0) break
            when (allocType) {
                0 -> out.add(Ad(t, l, buf.u32(p + 4), null))
                1 -> out.add(Ad(t, l, buf.u32(p + 4), buf.u16(p + 8)))
                2 -> out.add(Ad(t, l, buf.u32(p + 12), buf.u16(p + 16)))
            }
            p += step
        }
        return out
    }

    private fun buildExtents(b: ByteArray, adOff: Int, lAD: Int, allocType: Int, icbPartRef: Int, fileType: Int, infoLen: Long): List<Extent> {
        val ads = ArrayList<Ad>()
        var chunk = parseAds(b, adOff, lAD, allocType)
        var guard = 0
        while (true) {
            val next = chunk.lastOrNull()?.takeIf { it.type == 3 }
            ads.addAll(chunk.filter { it.type != 3 })
            if (next == null || guard++ > 64) break
            // Allocation Extent Descriptor: cabecera de 24 bytes y luego más ADs
            val ref = next.partRef ?: dataPartRef(icbPartRef, FT_DIRECTORY) // los AEDs son metadatos
            val pieces = mapRange(ref, next.lbn, next.length)
            val aed = dev.readBlock(pieces.first().first)
            if (aed.u16(0) != TAG_AED) throw IOException("Se esperaba AED")
            chunk = parseAds(aed, 24, aed.u32(20).toInt(), allocType)
        }

        val extents = ArrayList<Extent>()
        var fileOff = 0L
        for (ad in ads) {
            if (fileOff >= infoLen) break
            val len = minOf(ad.length, infoLen - fileOff)
            if (ad.type != 0) { // no grabado / sin asignar -> ceros
                extents.add(Extent(fileOff, -1, len))
            } else {
                val ref = ad.partRef ?: dataPartRef(icbPartRef, fileType)
                var consumed = 0L
                for ((lba, n) in mapRange(ref, ad.lbn, len)) {
                    extents.add(Extent(fileOff + consumed, lba, n))
                    consumed += n
                }
            }
            fileOff += len
        }
        return extents
    }

    private fun contentOf(icb: Icb): DiscFile =
        icb.embedded?.let { MemoryFile(it) } ?: ExtentFile(dev, icb.extents, icb.size)

    // ---------- Directorios ----------

    private fun parseDirectory(icb: Icb): List<Child> {
        val data = contentOf(icb).readFully()
        val out = ArrayList<Child>()
        var p = 0
        while (p + 38 <= data.size) {
            if (data.u16(p) != TAG_FID) break
            val chars = data.u8(p + 18)
            val lFI = data.u8(p + 19)
            val icbLbn = data.u32(p + 24)
            val icbRef = data.u16(p + 28)
            val lIU = data.u16(p + 36)
            val nameOff = p + 38 + lIU
            val total = (38 + lIU + lFI + 3) and 3.inv()
            if (nameOff + lFI > data.size) break
            if (chars and 0x08 == 0 && chars and 0x04 == 0 && lFI > 0) {
                out.add(Child(Companion.decodeFileName(data, nameOff, lFI), chars and 0x02 != 0, icbRef, icbLbn))
            }
            p += total
        }
        return out
    }

    private fun rootIcb(): Icb = root ?: run {
        val fsd = readLogicalBlock(fsdPartRef, fsdLbn)
        if (fsd.u16(0) != TAG_FSD) throw IOException("File Set Descriptor no encontrado")
        val r = readIcbAt(fsd.u16(400 + 8), fsd.u32(400 + 4))
        root = r
        r
    }

    private fun childrenOf(path: String, icb: Icb): List<Child> = dirCache.getOrPut(path) { parseDirectory(icb) }

    private fun canonical(path: String) = if (normalizePath(path).isEmpty()) "/" else "/" + normalizePath(path).joinToString("/")

    private class Node(val name: String, val icb: Icb)

    private fun resolve(path: String): Node? {
        var cur = Node("", rootIcb())
        var curPath = "/"
        for (part in normalizePath(path)) {
            if (cur.icb.fileType != FT_DIRECTORY) return null
            val c = childrenOf(curPath, cur.icb).firstOrNull { it.name.equals(part, ignoreCase = true) } ?: return null
            curPath = joinPath(curPath, c.name)
            cur = Node(c.name, readIcbAt(c.partRef, c.lbn))
        }
        return cur
    }

    @Synchronized
    override fun list(path: String): List<DiscEntry> {
        val node = resolve(path) ?: throw FileNotFoundException(path)
        if (node.icb.fileType != FT_DIRECTORY) throw FileNotFoundException("$path no es un directorio")
        val base = canonical(path)
        return childrenOf(base, node.icb).map {
            val size = try { readIcbAt(it.partRef, it.lbn).size } catch (e: IOException) { 0L }
            DiscEntry(it.name, joinPath(base, it.name), it.isDir, if (it.isDir) 0 else size)
        }
    }

    @Synchronized
    override fun stat(path: String): DiscEntry? {
        val n = resolve(path) ?: return null
        val isDir = n.icb.fileType == FT_DIRECTORY
        return DiscEntry(n.name.ifEmpty { "/" }, canonical(path), isDir, if (isDir) 0 else n.icb.size)
    }

    @Synchronized
    override fun open(path: String): DiscFile {
        val n = resolve(path) ?: throw FileNotFoundException(path)
        if (n.icb.fileType == FT_DIRECTORY) throw FileNotFoundException("$path es un directorio")
        return contentOf(n.icb)
    }

    @Synchronized
    override fun locate(path: String): List<Extent> {
        val n = resolve(path) ?: throw FileNotFoundException(path)
        return n.icb.extents
    }

    companion object {
        private const val TAG_PVD = 1
        private const val TAG_AVDP = 2
        private const val TAG_VDP = 3
        private const val TAG_PD = 5
        private const val TAG_LVD = 6
        private const val TAG_TERMINATOR = 8
        private const val TAG_FSD = 256
        private const val TAG_FID = 257
        private const val TAG_AED = 258
        private const val TAG_INDIRECT = 259
        private const val TAG_FILE_ENTRY = 261
        private const val TAG_EXT_FILE_ENTRY = 266

        private const val FT_DIRECTORY = 4
        private const val FT_METADATA = 250
        private const val FT_METADATA_MIRROR = 251

        private fun validTag(b: ByteArray, expected: Int? = null): Boolean {
            val id = b.u16(0)
            if (expected != null && id != expected) return false
            if (id == 0) return false
            var sum = 0
            for (i in 0 until 16) if (i != 4) sum += b.u8(i)
            return (sum and 0xFF) == b.u8(4)
        }

        fun open(dev: BlockDevice): UdfFileSystem {
            val bs = dev.blockSize
            // 1. Anchor Volume Descriptor Pointer
            var avdp: ByteArray? = null
            for (lba in longArrayOf(256, dev.blockCount - 256, dev.blockCount - 1)) {
                if (lba < 0 || lba >= dev.blockCount) continue
                val b = try { dev.readBlock(lba) } catch (e: IOException) { continue }
                if (validTag(b, TAG_AVDP)) { avdp = b; break }
            }
            if (avdp == null) throw UnsupportedFormatException("No hay ancla UDF (AVDP)")

            // 2. Volume Descriptor Sequence (principal, y reserva si falla)
            var lastError: Exception? = null
            for (extOff in intArrayOf(16, 24)) {
                val len = avdp.u32(extOff)
                val loc = avdp.u32(extOff + 4)
                try {
                    return fromVds(dev, bs, loc, len)
                } catch (e: UnsupportedFormatException) {
                    throw e
                } catch (e: IOException) {
                    lastError = e
                }
            }
            throw UnsupportedFormatException("UDF inválido: ${lastError?.message}")
        }

        private fun fromVds(dev: BlockDevice, bs: Int, startLoc: Long, startLen: Long): UdfFileSystem {
            var lvd: ByteArray? = null
            var lvdSeq = -1L
            val pds = HashMap<Int, PartitionDesc>()
            val pdSeq = HashMap<Int, Long>()

            var loc = startLoc
            var sectors = (startLen / bs).coerceIn(1, 256)
            var guard = 0
            outer@ while (guard++ < 8) {
                var next: Pair<Long, Long>? = null
                for (i in 0 until sectors) {
                    val b = dev.readBlock(loc + i)
                    if (!validTag(b)) break
                    when (b.u16(0)) {
                        TAG_LVD -> { val s = b.u32(16); if (s >= lvdSeq) { lvd = b; lvdSeq = s } }
                        TAG_PD -> {
                            val num = b.u16(22)
                            val s = b.u32(16)
                            if (s >= (pdSeq[num] ?: -1)) { pds[num] = PartitionDesc(b.u32(188), b.u32(192)); pdSeq[num] = s }
                        }
                        TAG_VDP -> next = b.u32(20) to b.u32(16)
                        TAG_TERMINATOR -> break@outer
                    }
                }
                val n = next ?: break
                loc = n.first; sectors = (n.second / bs).coerceIn(1, 256)
            }
            val l = lvd ?: throw IOException("No hay Logical Volume Descriptor")
            if (l.u32(212).toInt() != bs) throw UnsupportedFormatException("Tamaño de bloque UDF ${l.u32(212)} != $bs")

            val nMaps = l.u32(268).toInt()
            val maps = ArrayList<PartMap>()
            var p = 440
            repeat(nMaps) {
                val t = l.u8(p)
                val len = l.u8(p + 1)
                if (len == 0) throw IOException("Mapa de partición corrupto")
                when (t) {
                    1 -> maps.add(PartMap(Kind.PHYSICAL, l.u16(p + 4)))
                    2 -> {
                        val ident = String(l, p + 5, 23, Charsets.ISO_8859_1).trimEnd('\u0000')
                        when {
                            ident.startsWith("*UDF Metadata Partition") ->
                                maps.add(PartMap(Kind.METADATA, l.u16(p + 38), l.u32(p + 40), l.u32(p + 44)))
                            ident.startsWith("*UDF Sparable Partition") ->
                                maps.add(PartMap(Kind.PHYSICAL, l.u16(p + 38))) // sin remapeo de sectores defectuosos
                            ident.startsWith("*UDF Virtual Partition") ->
                                maps.add(PartMap(Kind.VIRTUAL, l.u16(p + 38)))
                            else -> throw UnsupportedFormatException("Mapa de partición desconocido: $ident")
                        }
                    }
                    else -> throw UnsupportedFormatException("Tipo de mapa de partición $t")
                }
                p += len
            }
            if (maps.isEmpty()) throw IOException("Sin mapas de partición")

            val label = decodeDString(l, 84, 128)
            val domain = String(l, 217, 19, Charsets.ISO_8859_1)
            val rev = if (domain.startsWith("*OSTA UDF")) l.u16(240) else 0x0102
            return UdfFileSystem(dev, maps, pds, l.u16(248 + 8), l.u32(248 + 4), label, rev)
        }

        /** dstring de longitud fija: el último byte contiene la longitud usada (incluido el byte de compresión). */
        private fun decodeDString(b: ByteArray, off: Int, size: Int): String {
            val n = b.u8(off + size - 1)
            if (n <= 1) return ""
            return decodeCompressed(b, off, n)
        }

        /** Nombre de fichero OSTA: primer byte = 8 (Latin-1) o 16 (UTF-16BE). */
        fun decodeFileName(b: ByteArray, off: Int, len: Int): String = decodeCompressed(b, off, len)

        private fun decodeCompressed(b: ByteArray, off: Int, len: Int): String {
            if (len <= 1) return ""
            return when (b.u8(off)) {
                16 -> String(b, off + 1, (len - 1) and 1.inv(), Charsets.UTF_16BE)
                else -> String(b, off + 1, len - 1, Charsets.ISO_8859_1)
            }
        }
    }
}


