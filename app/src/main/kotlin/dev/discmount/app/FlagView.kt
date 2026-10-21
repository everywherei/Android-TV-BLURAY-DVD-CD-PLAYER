package dev.discmount.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/** Bandera pequeña dibujada con Canvas (sin imágenes). Se resalta cuando tiene el foco del mando. */
class FlagView(ctx: Context, var lang: Lang) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    init { isFocusable = true; isClickable = true; minimumWidth = (42 * d).toInt(); minimumHeight = (30 * d).toInt() }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension((42 * d).toInt(), (30 * d).toInt())

    private fun fill(c: Canvas, color: Int, l: Float, t: Float, r: Float, b: Float) { p.style = Paint.Style.FILL; p.color = color; c.drawRect(l, t, r, b, p) }

    override fun onDraw(c: Canvas) {
        val pad = 3 * d
        val f = RectF(pad, pad, width - pad, height - pad)
        if (isFocused) { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0xFF42A5F5.toInt(); c.drawRoundRect(RectF(f).apply { inset(-1.5f * d, -1.5f * d) }, 4 * d, 4 * d, p) }
        c.save()
        clip.reset(); clip.addRoundRect(f, 2 * d, 2 * d, Path.Direction.CW); c.clipPath(clip)
        c.translate(f.left, f.top)
        drawFlag(c, f.width(), f.height())
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0x55000000; c.drawRoundRect(f, 2 * d, 2 * d, p)
    }

    private fun hStripes(c: Canvas, w: Float, h: Float, vararg col: Int) { val s = h / col.size; col.forEachIndexed { i, k -> fill(c, k, 0f, i * s, w, (i + 1) * s + 1) } }
    private fun vStripes(c: Canvas, w: Float, h: Float, vararg col: Int) { val s = w / col.size; col.forEachIndexed { i, k -> fill(c, k, i * s, 0f, (i + 1) * s + 1, h) } }
    private fun circle(c: Canvas, x: Float, y: Float, r: Float, color: Int) { p.style = Paint.Style.FILL; p.color = color; c.drawCircle(x, y, r, p) }

    private fun drawFlag(c: Canvas, w: Float, h: Float) {
        when (lang) {
            Lang.EN -> {
                fill(c, 0xFF012169.toInt(), 0f, 0f, w, h)
                p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
                p.color = 0xFFFFFFFF.toInt(); p.strokeWidth = h * 0.2f
                c.drawLine(0f, 0f, w, h, p); c.drawLine(0f, h, w, 0f, p)
                p.color = 0xFFC8102E.toInt(); p.strokeWidth = h * 0.07f
                c.drawLine(0f, 0f, w, h, p); c.drawLine(0f, h, w, 0f, p)
                fill(c, 0xFFFFFFFF.toInt(), w / 2 - h * 0.17f, 0f, w / 2 + h * 0.17f, h)
                fill(c, 0xFFFFFFFF.toInt(), 0f, h / 2 - h * 0.17f, w, h / 2 + h * 0.17f)
                fill(c, 0xFFC8102E.toInt(), w / 2 - h * 0.1f, 0f, w / 2 + h * 0.1f, h)
                fill(c, 0xFFC8102E.toInt(), 0f, h / 2 - h * 0.1f, w, h / 2 + h * 0.1f)
            }
            Lang.FR -> vStripes(c, w, h, 0xFF0055A4.toInt(), 0xFFFFFFFF.toInt(), 0xFFEF4135.toInt())
            Lang.DE -> hStripes(c, w, h, 0xFF000000.toInt(), 0xFFDD0000.toInt(), 0xFFFFCE00.toInt())
            Lang.JA -> { fill(c, 0xFFFFFFFF.toInt(), 0f, 0f, w, h); circle(c, w / 2, h / 2, h * 0.3f, 0xFFBC002D.toInt()) }
            Lang.ES -> {
                fill(c, 0xFFAA151B.toInt(), 0f, 0f, w, h)
                fill(c, 0xFFF1BF00.toInt(), 0f, h * 0.25f, w, h * 0.75f)
            }
            Lang.IT -> vStripes(c, w, h, 0xFF009246.toInt(), 0xFFFFFFFF.toInt(), 0xFFCE2B37.toInt())
            Lang.NL -> hStripes(c, w, h, 0xFFAE1C28.toInt(), 0xFFFFFFFF.toInt(), 0xFF21468B.toInt())
            Lang.PT -> {
                fill(c, 0xFF006600.toInt(), 0f, 0f, w * 0.4f, h); fill(c, 0xFFFF0000.toInt(), w * 0.4f, 0f, w, h)
                circle(c, w * 0.4f, h / 2, h * 0.24f, 0xFFFFE000.toInt()); circle(c, w * 0.4f, h / 2, h * 0.14f, 0xFFFF0000.toInt())
                circle(c, w * 0.4f, h / 2, h * 0.07f, 0xFFFFFFFF.toInt())
            }
            Lang.KO -> {
                fill(c, 0xFFFFFFFF.toInt(), 0f, 0f, w, h)
                val r = h * 0.26f; val cx = w / 2; val cy = h / 2
                val oval = RectF(cx - r, cy - r, cx + r, cy + r)
                p.style = Paint.Style.FILL
                p.color = 0xFFCD2E3A.toInt(); c.drawArc(oval, 180f, 180f, true, p)
                p.color = 0xFF0047A0.toInt(); c.drawArc(oval, 0f, 180f, true, p)
                circle(c, cx - r / 2, cy, r / 2, 0xFFCD2E3A.toInt()); circle(c, cx + r / 2, cy, r / 2, 0xFF0047A0.toInt())
                fun tri(angle: Float, pat: String) {
                    c.save(); c.rotate(angle, cx, cy)
                    val bw = h * 0.22f; val bh = h * 0.035f; val gap = h * 0.03f
                    val top = cy - h * 0.43f
                    pat.forEachIndexed { i, ch ->
                        val y = top + i * (bh + gap)
                        if (ch == '-') fill(c, 0xFF000000.toInt(), cx - bw / 2, y, cx + bw / 2, y + bh)
                        else { fill(c, 0xFF000000.toInt(), cx - bw / 2, y, cx - bw * 0.1f, y + bh); fill(c, 0xFF000000.toInt(), cx + bw * 0.1f, y, cx + bw / 2, y + bh) }
                    }
                    c.restore()
                }
                val a = Math.toDegrees(Math.atan2(h.toDouble(), w.toDouble())).toFloat()
                tri(-a - 0f, "---"); tri(a + 0f, "=-="); tri(180f - a, "-=-"); tri(180f + a, "===")
            }
            Lang.RU -> hStripes(c, w, h, 0xFFFFFFFF.toInt(), 0xFF0039A6.toInt(), 0xFFD52B1E.toInt())
            Lang.CA -> {
                fill(c, 0xFFFCDD09.toInt(), 0f, 0f, w, h)
                val s = h / 9
                for (i in 1..7 step 2) fill(c, 0xFFDA121A.toInt(), 0f, i * s, w, (i + 1) * s)
            }
        }
    }
}

