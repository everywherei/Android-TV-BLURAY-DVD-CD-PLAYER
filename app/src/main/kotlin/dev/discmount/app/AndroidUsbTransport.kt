package dev.discmount.app

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import dev.discmount.core.scsi.BotTransport
import dev.discmount.core.scsi.ScsiDrive
import dev.discmount.core.scsi.UsbTransport
import java.io.IOException

/** Implementación de [UsbTransport] sobre la API USB Host de Android. */
class AndroidUsbTransport(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
) : UsbTransport {

    override fun bulkOut(src: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        var total = 0
        while (total < len) {
            val n = conn.bulkTransfer(outEp, src, off + total, minOf(CHUNK, len - total), timeoutMs)
            if (n < 0) return if (total > 0) total else -1
            total += n
        }
        return total
    }

    override fun bulkIn(dst: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        var total = 0
        while (total < len) {
            val want = minOf(CHUNK, len - total)
            val n = conn.bulkTransfer(inEp, dst, off + total, want, timeoutMs)
            if (n < 0) return if (total > 0) total else -1
            total += n
            if (n < want) break // paquete corto: fin de la fase de datos
        }
        return total
    }

    override fun clearHalt(inEndpoint: Boolean) {
        val ep = if (inEndpoint) inEp else outEp
        // CLEAR_FEATURE(ENDPOINT_HALT) dirigido al endpoint
        conn.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
    }

    override fun massStorageReset() {
        conn.controlTransfer(0x21, 0xFF, 0, intf.id, null, 0, 1000)
    }

    fun close() {
        conn.releaseInterface(intf)
        conn.close()
    }

    companion object {
        /**
         * Tamaño máximo por transferencia bulk. 16 KiB es seguro en todas las versiones de Android;
         * en Android 9+ se puede probar a subirlo a 64 KiB o más para ganar velocidad.
         */
        const val CHUNK = 16 * 1024
    }
}

object UsbDrives {
    class Candidate(val device: UsbDevice, val intf: UsbInterface) {
        val name: String get() = device.productName ?: "USB %04x:%04x".format(device.vendorId, device.productId)
    }

    /** Interfaz Mass Storage Bulk-Only (clase 8, subclase 2 o 6, protocolo 0x50). Se ignora UAS (0x62). */
    private fun findBot(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE &&
                (f.interfaceSubclass == 6 || f.interfaceSubclass == 2) && f.interfaceProtocol == 0x50
            ) return f
        }
        return null
    }

    fun candidates(mgr: UsbManager): List<Candidate> =
        mgr.deviceList.values.mapNotNull { d -> findBot(d)?.let { Candidate(d, it) } }

    /** Abre la conexión (el permiso USB debe estar concedido) y devuelve la unidad SCSI y el transporte. */
    fun open(mgr: UsbManager, c: Candidate): Pair<ScsiDrive, AndroidUsbTransport> {
        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until c.intf.endpointCount) {
            val e = c.intf.getEndpoint(i)
            if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (e.direction == UsbConstants.USB_DIR_IN) inEp = e else outEp = e
        }
        if (inEp == null || outEp == null) throw IOException("La interfaz no tiene endpoints bulk IN/OUT")
        val conn = mgr.openDevice(c.device) ?: throw IOException("No se pudo abrir el dispositivo (¿permiso USB denegado?)")
        if (!conn.claimInterface(c.intf, true)) {
            conn.close()
            throw IOException("No se pudo reclamar la interfaz USB (la usa otro controlador)")
        }
        if (c.intf.alternateSetting != 0) conn.setInterface(c.intf)
        val t = AndroidUsbTransport(conn, c.intf, inEp, outEp)
        return ScsiDrive(BotTransport(t)) to t
    }
}
