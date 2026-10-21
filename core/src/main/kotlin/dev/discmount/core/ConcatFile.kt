package dev.discmount.core

/** Une varios ficheros del disco como un solo flujo continuo (p. ej. VTS_01_1.VOB, VTS_01_2.VOB… de un DVD). */
class ConcatFile(private val parts: List<DiscFile>) : DiscFile {
    private val starts = LongArray(parts.size)
    override val size: Long

    init {
        var acc = 0L
        for ((i, p) in parts.withIndex()) { starts[i] = acc; acc += p.size }
        size = acc
    }

    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        var done = 0
        val want = minOf(len.toLong(), size - pos).toInt()
        while (done < want) {
            val p = pos + done
            var i = parts.size - 1
            while (i > 0 && starts[i] > p) i--
            val inPart = p - starts[i]
            val n = parts[i].read(inPart, dst, off + done, minOf((parts[i].size - inPart), (want - done).toLong()).toInt())
            if (n <= 0) break
            done += n
        }
        return if (done == 0) -1 else done
    }

    override fun close() { parts.forEach { runCatching { it.close() } } }
}
