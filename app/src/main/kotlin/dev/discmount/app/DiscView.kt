package dev.discmount.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.view.View
import dev.discmount.core.scsi.DiscKind

/**
 * Dibuja un disco con el distintivo del formato (CD / DVD / Blu-ray). Son diseños propios, inspirados en el aspecto
 * habitual de cada formato; no son los logotipos oficiales (marcas registradas).
 */
class DiscView(ctx: Context) : View(ctx) {

    var kind: DiscKind? = null
        set(v) { field = v; invalidate() }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val d = ctx.resources.displayMetrics.density

    override fun onMeasure(w: Int, h: Int) {
        val s = (128 * d).toInt()
        setMeasuredDimension(s, s)
    }

    private fun accent(k: DiscKind) = when (k) {
        DiscKind.AUDIO_CD -> 0xFFE53935.toInt()
        DiscKind.DATA_CD -> 0xFFFB8C00.toInt()
        DiscKind.DVD_VIDEO -> 0xFF8E24AA.toInt()
        DiscKind.DVD_DATA -> 0xFF5E35B1.toInt()
        DiscKind.BLURAY_VIDEO -> 0xFF1E88E5.toInt()
        DiscKind.BLURAY_DATA -> 0xFF0D47A1.toInt()
        DiscKind.UNKNOWN -> 0xFF78909C.toInt()
    }

    private fun mark(k: DiscKind) = when (k) {
        DiscKind.AUDIO_CD, DiscKind.DATA_CD -> "CD"
        DiscKind.DVD_VIDEO, DiscKind.DVD_DATA -> "DVD"
        DiscKind.BLURAY_VIDEO, DiscKind.BLURAY_DATA -> "Blu-ray"
        DiscKind.UNKNOWN -> "?"
    }

    private fun sub(k: DiscKind) = when (k) {
        DiscKind.AUDIO_CD -> I18n.t("sub_audio")
        DiscKind.DATA_CD, DiscKind.DVD_DATA, DiscKind.BLURAY_DATA -> I18n.t("sub_data")
        DiscKind.DVD_VIDEO, DiscKind.BLURAY_VIDEO -> I18n.t("sub_video")
        DiscKind.UNKNOWN -> ""
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(cx, cy) - 3 * d
        val k = kind
        if (k == null) {
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = 0xFF78909C.toInt()
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(10 * d, 8 * d), 0f)
            c.drawCircle(cx, cy, r, p)
            p.pathEffect = null; p.style = Paint.Style.FILL
            return
        }
        val acc = accent(k)
        // cuerpo plateado con reflejos
        p.style = Paint.Style.FILL
        p.shader = SweepGradient(cx, cy,
            intArrayOf(0xFFF5F5F5.toInt(), 0xFFB0BEC5.toInt(), 0xFFFFFFFF.toInt(), 0xFF90A4AE.toInt(), 0xFFECEFF1.toInt(), 0xFFB0BEC5.toInt(), 0xFFF5F5F5.toInt()), null)
        c.drawCircle(cx, cy, r, p)
        // tinte del formato
        p.shader = RadialGradient(cx, cy, r, intArrayOf(0x00000000, 0x00000000, (acc and 0xFFFFFF) or 0x66000000.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        // borde exterior
        p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFF455A64.toInt()
        c.drawCircle(cx, cy, r, p)
        // anillo de color
        p.strokeWidth = r * 0.07f; p.color = acc
        c.drawCircle(cx, cy, r * 0.66f, p)
        // etiqueta central
        p.style = Paint.Style.FILL; p.color = 0xFF263238.toInt()
        c.drawCircle(cx, cy, r * 0.58f, p)
        // orificio
        p.color = 0xFFCFD8DC.toInt(); c.drawCircle(cx, cy, r * 0.13f, p)
        p.color = 0xFF121212.toInt(); c.drawCircle(cx, cy, r * 0.085f, p)
        // texto: formato (grande) y tipo de contenido (pequeño)
        p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER; p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        p.textSize = r * (if (k.isBluray) 0.22f else 0.30f)
        c.drawText(mark(k), cx, cy + r * 0.42f, p)
        p.textSize = r * 0.13f; p.color = acc
        c.drawText(sub(k), cx, cy - r * 0.26f, p)
        // pequeño brillo
        p.style = Paint.Style.STROKE; p.strokeWidth = 3 * d; p.color = 0x66FFFFFF
        c.drawArc(RectF(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.9f, cy + r * 0.9f), 200f, 40f, false, p)
        p.style = Paint.Style.FILL
    }
}
