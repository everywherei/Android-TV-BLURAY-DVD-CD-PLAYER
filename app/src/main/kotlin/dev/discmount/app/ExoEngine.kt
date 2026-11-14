// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

@file:Suppress("UnsafeOptInUsageError")

package dev.discmount.app

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dev.discmount.core.DiscFile

/** DataSource de Media3 que lee directamente de un [DiscFile] (acceso aleatorio, sin pasar por el sistema de ficheros). */
class DiscDataSource(private val file: DiscFile, private val probe: StreamProbe? = null) : BaseDataSource(false) {
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
        probe?.feed(buffer, offset, n)
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
 * Detecta en los primeros MB del flujo qué formatos HDR trae el vídeo, mirando los bytes que el propio reproductor ya lee
 * (sin lecturas extra del disco): capa Dolby Vision (NAL 62) y metadatos dinámicos HDR10+ (SEI ITU-T T.35).
 */
class StreamProbe {
    @Volatile var dolbyVision = false; private set
    @Volatile var hdr10Plus = false; private set
    private var scanned = 0L
    private val tail = ByteArray(5)
    private var tailLen = 0

    private fun scan(b: ByteArray, from: Int, to: Int) {
        var i = from
        while (i < to) {
            val x = b[i].toInt() and 0xFF
            if (x == 0x00 && i + 4 < to && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 &&
                (b[i + 3].toInt() and 0xFF) == 0x7C && b[i + 4].toInt() == 1) dolbyVision = true
            else if (x == 0xB5 && i + 5 < to && b[i + 1].toInt() == 0 && (b[i + 2].toInt() and 0xFF) == 0x3C &&
                b[i + 3].toInt() == 0 && b[i + 4].toInt() == 1 && b[i + 5].toInt() == 4) hdr10Plus = true
            i++
        }
    }

    @Synchronized fun feed(b: ByteArray, off: Int, len: Int) {
        if (scanned >= 24L * 1024 * 1024 || (dolbyVision && hdr10Plus)) return
        scanned += len
        if (tailLen > 0) {
            val head = minOf(6, len)
            val tmp = ByteArray(tailLen + head)
            System.arraycopy(tail, 0, tmp, 0, tailLen)
            System.arraycopy(b, off, tmp, tailLen, head)
            scan(tmp, 0, tmp.size)
        }
        scan(b, off, off + len)
        val keep = minOf(5, len)
        System.arraycopy(b, off + len - keep, tail, 0, keep)
        tailLen = keep
    }
}

/**
 * Motor de reproducción alternativo basado en Media3/ExoPlayer. Descodifica por MediaCodec y entrega a la tele el color
 * del vídeo (HDR10, HDR10+, HLG, Dolby Vision) junto con la superficie, que es lo que activa el modo HDR de la pantalla.
 * Limitación: solo reproduce los formatos de audio que admita el dispositivo (p. ej. AC-3/E-AC-3/DTS), no subtítulos de disco.
 */
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
    private val probe = StreamProbe()

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
                cb.onError("${error.errorCodeName}${error.cause?.message?.let { ": $it" } ?: ""}")
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
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        val factory = DataSource.Factory { DiscDataSource(f, probe) }
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
        if (probe.dolbyVision || v?.sampleMimeType?.contains("dolby", true) == true) out += "Dolby Vision"
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
        val dvActive = mimeDv && !avoidDv
        val t = v?.colorInfo?.colorTransfer
        val format = when {
            dvActive -> "Dolby Vision"
            t == C.COLOR_TRANSFER_ST2084 || mimeDv || probe.hdr10Plus || probe.dolbyVision ->
                if (probe.hdr10Plus && "HDR10+" in display) "HDR10+" else "HDR10"
            t == C.COLOR_TRANSFER_HLG -> "HLG"
            else -> "SDR"
        }
        return HdrState(format, (probe.dolbyVision || mimeDv) && !dvActive)
    }

    /** true si ahora mismo se usa un decodificador Dolby Vision (para poder reintentar sin él si falla). */
    val dolbyVisionActive: Boolean get() = !avoidDv && player.videoFormat?.sampleMimeType?.contains("dolby", true) == true

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
