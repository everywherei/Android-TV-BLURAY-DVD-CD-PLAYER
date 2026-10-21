package dev.discmount.app

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.discmount.core.ConcatFile
import dev.discmount.core.DiscFile
import dev.discmount.core.bd.AacsFile
import dev.discmount.core.bd.AacsResult
import dev.discmount.core.bd.BdPlaylists
import dev.discmount.core.scsi.CdTrackFile
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Reproductor (libvlc) para vídeo y audio del disco (incluye pistas de CD de audio, «cdda:N»). Mando: OK = pausa,
 * ←/→ = ±10 s, ↑ = pista de audio / anterior (en música), ↓ = subtítulos / siguiente (en música),
 * ⏮/⏭ = pista anterior/siguiente, Atrás = salir. Los VOB de un DVD (VTS_xx_n.VOB) se enlazan para reproducir el título completo.
 */
class PlayerActivity : Activity() {

    private lateinit var surface: SurfaceView
    private lateinit var hud: TextView
    private lateinit var info: TextView
    private var playlist: List<String> = emptyList()
    private var titles: List<String> = emptyList()
    private var index = 0
    private var audioMode = false
    private var paused = false
    @Volatile private var cdFile: CdTrackFile? = null
    private var loadGen = 0
    private val ticker = object : Runnable {
        override fun run() { updateInfo(); ui.postDelayed(this, 500) }
    }
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        surface = SurfaceView(this)
        hud = TextView(this).apply {
            textSize = 22f; setTextColor(0xFFFFFFFF.toInt()); setBackgroundColor(0x99000000.toInt()); setPadding(32, 16, 32, 16)
        }
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        info = TextView(this).apply {
            textSize = 32f; setTextColor(0xFFFFFFFF.toInt()); gravity = Gravity.CENTER; visibility = View.GONE; setPadding(64, 32, 64, 32)
        }
        root.addView(info, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        root.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))
        setContentView(root)

        val path = intent.getStringExtra(EXTRA_PATH) ?: return finish()
        playlist = intent.getStringArrayListExtra(EXTRA_PLAYLIST)?.takeIf { it.isNotEmpty() } ?: listOf(path)
        titles = intent.getStringArrayListExtra(EXTRA_TITLES) ?: emptyList()
        index = intent.getIntExtra(EXTRA_INDEX, playlist.indexOf(path).coerceAtLeast(0)).coerceIn(0, playlist.size - 1)
        loadCurrent()
    }

    private fun isAudioPath(path: String) =
        path.startsWith("cdda:") || path.substringAfterLast('.', "").lowercase() in MainActivity.AUDIO_EXT

    private fun currentTitle(): String =
        titles.getOrNull(index) ?: if (playlist[index] == BD_MAIN) I18n.t("bd_title") else playlist[index].substringAfterLast('/')

    /** Abre la pista/fichero actual de la lista en segundo plano y lo reproduce. */
    private fun loadCurrent() {
        val path = playlist[index]
        val gen = ++loadGen
        cdFile = null
        audioMode = isAudioPath(path)
        diagShown = false
        paused = false
        info.visibility = if (audioMode) View.VISIBLE else View.GONE
        ui.removeCallbacks(ticker)
        if (audioMode) ui.post(ticker)
        updateInfo()
        showHud(I18n.t("opening_title", currentTitle()), if (audioMode) 2000 else 60_000)
        Thread {
            try {
                val file = openFile(path)
                runOnUiThread { if (gen == loadGen && !isDestroyed) start(file) else runCatching { file.close() } }
            } catch (e: Exception) {
                runOnUiThread { showHud(I18n.t("error", e.message ?: ""), 10_000) }
            }
        }.start()
    }

    private fun updateInfo() {
        if (!audioMode) return
        val mp = player
        val t = mp?.time ?: 0L
        val len = mp?.length ?: 0L
        val state = if (paused) "⏸  " else "♪  "
        info.text = state + currentTitle() + "\n\n" + fmtShort(t) + (if (len > 0) " / " + fmtShort(len) else "") +
            (cdFile?.takeIf { audioMode && it.unreadable > 0 }?.let { "\n\n" + I18n.t("unreadable", it.unreadable, it.lastError) } ?: "") +
            (if (playlist.size > 1) "\n\n" + I18n.t("track_of", index + 1, playlist.size) else "")
    }

    private fun skip(delta: Int) {
        val n = index + delta
        if (n !in playlist.indices) { if (delta > 0) finish(); return }
        index = n
        loadCurrent()
    }

    private fun openFile(path: String): DiscFile {
        if (path.startsWith("cdda:")) {
            val n = path.substringAfter(':').toInt()
            val drive = DiscSession.drive ?: throw IllegalStateException(I18n.t("no_mounted"))
            val track = DiscSession.toc?.tracks?.firstOrNull { it.number == n && it.isAudio } ?: throw IllegalStateException(I18n.t("track_nf", n))
            val f = CdTrackFile(drive, track)
            f.probe()   // si la unidad no puede leer CD-DA, el error real aparece en pantalla
            cdFile = f
            return f
        }
        val fs = DiscSession.fs ?: throw IllegalStateException(I18n.t("no_mounted"))
        if (path == BD_MAIN || path.endsWith(".m2ts", true)) return openBluray(fs, path)
        val m = Regex("(?i)^(.*/VTS_\\d\\d)_([1-9])\\.VOB$").find(path)
        val isVob = path.endsWith(".vob", true)
        val paths = if (m != null) {
            val prefix = m.groupValues[1]
            (1..9).map { "${prefix}_$it.VOB" }.filter { fs.stat(it) != null }.ifEmpty { listOf(path) }
        } else listOf(path)

        // Los VOB de un DVD comercial están cifrados con CSS: se leen descifrándolos con libdvdcss.
        val css = if (isVob) DiscSession.css() else null
        if (css != null) {
            val parts = paths.map { p ->
                val ext = fs.locate(p)
                if (ext.isEmpty()) fs.open(p) else CssPartFile(css, ext, fs.stat(p)!!.size)
            }
            runOnUiThread { if (css.isScrambled) showHud(I18n.t("css_decrypt"), 3000) }
            return if (parts.size == 1) parts[0] else ConcatFile(parts)
        }
        val parts = paths.map { fs.open(it) }
        return if (parts.size == 1) parts[0] else ConcatFile(parts)
    }

    /** Blu-ray: «bd:main» = lista de reproducción principal; un .m2ts suelto también. Descifra con AACS si el disco lo usa. */
    private fun openBluray(fs: dev.discmount.core.DiscFileSystem, path: String): DiscFile {
        val clips = if (path == BD_MAIN) {
            val pl = BdPlaylists.main(fs) ?: throw IllegalStateException(I18n.t("no_playlist"))
            pl.clips.map { BdPlaylists.clipPath(it) }
        } else listOf(path)
        val keys = when (val r = DiscSession.aacs(this)) {
            is AacsResult.None -> null
            is AacsResult.Ok -> r.keys.unitKeys
            is AacsResult.Failed -> throw IllegalStateException(I18n.aacs(r))
        }
        if (keys != null && fs.stat("/BDSVM") != null) runOnUiThread { showHud(I18n.t("bdplus_warn"), 5000) }
        val parts = clips.map { c ->
            val f = fs.open(c)
            if (keys == null) f else AacsFile(f, keys).also { it.probe() }
        }
        if (keys != null) runOnUiThread { showHud(I18n.t("aacs_decrypt"), 3000) }
        return if (parts.size == 1) parts[0] else ConcatFile(parts)
    }

    private var aout = "auto"
    private var diagShown = false

    private fun start(file: DiscFile) {
        var mp = player
        if (mp == null) {
            aout = getSharedPreferences("discmount", MODE_PRIVATE).getString("aout", "auto") ?: "auto"
            val opts = arrayListOf("--no-drop-late-frames", "--no-skip-frames")
            if (aout == "opensles") opts.add("--aout=opensles")   // salida estéreo clásica, por si el multicanal falla
            val vlc = LibVLC(this, opts)
            libVLC = vlc
            mp = MediaPlayer(vlc)
            player = mp
            mp.vlcVout.setVideoView(surface)
            mp.vlcVout.attachViews()
            setupPlayer(mp)
        } else {
            runCatching { mp.stop() }
        }
        val vlc = libVLC!!
        val old = pfd
        val fd = DiscProxy.open(this, file)
        pfd = fd
        val media = Media(vlc, fd.fileDescriptor)
        mp.media = media
        media.release()
        mp.play()
        runCatching { old?.close() }
        surface.post { fitNow() }
    }

    private fun fitNow() {
        val mp = player ?: return
        val w = surface.width
        val h = surface.height
        if (w > 0 && h > 0) {
            mp.vlcVout.setWindowSize(w, h)
            mp.setAspectRatio(null)
            mp.scale = 0f
        }
    }

    private fun setupPlayer(mp: MediaPlayer) {
        // Ajusta el vídeo a toda la pantalla: libvlc necesita conocer el tamaño de la ventana.
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = fitNow()
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
        })
        surface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fitNow() }

        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> {
                    runOnUiThread { fitNow() }
                    if (!diagShown) {
                        diagShown = true
                        runOnUiThread {
                            if (audioMode) hud.visibility = View.GONE
                            else {
                                showHud(currentTitle(), 3000)
                                ui.postDelayed({ audioCheck() }, 3000)
                            }
                        }
                    }
                }
                MediaPlayer.Event.EndReached -> runOnUiThread { skip(+1) }
                MediaPlayer.Event.EncounteredError -> runOnUiThread {
                    showHud(
                        if (audioMode) I18n.t("cant_play_audio", currentTitle())
                        else I18n.t("cant_play"), 15_000)
                }
            }
        }
    }

    /** Comprueba las pistas de audio, activa la primera si ninguna lo está y muestra un resumen en pantalla. */
    private fun audioCheck() {
        val mp = player ?: return
        val tracks = mp.audioTracks?.filter { it.id >= 0 } ?: emptyList()
        if (mp.audioTrack < 0 && tracks.isNotEmpty()) mp.audioTrack = tracks.first().id
        val salida = if (aout == "opensles") "OpenSL ES" else I18n.t("out_auto")
        showHud(
            if (tracks.isEmpty()) I18n.t("no_audio_detected", salida)
            else I18n.t("audio_summary", salida, tracks.joinToString(" | ") { it.name }, mp.audioTrack),
            8000,
        )
    }

    private fun showHud(text: String, ms: Long) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHud)
        ui.postDelayed(hideHud, ms)
    }

    private fun seek(deltaMs: Long) {
        val mp = player ?: return
        val len = mp.length
        val t = (mp.time + deltaMs).coerceIn(0, if (len > 0) len else Long.MAX_VALUE)
        mp.time = t
        showHud(fmt(t) + " / " + fmt(len), 2000)
    }

    private fun fmtShort(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    private fun cycleAudio() {
        val mp = player ?: return
        val tracks = mp.audioTracks?.filter { it.id >= 0 } ?: return
        if (tracks.isEmpty()) return showHud(I18n.t("no_audio"), 2000)
        val i = tracks.indexOfFirst { it.id == mp.audioTrack }
        val next = tracks[(i + 1) % tracks.size]
        mp.audioTrack = next.id
        showHud(I18n.t("audio_label", next.name), 3000)
    }

    private fun cycleSubs() {
        val mp = player ?: return
        val tracks = mp.spuTracks ?: return
        if (tracks.isEmpty()) return showHud(I18n.t("no_subs"), 2000)
        val i = tracks.indexOfFirst { it.id == mp.spuTrack }
        val next = tracks[(i + 1) % tracks.size]
        mp.spuTrack = next.id
        showHud(I18n.t("subs", if (next.id < 0) I18n.t("subs_off") else next.name), 3000)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val mp = player ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (mp.isPlaying) { mp.pause(); paused = true; if (!audioMode) showHud(I18n.t("pause", fmt(mp.time)), 60_000) } else { mp.play(); paused = false; hud.visibility = View.GONE }
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { mp.play(); paused = false; hud.visibility = View.GONE }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { mp.pause(); paused = true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(10_000)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-10_000)
            KeyEvent.KEYCODE_DPAD_UP -> if (audioMode) skip(-1) else cycleAudio()
            KeyEvent.KEYCODE_DPAD_DOWN -> if (audioMode) skip(+1) else cycleSubs()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> skip(+1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                // Si la pista lleva más de 3 s suena desde el principio; si no, pasa a la anterior.
                if (mp.time > 3000) mp.time = 0 else skip(-1)
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> finish()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        loadGen++
        ui.removeCallbacksAndMessages(null)
        player?.let {
            runCatching { it.stop() }
            runCatching { it.vlcVout.detachViews() }
            runCatching { it.release() }
        }
        runCatching { libVLC?.release() }
        runCatching { pfd?.close() }
        player = null; libVLC = null; pfd = null
        super.onDestroy()
    }

    companion object {
        const val BD_MAIN = "bd:main"
        const val EXTRA_PATH = "path"
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_TITLES = "titles"
        const val EXTRA_INDEX = "index"
    }
}
