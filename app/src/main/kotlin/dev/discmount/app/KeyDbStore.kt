package dev.discmount.app

import android.content.Context
import android.net.Uri
import dev.discmount.core.bd.KeyDb
import java.io.BufferedReader
import java.io.File
import java.io.IOException

/**
 * KEYDB.cfg del usuario. Se importa desde el selector de archivos (se copia a almacenamiento privado de la app) o se
 * puede dejar a mano en la carpeta de la app (`/sdcard/Android/data/dev.discmount/files/KEYDB.cfg`, p. ej. con `adb push`).
 */
object KeyDbStore {
    private const val NAME = "KEYDB.cfg"

    private fun imported(ctx: Context) = File(ctx.filesDir, NAME)

    private fun manual(ctx: Context): File? =
        ctx.getExternalFilesDir(null)?.listFiles()?.firstOrNull { it.isFile && it.name.equals(NAME, true) }

    fun files(ctx: Context): List<File> = listOfNotNull(imported(ctx).takeIf { it.isFile }, manual(ctx))

    fun readers(ctx: Context): List<() -> BufferedReader> =
        files(ctx).map { f -> { java.io.BufferedReader(java.io.InputStreamReader(f.inputStream(), Charsets.UTF_8), 1 shl 16) } }

    /** Número de discos, sin leer ningún fichero: usa el valor guardado (válido mientras el fichero no cambie). */
    fun cachedCount(ctx: Context): Int {
        val p = ctx.getSharedPreferences("keydb", Context.MODE_PRIVATE)
        var total = 0
        for (f in files(ctx)) {
            if (p.getString("sig_${f.name}_${f.parent.hashCode()}", null) == sig(f)) total += p.getInt("n_${f.name}_${f.parent.hashCode()}", 0)
            else return -1
        }
        return total
    }

    private fun sig(f: File) = "${f.length()}:${f.lastModified()}"

    /** Cuenta las entradas (lento con ficheros grandes: llamar fuera del hilo de la interfaz) y lo guarda. */
    fun entryCount(ctx: Context): Int {
        val p = ctx.getSharedPreferences("keydb", Context.MODE_PRIVATE)
        var total = 0
        for (f in files(ctx)) {
            val n = runCatching { KeyDb.countEntries(java.io.BufferedReader(java.io.InputStreamReader(f.inputStream(), Charsets.UTF_8), 1 shl 16)) }.getOrDefault(0)
            p.edit().putString("sig_${f.name}_${f.parent.hashCode()}", sig(f)).putInt("n_${f.name}_${f.parent.hashCode()}", n).apply()
            total += n
        }
        return total
    }

    /** Copia el fichero elegido y comprueba que tiene entradas. Devuelve el número de discos del fichero. */
    fun import(ctx: Context, uri: Uri): Int {
        val tmp = File(ctx.filesDir, "$NAME.tmp")
        ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            ?: throw IOException(I18n.t("keydb_cant_open"))
        val n = KeyDb.countEntries(java.io.BufferedReader(java.io.InputStreamReader(tmp.inputStream(), Charsets.UTF_8), 1 shl 16))
        if (n == 0) { tmp.delete(); throw IOException(I18n.t("keydb_invalid")) }
        if (!tmp.renameTo(imported(ctx))) { tmp.copyTo(imported(ctx), true); tmp.delete() }
        ctx.getSharedPreferences("keydb", Context.MODE_PRIVATE).edit()
            .putString("sig_${imported(ctx).name}_${imported(ctx).parent.hashCode()}", sig(imported(ctx)))
            .putInt("n_${imported(ctx).name}_${imported(ctx).parent.hashCode()}", n).apply()
        return n
    }
}
