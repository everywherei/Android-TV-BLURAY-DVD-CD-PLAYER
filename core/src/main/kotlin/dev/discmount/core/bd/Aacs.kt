package dev.discmount.core.bd

import dev.discmount.core.DiscFile
import dev.discmount.core.DiscFileSystem
import java.io.BufferedReader
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Claves de unidad CPS de un disco AACS ya resueltas con el KEYDB.cfg del usuario. */
class AacsKeys(val discId: String, val title: String, val unitKeys: List<ByteArray>)

sealed class AacsResult {
    /** El disco no usa AACS. */
    object None : AacsResult()
    class Ok(val keys: AacsKeys) : AacsResult()
    /** Disco AACS que no se puede abrir; [message] explica por qué (en español, para mostrar). */
    class Failed(val message: String, val code: String = "", val arg: String = "") : AacsResult()
}

/** Descifrado AACS (Blu-ray) a partir de la VUK / claves de unidad de KEYDB.cfg. */
object Aacs {
    const val UNIT = 6144                       // unidad alineada de AACS: 32 paquetes de 192 bytes
    private val IV = byteArrayOf(
        0x0B, 0xA0.toByte(), 0xF8.toByte(), 0xDD.toByte(), 0xFE.toByte(), 0xA6.toByte(), 0x1F, 0xB3.toByte(),
        0xD8.toByte(), 0xDF.toByte(), 0x9F.toByte(), 0x56, 0x6A, 0x05, 0x0F, 0x78,
    )
    private val UKRO = listOf("/AACS/Unit_Key_RO.inf", "/AACS/DUPLICATE/Unit_Key_RO.inf")

    private fun readAll(fs: DiscFileSystem, path: String): ByteArray? {
        if (fs.stat(path) == null) return null
        fs.open(path).use { f ->
            val out = ByteArray(f.size.toInt().coerceAtMost(1 shl 20))
            var p = 0
            while (p < out.size) { val n = f.read(p.toLong(), out, p, out.size - p); if (n <= 0) break; p += n }
            return out.copyOf(p)
        }
    }

    fun isProtected(fs: DiscFileSystem): Boolean =
        try { UKRO.any { fs.stat(it) != null } } catch (e: Exception) { false }

    /** Versión de AACS por index.bdmv ("0300" = AACS 2.0 / Ultra HD Blu-ray, no soportado). */
    private fun isUhd(fs: DiscFileSystem): Boolean = try {
        readAll(fs, "/BDMV/index.bdmv")?.takeIf { it.size >= 8 }?.let { String(it, 4, 4, Charsets.ISO_8859_1) == "0300" } ?: false
    } catch (e: Exception) { false }

    /**
     * Resuelve las claves de unidad del disco. [keyDb] abre (una vez por llamada) la fuente de KEYDB.cfg y devuelve null si el
     * usuario no ha importado ninguna.
     */
    fun resolve(fs: DiscFileSystem, keyDb: (() -> List<() -> BufferedReader>)?): AacsResult {
        val inf = try { UKRO.firstNotNullOfOrNull { readAll(fs, it) } } catch (e: Exception) { null } ?: return AacsResult.None
        if (inf.size < 4 + 2) return AacsResult.Failed("Unit_Key_RO.inf del disco no es válido.", "bad_inf")
        if (isUhd(fs)) return AacsResult.Failed("Ultra HD Blu-ray (AACS 2.0): no soportado.", "uhd")
        val discId = KeyDb.bytesToHex(MessageDigest.getInstance("SHA-1").digest(inf))

        val sources = keyDb?.invoke().orEmpty()
        if (sources.isEmpty()) return AacsResult.Failed("Disco cifrado con AACS. Importa tu KEYDB.cfg con el botón «Importar KEYDB.cfg».", "no_keydb")
        var entry: KeyDbEntry? = null
        for (open in sources) {
            entry = try { KeyDb.find(open(), discId) } catch (e: IOException) { null }
            if (entry != null) break
        }
        if (entry == null) return AacsResult.Failed("Este disco no figura en tu KEYDB.cfg (hash $discId).", "not_listed", discId)

        val encKeys = try { parseEncryptedUnitKeys(inf) } catch (e: Exception) {
            return AacsResult.Failed("Unit_Key_RO.inf del disco no es válido (${e.message}).", "bad_inf")
        }
        val keys: List<ByteArray> = when {
            entry.vuk != null -> {
                val aes = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(entry.vuk, "AES")) }
                encKeys.map { aes.doFinal(it) }
            }
            entry.unitKeys.isNotEmpty() -> encKeys.indices.map {
                entry.unitKeys[it + 1] ?: return AacsResult.Failed("La entrada de KEYDB.cfg no trae la clave de unidad ${it + 1}.", "missing_uk", "${it + 1}")
            }
            entry.hasMediaKeyOnly -> return AacsResult.Failed(
                "La entrada de este disco en KEYDB.cfg solo trae la clave de medio (M/MEK): hace falta una entrada con VUK (V) o claves de unidad (U).", "mek_only")
            else -> return AacsResult.Failed("La entrada de este disco en KEYDB.cfg no trae VUK ni claves de unidad.", "no_keys")
        }
        return AacsResult.Ok(AacsKeys(discId, entry.title, keys))
    }

    /** Claves de unidad cifradas de Unit_Key_RO.inf: tabla en `uk_pos`; número de claves (16 bits) y entradas de 48 bytes desde uk_pos+48. */
    internal fun parseEncryptedUnitKeys(b: ByteArray): List<ByteArray> {
        fun be32(o: Int) = ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)
        val pos = be32(0)
        if (pos < 0 || pos + 2 > b.size) throw IOException("tabla de claves fuera de rango")
        val n = ((b[pos].toInt() and 0xFF) shl 8) or (b[pos + 1].toInt() and 0xFF)
        if (n == 0) throw IOException("sin claves de unidad")
        return (0 until n).map { i ->
            val o = pos + 48 * (i + 1)
            if (o + 16 > b.size) throw IOException("clave $i fuera de rango")
            b.copyOfRange(o, o + 16)
        }
    }

    /** Descifra in situ la unidad alineada en [buf]/[off] con [uk]. true si no estaba cifrada o si el resultado es TS válido. */
    fun decryptUnit(uk: Cipher, cbc: Cipher, buf: ByteArray, off: Int, verify: Boolean = true): Boolean {
        if ((buf[off].toInt() and 0xC0) == 0) return true          // copy_permission_indicator = 0 → sin cifrar
        val block = uk.doFinal(buf, off, 16)
        for (i in 0 until 16) block[i] = (block[i].toInt() xor buf[off + i].toInt()).toByte()
        cbc.init(Cipher.DECRYPT_MODE, SecretKeySpec(block, "AES"), IvParameterSpec(IV))
        val plain = cbc.doFinal(buf, off + 16, UNIT - 16)
        if (verify) for (p in 0 until UNIT / 192) if (!syncAt(buf, off, plain, p)) return false
        System.arraycopy(plain, 0, buf, off + 16, plain.size)
        buf[off] = (buf[off].toInt() and 0x3F).toByte()
        return true
    }

    private fun syncAt(buf: ByteArray, off: Int, plain: ByteArray, packet: Int): Boolean {
        val abs = 4 + 192 * packet                  // posición del byte de sincronismo 0x47 dentro de la unidad
        return if (abs < 16) buf[off + abs].toInt() == 0x47 else plain[abs - 16].toInt() == 0x47
    }
}

