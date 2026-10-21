package dev.discmount.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import dev.discmount.core.CachedBlockDevice
import dev.discmount.core.DiscEntry
import dev.discmount.core.DiscFileSystem
import dev.discmount.core.DiscFileSystems
import dev.discmount.core.bd.AacsResult
import dev.discmount.core.scsi.DiscKind
import dev.discmount.core.scsi.CdText
import dev.discmount.core.scsi.CdToc
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Pantalla única, manejable con el mando de la tele (D-pad): estado de la unidad arriba y lista de ficheros debajo.
 * Intro/OK entra en una carpeta; Atrás sube un nivel; sobre un fichero ofrece copiarlo a otro almacenamiento.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var pathView: TextView
    private lateinit var list: ListView
    private lateinit var mountBtn: Button
    private lateinit var ejectBtn: Button
    private lateinit var playBtn: Button
    private lateinit var discView: DiscView
    private lateinit var kindTitle: TextView
    private lateinit var audioBtn: Button
    private lateinit var keydbBtn: Button
    private var playAction: (() -> Unit)? = null
    /** Si es un CD de audio, la lista muestra las pistas en vez de ficheros. */
    private var trackMode = false

    private val io = Executors.newSingleThreadExecutor()
    private val usb by lazy { getSystemService(Context.USB_SERVICE) as UsbManager }

    private var currentPath = "/"
    private var entries: List<DiscEntry> = emptyList()
    private var pendingCopy: DiscEntry? = null

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != ACTION_PERMISSION) return
            if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) mountFirstDrive()
            else setStatus(I18n.t("usb_denied"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32) }
        status = TextView(this).apply { textSize = 18f }
        kindTitle = TextView(this).apply { textSize = 26f; typeface = android.graphics.Typeface.DEFAULT_BOLD; text = I18n.t("no_disc") }
        discView = DiscView(this)
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val headerText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 0, 0, 0) }
        headerText.addView(kindTitle); headerText.addView(status)
        header.addView(discView); header.addView(headerText, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(FlagView(this, I18n.lang).apply { setOnClickListener { LanguagePicker.show(this@MainActivity) { recreate() } } },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.TOP })
        pathView = TextView(this).apply { textSize = 16f; setPadding(0, 16, 0, 8) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.START }
        mountBtn = Button(this).apply { text = I18n.t("mount"); setOnClickListener { mountFirstDrive() } }
        ejectBtn = Button(this).apply { text = I18n.t("eject"); setOnClickListener { eject() } }
        playBtn = Button(this).apply {
            visibility = android.view.View.GONE
            setOnClickListener { playAction?.invoke() }
        }
        audioBtn = Button(this).apply {
            fun refresh() {
                val v = getSharedPreferences("discmount", MODE_PRIVATE).getString("aout", "auto")
                text = if (v == "opensles") I18n.t("audio_opensles") else I18n.t("audio_auto")
            }
            refresh()
            setOnClickListener {
                val prefs = getSharedPreferences("discmount", MODE_PRIVATE)
                prefs.edit().putString("aout", if (prefs.getString("aout", "auto") == "opensles") "auto" else "opensles").apply()
                refresh()
            }
        }
        keydbBtn = Button(this).apply {
            setOnClickListener { pickKeyDb() }
        }
        refreshKeyDbButton()
        buttons.addView(mountBtn); buttons.addView(ejectBtn); buttons.addView(playBtn); buttons.addView(audioBtn); buttons.addView(keydbBtn)
        list = ListView(this).apply {
            isFocusable = true
            setOnItemClickListener { _, _, pos, _ -> onEntryClick(entries[pos]) }
            setOnItemLongClickListener { _, _, pos, _ -> copyEntry(entries[pos]); true }
        }
        root.addView(header); root.addView(buttons); root.addView(pathView)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        val filter = IntentFilter(ACTION_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(permissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(permissionReceiver, filter)

        setStatus(I18n.t("hint"))
        if (DiscSession.kind != DiscKind.UNKNOWN || DiscSession.fs != null) { applyDisc(); } else if (USB_ATTACHED == intent?.action) mountFirstDrive()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) mountFirstDrive()
    }

    override fun onDestroy() {
        unregisterReceiver(permissionReceiver)
        super.onDestroy()
    }

    private fun refreshKeyDbButton() {
        fun show(n: Int) { keydbBtn.text = if (n > 0) I18n.t("keydb_n", n) else if (n == 0) I18n.t("keydb_import") else I18n.t("keydb_change") }
        val cached = KeyDbStore.cachedCount(this)
        show(cached)
        if (cached < 0) Thread { val n = KeyDbStore.entryCount(this); runOnUiThread { show(n) } }.start()
    }

    private fun pickKeyDb() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_KEYDB)
        } catch (e: Exception) {
            setStatus(I18n.t("no_picker", getExternalFilesDir(null)?.path ?: ""))
        }
    }

    private fun setStatus(s: String) = runOnUiThread { status.text = s }

    private fun mountFirstDrive() {
        val c = UsbDrives.candidates(usb).firstOrNull()
        if (c == null) { setStatus(I18n.t("no_drive")); return }
        if (!usb.hasPermission(c.device)) {
            val pi = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_PERMISSION).setPackage(packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            setStatus(I18n.t("req_perm", c.name))
            usb.requestPermission(c.device, pi)
            return
        }
        mountBtn.isEnabled = false
        io.execute {
            try {
                setStatus(I18n.t("opening", c.name))
                val (drive, transport) = UsbDrives.open(usb, c)
                try {
                    val inq = drive.inquiry()
                    if (!inq.isOptical) throw IOException(I18n.t("not_optical", inq.deviceType))
                    val s = drive.waitUntilReady(90_000) { setStatus(I18n.t("preparing", inq.toString())) }
                    if (s != null) throw IOException(if (s.mediumNotPresent) I18n.t("no_medium") else I18n.t("not_ready", s.toString()))
                    runCatching { drive.readCapacity() }
                    drive.preventRemoval(true)
                    val profile = drive.currentProfile()
                    setStatus(I18n.t("reading_disc"))
                    // En un CD se lee el TOC para saber si hay pistas de audio.
                    var toc: CdToc? = null
                    if (profile.isCd || profile == dev.discmount.core.scsi.DiscProfile.UNKNOWN) toc = runCatching { drive.readToc() }.getOrNull()
                    val audio = toc?.hasAudio == true
                    val fs = try {
                        DiscFileSystems.open(CachedBlockDevice(drive))
                    } catch (e: IOException) {
                        if (!audio) throw e
                        null
                    }
                    val kind = DiscKind.detect(profile, drive.blockCount, toc, fs)
                    val text = if (audio) runCatching { drive.readCdText() }.getOrDefault(CdText.EMPTY) else CdText.EMPTY
                    DiscSession.mount(fs, drive, transport, inq.toString(), profile, kind, toc, text)
                    runOnUiThread { applyDisc() }
                } catch (e: Exception) {
                    transport.close()
                    throw e
                }
            } catch (e: Exception) {
                setStatus(I18n.t("error", e.message ?: ""))
            } finally {
                runOnUiThread { mountBtn.isEnabled = true }
            }
        }
    }

    private fun eject() {
        val drive = DiscSession.drive ?: return
        io.execute {
            try {
                drive.preventRemoval(false)
                drive.startStop(load = false, eject = true)
            } catch (_: Exception) {}
            DiscSession.unmount()
            runOnUiThread { entries = emptyList(); list.adapter = null; pathView.text = ""; playBtn.visibility = android.view.View.GONE; discView.kind = null; kindTitle.text = I18n.t("no_disc"); trackMode = false }
            setStatus(I18n.t("ejected"))
        }
    }

    /** Actualiza cabecera (símbolo + tipo de disco), botón de reproducir y lista según el disco montado. */
    private fun applyDisc() {
        val kind = DiscSession.kind
        val fs = DiscSession.fs
        discView.kind = if (kind == DiscKind.UNKNOWN && fs == null) null else kind
        kindTitle.text = "${I18n.kindLabel(kind)} · ${I18n.kindDetail(kind)}"
        val details = buildList {
            add(I18n.profile(DiscSession.profile))
            if (fs != null) { add(fs.type); if (fs.label.isNotBlank()) add("«${fs.label}»") }
            DiscSession.toc?.takeIf { kind == DiscKind.AUDIO_CD }?.let { add(I18n.t("tracks_n", it.audioTracks.size)) }
            add(DiscSession.driveName)
        }
        status.text = details.joinToString(" · ")
        playBtn.visibility = android.view.View.GONE
        playAction = null
        trackMode = false
        when {
            kind == DiscKind.AUDIO_CD -> {
                trackMode = true
                playBtn.text = I18n.t("play_cd")
                playBtn.visibility = android.view.View.VISIBLE
                playAction = { playTracks(0) }
                showTracks()
            }
            fs != null -> {
                io.execute {
                    val aacs = if (kind == DiscKind.BLURAY_VIDEO) DiscSession.aacs(this) else null
                    val dvd = if (kind == DiscKind.DVD_VIDEO) DvdHelper.mainTitle(fs) else null
                    runOnUiThread {
                        if (dvd != null) {
                            playBtn.text = I18n.t("play_dvd")
                            playBtn.visibility = android.view.View.VISIBLE
                            playAction = { startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, dvd)) }
                        }
                        if (kind == DiscKind.BLURAY_VIDEO && fs.stat("/BDMV/PLAYLIST") != null) {
                            playBtn.text = I18n.t("play_bd")
                            playBtn.visibility = android.view.View.VISIBLE
                            playAction = { startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, PlayerActivity.BD_MAIN)) }
                            when (aacs) {
                                is AacsResult.Ok -> status.text = status.text.toString() + " · " + I18n.t("aacs_ready", aacs.keys.title)
                                is AacsResult.Failed -> status.text = status.text.toString() + " · AACS: " + I18n.aacs(aacs)
                                else -> {}
                            }
                        }
                        showDirectory("/")
                    }
                }
            }
        }
    }

    private fun trackTitle(t: dev.discmount.core.scsi.CdTrack): String {
        val text = DiscSession.cdText
        val name = text.titles[t.number]
        val artist = text.performers[t.number]
        val base = "%02d. ".format(t.number) + (if (name != null) (if (artist != null) "$artist – $name" else name) else I18n.t("track", t.number))
        val s = t.seconds.toInt()
        return "$base   (${s / 60}:${"%02d".format(s % 60)})"
    }

    private fun showTracks() {
        val toc = DiscSession.toc ?: return
        val album = DiscSession.cdText.titles[0]
        val tracks = toc.audioTracks
        entries = tracks.map {
            DiscEntry(trackTitle(it), "cdda:${it.number}", false, 44 + it.sectors * 2352)
        }
        currentPath = "/"
        pathView.text = album ?: I18n.t("audio_tracks")
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, entries.map { "♪ " + it.name })
        list.requestFocus()
    }

    private fun playTracks(index: Int) {
        val tracks = entries.filter { it.path.startsWith("cdda:") }
        if (tracks.isEmpty()) return
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, tracks[index].path)
                .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(tracks.map { it.path }))
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(tracks.map { it.name }))
                .putExtra(PlayerActivity.EXTRA_INDEX, index)
        )
    }

    private fun showDirectory(path: String) {
        val fs: DiscFileSystem = DiscSession.fs ?: return
        trackMode = false
        io.execute {
            try {
                val items = fs.list(path).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                runOnUiThread {
                    currentPath = path
                    entries = items
                    pathView.text = path
                    list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, items.map {
                        if (it.isDirectory) "📁 ${it.name}" else "${it.name}   (${human(it.size)})"
                    })
                    list.requestFocus()
                }
            } catch (e: IOException) {
                setStatus(I18n.t("err_reading", path, e.message ?: ""))
            }
        }
    }

    private fun ext(e: DiscEntry) = e.name.substringAfterLast('.', "").lowercase()

    private fun onEntryClick(e: DiscEntry) {
        when {
            e.path.startsWith("cdda:") -> playTracks(entries.filter { it.path.startsWith("cdda:") }.indexOfFirst { it.path == e.path }.coerceAtLeast(0))
            e.isDirectory -> showDirectory(e.path)
            ext(e) in AUDIO_EXT -> {
                // Lista de reproducción con los audios de la carpeta; al acabar una pista sigue la siguiente.
                val songs = entries.filter { !it.isDirectory && ext(it) in AUDIO_EXT }
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra(PlayerActivity.EXTRA_PATH, e.path)
                        .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(songs.map { it.path }))
                        .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(songs.map { it.name }))
                        .putExtra(PlayerActivity.EXTRA_INDEX, songs.indexOfFirst { it.path == e.path }.coerceAtLeast(0))
                )
            }
            ext(e) in VIDEO_EXT ->
                startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_PATH, e.path))
            ext(e) in IMAGE_EXT -> {
                val imgs = entries.filter { !it.isDirectory && ext(it) in IMAGE_EXT }
                startActivity(
                    Intent(this, ImageActivity::class.java)
                        .putStringArrayListExtra(ImageActivity.EXTRA_PATHS, ArrayList(imgs.map { it.path }))
                        .putExtra(ImageActivity.EXTRA_INDEX, imgs.indexOfFirst { it.path == e.path }.coerceAtLeast(0))
                )
            }
            else -> copyEntry(e)
        }
    }

    /** Copia el fichero a otro almacenamiento (pulsación larga sobre cualquier fichero, o clic en tipos desconocidos). */
    private fun copyEntry(e: DiscEntry) {
        if (e.isDirectory || e.path.startsWith("cdda:")) return
        pendingCopy = e
        val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*").putExtra(Intent.EXTRA_TITLE, e.name)
        startActivityForResult(i, REQ_COPY)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_KEYDB) {
            val u = data?.data
            if (resultCode != RESULT_OK || u == null) return
            io.execute {
                try {
                    val n = KeyDbStore.import(this, u)
                    DiscSession.resetAacs()
                    setStatus(I18n.t("keydb_imported", n))
                    runOnUiThread { refreshKeyDbButton(); if (DiscSession.fs != null) applyDisc() }
                } catch (ex: Exception) { setStatus(I18n.t("keydb_err", ex.message ?: "")) }
            }
            return
        }
        val e = pendingCopy
        val uri = data?.data
        if (requestCode != REQ_COPY || resultCode != RESULT_OK || e == null || uri == null) return
        val fs = DiscSession.fs ?: return
        io.execute {
            try {
                fs.open(e.path).use { f ->
                    contentResolver.openOutputStream(uri)!!.use { out ->
                        val buf = ByteArray(1 shl 20)
                        var pos = 0L
                        var lastShown = 0L
                        while (true) {
                            val n = f.read(pos, buf, 0, buf.size)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            pos += n
                            if (pos - lastShown > 32L shl 20) { lastShown = pos; setStatus(I18n.t("copying", e.name, human(pos), human(f.size))) }
                        }
                    }
                }
                setStatus(I18n.t("copied", e.name))
            } catch (ex: Exception) {
                setStatus(I18n.t("copy_err", ex.message ?: ""))
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!trackMode && DiscSession.fs != null && currentPath != "/") {
            val parent = currentPath.substringBeforeLast('/').ifEmpty { "/" }
            showDirectory(parent)
        } else super.onBackPressed()
    }

    private fun human(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1 shl 20 -> "%.1f KB".format(b / 1024.0)
        b < 1L shl 30 -> "%.1f MB".format(b / 1048576.0)
        else -> "%.2f GB".format(b / 1073741824.0)
    }

    companion object {
        const val ACTION_PERMISSION = "dev.discmount.USB_PERMISSION"
        const val USB_ATTACHED = UsbManager.ACTION_USB_DEVICE_ATTACHED
        const val REQ_COPY = 1
        const val REQ_KEYDB = 2
        val VIDEO_EXT = setOf("mkv", "mp4", "m4v", "avi", "mov", "wmv", "ts", "m2ts", "mts", "vob", "mpg", "mpeg", "flv", "webm", "3gp", "ogv", "divx", "asf")
        val AUDIO_EXT = setOf(
            "mp3", "mp2", "flac", "wav", "ogg", "oga", "m4a", "m4b", "aac", "wma", "opus", "ac3", "eac3", "dts", "mka",
            "ape", "wv", "tta", "tak", "mpc", "aif", "aiff", "aifc", "caf", "amr", "au", "snd", "ra", "mid", "midi",
            "dsf", "dff", "dsd", "shn", "alac", "weba", "spx", "mlp", "thd",
        )
        val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic")
    }
}
