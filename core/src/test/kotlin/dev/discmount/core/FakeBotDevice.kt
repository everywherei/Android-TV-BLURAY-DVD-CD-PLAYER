package dev.discmount.core

import dev.discmount.core.scsi.UsbTransport

/** Emula una unidad óptica USB Bulk-Only sobre una imagen, con fallos inyectados (no listo, unit attention). */
class FakeBotDevice(private val image: BlockDevice, private var notReadyCount: Int = 2, private var unitAttention: Boolean = true) : UsbTransport {
    private var pendingIn: ByteArray? = null
    private var cswTag = 0
    private var cswStatus = 0
    private var cswResidue = 0
    private var sense = byteArrayOf(0, 0, 0)
    private var haltIn = false
    var resets = 0

    private fun le32(b: ByteArray, o: Int) = b.u32(o).toInt()
    private fun be32(b: ByteArray, o: Int) = b.u32be(o)

    override fun bulkOut(src: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        val cbw = src.copyOfRange(off, off + len)
        if (len != 31 || le32(cbw, 0) != 0x43425355) return -1
        cswTag = le32(cbw, 4)
        val expect = le32(cbw, 8)
        val cdb = cbw.copyOfRange(15, 15 + cbw.u8(14))
        var data = ByteArray(0)
        cswStatus = 0
        when (cdb.u8(0)) {
            0x00 -> if (notReadyCount > 0) { notReadyCount--; check(0x02, 0x04, 0x01) } else if (unitAttention) { unitAttention = false; check(0x06, 0x28, 0x00) }
            0x03 -> data = ByteArray(18).also { it[0] = 0x70; it[2] = sense[0]; it[12] = sense[1]; it[13] = sense[2] }
            0x12 -> data = ByteArray(36).also {
                it[0] = 5
                "FAKE    ".toByteArray().copyInto(it, 8); "OPTICAL DRIVE   ".toByteArray().copyInto(it, 16); "1.00".toByteArray().copyInto(it, 32)
            }
            0x25 -> data = ByteArray(8).also {
                val last = image.blockCount - 1
                for (i in 0..3) it[i] = (last shr (24 - 8 * i)).toByte()
                it[6] = 0x08 // 2048
            }
            0x46 -> data = ByteArray(8).also { it[7] = 0x40 } // BD-ROM
            0x28 -> {
                val lba = be32(cdb, 2); val n = (cdb.u8(7) shl 8) or cdb.u8(8)
                data = ByteArray(n * 2048)
                image.readBlocks(lba, n, data, 0)
            }
            else -> check(0x05, 0x20, 0x00)
        }
        if (cswStatus != 0) data = ByteArray(0)
        pendingIn = if (expect == 0) null else data.copyOf(minOf(data.size, expect))
        cswResidue = expect - (pendingIn?.size ?: 0)
        return len
    }

    private fun check(key: Int, asc: Int, ascq: Int) { cswStatus = 1; sense = byteArrayOf(key.toByte(), asc.toByte(), ascq.toByte()) }

    private var phase = 0 // 0 = datos, 1 = CSW
    override fun bulkIn(dst: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (len == 13 && (pendingIn == null)) { // CSW
            val csw = ByteArray(13)
            csw[0] = 0x55; csw[1] = 0x53; csw[2] = 0x42; csw[3] = 0x53
            for (i in 0..3) csw[4 + i] = (cswTag shr (8 * i)).toByte()
            for (i in 0..3) csw[8 + i] = (cswResidue shr (8 * i)).toByte()
            csw[12] = cswStatus.toByte()
            System.arraycopy(csw, 0, dst, off, 13)
            return 13
        }
        val d = pendingIn ?: return -1
        pendingIn = null
        // Si el comando esperaba datos pero no hay (error), emulamos stall como un dispositivo real
        if (d.isEmpty()) { pendingIn = null; return 0 }
        System.arraycopy(d, 0, dst, off, d.size)
        return d.size
    }

    override fun clearHalt(inEndpoint: Boolean) {}
    override fun massStorageReset() { resets++ }
}
