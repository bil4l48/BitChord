package com.music.bitchord.data.storage

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import com.music.bitchord.data.canvas.CanvasCache
import com.music.bitchord.download.DownloadArtwork
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.AudioCache
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything BitChord keeps on disk that the Storage page can put a number on.
 *
 * Nothing here owns a byte — every figure reads an existing store back ([Downloads]
 * for the tracks, [AudioCache] and [CanvasCache] for the two Media3 caches, Coil's
 * disk cache and [DownloadArtwork] for artwork, the updates folder for a sideloaded
 * APK). The page is
 * deliberately read-only about the stores it must not touch: listening history,
 * offline library metadata, playlists and settings live in prefs and `filesDir`
 * and never appear in these sums.
 */
object StorageSizes {

    /**
     * [Downloads.saved] again — every downloaded track, by the uri the app
     * recorded for it.
     *
     * Counted through the record rather than by scanning the downloads folder,
     * because that is what the app owns: an exported `content://` copy has no
     * file path to scan, and a file that the user removed with a file manager is
     * no longer taking space even though the record still names it (a zero-length
     * or missing file quietly adds nothing). A cue file for a download-as-playlist
     * (`playlist.m3u8`) is the directory that was moved into offline-hls, so its
     * size is the whole package's — the same special case [DownloadStore.delete]
     * recognizes, so "what clearing deletes" and "what clearing says it frees"
     * can't drift apart.
     */
    suspend fun downloadBytes(context: Context): Long = withContext(Dispatchers.IO) {
        var total = 0L
        for (uri in Downloads.saved.value.values) total += lengthOf(context, uri)
        total
    }

    /** The Media3 streaming cache — the audio behind Cached Music and seeks. */
    fun audioCacheBytes(): Long = AudioCache.usedBytes()

    /**
     * Everything artwork this app ever keeps on disk: Coil's disk cache, plus
     * the per-download covers [DownloadArtwork] saves so a downloaded track
     * keeps its artwork offline. Both are cleared by the same Storage-page
     * action, so the number and the clear can't drift apart.
     */
    fun artworkBytes(loader: ImageLoader, context: Context): Long =
        (loader.diskCache?.size ?: 0L) + folderBytes(DownloadArtwork.dir(context))

    /** The Media3 canvas-clip cache. */
    fun canvasBytes(): Long = CanvasCache.usedBytes()

    /** APKs left in the cache by [com.music.bitchord.data.AppUpdateChecker]. */
    fun updatesBytes(context: Context): Long = folderBytes(File(context.cacheDir, "updates"))

    /**
     * Recursive byte count of everything under [file]; 0 for a missing path.
     *
     * Pure, so the arithmetic is testable without Android.
     */
    fun folderBytes(file: File?): Long {
        if (file == null || !file.exists()) return 0L
        if (file.isFile) return file.length()
        return file.listFiles()?.sumOf { folderBytes(it) } ?: 0L
    }

    /** The one figure a total row can print; pure so the sum is testable. */
    data class Totals(
        val downloadsBytes: Long = 0L,
        val cachedAudioBytes: Long = 0L,
        val artworkBytes: Long = 0L,
        val canvasBytes: Long = 0L,
        val updatesBytes: Long = 0L,
    ) {
        val totalBytes: Long
            get() = downloadsBytes + cachedAudioBytes + artworkBytes + canvasBytes + updatesBytes
    }

    /** "512 MB", "2 GB", "2.5 GB" — whichever reads naturally at that size. */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024f
        if (kb < 1024f) return "%.0f KB".format(Locale.ROOT, kb)
        val mb = kb / 1024f
        return when {
            mb < 1024f -> if (mb == mb.toInt().toFloat()) "${mb.toInt()} MB" else "%.1f MB".format(Locale.ROOT, mb)
            else -> {
                val gb = mb / 1024f
                if (gb == gb.toInt().toFloat()) "${gb.toInt()} GB" else "%.1f GB".format(Locale.ROOT, gb)
            }
        }
    }

    private fun lengthOf(context: Context, uri: String): Long = when (uri.startsWith("file://")) {
        true -> runCatching {
            val path = uri.removePrefix("file://")
            val file = File(path)
            val cue = file.name == "playlist.m3u8" && file.parentFile?.parentFile?.name == "offline-hls"
            if (cue) folderBytes(file.parentFile) else file.length()
        }.getOrDefault(0L)
        false -> runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { it.length } ?: 0L
        }.getOrDefault(0L)
    }
}