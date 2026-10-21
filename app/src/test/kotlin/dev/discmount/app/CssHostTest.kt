package dev.discmount.app

import dev.discmount.core.*
import dev.discmount.core.scsi.*
import java.io.File
import java.security.MessageDigest

/** Prueba en el PC (sin Android) del puente JNI + libdvdcss con una unidad SCSI simulada.
 *  Uso: CssHostTest <libdiscmountcss.so> <dir imágenes> */
private class FakeScsi(val image: BlockDevice, val copyright: Int) : ScsiTransport {
    val log = ArrayList<String>()
    private var sense = intArrayOf(0, 0, 0)

    override fun execute(cdb: ByteArray, direction: DataDirection, buf: ByteArray?, off: Int, len: Int, timeoutMs: Int): ScsiResult {
        val op = cdb[0].toInt() and 0xFF
        fun u(i: Int) = cdb[i].toInt() and 0xFF
        fun ok(data: ByteArray): ScsiResult { val n = minOf(len, data.size); if (n > 0) System.arraycopy(data, 0, buf!!, off, n); return ScsiResult(true, n) }
        when (op) {
            0x12 -> return ok(ByteArray(36).also { it[0] = 5; "FAKE    ".toByteArray().copyInto(it, 8) })
            0x00, 0x1E -> return ScsiResult(true, 0)
            0x25 -> return ok(ByteArray(8).also { val l = image.blockCount - 1; for (i in 0..3) it[i] = (l shr (24 - 8 * i)).toByte(); it[6] = 8 })
            0x03 -> return ok(ByteArray(18).also { it[0] = 0x70 })
            0x28 -> {
                val lba = ((u(2).toLong() shl 24) or (u(3).toLong() shl 16) or (u(4).toLong() shl 8) or u(5).toLong())
                val n = (u(7) shl 8) or u(8)
                image.readBlocks(lba, n, buf!!, off)
                return ScsiResult(true, n * 2048)
            }
            0xAD, 0xA4, 0xA3 -> {
                val fmt = if (op == 0xAD) u(7) else u(10) and 0x3F
                log.add("%02X fmt=%02X len=%d agid=%d".format(op, fmt, (u(8) shl 8) or u(9), u(10) shr 6))
                if (op == 0xAD && fmt == 0x01) return ok(ByteArray(8).also { it[4] = copyright.toByte() }) // copyright
                if (op == 0xA4 && fmt == 0x08) return ok(ByteArray(8))                                      // RPC-I
                if (op == 0xA4 && fmt == 0x00) return ok(ByteArray(8).also { it[7] = 0x40 })                // AGID = 1
                if (op == 0xA4 && fmt == 0x3F) return ScsiResult(true, 0)
                if (op == 0xA4 && fmt == 0x05) return ok(ByteArray(8))                                      // ASF = 0
                return ScsiResult(false, 0) // el resto: fallo (la autenticación real no se simula)
            }
        }
        return ScsiResult(false, 0)
    }
}

private var failures = 0
private fun check(c: Boolean, m: String) { if (!c) { failures++; println("  FALLO: $m") } }
private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    System.load(args[0]); CssNative.loaded = true
    val img = File(args[1], "udf_bridge.iso")
    val manifest = File(args[1], "udf_bridge.iso.manifest").readLines().filter { it.isNotBlank() }.map { it.split('\t') }

    for (copyright in intArrayOf(0, 1)) {
        println("== copyright=$copyright")
        val fake = FakeScsi(FileBlockDevice(img.path), copyright)
        val drive = ScsiDrive(fake)
        drive.readCapacity()
        val fs = DiscFileSystems.open(CachedBlockDevice(drive))
        val css = CssDisc.open(drive)
        check(css != null, "CssDisc.open devolvió null")
        if (css == null) continue
        println("  scrambled=${css.isScrambled} error='${css.lastError}'")
        println("  CDBs: " + fake.log.distinct().joinToString(" | "))
        if (copyright == 0) {
            for ((path, size, hash) in manifest) {
                val ext = fs.locate(path)
                if (ext.isEmpty()) continue
                val f = CssPartFile(css, ext, size.toLong())
                val d = ByteArray(size.toInt()); var p = 0
                while (p < d.size) { val n = f.read(p.toLong(), d, p, minOf(100_000, d.size - p)); if (n <= 0) break; p += n }
                check(sha(d) == hash, "sha256 distinto en $path (leídos $p)")
                println("  ok $path vía CSS/JNI")
            }
        }
        css.close()
    }
    if (failures > 0) { println("$failures FALLOS"); System.exit(1) }
    println("TODO OK")
}
