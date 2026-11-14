// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

package dev.discmount.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.io.File

/**
 * Explorador de archivos propio, pensado para el mando: empieza en la raíz del sistema ("/").
 * MODE_CFG devuelve el fichero elegido como Uri (para importar KEYDB.cfg); MODE_MEDIA abre vídeos, audios e imágenes.
 */
class FileBrowserActivity : Activity() {
    private enum class Kind { UP, DIR, FILE, GRANT, PICKER, INFO }
    private class Row(val label: String, val kind: Kind, val file: File? = null)

    private lateinit var title: TextView
    private lateinit var list: ListView
    private var mode = MODE_MEDIA
    private var dir = File("/")
    private var rows: List<Row> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        I18n.load(this)
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_MEDIA
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 32, 48, 32) }
        title = TextView(this).apply { textSize = 22f; setPadding(0, 0, 0, 16) }
        list = ListView(this).apply {
            isFocusable = true
            setOnItemClickListener { _, _, pos, _ -> onRow(rows[pos]) }
            adapter = object : BaseAdapter() {
                override fun getCount() = rows.size
                override fun getItem(i: Int) = rows[i]
                override fun getItemId(i: Int) = i.toLong()
                override fun getView(i: Int, v: View?, p: ViewGroup?): View =
                    ((v as? TextView) ?: TextView(this@FileBrowserActivity).apply { textSize = 22f; setPadding(24, 20, 24, 20) }).also {
                        it.text = rows[i].label
                    }
            }
        }
        root.addView(title)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (Build.VERSION.SDK_INT in 23..29 && checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()   // al volver de la pantalla de permisos
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = refresh()

    private fun allFiles(): Boolean = Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun refresh() {
        title.text = (if (mode == MODE_CFG) I18n.t("fb_cfg_title") + "\n" else "") + dir.path
        val out = ArrayList<Row>()
        if (!allFiles()) out += Row(I18n.t("fb_grant"), Kind.GRANT)
        if (mode == MODE_CFG) out += Row(I18n.t("fb_picker"), Kind.PICKER)
        if (dir.path == "/") {
            for ((key, path) in listOf("fb_internal" to "/storage/emulated/0", "fb_volumes" to "/storage"))
                if (File(path).exists()) out += Row(I18n.t(key), Kind.DIR, File(path))
        } else out += Row(I18n.t("fb_up"), Kind.UP, dir.parentFile ?: File("/"))
        val all = try { dir.listFiles()?.toList() } catch (e: Exception) { null } ?: emptyList()
        val shown = all.filter { f ->
            if (f.name.startsWith(".")) false
            else if (f.isDirectory) true
            else mode == MODE_CFG || ext(f) in MainActivity.VIDEO_EXT || ext(f) in MainActivity.AUDIO_EXT || ext(f) in MainActivity.IMAGE_EXT
        }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        for (f in shown) out += Row(if (f.isDirectory) "📁  ${f.name}" else "      ${f.name}", if (f.isDirectory) Kind.DIR else Kind.FILE, f)
        if (shown.isEmpty()) out += Row(I18n.t("fb_empty"), Kind.INFO)
        rows = out
        (list.adapter as BaseAdapter).notifyDataSetChanged()
        list.setSelection(0)
        list.requestFocus()
    }

    private fun ext(f: File) = f.name.substringAfterLast('.', "").lowercase()

    private fun onRow(r: Row) {
        when (r.kind) {
            Kind.UP, Kind.DIR -> { dir = r.file!!; refresh() }
            Kind.GRANT -> grant()
            Kind.PICKER -> try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_PICK)
            } catch (e: Exception) { }
            Kind.FILE -> openFile(r.file!!)
            Kind.INFO -> {}
        }
    }

    private fun grant() {
        if (Build.VERSION.SDK_INT < 30) { requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1); return }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
        }
    }

    private fun openFile(f: File) {
        if (mode == MODE_CFG) {
            setResult(RESULT_OK, Intent().setData(Uri.fromFile(f)))
            finish()
            return
        }
        val siblings = (f.parentFile?.listFiles() ?: arrayOf(f)).filter { it.isFile }.sortedBy { it.name.lowercase() }
        fun paths(set: Set<String>) = siblings.filter { ext(it) in set }
        val e = ext(f)
        when {
            e in MainActivity.IMAGE_EXT -> {
                val imgs = paths(MainActivity.IMAGE_EXT)
                startActivity(Intent(this, ImageActivity::class.java)
                    .putStringArrayListExtra(ImageActivity.EXTRA_PATHS, ArrayList(imgs.map { "file:" + it.path }))
                    .putExtra(ImageActivity.EXTRA_INDEX, imgs.indexOfFirst { it.path == f.path }.coerceAtLeast(0)))
            }
            e in MainActivity.AUDIO_EXT -> {
                val songs = paths(MainActivity.AUDIO_EXT)
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_PATH, "file:" + f.path)
                    .putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST, ArrayList(songs.map { "file:" + it.path }))
                    .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, ArrayList(songs.map { it.name }))
                    .putExtra(PlayerActivity.EXTRA_INDEX, songs.indexOfFirst { it.path == f.path }.coerceAtLeast(0)))
            }
            else -> startActivity(Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_PATH, "file:" + f.path)
                .putStringArrayListExtra(PlayerActivity.EXTRA_TITLES, arrayListOf(f.name)))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data?.data != null) {
            setResult(RESULT_OK, Intent().setData(data.data))
            finish()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            val parent = if (dir.path == "/") null else dir.parentFile
            if (parent != null) { dir = parent; refresh() } else finish()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_CFG = "cfg"
        const val MODE_MEDIA = "media"
        private const val REQ_PICK = 7
    }
}
