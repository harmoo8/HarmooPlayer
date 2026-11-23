package com.reelplayer.app.subs

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

object SubtitleFiles {
    val SUB_EXTENSIONS = setOf("srt", "ass", "ssa", "vtt", "sub", "smi", "txt")

    fun dir(ctx: Context): File = File(ctx.filesDir, "subtitles").apply { mkdirs() }

    /**
     * Saves a downloaded subtitle (plain, .gz or .zip) into app storage and returns the file.
     * For zips containing several files, prefers the one matching the episode tag, then .srt.
     */
    fun saveDownloaded(ctx: Context, bytes: ByteArray, baseName: String, lang: String, episodeTag: String?): File {
        var data = bytes
        var ext = "srt"

        if (isGzip(data)) data = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

        if (isZip(data)) {
            val entries = ArrayList<Pair<String, ByteArray>>()
            ZipInputStream(ByteArrayInputStream(data)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    val n = e.name.substringAfterLast('/')
                    val x = n.substringAfterLast('.', "").lowercase(Locale.ROOT)
                    if (!e.isDirectory && x in SUB_EXTENSIONS && x != "txt") entries += n to zis.readBytes()
                }
            }
            if (entries.isEmpty()) throw IOException("The download didn't contain a subtitle file")
            val tag = episodeTag?.lowercase(Locale.ROOT)
            val pick = entries.firstOrNull { tag != null && it.first.lowercase(Locale.ROOT).replace(" ", "").contains(tag) }
                ?: entries.firstOrNull { it.first.endsWith(".srt", true) }
                ?: entries.first()
            data = pick.second
            ext = pick.first.substringAfterLast('.').lowercase(Locale.ROOT)
        } else {
            ext = sniffExtension(data)
        }

        val safeBase = baseName.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "subtitle" }
        val safeLang = lang.replace(Regex("[^A-Za-z-]"), "").take(8).ifBlank { "xx" }
        var f = File(dir(ctx), "$safeBase.$safeLang.$ext")
        var i = 2
        while (f.exists() && !f.readBytes().contentEquals(data)) f = File(dir(ctx), "$safeBase.$safeLang.$i.$ext").also { i++ }
        f.writeBytes(data)
        return f
    }

    /** Copies a subtitle chosen with the file picker into app storage (libVLC needs a real file path). */
    fun importFromUri(ctx: Context, uri: Uri): File {
        val name = displayName(ctx, uri) ?: "subtitle.srt"
        val ext = name.substringAfterLast('.', "srt").lowercase(Locale.ROOT).let { if (it in SUB_EXTENSIONS) it else "srt" }
        val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120).ifBlank { "subtitle" }
        val out = File(dir(ctx), "$base.$ext")
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IOException("Couldn't read that file")
        val data = if (isGzip(bytes)) GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        if (isZip(data)) return saveDownloaded(ctx, data, base, "xx", null)
        out.writeBytes(data)
        return out
    }

    private fun isZip(b: ByteArray) = b.size > 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte()
    private fun isGzip(b: ByteArray) = b.size > 2 && b[0] == 0x1f.toByte() && b[1] == 0x8b.toByte()

    private fun sniffExtension(b: ByteArray): String {
        val head = String(b, 0, minOf(b.size, 512), Charsets.ISO_8859_1).trimStart('﻿', 'ï', '»', '¿', ' ', '\r', '\n')
        return when {
            head.startsWith("WEBVTT") -> "vtt"
            head.startsWith("[Script Info]", true) -> "ass"
            head.startsWith("{") -> "sub"
            else -> "srt"
        }
    }

    fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            runCatching {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) return c.getString(0)
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')
    }
}

/** The OpenSubtitles "moviehash": file size + 64-bit sums of the first and last 64 KB. */
object MovieHash {
    private const val CHUNK = 65536

    fun compute(ctx: Context, uri: Uri): Pair<String, Long>? = runCatching {
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { stream ->
            val ch = stream.channel
            val size = ch.size()
            if (size < CHUNK * 2) return null
            var hash = size
            val buf = ByteBuffer.allocate(CHUNK).order(ByteOrder.LITTLE_ENDIAN)
            for (start in longArrayOf(0L, size - CHUNK)) {
                buf.clear()
                while (buf.hasRemaining()) {
                    if (ch.read(buf, start + buf.position()) <= 0) break
                }
                buf.flip()
                while (buf.remaining() >= 8) hash += buf.long
            }
            String.format(Locale.ROOT, "%016x", hash) to size
        }
    }.getOrNull()
}
