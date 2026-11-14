// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 DiscMount contributors

package dev.discmount.app

import dev.discmount.core.DiscFile
import java.io.File
import java.io.RandomAccessFile

/** Fichero del almacenamiento del dispositivo (USB, memoria interna...) con acceso aleatorio, para reproducirlo como si fuera del disco. */
class LocalFile(file: File) : DiscFile {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    @Synchronized
    override fun read(pos: Long, dst: ByteArray, off: Int, len: Int): Int {
        if (pos >= size) return -1
        raf.seek(pos)
        val n = raf.read(dst, off, len)
        return if (n <= 0) -1 else n
    }

    override fun close() { runCatching { raf.close() } }
}
