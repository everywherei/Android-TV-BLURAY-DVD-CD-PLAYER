package dev.discmount.core

import dev.discmount.core.bd.*
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.security.MessageDigest
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private class MemFs(val files: Map<String, ByteArray>) : DiscFileSystem {
    override val type = "mem"
    override val label = "T"
    override fun list(path: String): List<DiscEntry> {
        val pre = if (path == "/") "/" else "$path/"
        return files.filterKeys { it.startsWith(pre) && !it.removePrefix(pre).contains('/') }
            .map { (k, v) -> DiscEntry(k.substringAfterLast('/'), k, false, v.size.toLong()) }
    }
    override fun stat(path: String) = files[path]?.let { DiscEntry(path.substringAfterLast('/'), path, false, it.size.toLong()) }
    override fun open(path: String): DiscFile = MemoryFile(files[path] ?: throw IOException("no existe $path"))
    override fun locate(path: String) = emptyList<Extent>()
}

private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
private val IV = KeyDb.hexToBytes("0BA0F8DDFEA61FB3D8DF9F566A050F78")

private fun ecb(mode: Int, key: ByteArray, data: ByteArray) =
    Cipher.getInstance("AES/ECB/NoPadding").apply { init(mode, SecretKeySpec(key, "AES")) }.doFinal(data)

/** Cifra una unidad alineada (6144 bytes) como lo hace un disco AACS. */
private fun encryptUnit(uk: ByteArray, plain: ByteArray): ByteArray {
    val u = plain.copyOf(); u[0] = (u[0].toInt() or 0xC0).toByte()
    val bk = ecb(Cipher.ENCRYPT_MODE, uk, u.copyOfRange(0, 16))
    for (i in 0 until 16) bk[i] = (bk[i].toInt() xor u[i].toInt()).toByte()
    val c = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(bk, "AES"), IvParameterSpec(IV)) }
    System.arraycopy(c.doFinal(u, 16, Aacs.UNIT - 16), 0, u, 16, Aacs.UNIT - 16)
    return u
}

private fun tsUnit(rnd: Random): ByteArray {
    val u = ByteArray(Aacs.UNIT); rnd.nextBytes(u)
    for (p in 0 until 32) { u[192 * p + 4] = 0x47 }
    u[0] = (u[0].toInt() and 0x3F).toByte()
    return u
}

