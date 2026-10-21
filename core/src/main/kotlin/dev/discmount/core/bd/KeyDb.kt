package dev.discmount.core.bd

import java.io.BufferedReader

/** Una entrada de KEYDB.cfg (el fichero de claves que aporta el propio usuario para sus discos). */
class KeyDbEntry(
    val discId: String,
    val title: String,
    /** Volume Unique Key (16 bytes), si la entrada la trae. */
    val vuk: ByteArray?,
    /** Claves de unidad CPS ya descifradas, por número de unidad (1 = primera), si la entrada las trae. */
    val unitKeys: Map<Int, ByteArray>,
    /** Hay clave de medio (MEK/MK) pero no VUK: no basta para descifrar sin la autenticación AACS con la unidad. */
    val hasMediaKeyOnly: Boolean,
)

/**
 * Lector de KEYDB.cfg (formato de libaacs):
 * `0x<hash de 40 hex> = Título | D | fecha | M | 0x<mek> | I | 0x<id> | V | 0x<vuk> | U | 1-0x<clave>`
 * Las líneas que empiezan por ';' o '#' son comentarios; las líneas DK/PK/HC/CERT se ignoran.
 */
object KeyDb {
    private val entryRe = Regex("^(?:0x)?([0-9A-Fa-f]{40})\\s*=\\s*(.*)$")
    private val hex32 = Regex("(?<![0-9A-Fa-f])(?:0x)?([0-9A-Fa-f]{32})(?![0-9A-Fa-f])")
    private val unitRe = Regex("(\\d+)\\s*-\\s*(?:0x)?([0-9A-Fa-f]{32})(?![0-9A-Fa-f])")

    fun parseLine(raw: String): KeyDbEntry? {
        val line = raw.trim().removePrefix("﻿")
        if (line.isEmpty() || line[0] == ';' || line[0] == '#') return null
        val m = entryRe.matchEntire(line) ?: return null
        val id = m.groupValues[1].uppercase()
        val parts = m.groupValues[2].split('|').map { it.trim() }
        val title = parts.firstOrNull().orEmpty()
        var vuk: ByteArray? = null
        var mek = false
        val uks = sortedMapOf<Int, ByteArray>()
        var i = 1
        while (i + 1 < parts.size + 1 && i < parts.size) {
            val tag = parts[i].uppercase()
            val value = parts.getOrNull(i + 1) ?: ""
            when (tag) {
                "V" -> hex32.find(value)?.let { vuk = hexToBytes(it.groupValues[1]) }
                "M" -> if (hex32.containsMatchIn(value)) mek = true
                "U" -> unitRe.findAll(value).forEach { uks[it.groupValues[1].toInt()] = hexToBytes(it.groupValues[2]) }
            }
            i += 2
        }
        return KeyDbEntry(id, title, vuk, uks, mek && vuk == null && uks.isEmpty())
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Comprobación barata (sin regex) de que la línea empieza por «[0x]<40 hex> =». */
    private fun looksLikeEntry(l: String): Boolean {
        var s = 0
        if (l.length > 1 && l[0] == '\uFEFF') s = 1
        if (l.startsWith("0x", s) || l.startsWith("0X", s)) s += 2
        if (l.length < s + 41) return false
        for (i in s until s + 40) if (!isHex(l[i])) return false
        var i = s + 40
        while (i < l.length && (l[i] == ' ' || l[i] == '\t')) i++
        return i < l.length && l[i] == '='
    }

    /** Busca el disco [discId] (SHA-1 de Unit_Key_RO.inf, 40 hex) leyendo el fichero línea a línea (sin cargarlo entero). */
    fun find(reader: BufferedReader, discId: String): KeyDbEntry? {
        val id = discId.uppercase()
        reader.useLines { lines ->
            for (l in lines) {
                val s = if (l.startsWith("0x") || l.startsWith("0X")) 2 else if (l.startsWith("\uFEFF0x")) 3 else if (l.startsWith("\uFEFF")) 1 else 0
                if (!l.regionMatches(s, id, 0, 40, ignoreCase = true)) continue
                val e = parseLine(l) ?: continue
                if (e.discId == id) return e
            }
        }
        return null
    }

    /** Número de entradas de disco (para validar un fichero importado). Rápido: no interpreta las líneas. */
    fun countEntries(reader: BufferedReader): Int = reader.useLines { ls -> ls.count { looksLikeEntry(it) } }

    fun hexToBytes(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02X".format(it) }
}
