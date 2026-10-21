package dev.discmount.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/** Visor de imágenes del disco. ←/→ cambian de imagen dentro de la carpeta; Atrás sale. */
class ImageActivity : Activity() {
    private lateinit var image: ImageView
    private lateinit var caption: TextView
    private var paths: List<String> = emptyList()
    private var index = 0
    private var token = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        image = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        caption = TextView(this).apply { textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x99000000.toInt()); setPadding(24, 8, 24, 8) }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(caption, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM or android.view.Gravity.START))
        setContentView(root)
        paths = intent.getStringArrayListExtra(EXTRA_PATHS) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, 0)
        if (paths.isEmpty()) finish() else show()
    }

    private fun show() {
        val path = paths[index]
        val my = ++token
        caption.text = "${index + 1}/${paths.size}  ${path.substringAfterLast('/')}"
        Thread {
            val bmp: Bitmap? = try {
                val fs = DiscSession.fs ?: return@Thread
                fs.open(path).use { f ->
                    if (f.size > 80L shl 20) null else {
                        val data = ByteArray(f.size.toInt())
                        var p = 0
                        while (p < data.size) { val n = f.read(p.toLong(), data, p, data.size - p); if (n <= 0) break; p += n }
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, o)
                        var s = 1
                        while (o.outWidth / s > 4096 || o.outHeight / s > 4096) s *= 2
                        BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = s })
                    }
                }
            } catch (e: Exception) { null }
            runOnUiThread {
                if (my != token) return@runOnUiThread
                if (bmp != null) image.setImageBitmap(bmp) else { image.setImageDrawable(null); caption.text = I18n.t("cant_show", path.substringAfterLast('/')) }
            }
        }.start()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> { index = (index + 1) % paths.size; show() }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { index = (index - 1 + paths.size) % paths.size; show() }
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    companion object {
        const val EXTRA_PATHS = "paths"
        const val EXTRA_INDEX = "index"
    }
}