/** Un fichero .m2ts de un Blu-ray AACS que se entrega descifrado. Las unidades sin cifrar pasan tal cual. */
class AacsFile(private val inner: DiscFile, private val keys: List<ByteArray>) : DiscFile {
    override val size: Long get() = inner.size
    private var keyIdx = -1
    private val ukCiphers = keys.map { k -> Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(k, "AES")) } }
    private val cbc = Cipher.getInstance("AES/CBC/NoPadding")
    private val raw = ByteArray(Aacs.UNIT * CHUNK)

    /** Comprueba (y elige) la clave con la primera unidad cifrada del fichero. Lanza IOException si ninguna clave sirve. */
    @Synchronized
    fun probe() {
        val total = (size / Aacs.UNIT).toInt()
        val tmp = ByteArray(Aacs.UNIT)
        for (u in 0 until minOf(total, 512)) {
            readFully(u.toLong() * Aacs.UNIT, tmp, Aacs.UNIT)
            if ((tmp[0].toInt() and 0xC0) == 0) continue
            selectKey(tmp)
            return
        }
    }

    private fun selectKey(unit: ByteArray) {
        for (i in keys.indices) {
            val t = unit.copyOf()
            if (Aacs.decryptUnit(ukCiphers[i], cbc, t, 0)) { keyIdx = i; return }
        }
        throw IOException("Ninguna clave de tu KEYDB.cfg descifra este disco (¿entrada incorrecta o BD+/AACS no soportado?).")
    }

    private fun readFully(pos: Long, dst: ByteArray, len: Int, dstOff: Int = 0): Int {
        var d = 0
        while (d < len) {
            val n = inner.read(pos + d, dst, dstOff + d, len - d)
            if (n <= 0) break
            d += n
        }
        return d
    }

    @Synchronized
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        while (done < want) {
            val p = pos + done
            val unit = p / Aacs.UNIT
            val inUnit = (p % Aacs.UNIT).toInt()
            val units = minOf(CHUNK, (inUnit + (want - done) + Aacs.UNIT - 1) / Aacs.UNIT)
            val start = unit * Aacs.UNIT
            val got = readFully(start, raw, minOf(units.toLong() * Aacs.UNIT, size - start).toInt())
            if (got <= 0) break
            val full = got / Aacs.UNIT
            for (u in 0 until full) {
                val o = u * Aacs.UNIT
                if ((raw[o].toInt() and 0xC0) == 0) continue
                if (keyIdx < 0) selectKey(raw.copyOfRange(o, o + Aacs.UNIT))
                if (!Aacs.decryptUnit(ukCiphers[keyIdx], cbc, raw, o)) {
                    // Otra unidad CPS dentro del mismo fichero: se vuelve a elegir la clave.
                    val saved = keyIdx
                    keyIdx = -1
                    try { selectKey(raw.copyOfRange(o, o + Aacs.UNIT)) } catch (e: IOException) { keyIdx = saved; throw e }
                    Aacs.decryptUnit(ukCiphers[keyIdx], cbc, raw, o)
                }
            }
            val n = minOf(got - inUnit, want - done)
            if (n <= 0) break
            System.arraycopy(raw, inUnit, dst, off + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    override fun close() = inner.close()

    private companion object { const val CHUNK = 32 }
}
