// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

@file:Suppress("UnsafeOptInUsageError")

package dev.discmount.app

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dev.discmount.core.DiscFile

/** DataSource de Media3 que lee directamente de un [DiscFile] (acceso aleatorio, sin pasar por el sistema de ficheros). */
class DiscDataSource(private val file: DiscFile) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var pos = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        pos = dataSpec.position
        if (pos > file.size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else file.size - pos
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val n = file.read(pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
    }
}

/**
 * Mira los primeros MB del flujo (paquetes TS/M2TS, por PID) para saber qué HDR trae el vídeo:
 *  - HDR10+ : SEI ITU-T T.35 de Samsung/SMPTE ST 2094-40.
 *  - Dolby Vision de capa única (perfil 8): NAL 62 (RPU) en el mismo PID que el vídeo base → se puede entregar al decodificador DV.
 *  - Dolby Vision de doble capa (perfil 7, UHD BD): el RPU va en otro PID (capa de mejora) → solo se reproduce la capa base HDR10.
 */
class StreamProbe {
    @Volatile var hdr10Plus = false; private set
    private val spsPids = HashSet<Int>()
    private val rpuPids = HashSet<Int>()
    private var stride = 0
    private var syncMod = 0
    /** 192 = M2TS de Blu-ray (4 bytes de cabecera + paquete TS), 188 = TS normal, 0 = no detectado. */
    val packetSize: Int get() = stride

    private val base: Int get() = spsPids.minOrNull() ?: -1
    val dolbyVision: Boolean get() = base >= 0 && base in rpuPids
    val dolbyVisionDual: Boolean get() = !dolbyVision && rpuPids.any { it != base }

    @Synchronized fun feed(pos: Long, b: ByteArray, off: Int, len: Int) {
        if (stride == 0) {
            if (len >= 400 && b[off + 4].toInt() == 0x47 && b[off + 196].toInt() == 0x47) { stride = 192; syncMod = 4 }
            else if (len >= 400 && b[off].toInt() == 0x47 && b[off + 188].toInt() == 0x47) { stride = 188; syncMod = 0 }
            else return
            if (pos % stride != 0L) { stride = 0; return }
        }
        var i = off + (((syncMod - (pos % stride).toInt()) % stride + stride) % stride)
        while (i + 188 <= off + len) {
            if (b[i].toInt() == 0x47) {
                val pid = ((b[i + 1].toInt() and 0x1F) shl 8) or (b[i + 2].toInt() and 0xFF)
                val afc = (b[i + 3].toInt() shr 4) and 3
                if (afc == 1 || afc == 3) {
                    var p = i + 4
                    if (afc == 3) p += 1 + (b[i + 4].toInt() and 0xFF)
                    scan(b, p, i + 188, pid)
                }
            }
            i += stride
        }
    }

    private fun scan(b: ByteArray, from: Int, to: Int, pid: Int) {
        var i = from
        while (i + 5 < to) {
            val x = b[i].toInt() and 0xFF
            if (x == 0x00 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && b[i + 4].toInt() == 1) {
                when (b[i + 3].toInt() and 0xFF) { 0x42 -> spsPids.add(pid); 0x7C -> rpuPids.add(pid) }
            } else if (x == 0xB5 && i + 5 < to && b[i + 1].toInt() == 0 && (b[i + 2].toInt() and 0xFF) == 0x3C &&
                b[i + 3].toInt() == 0 && b[i + 4].toInt() == 1 && b[i + 5].toInt() == 4) hdr10Plus = true
            i++
        }
    }
}

/** Para flujos de disco con Dolby Vision de capa única, etiqueta el vídeo HEVC como Dolby Vision (perfil 8) para que se use el decodificador DV. */
private class DvExtractor(private val d: Extractor, private val probe: StreamProbe) : Extractor by d {
    override fun init(output: ExtractorOutput) {
        d.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput {
                val t = output.track(id, type)
                return if (type == C.TRACK_TYPE_VIDEO) DvTrack(t, probe) else t
            }
            override fun endTracks() = output.endTracks()
            override fun seekMap(seekMap: SeekMap) = output.seekMap(seekMap)
        })
    }
}

private class DvTrack(private val t: TrackOutput, private val probe: StreamProbe) : TrackOutput {
    override fun format(format: Format) {
        if (probe.dolbyVision && format.sampleMimeType == MimeTypes.VIDEO_H265) {
            val fps = format.frameRate
            val level = if (format.width >= 3000) when { fps <= 24.1f -> "06"; fps <= 30.1f -> "07"; fps <= 48.1f -> "08"; else -> "09" }
                        else if (format.width >= 1900) when { fps <= 24.1f -> "03"; fps <= 30.1f -> "04"; else -> "05" } else "01"
            t.format(format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.08.$level").build())
        } else t.format(format)
    }
    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
        t.sampleData(input, length, allowEndOfInput, sampleDataPart)
    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) = t.sampleData(data, length, sampleDataPart)
    override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
        t.sampleMetadata(timeUs, flags, size, offset, cryptoData)
}

