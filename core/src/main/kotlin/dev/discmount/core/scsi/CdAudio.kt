package dev.discmount.core.scsi

import dev.discmount.core.DiscFile
import java.io.IOException

/** Una pista del TOC de un CD. Los LBA son absolutos del disco (el primer sector de la pista 1 suele ser el 0). */
class CdTrack(val number: Int, val isAudio: Boolean, val startLba: Long, val endLba: Long) {
    /** Número de sectores (exclusivo de [endLba]). */
    val sectors get() = (endLba - startLba).coerceAtLeast(0)
    val seconds get() = sectors / 75.0
    override fun toString() = "Pista $number ${if (isAudio) "audio" else "datos"} $startLba..$endLba"
}

class CdToc(val tracks: List<CdTrack>, val leadOutLba: Long) {
    val audioTracks get() = tracks.filter { it.isAudio }
    val hasAudio get() = tracks.any { it.isAudio }

    companion object {
        /** Interpreta la respuesta de READ TOC formato 0 en modo LBA (MSF=0). */
        fun parse(buf: ByteArray, len: Int): CdToc {
            if (len < 4) throw IOException("TOC demasiado corto: $len")
            val dataLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val total = minOf(len, dataLen + 2)
            val rows = (total - 4) / 8
            class Row(val control: Int, val track: Int, val lba: Long)
            val list = ArrayList<Row>()
            for (i in 0 until rows) {
                val o = 4 + i * 8
                val lba = ((buf[o + 4].toLong() and 0xFF) shl 24) or ((buf[o + 5].toLong() and 0xFF) shl 16) or
                    ((buf[o + 6].toLong() and 0xFF) shl 8) or (buf[o + 7].toLong() and 0xFF)
                list.add(Row(buf[o + 1].toInt() and 0x0F, buf[o + 2].toInt() and 0xFF, lba))
            }
            val leadOut = list.firstOrNull { it.track == 0xAA }?.lba ?: throw IOException("El TOC no tiene lead-out")
            val real = list.filter { it.track in 1..99 }.sortedBy { it.track }
            val tracks = real.mapIndexed { i, r ->
                val end = if (i + 1 < real.size) real[i + 1].lba else leadOut
                // bit 2 del campo CONTROL: 1 = pista de datos
                CdTrack(r.track, (r.control and 0x04) == 0, r.lba, end)
            }
            return CdToc(tracks, leadOut)
        }
    }
}

/** Títulos e intérpretes de CD-TEXT (si el disco los trae). Clave 0 = álbum. */
class CdText(val titles: Map<Int, String>, val performers: Map<Int, String>) {
    companion object {
        val EMPTY = CdText(emptyMap(), emptyMap())

        /** Interpreta READ TOC formato 5 (cabecera de 4 bytes + paquetes de 18). Solo paquetes de texto de 8 bits (ASCII/Latin-1). */
        fun parse(buf: ByteArray, len: Int): CdText {
            if (len < 22) return EMPTY
            val dataLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val total = minOf(len, dataLen + 2)
            val packs = (total - 4) / 18
            val titles = HashMap<Int, String>()
            val performers = HashMap<Int, String>()
            // El texto de un tipo de paquete se reparte en trozos de 12 bytes, con cadenas terminadas en 0 que
            // corresponden a pistas consecutivas.
            class Acc { val sb = StringBuilder(); var track = -1; var started = false }
            val acc = HashMap<Int, Acc>()
            for (p in 0 until packs) {
                val o = 4 + p * 18
                val type = buf[o].toInt() and 0xFF
                if (type != 0x80 && type != 0x81) continue
                val dbcc = (buf[o + 3].toInt() and 0x80) != 0
                if (dbcc) continue
                val a = acc.getOrPut(type) { Acc() }
                if (!a.started) { a.track = buf[o + 1].toInt() and 0x7F; a.started = true }
                val target = if (type == 0x80) titles else performers
                for (i in 4 until 16) {
                    val b = buf[o + i].toInt() and 0xFF
                    if (b == 0) {
                        val s = a.sb.toString().trim()
                        if (s.isNotEmpty() && a.track !in target) target[a.track] = s
                        a.sb.setLength(0)
                        a.track++
                    } else a.sb.append(b.toChar())
                }
            }
            return CdText(titles, performers)
        }
    }
}

