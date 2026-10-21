package dev.discmount.app

import android.content.Context
import dev.discmount.core.DiscFileSystem
import dev.discmount.core.bd.Aacs
import dev.discmount.core.bd.AacsResult
import dev.discmount.core.scsi.CdText
import dev.discmount.core.scsi.CdToc
import dev.discmount.core.scsi.DiscKind
import dev.discmount.core.scsi.DiscProfile
import dev.discmount.core.scsi.ScsiDrive
import java.util.concurrent.CopyOnWriteArrayList

/** Estado global del disco montado; lo comparten la Activity y el DocumentsProvider. */
object DiscSession {
    @Volatile var fs: DiscFileSystem? = null; private set
    @Volatile var drive: ScsiDrive? = null; private set
    @Volatile var transport: AndroidUsbTransport? = null; private set
    @Volatile var driveName: String = ""; private set
    @Volatile var profile: DiscProfile = DiscProfile.NONE; private set
    @Volatile var kind: DiscKind = DiscKind.UNKNOWN; private set
    @Volatile var toc: CdToc? = null; private set
    @Volatile var cdText: CdText = CdText.EMPTY; private set

    @Volatile private var cssDisc: CssDisc? = null
    @Volatile private var cssTried = false

    /** Abre (una vez por disco) el descifrado CSS; null si no está disponible. */
    @Synchronized
    fun css(): CssDisc? {
        if (!cssTried) {
            cssTried = true
            cssDisc = try { drive?.let { CssDisc.open(it) } } catch (e: Throwable) { null }
        }
        return cssDisc
    }

    @Volatile private var aacsResult: AacsResult? = null

    /** Resuelve (y guarda) las claves AACS del disco montado con el KEYDB.cfg del usuario. */
    @Synchronized
    fun aacs(ctx: Context): AacsResult {
        aacsResult?.let { return it }
        val fs = fs ?: return AacsResult.None
        val r = Aacs.resolve(fs) { KeyDbStore.readers(ctx) }
        aacsResult = r
        return r
    }

    /** Fuerza a recalcular las claves (p. ej. tras importar otro KEYDB.cfg). */
    @Synchronized fun resetAacs() { aacsResult = null }

    val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Synchronized
    fun mount(
        fs: DiscFileSystem?, drive: ScsiDrive, transport: AndroidUsbTransport, driveName: String, profile: DiscProfile,
        kind: DiscKind = DiscKind.UNKNOWN, toc: CdToc? = null, cdText: CdText = CdText.EMPTY,
    ) {
        unmountQuiet()
        this.fs = fs; this.drive = drive; this.transport = transport; this.driveName = driveName; this.profile = profile
        this.kind = kind; this.toc = toc; this.cdText = cdText
        listeners.forEach { it() }
    }

    @Synchronized
    fun unmount() { unmountQuiet(); listeners.forEach { it() } }

    private fun unmountQuiet() {
        runCatching { cssDisc?.close() }
        cssDisc = null; cssTried = false; aacsResult = null
        runCatching { drive?.close() }
        runCatching { transport?.close() }
        fs = null; drive = null; transport = null; profile = DiscProfile.NONE
        kind = DiscKind.UNKNOWN; toc = null; cdText = CdText.EMPTY
    }
}
