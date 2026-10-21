package dev.discmount.app

/** Puente hacia la unidad que usa libdvdcss (implementado por [CssDisc]). Lo llama el código nativo. */
interface CssBridge {
    /** Lee [count] sectores desde [lba] en [dst]. Devuelve sectores leídos o -1. */
    fun readBlocks(lba: Long, count: Int, dst: ByteArray): Int

    /** Ejecuta un CDB (12 bytes). [dir]: 0 sin datos, 1 entrada, 2 salida. Devuelve bytes transferidos o -1. */
    fun scsi(cdb: ByteArray, dir: Int, buf: ByteArray, len: Int): Int
}

/** Funciones JNI de libdiscmountcss.so (libdvdcss + puente SCSI). */
object CssNative {
    @Volatile var loaded = false

    fun load() {
        if (!loaded) {
            System.loadLibrary("discmountcss")
            loaded = true
        }
    }

    @JvmStatic external fun nativeOpen(bridge: CssBridge): Long
    @JvmStatic external fun nativeSeek(handle: Long, block: Int, flags: Int): Int
    @JvmStatic external fun nativeRead(handle: Long, dst: ByteArray, blocks: Int, flags: Int): Int
    @JvmStatic external fun nativeError(handle: Long): String
    @JvmStatic external fun nativeIsScrambled(handle: Long): Boolean
    @JvmStatic external fun nativeClose(handle: Long)

    const val SEEK_MPEG = 1
    const val SEEK_KEY = 2
    const val READ_DECRYPT = 1
}
