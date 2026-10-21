package dev.discmount.core.scsi

import dev.discmount.core.DiscFileSystem

/** Tipo de disco detectado al insertarlo. */
enum class DiscKind(val label: String, val detail: String) {
    AUDIO_CD("Compact Disc", "CD de audio"),
    DATA_CD("Compact Disc", "CD de datos"),
    DVD_VIDEO("DVD", "DVD-Video"),
    DVD_DATA("DVD", "DVD de datos"),
    BLURAY_VIDEO("Blu-ray", "Blu-ray Disc de vídeo"),
    BLURAY_DATA("Blu-ray", "Blu-ray Disc de datos"),
    UNKNOWN("Disco", "Disco desconocido");

    val isBluray get() = this == BLURAY_VIDEO || this == BLURAY_DATA
    val isDvd get() = this == DVD_VIDEO || this == DVD_DATA
    val isCd get() = this == AUDIO_CD || this == DATA_CD

    companion object {
        /**
         * Decide el tipo a partir del perfil MMC, el TOC (si es un CD) y el contenido del sistema de ficheros.
         * Si la unidad no informa del perfil se usa la capacidad (CD ≤ ~800 MB, DVD ≤ ~9 GB, el resto BD).
         */
        fun detect(profile: DiscProfile, blockCount: Long, toc: CdToc?, fs: DiscFileSystem?): DiscKind {
            if (toc != null && toc.hasAudio) return AUDIO_CD
            fun has(p: String) = try { fs?.stat(p) != null } catch (e: Exception) { false }
            val bdmv = has("/BDMV")
            val videoTs = has("/VIDEO_TS")
            return when {
                profile.isBluray -> if (bdmv) BLURAY_VIDEO else BLURAY_DATA
                profile.isDvd -> if (videoTs) DVD_VIDEO else DVD_DATA
                profile.isCd -> DATA_CD
                bdmv -> BLURAY_VIDEO
                videoTs -> DVD_VIDEO
                blockCount <= 450_000 && blockCount > 0 -> DATA_CD
                blockCount <= 4_600_000 && blockCount > 0 -> DVD_DATA
                blockCount > 0 -> BLURAY_DATA
                else -> UNKNOWN
            }
        }
    }
}