/** Botón de bandera arriba a la derecha + diálogo para elegir idioma. */
object LanguagePicker {
    fun show(a: Activity, onChosen: () -> Unit) {
        val list = ListView(a)
        val dlg = AlertDialog.Builder(a).setTitle(I18n.t("language")).setView(list).create()
        list.adapter = object : BaseAdapter() {
            override fun getCount() = Lang.entries.size
            override fun getItem(i: Int) = Lang.entries[i]
            override fun getItemId(i: Int) = i.toLong()
            override fun getView(i: Int, v: View?, parent: ViewGroup): View {
                val l = Lang.entries[i]
                val d = a.resources.displayMetrics.density
                return LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * d).toInt(), (4 * d).toInt(), (12 * d).toInt(), (4 * d).toInt())
                    addView(FlagView(a, l).apply { isFocusable = false; isClickable = false })
                    addView(TextView(a).apply {
                        text = l.native; textSize = 20f; setPadding((16 * d).toInt(), 0, 0, 0)
                        if (l == I18n.lang) typeface = android.graphics.Typeface.DEFAULT_BOLD
                    })
                }
            }
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            val l = Lang.entries[pos]
            dlg.dismiss()
            if (l != I18n.lang) { I18n.set(a, l); onChosen() }
        }
        dlg.show()
        list.setSelection(I18n.lang.ordinal)
    }
}
