// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

package dev.discmount.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Barra de progreso enfocable con el mando: ←/→ avanzan o retroceden, OK pausa. */
class SeekView(ctx: Context) : View(ctx) {
    var pos = 0L
    var len = 0L
    var onKey: ((Int, KeyEvent) -> Boolean)? = null
    private val d = resources.displayMetrics.density
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init { isFocusable = true; isClickable = true }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (30 * d).toInt())

    override fun onFocusChanged(gained: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val pad = 8 * d
        val cy = height / 2f
        val bar = (if (isFocused) 10 else 6) * d
        p.style = Paint.Style.FILL
        p.color = 0x44FFFFFF
        rect.set(pad, cy - bar / 2, width - pad, cy + bar / 2)
        c.drawRoundRect(rect, bar / 2, bar / 2, p)
        val frac = if (len > 0) (pos.toFloat() / len).coerceIn(0f, 1f) else 0f
        val x = pad + (width - 2 * pad) * frac
        p.color = 0xFF42A5F5.toInt()
        rect.set(pad, cy - bar / 2, x, cy + bar / 2)
        c.drawRoundRect(rect, bar / 2, bar / 2, p)
        p.color = 0xFFFFFFFF.toInt()
        c.drawCircle(x, cy, (if (isFocused) 12 else 7) * d, p)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = onKey?.invoke(keyCode, event) ?: super.onKeyDown(keyCode, event)
}

/**
 * Controles en pantalla pensados para el mando: ↓ (o ←/→, MENÚ) despliega la barra inferior con la línea de tiempo y los botones;
 * ←/→ mueven las opciones, OK las abre. Los menús (audio, subtítulos, vídeo/HDR, velocidad, información) salen a la derecha.
 */
class PlayerOverlay(private val act: Activity, private val root: FrameLayout, private val host: Host) {

    interface Host {
        fun isPlaying(): Boolean
        fun togglePlay()
        fun time(): Long
        fun length(): Long
        fun seekTo(ms: Long)
        fun hasPlaylist(): Boolean
        fun skip(delta: Int)
        fun title(): String
        fun badge(): String
        fun openAudio()
        fun openSubs()
        fun openVideo()
        fun openSpeed()
        fun openInfo()
    }

