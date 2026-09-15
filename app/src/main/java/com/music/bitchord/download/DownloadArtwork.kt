package com.music.bitchord.download

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.DebugLog as Log
import java.io.File
import java.io.FileOutputStream

/**
 * BitChord's own copy of a download's cover art, kept in app-private storage.
 *
 * The cover is fetched once per track at download time so it can be embedded
 * in the audio file by [MediaTagger], and this store keeps the same JPEG to
 * the side so the app's own surfaces — Cached Music, a downloaded playlist's
 * page, the player — keep showing the artwork with no network. It does for an
 * ordinary download exactly what the `cover.*` file already does inside an
 * offline HLS/DASH package (and is reached the same way: the saved record's
 * thumbnail is repointed at a local file, see [offlineThumbnail]).
 *
 * Nothing else had a stable offline handle for that artwork: the recorded
 * thumbnail is a remote URL, Coil's disk cache is a cache (evictable, and
 * "clear artwork" empties it), and the art embedded in the file has no cheap
 * reader that walks it back out for every list row. This store is keyed by the
 * track id the file was embedded under, is written only while the audio bytes
 * are already being saved, and is accounted for and cleared through the
 * existing Storage page — it is the existing pattern, not a second system.
 *
 * Every operation is best-effort: a cover that cannot be fetched, written,
 * read or deleted never fails the download and never leaves a metadata record
 * pointing at a corrupt file — the row simply falls back to its remote
 * thumbnail, exactly as downloads before this feature always did.
 */
object DownloadArtwork {

    private const val TAG = "BitChord"
    private const val MIME = "image/jpeg"

    /** The folder downloaded tracks put their covers in: app-private, so it is
     *  only ever removed through the app. */
    fun dir(context: Context): File = File(context.filesDir, "artwork")

    private fun file(context: Context, videoId: String): File = File(dir(context), "$videoId.jpg")

    /**
     * The thumbnail a saved record should render: the local cover while it
     * still exists, otherwise the recorded (remote) url — the same answer a
     * package's `cover.*` gives, without the package. Pure, so the offline
     * artwork rules are testable without Android.
     */
    fun offlineThumbnail(remote: String?, artworkPath: String?, artworkExists: Boolean): String? =
        if (artworkPath != null && artworkExists) artworkPath else remote

    /** Whether the recorded [path] still has a cover file behind it. */
    fun exists(path: String?): Boolean {
        if (path == null) return false
        val file = localFileOf(path) ?: return false
        return file.isFile
    }

    /**
     * The saved cover for the track that was downloaded as [videoId], as the
     * `file://` uri the app records, or null when none exists. The re-download
     * path asks this before fetching anything: a track saved once never spends
     * a second artwork download on itself.
     */
    fun path(context: Context, videoId: String): String? =
        file(context, videoId).takeIf { it.isFile }?.let(Uri::fromFile)?.toString()

    /**
     * The cover bytes for a [MediaTagger.embed] pass already on disk, or null.
     * Reused exactly as fetched, so a track downloaded twice embeds the same
     * artwork rather than paying for it again.
     */
    internal fun load(context: Context, videoId: String): MediaTagger.Artwork? {
        val cover = file(context, videoId)
        if (!cover.isFile) return null
        val bytes = runCatching { cover.readBytes() }.onFailure { Log.w(TAG, "cover unreadable for $videoId: ${it.message}") }
            .getOrNull() ?: return null
        if (bytes.isEmpty()) return null
        return MediaTagger.Artwork(bytes, MIME)
    }

    /**
     * Store the [bytes] a successful download embedded as its cover, returning
     * the `file://` uri to record (null if it could not be written). Called
     * only on the success side of a download, so a failed download leaves no
     * cover record behind it.
     */
    fun save(context: Context, videoId: String, bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        return runCatching {
            val folder = dir(context)
            if (!folder.exists() && !folder.mkdirs()) {
                Log.w(TAG, "could not make $folder; saving $videoId without an on-disk cover")
                return null
            }
            FileOutputStream(file(context, videoId)).use { it.write(bytes) }
            Uri.fromFile(file(context, videoId)).toString()
        }.onFailure { Log.w(TAG, "could not save cover for $videoId: ${it.message}") }.getOrNull()
    }

    /** Drop the cover recorded at [path] (which may be null or already gone). */
    fun deletePath(path: String?) {
        if (path == null) return
        localFileOf(path)?.let { cover -> runCatching { cover.delete() } }
    }

    /** Drop this track's cover when its download is deleted. */
    fun delete(context: Context, videoId: String) {
        runCatching { file(context, videoId).delete() }
    }

    /** Empty the whole store — the artwork clear on the Storage page. */
    fun deleteAll(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /** The [File] a recorded `file://` (or bare) path names, or null. */
    private fun localFileOf(path: String): File? {
        val local = path.removePrefix("file://")
        if (local.isEmpty()) return null
        return runCatching { File(local) }.getOrNull()
    }
}