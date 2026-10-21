package dev.discmount.core.scsi

import dev.discmount.core.BlockDevice
import java.io.IOException

class ScsiSense(val key: Int, val asc: Int, val ascq: Int) {
    override fun toString() = "SENSE %X/%02X/%02X".format(key, asc, ascq)
    val notReady get() = key == 2
    val mediumNotPresent get() = key == 2 && asc == 0x3A
    val becomingReady get() = key == 2 && asc == 0x04
    val unitAttention get() = key == 6
    val mediumError get() = key == 3 || key == 4
}

class ScsiException(val sense: ScsiSense?, msg: String) : IOException(msg)

class InquiryData(val deviceType: Int, val vendor: String, val product: String, val revision: String) {
    val isOptical get() = deviceType == 5
    override fun toString() = "$vendor $product $revision".trim()
}

/** Perfil MMC actual del medio (GET CONFIGURATION). */
enum class DiscProfile(val code: Int, val label: String) {
    NONE(0, "Sin disco"), CD_ROM(0x08, "CD-ROM"), CD_R(0x09, "CD-R"), CD_RW(0x0A, "CD-RW"),
    DVD_ROM(0x10, "DVD-ROM"), DVD_R(0x11, "DVD-R"), DVD_RAM(0x12, "DVD-RAM"), DVD_RW(0x13, "DVD-RW"),
    DVD_RW_SEQ(0x14, "DVD-RW"), DVD_R_DL(0x15, "DVD-R DL"), DVD_PLUS_RW(0x1A, "DVD+RW"), DVD_PLUS_R(0x1B, "DVD+R"),
    DVD_PLUS_R_DL(0x2B, "DVD+R DL"), BD_ROM(0x40, "BD-ROM"), BD_R(0x41, "BD-R"), BD_R_RRM(0x42, "BD-R"), BD_RE(0x43, "BD-RE"),
    UNKNOWN(-1, "Desconocido");

    val isBluray get() = code in 0x40..0x43
    val isDvd get() = code in 0x10..0x2B
    val isCd get() = code in 0x08..0x0A

    companion object { fun fromCode(c: Int) = entries.firstOrNull { it.code == c } ?: UNKNOWN }
}

/** Unidad óptica accesible por SCSI/MMC. Implementa [BlockDevice] para poder montar UDF/ISO encima. */
class ScsiDrive(private val scsi: ScsiTransport) : BlockDevice, CddaSource {
    override var blockSize: Int = 2048
        private set
    override var blockCount: Long = 0
        private set

    /** Máx. bloques por READ(10). 32 × 2048 = 64 KiB, compatible con casi todos los puentes USB-SATA. */
    var maxBlocksPerRead = 32

    // ---- comandos básicos ----

    fun inquiry(): InquiryData {
        val buf = ByteArray(36)
        val r = run(byteArrayOf(0x12, 0, 0, 0, 36, 0), DataDirection.IN, buf, 36)
        if (r.transferred < 36) throw IOException("INQUIRY corto: ${r.transferred}")
        fun s(o: Int, n: Int) = String(buf, o, n, Charsets.US_ASCII).trim()
        return InquiryData(buf[0].toInt() and 0x1F, s(8, 8), s(16, 16), s(32, 4))
    }

    fun testUnitReady(): ScsiSense? {
        val r = scsi.execute(ByteArray(6), DataDirection.NONE, null, 0, 0)
        return if (r.goodStatus) null else requestSense()
    }

    fun requestSense(): ScsiSense {
        val buf = ByteArray(18)
        val r = scsi.execute(byteArrayOf(0x03, 0, 0, 0, 18, 0), DataDirection.IN, buf, 0, 18)
        if (!r.goodStatus || r.transferred < 14) return ScsiSense(0, 0, 0)
        return ScsiSense(buf[2].toInt() and 0x0F, buf[12].toInt() and 0xFF, buf[13].toInt() and 0xFF)
    }

