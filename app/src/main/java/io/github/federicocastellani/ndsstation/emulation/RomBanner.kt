package io.github.federicocastellani.ndsstation.emulation

import android.graphics.Bitmap
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile

/** Icon and title embedded in an NDS ROM header (the banner). */
class RomBanner private constructor(val icon: Bitmap, val title: String?) {
    /** Last line of the English title, usually the publisher. */
    val subtitle: String? get() = title?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.size > 1 }?.last()

    /** First line of the English title, usually the game name. */
    val name: String? get() = title?.lines()?.firstOrNull()?.trim()?.ifEmpty { null }

    companion object {
        private const val ICON_SIZE = 32

        fun read(rom: File): RomBanner? = try {
            RandomAccessFile(rom, "r").use { f ->
                val header = ByteArray(0x200)
                f.readFully(header)
                val offset = u32(header, 0x68)
                if (offset == 0L || offset + 0x840 > f.length()) return null
                val banner = ByteArray(0x840)
                f.seek(offset)
                f.readFully(banner)
                decode(banner)
            }
        } catch (_: IOException) {
            null
        }

        /** Reads the banner from a stream (e.g. a ROM picked from storage), skipping as needed. */
        fun read(stream: InputStream): RomBanner? = try {
            stream.use { input ->
                val header = input.readNBytesCompat(0x200) ?: return null
                val offset = u32(header, 0x68)
                if (offset < 0x200) return null
                var toSkip = offset - 0x200
                while (toSkip > 0) {
                    val skipped = input.skip(toSkip)
                    if (skipped <= 0) return null
                    toSkip -= skipped
                }
                decode(input.readNBytesCompat(0x840) ?: return null)
            }
        } catch (_: IOException) {
            null
        }

        // The icon is 4x4 tiles of 8x8 pixels, 4 bits per pixel, with a 16-color BGR555
        // palette whose entry 0 is transparent.
        private fun decode(banner: ByteArray): RomBanner {
            val palette = IntArray(16) { i ->
                val c = (banner[0x220 + i * 2].toInt() and 0xFF) or ((banner[0x221 + i * 2].toInt() and 0xFF) shl 8)
                val r = (c and 0x1F) * 255 / 31
                val g = ((c shr 5) and 0x1F) * 255 / 31
                val b = ((c shr 10) and 0x1F) * 255 / 31
                if (i == 0) 0 else (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            val pixels = IntArray(ICON_SIZE * ICON_SIZE)
            for (tile in 0 until 16) {
                val tx = tile % 4 * 8
                val ty = tile / 4 * 8
                for (i in 0 until 32) {
                    val packed = banner[0x20 + tile * 32 + i].toInt() and 0xFF
                    for (half in 0..1) {
                        val index = if (half == 0) packed and 0x0F else packed shr 4
                        pixels[(ty + i / 4) * ICON_SIZE + tx + (i % 4) * 2 + half] = palette[index]
                    }
                }
            }
            val icon = Bitmap.createBitmap(pixels, ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val title = String(banner, 0x340, 0x100, Charsets.UTF_16LE).substringBefore('\u0000').trim().ifEmpty { null }
            return RomBanner(icon, title)
        }

        private fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

        private fun InputStream.readNBytesCompat(n: Int): ByteArray? {
            val out = ByteArray(n)
            var read = 0
            while (read < n) {
                val r = read(out, read, n - read)
                if (r < 0) return null
                read += r
            }
            return out
        }
    }
}