/** Origen de sectores CD-DA crudos (2352 bytes por sector). Lo implementa [ScsiDrive]. */
interface CddaSource {
    /** Lee [count] sectores de audio desde [lba] a [dst] (count × 2352 bytes). */
    fun readCdda(lba: Long, count: Int, dst: ByteArray, off: Int)
}

/**
 * Una pista de audio como fichero WAV (PCM 16 bit, 44,1 kHz, estéreo) generado al vuelo: así cualquier reproductor
 * (libvlc) puede leerla y hacer seek sin que haya que copiar la pista antes.
 */
class CdTrackFile(private val src: CddaSource, private val track: CdTrack) : DiscFile {
    private val dataBytes = track.sectors * SECTOR
    override val size = HEADER + dataBytes

    private val header: ByteArray = ByteArray(HEADER.toInt()).also { h ->
        fun put32(o: Int, v: Long) { for (i in 0..3) h[o + i] = (v shr (8 * i)).toByte() }
        fun put16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
        "RIFF".toByteArray().copyInto(h, 0); put32(4, size - 8)
        "WAVE".toByteArray().copyInto(h, 8); "fmt ".toByteArray().copyInto(h, 12)
        put32(16, 16); put16(20, 1); put16(22, 2); put32(24, 44100); put32(28, 44100L * 4); put16(32, 4); put16(34, 16)
        "data".toByteArray().copyInto(h, 36); put32(40, dataBytes)
    }

    /** Sectores que no se han podido leer (se sustituyen por silencio) y último error, para mostrarlos al usuario. */
    @Volatile var unreadable = 0
        private set
    @Volatile var lastError: String? = null
        private set

    /** Lee el principio de la pista y propaga el error real de la unidad si no se puede leer (en vez de dar silencio). */
    fun probe() {
        if (track.sectors <= 0) throw IOException("La pista está vacía")
        src.readCdda(track.startLba, 1, ByteArray(SECTOR.toInt()), 0)
    }

    private var cache = ByteArray(CHUNK_SECTORS * SECTOR.toInt())
    private var cacheStart = -1L   // primer sector (relativo a la pista) en caché
    private var cacheCount = 0

    @Synchronized
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        if (pos < HEADER) {
            val n = minOf(HEADER - pos, want.toLong()).toInt()
            System.arraycopy(header, pos.toInt(), dst, off, n)
            done = n
        }
        while (done < want) {
            val rel = pos + done - HEADER
            val sector = rel / SECTOR
            val inSector = (rel % SECTOR).toInt()
            loadChunk(sector)
            val idx = (sector - cacheStart).toInt()
            val avail = cacheCount * SECTOR.toInt() - (idx * SECTOR.toInt() + inSector)
            val n = minOf(avail, want - done)
            if (n <= 0) break
            System.arraycopy(cache, idx * SECTOR.toInt() + inSector, dst, off + done, n)
            done += n
        }
        return done
    }

    private fun loadChunk(sector: Long) {
        if (cacheStart >= 0 && sector >= cacheStart && sector < cacheStart + cacheCount) return
        val start = sector - sector % CHUNK_SECTORS
        val count = minOf(CHUNK_SECTORS.toLong(), track.sectors - start).toInt()
        try {
            src.readCdda(track.startLba + start, count, cache, 0)
        } catch (e: IOException) {
            // Sector dañado: se reintenta uno a uno y lo ilegible se sustituye por silencio, para no cortar la pista.
            for (i in 0 until count) {
                try { src.readCdda(track.startLba + start + i, 1, cache, i * SECTOR.toInt()) }
                catch (e2: IOException) {
                    unreadable++; lastError = e2.message
                    java.util.Arrays.fill(cache, i * SECTOR.toInt(), (i + 1) * SECTOR.toInt(), 0)
                }
            }
        }
        cacheStart = start
        cacheCount = count
    }

    companion object {
        const val SECTOR = 2352L
        const val HEADER = 44L
        const val CHUNK_SECTORS = 26
    }
}