    /** Espera a que haya un disco listo (bandeja cerrada, disco girando, leído el TOC). */
    fun waitUntilReady(timeoutMs: Long = 60_000, onProgress: ((ScsiSense) -> Unit)? = null): ScsiSense? {
        val end = System.currentTimeMillis() + timeoutMs
        var last: ScsiSense? = null
        while (System.currentTimeMillis() < end) {
            val s = testUnitReady() ?: return null
            last = s
            onProgress?.invoke(s)
            if (s.mediumNotPresent) return s // no tiene sentido esperar más
            Thread.sleep(500)
        }
        return last
    }

    /** Devuelve (último LBA, tamaño de bloque) y actualiza [blockCount]/[blockSize]. */
    fun readCapacity(): Pair<Long, Int> {
        val buf = ByteArray(8)
        run(byteArrayOf(0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0), DataDirection.IN, buf, 8)
        val last = be32(buf, 0)
        val bs = be32(buf, 4).toInt()
        if (bs <= 0) throw IOException("Tamaño de bloque inválido: $bs")
        blockSize = bs
        blockCount = last + 1
        return last to bs
    }

    fun currentProfile(): DiscProfile {
        val buf = ByteArray(8)
        // GET CONFIGURATION, RT=00 (todas las features), start feature 0, longitud 8: solo cabecera con el perfil actual
        val cdb = byteArrayOf(0x46, 0, 0, 0, 0, 0, 0, 0, 8, 0)
        return try {
            val r = scsi.execute(cdb, DataDirection.IN, buf, 0, 8)
            if (!r.goodStatus || r.transferred < 8) DiscProfile.UNKNOWN else DiscProfile.fromCode(((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF))
        } catch (e: IOException) { DiscProfile.UNKNOWN }
    }

    /** Abre ([load]=true) o expulsa la bandeja. */
    fun startStop(load: Boolean, eject: Boolean = false) {
        val b4 = (if (load) 1 else 0) or (if (eject) 2 else 0)
        run(byteArrayOf(0x1B, 0, 0, 0, b4.toByte(), 0), DataDirection.NONE, null, 0)
    }

    fun preventRemoval(prevent: Boolean) {
        runCatching { run(byteArrayOf(0x1E, 0, 0, 0, if (prevent) 1 else 0, 0), DataDirection.NONE, null, 0) }
    }

    // ---- CD de audio ----

    /** TOC del disco (READ TOC, formato 0, direcciones LBA). */
    fun readToc(): CdToc {
        val buf = ByteArray(804)
        val cdb = byteArrayOf(0x43, 0, 0, 0, 0, 0, 1, (804 shr 8).toByte(), (804 and 0xFF).toByte(), 0)
        val r = run(cdb, DataDirection.IN, buf, buf.size)
        return CdToc.parse(buf, r.transferred)
    }

    /** CD-TEXT (READ TOC, formato 5); vacío si el disco o la unidad no lo ofrecen. */
    fun readCdText(): CdText {
        val buf = ByteArray(4096)
        val cdb = byteArrayOf(0x43, 0, 5, 0, 0, 0, 0, (4096 shr 8).toByte(), (4096 and 0xFF).toByte(), 0)
        return try {
            val r = run(cdb, DataDirection.IN, buf, buf.size)
            CdText.parse(buf, r.transferred)
        } catch (e: IOException) { CdText.EMPTY }
    }

    /** READ CD (0xBE): sectores de audio crudos de 2352 bytes. */
    @Synchronized
    override fun readCdda(lba: Long, count: Int, dst: ByteArray, off: Int) {
        val bytes = count * 2352
        var lastError: IOException? = null
        for (sectorType in intArrayOf(0x04, 0x00)) {   // 1 = CD-DA; 0 = cualquiera (algunas unidades rechazan el primero)
            val cdb = ByteArray(12)
            cdb[0] = 0xBE.toByte(); cdb[1] = sectorType.toByte()
            cdb[2] = (lba shr 24).toByte(); cdb[3] = (lba shr 16).toByte(); cdb[4] = (lba shr 8).toByte(); cdb[5] = lba.toByte()
            cdb[6] = (count shr 16).toByte(); cdb[7] = (count shr 8).toByte(); cdb[8] = count.toByte()
            cdb[9] = 0x10   // solo datos de usuario (2352 bytes)
            var attempt = 0
            while (true) {
                try {
                    val r = run(cdb, DataDirection.IN, dst, bytes, off)
                    if (r.transferred != bytes) throw IOException("Lectura CD-DA corta en lba=$lba: ${r.transferred}/$bytes")
                    return
                } catch (e: ScsiException) {
                    lastError = e
                    val s = e.sense
                    if (s != null && (s.unitAttention || s.becomingReady) && attempt++ < 3) { Thread.sleep(300); continue }
                    break
                } catch (e: UsbProtocolException) {
                    lastError = e
                    if (attempt++ < 2) continue
                    break
                }
            }
        }
        throw lastError ?: IOException("Fallo leyendo CD-DA en lba=$lba")
    }

    // ---- BlockDevice ----

    override fun readBlocks(lba: Long, count: Int, dst: ByteArray, dstOff: Int) {
        var done = 0
        while (done < count) {
            val n = minOf(maxBlocksPerRead, count - done)
            readChunk(lba + done, n, dst, dstOff + done * blockSize)
            done += n
        }
    }

    private fun readChunk(lba: Long, n: Int, dst: ByteArray, off: Int) {
        if (lba < 0 || lba + n > blockCount) throw IOException("Lectura fuera de rango: lba=$lba n=$n (bloques=$blockCount)")
        val cdb = ByteArray(10)
        cdb[0] = 0x28
        cdb[2] = (lba shr 24).toByte(); cdb[3] = (lba shr 16).toByte(); cdb[4] = (lba shr 8).toByte(); cdb[5] = lba.toByte()
        cdb[7] = (n shr 8).toByte(); cdb[8] = n.toByte()
        val bytes = n * blockSize
        var attempt = 0
        while (true) {
            try {
                val r = run(cdb, DataDirection.IN, dst, bytes, off)
                if (r.transferred != bytes) throw IOException("Lectura corta en lba=$lba: ${r.transferred}/$bytes")
                return
            } catch (e: ScsiException) {
                // Unit attention / "becoming ready": reintentar; errores de medio: se propagan.
                val s = e.sense
                if (s != null && (s.unitAttention || s.becomingReady) && attempt++ < 3) { Thread.sleep(300); continue }
                throw e
            } catch (e: UsbProtocolException) {
                if (attempt++ < 2) continue // el transporte ya hizo reset recovery
                throw e
            }
        }
    }

    override fun close() { preventRemoval(false) }

    // ---- utilidades ----

    /** Ejecuta un CDB y convierte CHECK CONDITION en [ScsiException] con el sense data. */
    fun run(cdb: ByteArray, dir: DataDirection, buf: ByteArray?, len: Int, off: Int = 0): ScsiResult {
        val r = scsi.execute(cdb, dir, buf, off, len)
        if (!r.goodStatus) {
            val s = requestSense()
            throw ScsiException(s, "Comando SCSI 0x%02X falló: %s".format(cdb[0], s))
        }
        return r
    }

    /**
     * Ejecuta un CDB sin interpretar el sense data (lo usa el puente de CSS).
     * Devuelve los bytes transferidos, o -1 si hubo CHECK CONDITION o fallo de transporte.
     */
    fun execRaw(cdb: ByteArray, dir: DataDirection, buf: ByteArray?, len: Int): Int {
        val r = try { scsi.execute(cdb, dir, buf, 0, len) } catch (e: UsbProtocolException) { return -1 }
        if (!r.goodStatus) {
            runCatching { requestSense() } // limpia la condición pendiente
            return -1
        }
        return r.transferred
    }

    private fun be32(b: ByteArray, o: Int) =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)
}
