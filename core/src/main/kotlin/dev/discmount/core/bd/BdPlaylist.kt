package dev.discmount.core.bd

import dev.discmount.core.DiscFileSystem

/** Lista de reproducción (.mpls) de un Blu-ray: clips en orden y duración total. */
class BdPlaylist(val name: String, val clips: List<String>, val seconds: Double)

object BdPlaylists {
    /** Interpreta un fichero MPLS. Devuelve null si no es válido. Solo se usa el primer ángulo de cada PlayItem. */
    fun parse(name: String, b: ByteArray): BdPlaylist? {
        if (b.size < 20 || String(b, 0, 4, Charsets.ISO_8859_1) != "MPLS") return null
        fun u16(o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        fun u32(o: Int) = (u16(o).toLong() shl 16) or u16(o + 2).toLong()
        return try {
            val start = u32(8).toInt()
            val n = u16(start + 6)
            var o = start + 10
            val clips = ArrayList<String>()
            var ticks = 0L
            repeat(n) {
                val len = u16(o)
                val clip = String(b, o + 2, 5, Charsets.ISO_8859_1)
                ticks += (u32(o + 18) - u32(o + 14)).coerceAtLeast(0)
                clips += clip
                o += len + 2
            }
            if (clips.isEmpty()) null else BdPlaylist(name, clips, ticks / 45000.0)
        } catch (e: IndexOutOfBoundsException) { null }
    }

    /** Lista de reproducción principal: la más larga, descartando las que repiten un clip (listas "señuelo"). */
    fun main(fs: DiscFileSystem): BdPlaylist? {
        val dir = fs.list("/BDMV/PLAYLIST").filter { !it.isDirectory && it.name.endsWith(".mpls", true) }
        val lists = dir.mapNotNull { e ->
            val data = try { fs.open(e.path).use { f ->
                val buf = ByteArray(f.size.toInt().coerceAtMost(4 shl 20))
                var p = 0
                while (p < buf.size) { val r = f.read(p.toLong(), buf, p, buf.size - p); if (r <= 0) break; p += r }
                buf.copyOf(p)
            } } catch (ex: Exception) { null } ?: return@mapNotNull null
            parse(e.name, data)
        }
        val clean = lists.filter { it.clips.size == it.clips.toSet().size }
        return (clean.ifEmpty { lists }).maxByOrNull { it.seconds }
    }

    /** Ruta del fichero .m2ts de un clip ("00001" → "/BDMV/STREAM/00001.m2ts"). */
    fun clipPath(clip: String) = "/BDMV/STREAM/$clip.m2ts"
}
