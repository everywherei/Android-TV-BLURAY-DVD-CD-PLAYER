package dev.discmount.app

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import dev.discmount.core.DiscFile
import java.io.IOException

/** Convierte un [DiscFile] en un descriptor de fichero del sistema con lectura y seek (para libvlc, MediaPlayer…). */
object DiscProxy {
    private val handler by lazy { Handler(HandlerThread("disc-fd").apply { start() }.looper) }

    fun open(ctx: Context, f: DiscFile): ParcelFileDescriptor {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            override fun onGetSize(): Long = f.size
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                try {
                    var done = 0
                    while (done < size) {
                        val n = f.read(offset + done, data, done, size - done)
                        if (n <= 0) break
                        done += n
                    }
                    return done
                } catch (e: IOException) {
                    throw ErrnoException("onRead", OsConstants.EIO)
                }
            }
            override fun onRelease() { f.close() }
        }, handler)
    }
}