    class Item(val label: String, val selected: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

    private val ui = Handler(Looper.getMainLooper())
    private val d = act.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val panel = LinearLayout(act)
    private val titleView = TextView(act)
    private val badgeView = TextView(act)
    private val elapsed = TextView(act)
    private val total = TextView(act)
    private val seek = SeekView(act)
    private val playBtn: TextView
    private val prevBtn: TextView
    private val nextBtn: TextView
    private val menuBox = LinearLayout(act)
    private val menuTitle = TextView(act)
    private val menuList = LinearLayout(act)
    private val menuScroll = ScrollView(act)
    private var infoView: TextView? = null
    private var infoProvider: (() -> String)? = null
    private var menuOpener: View? = null

    var visible = false; private set
    var menuOpen = false; private set
    private var pending: Long? = null

    private val hideRun = Runnable { hide() }
    private val commitRun = Runnable { pending?.let { host.seekTo(it) }; pending = null }
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            refresh()
            infoProvider?.let { infoView?.text = it() }
            ui.postDelayed(this, 500)
        }
    }

    private fun bg(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius * d }

    private fun pill(text: String, onClick: () -> Unit) = TextView(act).apply {
        this.text = text
        textSize = 18f
        gravity = Gravity.CENTER
        setPadding(dp(18), dp(10), dp(18), dp(10))
        isFocusable = true
        isClickable = true
        setTextColor(0xFFFFFFFF.toInt())
        background = bg(0x33FFFFFF, 22)
        setOnClickListener { onClick(); touch() }
        setOnFocusChangeListener { v, focused ->
            (v as TextView).setTextColor(if (focused) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            v.background = bg(if (focused) 0xFFFFFFFF.toInt() else 0x33FFFFFF, 22)
            v.animate().scaleX(if (focused) 1.08f else 1f).scaleY(if (focused) 1.08f else 1f).setDuration(120).start()
        }
    }

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(56), dp(56), dp(56), dp(28))
        panel.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00000000, 0xE6000000.toInt()))
        panel.visibility = View.GONE

        // Título + distintivo
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titleView.apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt()); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        badgeView.apply { textSize = 14f; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(12), dp(4), dp(12), dp(4)); background = bg(0x55FFFFFF, 12) }
        top.addView(titleView, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(badgeView, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(16) })
        panel.addView(top)

        // Línea de tiempo
        val line = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        for (t in listOf(elapsed, total)) t.apply { textSize = 16f; setTextColor(0xFFDDDDDD.toInt()); typeface = Typeface.MONOSPACE }
        line.addView(elapsed, LinearLayout.LayoutParams(-2, -2))
        line.addView(seek, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        line.addView(total, LinearLayout.LayoutParams(-2, -2))
        panel.addView(line, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        seek.onKey = { code, ev ->
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { seekKey(-1, ev.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { seekKey(+1, ev.repeatCount); true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { host.togglePlay(); touch(); true }
                else -> false
            }
        }

        // Botones
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun add(v: View, gap: Int = 12) = row.addView(v, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(gap) })
        prevBtn = pill("⏮") { host.skip(-1) }
        playBtn = pill("▶") { host.togglePlay() }
        nextBtn = pill("⏭") { host.skip(+1) }
        add(prevBtn)
        add(pill("« 10") { host.seekTo((host.time() - 10_000).coerceAtLeast(0)) })
        add(playBtn)
        add(pill("10 »") { host.seekTo(host.time() + 10_000) })
        add(nextBtn, 36)
        add(pill(I18n.t("ui_audio")) { host.openAudio() })
        add(pill(I18n.t("ui_subtitles")) { host.openSubs() })
        add(pill(I18n.t("ui_video")) { host.openVideo() })
        add(pill(I18n.t("ui_speed")) { host.openSpeed() })
        add(pill(I18n.t("ui_info")) { host.openInfo() })
        panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })

        // Menú lateral
        menuBox.orientation = LinearLayout.VERTICAL
        menuBox.background = bg(0xF2161616.toInt(), 18)
        menuBox.setPadding(dp(20), dp(18), dp(20), dp(18))
        menuBox.visibility = View.GONE
        menuTitle.apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFFFFFFFF.toInt()); setPadding(dp(8), 0, dp(8), dp(10)) }
        menuList.orientation = LinearLayout.VERTICAL
        menuScroll.addView(menuList)
        menuBox.addView(menuTitle)
        menuBox.addView(menuScroll, LinearLayout.LayoutParams(-1, -2))

        root.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.addView(menuBox, FrameLayout.LayoutParams(dp(460), -2, Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = dp(40); bottomMargin = dp(190)
        })
    }

    // ---------------------------------------------------------------- mostrar / ocultar

    fun show(focusSeek: Boolean = true) {
        if (!visible) {
            visible = true
            prevBtn.visibility = if (host.hasPlaylist()) View.VISIBLE else View.GONE
            nextBtn.visibility = if (host.hasPlaylist()) View.VISIBLE else View.GONE
            titleView.text = host.title()
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            panel.animate().alpha(1f).setDuration(150).start()
            ui.removeCallbacks(tick)
            ui.post(tick)
        }
        refresh()
        if (focusSeek && !menuOpen) seek.requestFocus()
        touch()
    }

    fun hide() {
        closeMenu(restoreFocus = false)
        visible = false
        pending = null
        ui.removeCallbacks(hideRun); ui.removeCallbacks(tick); ui.removeCallbacks(commitRun)
        panel.animate().alpha(0f).setDuration(150).withEndAction { if (!visible) panel.visibility = View.GONE }.start()
        panel.clearFocus()
    }

    /** Reinicia el temporizador de ocultación (6 s sin tocar nada). No se oculta si hay pausa o un menú abierto. */
    fun touch() {
        ui.removeCallbacks(hideRun)
        if (visible && !menuOpen && host.isPlaying()) ui.postDelayed(hideRun, 6000)
    }

    /** Devuelve true si consumió «atrás». */
    fun back(): Boolean {
        if (menuOpen) { closeMenu(); return true }
        if (visible) { hide(); return true }
        return false
    }

    fun refresh() {
        val len = host.length()
        val t = pending ?: host.time()
        seek.pos = t; seek.len = len; seek.invalidate()
        elapsed.text = fmt(t)
        total.text = if (len > 0) fmt(len) else "--:--"
        playBtn.text = if (host.isPlaying()) "⏸" else "▶"
        badgeView.text = host.badge()
        badgeView.visibility = if (badgeView.text.isNullOrEmpty()) View.GONE else View.VISIBLE
        titleView.text = host.title()
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /** ←/→ sobre la línea de tiempo: 10 s por pulsación, hasta 2 min si se mantiene; se aplica al soltar (450 ms). */
    fun seekKey(dir: Int, repeat: Int) {
        val len = host.length()
        val base = pending ?: host.time()
        val step = 10_000L * (1 + (repeat / 4).coerceAtMost(11))
        pending = (base + dir * step).coerceIn(0, if (len > 0) len else Long.MAX_VALUE)
        refresh()
        ui.removeCallbacks(commitRun)
        ui.postDelayed(commitRun, 450)
        touch()
    }

    // ---------------------------------------------------------------- menús laterales

    fun showMenu(title: String, items: List<Item>) {
        menuOpener = act.currentFocus ?: menuOpener
        menuTitle.text = title
        menuList.removeAllViews()
        infoView = null; infoProvider = null
        var firstFocus: View? = null
        for (item in items) {
            val row = TextView(act).apply {
                text = (if (item.selected) "●  " else "     ") + item.label
                textSize = 18f
                setPadding(dp(14), dp(12), dp(14), dp(12))
                setTextColor(if (item.enabled) 0xFFFFFFFF.toInt() else 0x66FFFFFF)
                background = bg(0x00000000, 10)
                isFocusable = item.enabled
                isClickable = item.enabled
                if (item.enabled) {
                    setOnClickListener { _ -> closeMenu(); item.action(); touch() }
                    setOnFocusChangeListener { v, f ->
                        (v as TextView).setTextColor(if (f) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
                        v.background = bg(if (f) 0xFFFFFFFF.toInt() else 0x00000000, 10)
                    }
                }
            }
            menuList.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (item.enabled && (firstFocus == null || item.selected)) firstFocus = row
        }
        openMenu(items.size, firstFocus)
    }

    /** Panel de información de reproducción: se actualiza cada medio segundo mientras está abierto. */
    fun showInfo(title: String, provider: () -> String) {
        menuOpener = act.currentFocus ?: menuOpener
        menuTitle.text = title
        menuList.removeAllViews()
        val tv = TextView(act).apply {
            textSize = 15f; typeface = Typeface.MONOSPACE; setTextColor(0xFFEEEEEE.toInt()); setPadding(dp(8), dp(4), dp(8), dp(12))
            text = provider()
        }
        val close = TextView(act).apply {
            text = I18n.t("ui_close"); textSize = 18f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12)); isFocusable = true; isClickable = true
            setTextColor(0xFF000000.toInt()); background = bg(0xFFFFFFFF.toInt(), 10)
            setOnClickListener { closeMenu() }
        }
        menuList.addView(tv)
        menuList.addView(close, LinearLayout.LayoutParams(-1, -2))
        infoView = tv; infoProvider = provider
        openMenu(9, close)
    }

    private fun openMenu(rows: Int, focus: View?) {
        menuOpen = true
        ui.removeCallbacks(hideRun)
        val maxH = (root.height - dp(330)).coerceAtLeast(dp(200))
        menuScroll.layoutParams = LinearLayout.LayoutParams(-1, minOf(rows * dp(50) + dp(8), maxH))
        menuBox.alpha = 0f
        menuBox.visibility = View.VISIBLE
        menuBox.animate().alpha(1f).setDuration(120).start()
        focus?.requestFocus()
    }

    fun closeMenu(restoreFocus: Boolean = true) {
        if (!menuOpen) return
        menuOpen = false
        infoView = null; infoProvider = null
        menuBox.visibility = View.GONE
        if (restoreFocus && visible) (menuOpener?.takeIf { it.isShown } ?: seek).requestFocus()
        touch()
    }
}
