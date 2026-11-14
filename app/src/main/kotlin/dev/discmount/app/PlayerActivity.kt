// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

package dev.discmount.app

import android.app.Activity
import android.os.Bundle
import android.content.pm.ActivityInfo
import android.os.Build
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
import org.videolan.libvlc.interfaces.IMedia
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
    private lateinit var rootLayout: FrameLayout
    private lateinit var overlay: PlayerOverlay
    private var windowHdr = false
    private var speed = 1f
    private var avoidDv = false          // motor HDR: si el decodificador Dolby Vision falla, se reintenta con la capa base HDR10
    private var modeChecked = false
    private var exo: ExoEngine? = null
    private var useExo = false            // motor activo: false = LibVLC, true = Media3 (HDR)
    private var userChoseEngine = false   // el usuario cambió de motor con MENU: no elegir automáticamente
    private var exoFailed = false
    @Volatile private var engineHint = false  // el disco pide el motor HDR (UHD)
    private var libVLC: LibVLC? = null
    private var player: MediaPlayer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val ui = Handler(Looper.getMainLooper())
    private val hideHud = Runnable { hud.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        // Si la pantalla admite HDR, pide el modo de color HDR para esta ventana (necesario en algunas teles para activar el HDR).
        windowHdr = Build.VERSION.SDK_INT >= 26 && hdrTypes().isNotEmpty()
        // El modo HDR de la ventana ya NO se fuerza aquí: solo se activa en applyColorMode() cuando Media3 confirma que el vídeo es HDR.
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
        rootLayout = root
        overlay = PlayerOverlay(this, root, overlayHost)
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
        engineHint = false
        speed = 1f
        if (::overlay.isInitialized) overlay.hide()
        info.visibility = if (audioMode) View.VISIBLE else View.GONE
        ui.removeCallbacks(ticker)
        if (audioMode) ui.post(ticker)
        updateInfo()
        showHud(I18n.t("opening_title", currentTitle()), if (audioMode) 2000 else 60_000)
        Thread {
            try {
                val file = openFile(path)
                runOnUiThread {
                    if (gen == loadGen && !isDestroyed) {
                        if (engineHint && !userChoseEngine && !exoFailed && !audioMode) useExo = true
                        start(file)
                    } else runCatching { file.close() }
                }
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
        softVideo = false; softTried = false; resumeAt = 0
        loadCurrent()
    }

    private fun openFile(path: String): DiscFile {
        if (path.startsWith("file:")) return LocalFile(java.io.File(path.removePrefix("file:")))
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
        var uhd = false
        engineHint = false
        val keys = when (val r = DiscSession.aacs(this)) {
            is AacsResult.None -> null
            is AacsResult.Ok -> { uhd = r.keys.uhd; engineHint = uhd; r.keys.unitKeys }
            is AacsResult.Failed -> throw IllegalStateException(I18n.aacs(r))
        }
        if (keys != null && fs.stat("/BDSVM") != null) runOnUiThread { showHud(I18n.t("bdplus_warn"), 5000) }
        val parts = clips.map { c ->
            val f = fs.open(c)
            if (keys == null) f else AacsFile(f, keys).also {
                try { it.probe() } catch (e: dev.discmount.core.bd.AacsKeyException) { throw IllegalStateException(I18n.t(if (uhd) "aacs_bad_key_uhd" else "aacs_bad_key")) }
            }
        }
        if (keys != null) runOnUiThread { showHud(I18n.t(if (uhd) "aacs_decrypt_uhd" else "aacs_decrypt"), 3000) }
        return if (parts.size == 1) parts[0] else ConcatFile(parts)
    }

    private var aout = "auto"
    private var diagShown = false

    // Descodificación de vídeo por software (VC-1, MPEG-4 ASP, etc. que el decodificador hardware no muestra).
    private var softVideo = false
    private var softTried = false
    private var resumeAt = 0L
    private var voutCount = 0

    /** Tipos de HDR que admite la pantalla (HDR10, HLG, Dolby Vision, HDR10+). El HDR llega a la tele por el decodificador hardware. */
    @Suppress("DEPRECATION")
    private fun hdrTypes(): List<String> {
        val types = try {
            val d = windowManager.defaultDisplay
            if (Build.VERSION.SDK_INT >= 34) d?.mode?.supportedHdrTypes else d?.hdrCapabilities?.supportedHdrTypes
        } catch (e: Exception) { null }
        return (types?.toList() ?: emptyList<Int>()).mapNotNull { t ->
            when (t) { 1 -> "Dolby Vision"; 2 -> "HDR10"; 3 -> "HLG"; 4 -> "HDR10+"; else -> null }
        }.distinct()
    }

    private fun hdrLine(): String {
        val names = hdrTypes()
        return if (names.isEmpty()) I18n.t("hdr_none") else I18n.t("hdr_display", names.joinToString(" · "))
    }

    private fun checkVideo(last: Boolean) {
        val mp = player ?: return
        if (audioMode || softVideo || softTried) return
        val media = mp.media ?: return
        var hasVideo = false
        var vc1 = false
        var hdrCodec = false
        try {
            for (i in 0 until media.trackCount) {
                val t = media.getTrack(i)
                if (t.type == IMedia.Track.Type.Video) {
                    hasVideo = true
                    val c = "${t.codec} ${t.originalCodec}".lowercase()
                    if (listOf("wvc1", "wmv3", "wmva", "vc1", "vc-1").any { c.contains(it) }) vc1 = true
                    // HEVC / AV1 / VP9 son los códecs del HDR: por software serían inviables (4K) y se perdería el HDR.
                    if (listOf("hevc", "hvc1", "hev1", "h265", "av01", "av1", "vp09", "vp9").any { c.contains(it) }) hdrCodec = true
                }
            }
        } finally { media.release() }
        // VC-1: muchos decodificadores hardware de Android TV no lo soportan (audio sin imagen). Cualquier otro códec
        // de vídeo que siga sin ventana de vídeo tras unos segundos también pasa a software.
        if (vc1 || (last && hasVideo && voutCount == 0 && !hdrCodec)) {
            softTried = true
            softVideo = true
            resumeAt = mp.time
            showHud(I18n.t("soft_decode"), 3000)
            loadCurrent()
        }
    }

    private val exoCb = object : ExoEngine.Callback {
        override fun onReady() = runOnUiThread {
            val e = exo ?: return@runOnUiThread
            applyColorMode(e)
            showHud(currentTitle() + "\n" + hdrLine() + "\n" + e.describe(hdrTypes()) + "\n" + hdrActiveText(e), 7000)
            ui.postDelayed({
                if (exo === e && !e.hasPlayableAudio) showHud(I18n.t("exo_no_audio"), 12_000)
            }, 3500)
        }
        override fun onEnded() = runOnUiThread { skip(+1) }
        override fun onVideoSize(width: Int, height: Int, pixelRatio: Float) = runOnUiThread { fitExoSurface(width, height, pixelRatio); ensureDisplayMode(width, height, exo?.frameRate ?: 0f) }
        override fun onError(message: String) = runOnUiThread {
            // Fallo del decodificador Dolby Vision: primero se reintenta en Media3 con la capa base HDR10 (sigue siendo HDR).
            if (exo?.dolbyVisionActive == true && !avoidDv) {
                avoidDv = true
                resumeAt = curTime()
                releaseExo()
                loadCurrent()
                return@runOnUiThread
            }
            // Si el motor HDR falla, se vuelve automáticamente a LibVLC en la misma posición.
            if (!exoFailed) {
                exoFailed = true
                resumeAt = curTime()
                releaseExo()
                useExo = false
                showHud(I18n.t("exo_error", message), 6000)
                loadCurrent()
            } else showHud(I18n.t("exo_error", message), 15_000)
        }
    }

    private fun releaseExo() { runCatching { exo?.release() }; exo = null }

    private fun releaseVlc() {
        player?.let {
            runCatching { it.setEventListener(null) }
            runCatching { it.stop() }
            runCatching { it.vlcVout.detachViews() }
            runCatching { it.release() }
        }
        runCatching { libVLC?.release() }
        runCatching { pfd?.close() }
        player = null; libVLC = null; pfd = null
    }

    private fun curTime(): Long = exo?.time ?: player?.time ?: 0L
    private fun curLen(): Long = exo?.length ?: player?.length ?: 0L

    /** MENU / INFO / Y: cambia entre LibVLC (todos los formatos de audio y subtítulos) y Media3 (HDR). */
    private fun switchEngine() {
        if (audioMode) return
        overlay.hide()
        resumeAt = curTime()
        userChoseEngine = true
        useExo = !useExo
        releaseExo(); releaseVlc()
        softVideo = false; softTried = false
        showHud(I18n.t(if (useExo) "engine_exo" else "engine_vlc"), 3000)
        loadCurrent()
    }

    private fun startExo(file: DiscFile) {
        releaseVlc()
        releaseExo()
        paused = false
        modeChecked = false
        val e = ExoEngine(this, surface, exoCb, avoidDv)
        exo = e
        e.start(file, resumeAt)
        resumeAt = 0
    }

    private fun start(file: DiscFile) {
        if (useExo) { startExo(file); return }
        releaseExo()
        surface.layoutParams = FrameLayout.LayoutParams(-1, -1)
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
        if (!softVideo) {
            // Decodificación hardware (MediaCodec) directa a la superficie: es el único camino por el que el HDR llega a la tele.
            media.setHWDecoderEnabled(true, true)
        }
        if (softVideo) {
            media.setHWDecoderEnabled(false, false)
            media.addOption(":codec=avcodec,all")
            // Ajustes de rendimiento para descodificar por software (VC-1, etc.) en CPUs de TV:
            val cores = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            media.addOption(":avcodec-threads=$cores")       // todos los núcleos disponibles
            media.addOption(":avcodec-fast")                  // atajos de descodificación (calidad prácticamente igual)
            media.addOption(":avcodec-dr")                    // render directo: evita copias de cada fotograma
            media.addOption(":drop-late-frames")              // si no llega a tiempo, descarta fotogramas en vez de ir a tirones
            media.addOption(":skip-frames")
            media.addOption(":file-caching=3000")             // más búfer: la lectura USB no corta la imagen
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
        }
        voutCount = 0
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
                MediaPlayer.Event.Vout -> voutCount = ev.voutCount
                MediaPlayer.Event.Playing -> {
                    runOnUiThread {
                        fitNow()
                        if (resumeAt > 0 && !audioMode) { player?.time = resumeAt; resumeAt = 0 }
                        if (!softTried && !softVideo && !audioMode) {
                            ui.postDelayed({ checkVideo(false) }, 1500)
                            ui.postDelayed({ checkVideo(true) }, 6000)
                        }
                    }
                    if (!diagShown) {
                        diagShown = true
                        runOnUiThread {
                            if (audioMode) hud.visibility = View.GONE
                            else {
                                showHud(currentTitle() + "\n" + hdrLine(), 3000)
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
        if (player == null && exo == null) return
        val len = curLen()
        val t = (curTime() + deltaMs).coerceIn(0, if (len > 0) len else Long.MAX_VALUE)
        exo?.seekTo(t) ?: run { player?.time = t }
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

    // ------------------------------------------------------------------ controles en pantalla

    private fun isPlayingNow(): Boolean = exo?.isPlaying ?: player?.isPlaying ?: false

    private fun setTime(ms: Long) {
        val e = exo
        if (e != null) e.seekTo(ms) else player?.time = ms
    }

    private fun playNow() { exo?.play() ?: player?.play(); paused = false }
    private fun pauseNow() { exo?.pause() ?: player?.pause(); paused = true }

    private fun togglePlay() {
        if (isPlayingNow() && !paused) pauseNow() else playNow()
        if (!audioMode) { if (paused) overlay.show(false) else overlay.touch() }
    }

    private fun engineBadge(): String {
        val e = exo
        return if (e != null) e.describe(hdrTypes()).removePrefix("Media3 · ") else "VLC" + (if (softVideo) " · SW" else "")
    }

    private fun fitExoSurface(w: Int, h: Int, par: Float) {
        if (w <= 0 || h <= 0 || exo == null) return
        val pw = rootLayout.width
        val ph = rootLayout.height
        if (pw <= 0 || ph <= 0) return
        val ar = w * par / h
        var tw = pw
        var th = (pw / ar).toInt()
        if (th > ph) { th = ph; tw = (ph * ar).toInt() }
        surface.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.CENTER)
    }

    private val overlayHost = object : PlayerOverlay.Host {
        override fun isPlaying() = !paused && isPlayingNow()
        override fun togglePlay() = this@PlayerActivity.togglePlay()
        override fun time() = curTime()
        override fun length() = curLen()
        override fun seekTo(ms: Long) = setTime(ms.coerceAtLeast(0))
        override fun hasPlaylist() = playlist.size > 1
        override fun skip(delta: Int) = this@PlayerActivity.skip(delta)
        override fun title() = currentTitle()
        override fun badge() = engineBadge()
        override fun openAudio() = openAudioMenu()
        override fun openSubs() = openSubsMenu()
        override fun openVideo() = openVideoMenu()
        override fun openSpeed() = openSpeedMenu()
        override fun openInfo() = openInfoPanel()
    }

    private fun openAudioMenu() {
        val items = ArrayList<PlayerOverlay.Item>()
        val e = exo
        if (e != null) {
            e.audioChoices().forEachIndexed { i, c ->
                items += PlayerOverlay.Item(c.label + (if (c.supported) "" else "   " + I18n.t("m_unsupported")), c.selected, c.supported) { e.selectAudio(i) }
            }
        } else {
            val mp = player
            mp?.audioTracks?.toList().orEmpty().forEach { t ->
                items += PlayerOverlay.Item(if (t.id < 0) I18n.t("m_disabled") else t.name, t.id == mp?.audioTrack) { mp?.audioTrack = t.id }
            }
        }
        if (items.isEmpty()) items += PlayerOverlay.Item(I18n.t("no_audio"), false, false) {}
        overlay.showMenu(I18n.t("m_audio_title"), items)
    }

    private fun openSubsMenu() {
        val items = ArrayList<PlayerOverlay.Item>()
        if (exo != null) {
            items += PlayerOverlay.Item(I18n.t("m_subs_vlc_only")) { switchEngine() }
        } else {
            val mp = player
            mp?.spuTracks?.toList().orEmpty().forEach { t ->
                items += PlayerOverlay.Item(if (t.id < 0) I18n.t("m_disabled") else t.name, t.id == mp?.spuTrack) { mp?.spuTrack = t.id }
            }
            if (items.isEmpty()) items += PlayerOverlay.Item(I18n.t("no_subs"), false, false) {}
        }
        overlay.showMenu(I18n.t("m_subs_title"), items)
    }

    /** Texto del HDR realmente activo (formato, y avisos si la tele no lo declara o se ignora Dolby Vision). */
    private fun hdrActiveText(e: ExoEngine): String {
        val display = hdrTypes()
        val st = e.hdrState(display)
        if (st.format == "SDR") return I18n.t("hdr_active_sdr")
        val lines = arrayListOf(I18n.t("hdr_active", st.format))
        val need = if (st.format == "HDR10+") "HDR10" else st.format
        if (display.isEmpty()) lines += I18n.t("hdr_tv_none")
        else if (need !in display) lines += I18n.t("hdr_tv_nosupport", need)
        if (st.dvIgnored) lines += I18n.t("hdr_dv_ignored")
        return lines.joinToString("\n")
    }

    /**
     * Si la tele está emitiendo en una resolución menor que el vídeo (p. ej. interfaz a 1080p y vídeo UHD), pide el modo de
     * pantalla 4K más cercano para que el UHD no se reescale. Los cambios de frecuencia los gestiona Media3 (Surface.setFrameRate).
     */
    @Suppress("DEPRECATION")
    private fun ensureDisplayMode(w: Int, h: Int, fps: Float) {
        if (modeChecked || Build.VERSION.SDK_INT < 23 || w < 1200) return
        modeChecked = true
        try {
            val d = windowManager.defaultDisplay ?: return
            val cur = d.mode
            val modes = d.supportedModes.toList()
            // Resolución: la actual si ya cubre el vídeo; si no, la más pequeña que lo cubra (p. ej. interfaz a 1080p y vídeo UHD).
            val res = if (cur.physicalWidth >= w) cur.physicalWidth to cur.physicalHeight
                else modes.filter { it.physicalWidth >= w && it.physicalHeight >= h }.minByOrNull { it.physicalWidth * it.physicalHeight }
                    ?.let { it.physicalWidth to it.physicalHeight } ?: return
            val sameRes = modes.filter { it.physicalWidth == res.first && it.physicalHeight == res.second }
            // Frecuencia: la que sea múltiplo exacto de los fps del vídeo (23,976 → 23,976/47,952/119,88...), como hacen VLC y Kodi.
            // Sin fps conocidos se conserva la frecuencia actual.
            val best = if (fps > 1f) sameRes.minByOrNull { m -> (1..5).minOf { k -> Math.abs(m.refreshRate - k * fps) + k * 0.01f } }
                else sameRes.minByOrNull { Math.abs(it.refreshRate - cur.refreshRate) }
            if (best == null || best.modeId == cur.modeId) return
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
        } catch (e: Exception) { }
    }

    /** El modo de color HDR de la ventana solo se activa si el vídeo es HDR (en SDR volvería los colores saturados). */
    private fun applyColorMode(e: ExoEngine) {
        if (Build.VERSION.SDK_INT < 26 || hdrTypes().isEmpty()) return
        window.colorMode = if (e.hdrState(hdrTypes()).format == "SDR") ActivityInfo.COLOR_MODE_DEFAULT else ActivityInfo.COLOR_MODE_HDR
    }

    private fun openVideoMenu() {
        val e = exo
        val items = arrayListOf(
            PlayerOverlay.Item(I18n.t("m_engine_exo"), useExo) { if (!useExo) switchEngine() },
            PlayerOverlay.Item(I18n.t("m_engine_vlc"), !useExo) { if (useExo) switchEngine() },
        )
        if (e != null) {
            val info = arrayListOf(PlayerOverlay.Item(I18n.t("m_hdr_avail", e.available().joinToString(" · ")), false, false) {})
            for (l in hdrActiveText(e).split("\n")) info += PlayerOverlay.Item(l, false, false) {}
            items.addAll(0, info)
        }
        overlay.showMenu(I18n.t("m_video_title"), items)
    }

    private fun openSpeedMenu() {
        val items = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).map { v ->
            PlayerOverlay.Item("${v}x", Math.abs(speed - v) < 0.01f) {
                speed = v
                val e = exo
                if (e != null) e.setSpeed(v) else player?.rate = v
            }
        }
        overlay.showMenu(I18n.t("m_speed_title"), items)
    }

    private fun openInfoPanel() = overlay.showInfo(I18n.t("m_info_title")) { infoText() }

    private fun infoText(): String {
        val sb = StringBuilder()
        fun line(key: String, value: String) { if (value.isNotEmpty()) sb.append(I18n.t(key).padEnd(11)).append(value).append('\n') }
        val e = exo
        line("i_engine", if (e != null) "HDR · Media3" else "VLC · " + (if (softVideo) "SW" else "HW"))
        line("i_file", currentTitle())
        if (e != null) {
            line("i_video", e.describe(hdrTypes()).removePrefix("Media3 · "))
            line("i_hdr", hdrActiveText(e).replace("\n", " · "))
        } else {
            var v = ""
            val a = ArrayList<String>()
            var shown = -1
            var lost = -1
            player?.media?.let { m ->
                try {
                    for (i in 0 until m.trackCount) {
                        val t = m.getTrack(i)
                        if (t is IMedia.VideoTrack) {
                            v = "${t.width}x${t.height} · ${t.codec}" +
                                (if (t.frameRateDen > 0) " · %.3f fps".format(t.frameRateNum.toDouble() / t.frameRateDen) else "")
                        } else if (t is IMedia.AudioTrack) {
                            a += "${t.codec} ${t.channels}ch ${t.rate / 1000}kHz"
                        }
                    }
                    val st = m.stats
                    if (st != null) { shown = st.displayedPictures; lost = st.lostPictures }
                } catch (ex: Exception) { } finally { m.release() }
            }
            line("i_video", v)
            line("i_audio", a.joinToString(" | "))
            if (shown >= 0) line("i_frames", "$shown / $lost")
        }
        line("i_display", hdrLine().substringAfter(": "))
        line("i_position", fmt(curTime()) + " / " + fmt(curLen()))
        return sb.toString().trimEnd()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !audioMode && ::overlay.isInitialized) overlay.touch()
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::overlay.isInitialized && overlay.back()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (player == null && exo == null) return super.onKeyDown(keyCode, event)
        // Teclas multimedia del mando: funcionan siempre.
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { playNow(); if (!audioMode) overlay.touch(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pauseNow(); if (!audioMode) overlay.show(false); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seek(30_000); return true }
            KeyEvent.KEYCODE_MEDIA_REWIND -> { seek(-30_000); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_DOWN -> { skip(+1); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP -> {
                if (curTime() > 3000) setTime(0) else skip(-1)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> { finish(); return true }
        }
        if (audioMode) {
            // Música: pantalla simple con la información de la pista.
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> togglePlay()
                KeyEvent.KEYCODE_DPAD_RIGHT -> seek(10_000)
                KeyEvent.KEYCODE_DPAD_LEFT -> seek(-10_000)
                KeyEvent.KEYCODE_DPAD_UP -> skip(-1)
                KeyEvent.KEYCODE_DPAD_DOWN -> skip(+1)
                else -> return super.onKeyDown(keyCode, event)
            }
            return true
        }
        // Vídeo con la barra desplegada: el foco se mueve con las flechas; MENÚ/INFO la cierran.
        if (overlay.visible) {
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) { overlay.hide(); return true }
            return super.onKeyDown(keyCode, event)
        }
        // Vídeo con la barra oculta.
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> togglePlay()
            KeyEvent.KEYCODE_DPAD_LEFT -> { overlay.show(); overlay.seekKey(-1, event.repeatCount) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { overlay.show(); overlay.seekKey(+1, event.repeatCount) }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_PROG_YELLOW -> overlay.show()
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
        releaseExo()
        releaseVlc()
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