fun aacsSelfTest(): Int {
    var failures = 0
    fun check(c: Boolean, m: String) { if (!c) { failures++; println("  FALLO: $m") } }
    val rnd = Random(7)
    println("== AACS / KEYDB / MPLS")

    // --- disco sintético ---
    val vuk = ByteArray(16).also { rnd.nextBytes(it) }
    val uks = listOf(ByteArray(16).also { rnd.nextBytes(it) }, ByteArray(16).also { rnd.nextBytes(it) })
    val inf = ByteArray(64 + 48 * 3).also { rnd.nextBytes(it) }
    be32(64).copyInto(inf, 0); be16(2).copyInto(inf, 64)
    for ((i, k) in uks.withIndex()) ecb(Cipher.ENCRYPT_MODE, vuk, k).copyInto(inf, 64 + 48 * (i + 1))
    val discId = KeyDb.bytesToHex(MessageDigest.getInstance("SHA-1").digest(inf))

    val units = List(40) { tsUnit(rnd) }
    val plain = units.fold(ByteArray(0)) { a, b -> a + b }
    // las 3 primeras unidades sin cifrar, el resto cifradas con la 2.ª clave de unidad
    val enc = units.withIndex().fold(ByteArray(0)) { a, (i, u) -> a + (if (i < 3) u else encryptUnit(uks[1], u)) }
    val fs = MemFs(mapOf("/AACS/Unit_Key_RO.inf" to inf, "/BDMV/STREAM/00001.m2ts" to enc))

    val keydbText = """
        ; comentario
        0x0000000000000000000000000000000000000001 = Otra película | D | 2010-01-01 | V | 0x11111111111111111111111111111111
        0x$discId = Mi película (2020) | D | 2020-05-05 | M | 0x22222222222222222222222222222222 | I | 0x$discId | V | 0x${KeyDb.bytesToHex(vuk)} | U | 1-0x${KeyDb.bytesToHex(uks[0])}
    """.trimIndent()
    fun src(t: String): () -> List<() -> BufferedReader> = { listOf({ BufferedReader(StringReader(t)) }) }

    val r = Aacs.resolve(fs, src(keydbText))
    check(r is AacsResult.Ok, "resolve con VUK: ${(r as? AacsResult.Failed)?.message}")
    if (r is AacsResult.Ok) {
        check(r.keys.title == "Mi película (2020)", "título")
        check(r.keys.unitKeys.size == 2 && r.keys.unitKeys[0].contentEquals(uks[0]) && r.keys.unitKeys[1].contentEquals(uks[1]), "claves de unidad derivadas de la VUK")

        val f = AacsFile(MemoryFile(enc), r.keys.unitKeys)
        f.probe()
        val out = ByteArray(plain.size); var p = 0; var chunk = 1
        while (p < out.size) { val n = f.read(p.toLong(), out, p, minOf(chunk, out.size - p)); check(n > 0, "read=$n@$p"); if (n <= 0) break; p += n; chunk = (chunk * 5 + 11) % 20000 + 1 }
        check(out.contentEquals(plain), "descifrado completo (secuencial)")
        val rr = Random(3)
        repeat(100) {
            val o = rr.nextInt(plain.size); val l = 1 + rr.nextInt(minOf(30000, plain.size - o))
            val b = ByteArray(l); val n = f.read(o.toLong(), b, 0, l)
            check(n == l && b.contentEquals(plain.copyOfRange(o, o + l)), "lectura aleatoria $o+$l")
        }
        check(f.read(plain.size.toLong(), ByteArray(1), 0, 1) == -1, "EOF")

        var failed = false
        try { AacsFile(MemoryFile(enc), listOf(ByteArray(16))).probe() } catch (e: IOException) { failed = true }
        check(failed, "una clave incorrecta debe dar error")
    }

    // sin VUK, solo claves de unidad
    val onlyU = "0x$discId = X | U | 1-${KeyDb.bytesToHex(uks[0])}, 2-0x${KeyDb.bytesToHex(uks[1])}"
    val r2 = Aacs.resolve(fs, src(onlyU))
    check(r2 is AacsResult.Ok && r2.keys.unitKeys[1].contentEquals(uks[1]), "resolve solo con claves U")
    // solo clave de medio
    check(Aacs.resolve(fs, src("0x$discId = X | M | 0x${"33".repeat(16)}")) is AacsResult.Failed, "solo MEK debe fallar con mensaje")
    // disco ausente / sin keydb / disco sin AACS
    check((Aacs.resolve(fs, src("; vacío")) as? AacsResult.Failed)?.message?.contains(discId) == true, "disco no listado")
    check(Aacs.resolve(fs, null) is AacsResult.Failed, "sin KEYDB")
    check(Aacs.resolve(MemFs(mapOf("/BDMV/index.bdmv" to ByteArray(8))), null) is AacsResult.None, "sin AACS")
    check(KeyDb.countEntries(BufferedReader(StringReader(keydbText))) == 2, "countEntries")
    check(KeyDb.parseLine("0x$discId = a | V | $discId") == null || KeyDb.parseLine("0x$discId = a | V | $discId")!!.vuk == null, "hex de 40 no es VUK")

    // --- MPLS ---
    fun playItem(clip: String, inT: Int, outT: Int): ByteArray {
        val body = clip.toByteArray() + "M2TS".toByteArray() + byteArrayOf(0, 1, 0) + be32(inT) + be32(outT) + ByteArray(20)
        return be16(body.size) + body
    }
    fun mpls(vararg items: ByteArray): ByteArray {
        val pl = be32(0) + be16(0) + be16(items.size) + be16(0) + items.fold(ByteArray(0)) { a, b -> a + b }
        val head = "MPLS0200".toByteArray() + be32(40) + be32(0) + be32(0) + ByteArray(40 - 20)
        return head + pl
    }
    val longest = mpls(playItem("00001", 0, 45000 * 7000), playItem("00002", 0, 45000 * 100))
    val decoy = mpls(playItem("00009", 0, 45000 * 9000), playItem("00009", 0, 45000 * 9000))
    val short = mpls(playItem("00003", 0, 45000 * 60))
    val fs2 = MemFs(mapOf("/BDMV/PLAYLIST/00000.mpls" to short, "/BDMV/PLAYLIST/00001.mpls" to longest, "/BDMV/PLAYLIST/00002.mpls" to decoy))
    val m = BdPlaylists.main(fs2)
    check(m != null && m.name == "00001.mpls" && m.clips == listOf("00001", "00002") && Math.abs(m.seconds - 7100.0) < 0.01, "lista principal: ${m?.name} ${m?.clips} ${m?.seconds}")

    println(if (failures == 0) "  ok" else "  $failures fallos")
    return failures
}
