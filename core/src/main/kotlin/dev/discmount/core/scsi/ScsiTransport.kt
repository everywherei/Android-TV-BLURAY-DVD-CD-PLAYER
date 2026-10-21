package dev.discmount.core.scsi

import java.io.IOException

enum class DataDirection { NONE, IN, OUT }

/** Resultado de un comando SCSI: [goodStatus] false = CHECK CONDITION (usar REQUEST SENSE). */
class ScsiResult(val goodStatus: Boolean, val transferred: Int)

/** Ejecuta CDBs SCSI/MMC. Es el punto de enganche para fases posteriores (autenticación CSS, AACS). */
interface ScsiTransport {
    fun execute(cdb: ByteArray, direction: DataDirection, buf: ByteArray?, off: Int, len: Int, timeoutMs: Int = 10_000): ScsiResult
}

class UsbProtocolException(msg: String) : IOException(msg)

/** Protocolo USB Mass Storage Bulk-Only Transport (BOT). */
class BotTransport(private val usb: UsbTransport, private val lun: Int = 0) : ScsiTransport {
    private var nextTag = 1

    @Synchronized
    override fun execute(cdb: ByteArray, direction: DataDirection, buf: ByteArray?, off: Int, len: Int, timeoutMs: Int): ScsiResult {
        require(cdb.size in 1..16)
        require(direction == DataDirection.NONE || (buf != null && len > 0))
        val tag = nextTag++

        val cbw = ByteArray(31)
        putLE32(cbw, 0, 0x43425355)              // "USBC"
        putLE32(cbw, 4, tag)
        putLE32(cbw, 8, if (direction == DataDirection.NONE) 0 else len)
        cbw[12] = if (direction == DataDirection.IN) 0x80.toByte() else 0
        cbw[13] = lun.toByte()
        cbw[14] = cdb.size.toByte()
        System.arraycopy(cdb, 0, cbw, 15, cdb.size)

        if (usb.bulkOut(cbw, 0, cbw.size, timeoutMs) != cbw.size) {
            recover()
            throw UsbProtocolException("Fallo enviando CBW")
        }

        var transferred = 0
        var dataStall = false
        when (direction) {
            DataDirection.IN -> {
                val n = usb.bulkIn(buf!!, off, len, timeoutMs)
                if (n < 0) { usb.clearHalt(true); dataStall = true } else transferred = n
            }
            DataDirection.OUT -> {
                val n = usb.bulkOut(buf!!, off, len, timeoutMs)
                if (n < 0) { usb.clearHalt(false); dataStall = true } else transferred = n
            }
            DataDirection.NONE -> {}
        }

        val csw = ByteArray(13)
        var got = usb.bulkIn(csw, 0, 13, timeoutMs)
        if (got < 0) { // un stall al leer el CSW: se limpia y se reintenta una vez (BOT 6.7.2)
            usb.clearHalt(true)
            got = usb.bulkIn(csw, 0, 13, timeoutMs)
        }
        if (got != 13) {
            recover()
            throw UsbProtocolException("CSW inválido (leídos $got bytes)")
        }
        if (getLE32(csw, 0) != 0x53425355 || getLE32(csw, 4) != tag) {
            recover()
            throw UsbProtocolException("CSW con firma o tag incorrectos")
        }
        return when (csw[12].toInt()) {
            0 -> ScsiResult(!dataStall || direction == DataDirection.NONE, transferred)
            1 -> ScsiResult(false, transferred)
            else -> { recover(); throw UsbProtocolException("Error de fase BOT") }
        }
    }

    private fun recover() {
        try {
            usb.massStorageReset()
            usb.clearHalt(true)
            usb.clearHalt(false)
        } catch (_: Exception) { /* mejor esfuerzo */ }
    }

    private fun putLE32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte(); b[o + 2] = (v shr 16).toByte(); b[o + 3] = (v shr 24).toByte()
    }

    private fun getLE32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
