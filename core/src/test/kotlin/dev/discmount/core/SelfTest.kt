package dev.discmount.core

import java.io.File
import java.security.MessageDigest

/** Uso: SelfTest <dir con imágenes y .manifest>. Sale con código != 0 si algo falla. */

private var failures = 0

private fun check(cond: Boolean, msg: String) {
    if (!cond) { failures++; println("  FALLO: $msg") }
}

private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private val expectedType = mapOf(
    "iso_plain.iso" to "ISO9660",
    "iso_joliet.iso" to "ISO9660 + Joliet",
    "udf_bridge.iso" to "UDF 1.02", // pycdlib escribe 0x0102 en el identificador de dominio aunque se pida 2.60
    "udf250_metadata.img" to "UDF 2.50",
)

fun main(args: Array<String>) {
    val dir = File(args.getOrNull(0) ?: error("falta directorio de imágenes"))
    val images = dir.listFiles { f -> f.name.endsWith(".manifest") }!!.map { File(dir, it.name.removeSuffix(".manifest")) }.sortedBy { it.name }
    check(images.isNotEmpty(), "no hay imágenes")

    for (img in images) {
        println("== ${img.name}")
        val dev = CachedBlockDevice(FileBlockDevice(img.path))
        val fs = try { DiscFileSystems.open(dev) } catch (e: Exception) { check(false, "no abre: $e"); continue }
        println("  tipo=${fs.type} etiqueta='${fs.label}'")
        check(fs.type == expectedType[img.name], "tipo esperado ${expectedType[img.name]} pero es ${fs.type}")

        val manifest = File(dir, img.name + ".manifest").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        for ((path, sizeStr, hash) in manifest) {
            val st = fs.stat(path)
            if (st == null) { check(false, "no existe $path"); continue }
            check(!st.isDirectory, "$path no debería ser directorio")
            check(st.size == sizeStr.toLong(), "tamaño de $path: ${st.size} != $sizeStr")
            val f = fs.open(path)
            // lectura secuencial con trozos de tamaño raro para ejercitar cabeceras/colas parciales
            val data = ByteArray(sizeStr.toInt())
            var p = 0
            var chunk = 1
            while (p < data.size) {
                val n = f.read(p.toLong(), data, p, minOf(chunk, data.size - p))
                check(n > 0, "lectura devolvió $n en $path@$p"); if (n <= 0) break
                p += n; chunk = (chunk * 3 + 7) % 70000 + 1
            }
            check(sha256(data) == hash, "sha256 distinto en $path")
            // lecturas aleatorias
            if (data.size > 10) {
                val rnd = java.util.Random(42)
                repeat(50) {
                    val o = rnd.nextInt(data.size)
                    val l = 1 + rnd.nextInt(minOf(9000, data.size - o))
                    val buf = ByteArray(l)
                    val n = f.read(o.toLong(), buf, 0, l)
                    check(n == l && buf.contentEquals(data.copyOfRange(o, o + l)), "lectura aleatoria $path@$o+$l")
                }
            }
            check(f.read(data.size.toLong(), ByteArray(4), 0, 4) == -1, "EOF en $path")
            println("  ok $path (${st.size} bytes)")
        }

        // listados: cada fichero del manifiesto debe aparecer en el listado de su directorio (sin distinguir mayúsculas)
        val root = fs.list("/")
        check(root.isNotEmpty(), "raíz vacía")
        for ((path, _, _) in manifest) {
            val parent = path.substringBeforeLast('/').ifEmpty { "/" }
            val name = path.substringAfterLast('/')
            check(fs.list(parent).any { it.name.equals(name, true) && !it.isDirectory }, "$path no aparece en list($parent)")
        }
        check(fs.stat("/NO/EXISTE") == null, "stat de ruta inexistente")
        check(fs.stat("/")?.isDirectory == true, "stat('/')")
        println("  raíz: " + root.joinToString { it.name + if (it.isDirectory) "/" else "" })
    }

    // ---- ConcatFile ----
    run {
        val a = MemoryFile(ByteArray(5000) { (it % 251).toByte() })
        val b = MemoryFile(ByteArray(3000) { (it % 13).toByte() })
        val c = ConcatFile(listOf(a, b))
        val all = ByteArray(8000)
        var p = 0
        while (p < all.size) { val n = c.read(p.toLong(), all, p, 777); if (n <= 0) break; p += n }
        check(p == 8000 && all[4999] == ((4999 % 251).toByte()) && all[5000] == 0.toByte() && all[5014] == (14 % 13).toByte(), "ConcatFile")
        check(c.read(8000, ByteArray(1), 0, 1) == -1, "ConcatFile EOF")
        println("== ConcatFile ok")
    }


    // ---- CD de audio: TOC, CD-TEXT y WAV al vuelo ----
    run {
        fun row(ctrl: Int, track: Int, lba: Long, out: java.io.ByteArrayOutputStream) {
            out.write(byteArrayOf(0, ((1 shl 4) or ctrl).toByte(), track.toByte(), 0))
            out.write(byteArrayOf((lba shr 24).toByte(), (lba shr 16).toByte(), (lba shr 8).toByte(), lba.toByte()))
        }
        val body = java.io.ByteArrayOutputStream()
        row(0, 1, 0, body); row(0, 2, 1000, body); row(4, 3, 5000, body); row(0, 0xAA, 6000, body)
        val len = body.size() + 2
        val toc = byteArrayOf((len shr 8).toByte(), len.toByte(), 1, 3) + body.toByteArray()
        val t = dev.discmount.core.scsi.CdToc.parse(toc, toc.size)
        check(t.tracks.size == 3 && t.audioTracks.size == 2 && t.hasAudio, "TOC pistas")
        check(t.tracks[0].sectors == 1000L && t.tracks[1].endLba == 5000L && !t.tracks[2].isAudio && t.leadOutLba == 6000L, "TOC límites")

        // CD-TEXT: títulos "Album\0Uno\0Dos\0" en paquetes 0x80
        val txt = "Album\u0000Uno\u0000Dos\u0000".toByteArray(Charsets.ISO_8859_1)
        val packs = java.io.ByteArrayOutputStream()
        var seq = 0
        var i = 0
        while (i < txt.size) {
            val pk = ByteArray(18); pk[0] = 0x80.toByte(); pk[1] = 0; pk[2] = (seq++).toByte()
            for (k in 0 until 12) if (i + k < txt.size) pk[4 + k] = txt[i + k]
            packs.write(pk); i += 12
        }
        val l2 = packs.size() + 2
        val ct = byteArrayOf((l2 shr 8).toByte(), l2.toByte(), 0, 0) + packs.toByteArray()
        val text = dev.discmount.core.scsi.CdText.parse(ct, ct.size)
        check(text.titles[0] == "Album" && text.titles[1] == "Uno" && text.titles[2] == "Dos", "CD-TEXT: ${text.titles}")

        // WAV al vuelo sobre un origen falso
        val src = object : dev.discmount.core.scsi.CddaSource {
            override fun readCdda(lba: Long, count: Int, dst: ByteArray, off: Int) {
                for (s in 0 until count) for (b in 0 until 2352) dst[off + s * 2352 + b] = ((lba + s + b) % 251).toByte()
            }
        }
        val f = dev.discmount.core.scsi.CdTrackFile(src, t.tracks[1])
        check(f.size == 44L + 4000L * 2352, "tamaño WAV ${f.size}")
        val h = ByteArray(44); check(f.read(0, h, 0, 44) == 44 && String(h, 0, 4) == "RIFF" && String(h, 8, 4) == "WAVE", "cabecera WAV")
        val pos = 44L + 1234L * 2352 + 17
        val chunk = ByteArray(6000); check(f.read(pos, chunk, 0, chunk.size) == 6000, "lectura WAV")
        var ok = true
        for (b in chunk.indices) {
            val rel = 1234L * 2352 + 17 + b
            val sec = rel / 2352; val inS = (rel % 2352).toInt()
            if (chunk[b] != ((1000 + sec + inS) % 251).toByte()) { ok = false; break }
        }
        check(ok, "contenido WAV")
        check(f.read(f.size, ByteArray(1), 0, 1) == -1, "EOF WAV")
        println("== CD de audio ok")
    }

    // ---- pila completa: FileBlockDevice -> BOT falso -> ScsiDrive -> UDF/ISO ----
    println("== pila USB/SCSI simulada")
    for (name in listOf("udf250_metadata.img", "udf_bridge.iso")) {
        val fake = FakeBotDevice(FileBlockDevice(File(dir, name).path))
        val drive = dev.discmount.core.scsi.ScsiDrive(dev.discmount.core.scsi.BotTransport(fake))
        val inq = drive.inquiry()
        check(inq.isOptical && inq.vendor == "FAKE", "inquiry: $inq")
        check(drive.waitUntilReady(10_000) == null, "waitUntilReady")
        drive.readCapacity()
        check(drive.blockCount == File(dir, name).length() / 2048, "capacidad")
        check(drive.currentProfile().isBluray, "perfil BD")
        val fs = DiscFileSystems.open(CachedBlockDevice(drive))
        val manifest = File(dir, "$name.manifest").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        for ((path, _, hash) in manifest) {
            val f = fs.open(path)
            val d = ByteArray(f.size.toInt()); var p = 0
            while (p < d.size) { val n = f.read(p.toLong(), d, p, d.size - p); if (n <= 0) break; p += n }
            check(sha256(d) == hash, "[USB] sha256 distinto en $path")
        }
        println("  ok $name via ${inq}")
        drive.close()
    }

    failures += aacsSelfTest()

    if (failures > 0) { println("\n$failures FALLOS"); System.exit(1) }
    println("\nTODO OK")
}
