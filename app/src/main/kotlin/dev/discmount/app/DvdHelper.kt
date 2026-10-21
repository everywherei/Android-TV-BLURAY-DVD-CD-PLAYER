package dev.discmount.app

import dev.discmount.core.DiscFileSystem

object DvdHelper {
    private val vob = Regex("(?i)^VTS_(\\d\\d)_([1-9])\\.VOB$")

    /** Ruta del primer VOB del conjunto de títulos más grande de /VIDEO_TS (la película principal), o null. */
    fun mainTitle(fs: DiscFileSystem): String? {
        val entries = try { fs.list("/").firstOrNull { it.isDirectory && it.name.equals("VIDEO_TS", true) }
            ?.let { fs.list(it.path) } } catch (e: Exception) { null } ?: return null
        val sizes = HashMap<String, Long>()
        for (e in entries) {
            val m = vob.find(e.name) ?: continue
            sizes.merge(m.groupValues[1], e.size, Long::plus)
        }
        val best = sizes.maxByOrNull { it.value }?.key ?: return null
        return entries.firstOrNull { it.name.equals("VTS_${best}_1.VOB", true) }?.path
    }
}