/**
 * Blu-ray guarda el vídeo en M2TS: paquetes de 192 bytes (4 bytes TP_extra_header + 188 de TS).
 * El extractor TS de Media3 solo reconoce paquetes de 188, por eso fallaba con ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED.
 * Esta envoltura quita los 4 bytes de cada paquete y presenta el fichero como un TS de 188 con acceso aleatorio.
 */
class M2tsAs188(private val inner: DiscFile) : DiscFile {
    override val size: Long = inner.size / 192 * 188
    private val raw = ByteArray(192 * 64)

    private fun readRaw(pos: Long, len: Int): Int {
        var done = 0
        while (done < len) {
            val n = inner.read(pos + done, raw, done, len - done)
            if (n <= 0) break
            done += n
        }
        return done
    }

    @Synchronized
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size || len <= 0) return -1
        val want = minOf(len.toLong(), size - pos).toInt()
        var done = 0
        while (done < want) {
            val p = pos + done
            val pkt = p / 188
            val inPkt = (p % 188).toInt()
            val packets = minOf(64, (inPkt + (want - done) + 187) / 188)
            val got = readRaw(pkt * 192, packets * 192) / 192
            if (got <= 0) break
            var skip = inPkt
            for (k in 0 until got) {
                val n = minOf(188 - skip, want - done)
                System.arraycopy(raw, k * 192 + 4 + skip, dst, off + done, n)
                done += n
                skip = 0
                if (done >= want) break
            }
            if (got < packets) break
        }
        return if (done == 0) -1 else done
    }

    override fun close() { inner.close() }
}

/** Formato HDR que realmente se está entregando a la pantalla. [dvIgnored]: el vídeo lleva Dolby Vision pero se reproduce su capa base HDR10. */
class HdrState(val format: String, val dvIgnored: Boolean)

class ExoEngine(private val ctx: Context, surface: SurfaceView, private val cb: Callback, private val avoidDv: Boolean = false) {
    interface Callback {
        fun onReady()
        fun onEnded()
        fun onError(message: String)
        fun onVideoSize(width: Int, height: Int, pixelRatio: Float)
    }

    private var file: DiscFile? = null
    private var readyNotified = false
    @Volatile var hasPlayableAudio = true; private set
    private var released = false

