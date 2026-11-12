// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

@file:Suppress("UnsafeOptInUsageError")

package dev.discmount.app

import android.content.Context
import android.net.Uri
import android.view.SurfaceView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
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
 * Motor de reproducción alternativo basado en Media3/ExoPlayer. Descodifica por MediaCodec y entrega a la tele el color
 * del vídeo (HDR10, HDR10+, HLG, Dolby Vision) junto con la superficie, que es lo que activa el modo HDR de la pantalla.
 * Limitación: solo reproduce los formatos de audio que admita el dispositivo (p. ej. AC-3/E-AC-3/DTS), no subtítulos de disco.
 */
class ExoEngine(ctx: Context, surface: SurfaceView, private val cb: Callback) {
    interface Callback {
        fun onReady()
        fun onEnded()
        fun onError(message: String)
    }

    private var file: DiscFile? = null
    private var readyNotified = false
    @Volatile var hasPlayableAudio = true; private set
    private var released = false

    private val player: ExoPlayer

    init {
        val renderers = DefaultRenderersFactory(ctx)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_500, 3_000)
            .setTargetBufferBytes(48 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
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
        val factory = DataSource.Factory { DiscDataSource(f) }
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

    /** Resumen: resolución, códec y tipo de HDR que está entregando a la pantalla. */
    fun describe(): String {
        val v = player.videoFormat ?: return "Media3"
        val hdr = when (v.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> if (v.sampleMimeType?.contains("dolby", true) == true) "Dolby Vision" else "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> if (v.sampleMimeType?.contains("dolby", true) == true) "Dolby Vision" else "SDR"
        }
        val codec = v.sampleMimeType?.substringAfter('/')?.uppercase() ?: "?"
        return "Media3 · ${v.width}x${v.height} · $codec · $hdr"
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
