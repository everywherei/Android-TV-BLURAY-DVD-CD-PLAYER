package dev.discmount.core.scsi

/**
 * Abstracción mínima de los endpoints bulk de una interfaz USB Mass Storage.
 * En Android se implementa con UsbDeviceConnection; en las pruebas, con un dispositivo falso en memoria.
 */
interface UsbTransport {
    /** Envía datos por el endpoint bulk OUT. Devuelve los bytes enviados o <0 si hay error/stall. */
    fun bulkOut(src: ByteArray, off: Int, len: Int, timeoutMs: Int): Int

    /**
     * Lee del endpoint bulk IN hasta [len] bytes. Termina antes si el dispositivo envía un paquete corto.
     * Devuelve los bytes leídos (puede ser 0) o <0 si hay error/stall/timeout sin datos.
     */
    fun bulkIn(dst: ByteArray, off: Int, len: Int, timeoutMs: Int): Int

    /** CLEAR_FEATURE(ENDPOINT_HALT) sobre el endpoint IN o OUT. */
    fun clearHalt(inEndpoint: Boolean)

    /** Bulk-Only Mass Storage Reset (petición de clase 0xFF). */
    fun massStorageReset()
}