    private val player: ExoPlayer
    @Volatile private var probe = StreamProbe()
    private val main = Handler(Looper.getMainLooper())
    private val dvDecoder: Boolean = runCatching { MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false).isNotEmpty() }.getOrDefault(false)

    init {
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            // Cola asíncrona de MediaCodec: el decodificador no espera al hilo de reproducción (menos fotogramas perdidos en SoC modestos).
            .forceEnableMediaCodecAsynchronousQueueing()
            // Si el decodificador Dolby Vision falla, se reintenta sin él y se usa la capa base HDR10 del decodificador HEVC.
            .setMediaCodecSelector(MediaCodecSelector { mime, secure, tunneling ->
                if (avoidDv && mime == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
            })
        // Búfer adaptado a la memoria del equipo: un UHD de ~100 Mbps necesita colchón, pero un TV con poca RAM no puede reservar mucho.
        val memMb = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.memoryClass ?: 192
        val bufMb = (memMb / 4).coerceIn(24, 96)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 45_000, 1_500, 3_000)
            .setTargetBufferBytes(bufMb * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()
        player = ExoPlayer.Builder(ctx, renderers).setLoadControl(load).build()
        player.setAudioAttributes(AudioAttributes.DEFAULT, true)
        player.setVideoSurfaceView(surface)
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (!readyNotified) { readyNotified = true; cb.onReady() }
                    Player.STATE_ENDED -> cb.onEnded()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                // Diagnóstico completo: cadena de causas + dónde se lanzó la excepción (clase.método:línea).
                val sb = StringBuilder(error.errorCodeName)
                var t: Throwable? = error.cause
                var depth = 0
                while (t != null && depth < 4) {
                    sb.append('\n').append(t.javaClass.simpleName).append(t.message?.let { ": $it" } ?: "")
                    t.stackTrace.take(if (depth == 0) 4 else 1).forEach {
                        sb.append("\n   @ ").append(it.className.substringAfterLast('.')).append('.').append(it.methodName).append(':').append(it.lineNumber)
                    }
                    t = t.cause; depth++
                }
                android.util.Log.e("DiscMountExo", "Playback error", error)
                cb.onError(sb.toString())
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                cb.onVideoSize(videoSize.width, videoSize.height, videoSize.pixelWidthHeightRatio)
            }
            override fun onTracksChanged(tracks: Tracks) {
                val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                hasPlayableAudio = audio.any { it.isSupported }
            }
        })
    }

    fun start(f: DiscFile, startMs: Long) {
        file = f
        // Antes de preparar el reproductor se leen los primeros MB una vez para detectar HDR10+ / Dolby Vision (hilo aparte).
        Thread {
            val p = StreamProbe()
            try {
                val buf = ByteArray(1 shl 20)
                var pos = 0L
                while (pos < 8L * 1024 * 1024 && pos < f.size) {
                    val n = f.read(pos, buf, 0, buf.size)
                    if (n <= 0) break
                    p.feed(pos, buf, 0, n)
                    pos += n
                }
            } catch (e: Exception) { }
            main.post { if (!released) begin(f, startMs, p) }
        }.start()
    }

    private fun begin(f: DiscFile, startMs: Long, p: StreamProbe) {
        probe = p
        val base = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        val extractors = androidx.media3.extractor.ExtractorsFactory {
            base.createExtractors().map { if (!avoidDv && dvDecoder) DvExtractor(it, p) as Extractor else it }.toTypedArray()
        }
        // M2TS (192 B/paquete) -> TS (188 B/paquete) para que lo lea el extractor de Media3.
        val src: DiscFile = if (p.packetSize == 192) M2tsAs188(f) else f
        val factory = DataSource.Factory { DiscDataSource(src) }
        val source = ProgressiveMediaSource.Factory(factory, extractors)
            .createMediaSource(MediaItem.fromUri(Uri.parse("disc://video")))
        player.setMediaSource(source, if (startMs > 0) startMs else C.TIME_UNSET)
        player.prepare()
        player.playWhenReady = true
    }

    val isPlaying: Boolean get() = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    val time: Long get() = player.currentPosition
    val length: Long get() = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L

    fun play() { player.playWhenReady = true }
    fun pause() { player.playWhenReady = false }
    fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    private fun label(f: Format): String {
        val lang = f.language?.takeIf { it != "und" }
        val codec = f.sampleMimeType?.substringAfter('/')?.uppercase()
        val ch = f.channelCount.takeIf { it > 0 }?.let { "${it}ch" }
        return listOfNotNull(lang, codec, ch).joinToString(" ").ifEmpty { f.id ?: "?" }
    }

    class AudioChoice(val label: String, val supported: Boolean, val selected: Boolean)

    private fun audioGroups() = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    fun audioChoices(): List<AudioChoice> = audioGroups().map { AudioChoice(label(it.getTrackFormat(0)), it.isSupported, it.isSelected) }

    fun selectAudio(index: Int) {
        val g = audioGroups().getOrNull(index) ?: return
        if (!g.isSupported) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
    }

    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    /** Pasa a la siguiente pista de audio reproducible. Devuelve su descripción, o null si no hay. */
    fun cycleAudio(): String? {
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
        if (groups.isEmpty()) return null
        val cur = groups.indexOfFirst { it.isSelected }
        val next = groups[(cur + 1) % groups.size]
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, 0)).build()
        return label(next.getTrackFormat(0))
    }

    /** Formatos HDR presentes en el vídeo (o ["SDR"]). Se completa en los primeros segundos de reproducción. */
    fun available(): List<String> {
        val v = player.videoFormat
        val out = ArrayList<String>()
        if (probe.dolbyVision || probe.dolbyVisionDual || v?.sampleMimeType?.contains("dolby", true) == true) out += "Dolby Vision"
        if (probe.hdr10Plus) out += "HDR10+"
        when (v?.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> out += "HDR10"
            C.COLOR_TRANSFER_HLG -> out += "HLG"
        }
        if (out.isEmpty() && (probe.dolbyVision || probe.hdr10Plus)) out += "HDR10"
        return if (out.isEmpty()) listOf("SDR") else out
    }

    /** Formato HDR en uso. [display] son los tipos que declara la pantalla (para saber si HDR10+ puede aplicarse). */
    fun hdrState(display: List<String>): HdrState {
        val v = player.videoFormat
        val mimeDv = v?.sampleMimeType?.contains("dolby", true) == true
        val dvActive = mimeDv && !avoidDv && dvDecoder
        val t = v?.colorInfo?.colorTransfer
        val format = when {
            dvActive -> "Dolby Vision"
            t == C.COLOR_TRANSFER_ST2084 || mimeDv || probe.hdr10Plus || probe.dolbyVision ->
                if (probe.hdr10Plus && "HDR10+" in display) "HDR10+" else "HDR10"
            t == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
        return HdrState(format, (probe.dolbyVision || probe.dolbyVisionDual || mimeDv) && !dvActive)
    }

    /** true si ahora mismo se usa un decodificador Dolby Vision (para poder reintentar sin él si falla). */
    val dolbyVisionActive: Boolean get() = !avoidDv && dvDecoder && player.videoFormat?.sampleMimeType?.contains("dolby", true) == true

    val frameRate: Float get() = player.videoFormat?.frameRate?.takeIf { it > 0f } ?: 0f

    /** Resumen: resolución, códec y tipo de HDR que está entregando a la pantalla. */
    fun describe(display: List<String> = emptyList()): String {
        val v = player.videoFormat ?: return "Media3"
        val codec = v.sampleMimeType?.substringAfter('/')?.uppercase() ?: "?"
        val fps = if (v.frameRate > 0f) " · %.3f fps".format(v.frameRate) else ""
        return "Media3 · ${v.width}x${v.height} · $codec$fps · ${hdrState(display).format}"
    }

    fun release() {
        if (released) return
        released = true
        runCatching { player.stop() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
        runCatching { file?.close() }
        file = null
    }
}
