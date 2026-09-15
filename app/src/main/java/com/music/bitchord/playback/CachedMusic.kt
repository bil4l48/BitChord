package com.music.bitchord.playback

import android.content.Context
import android.net.Uri
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every track BitChord has stored on this device, in one place.
 *
 * Two stores hold audio locally, and neither is a copy of the other: a
 * **download** is a file in the BitChord folder the user asked for and keeps
 * on purpose; a **fully cached** track is audio [AudioCache] fetched whole on
 * the way through a play, kept until the evictor or the "clear song cache"
 * button makes room for something else. Both play offline — the download from
 * its file, the cached track from the cache without ever resolving a stream —
 * so this is the "Cached Music" view the Home and Library cards open: the
 * Downloads folder, plus whatever else is on the device that music has stopped
 * on.
 *
 * Nothing here is fetched, renamed, or written: it reads the records and the
 * cache that already exist (requirement: reuse, never duplicate). Deletion goes
 * back through the same systems — [Downloads.delete] removes the file and its
 * record, [AudioCache.discard] drops the cached bytes — so the view never
 * disagrees with the state the rest of the app knows.
 */
object CachedMusic {

    /**
     * All locally stored tracks, downloads first, then fully cached recordings
     * that were never downloaded.
     *
     * The Downloads folder's rows carry their own metadata — a download records
     * the title, artist, album and cover it was saved with, and [Downloads.getDownloadedSongs]
     * verifies each file is still there before it is offered. A cached-only
     * track has no file of its own to read tags off, so its row is drawn from
     * the metadata this app already knew (the listening record is the on-device
     * store that knows what that id is), and a cache entry no metadata anywhere
     * names is skipped rather than invented.
     *
     * Local-only reads, all of them: this must never touch the network, so a
     * fully offline device answers exactly the same list.
     */
    suspend fun songs(context: Context): List<Song> = withContext(Dispatchers.IO) {
        val downloaded = Downloads.getDownloadedSongs(context)
        // The Replay summary is the device-side record of what each videoId
        // is. Cache entries are keyed by id and nothing else, so this is the
        // one map back to a title and an artist.
        val known = ListeningStats.summary(ReplayPeriod.ALL_TIME).songs
            .map { it.song }
            .associateBy { it.videoId }
        merge(downloaded, known, AudioCache.fullyCachedVideoIds())
    }

    /**
     * Downloads first, then fully cached recordings whose [videoId] metadata
     * this device holds and that are not already in the downloads list.
     *
     * Kept pure and out of the coroutine so the listing rules are testable
     * without Android: [knownByVideoId] is whatever named the cache keys, and
     * [cachedVideoIds] is [AudioCache.fullyCachedVideoIds]'s answer. A track
     * on both sides appears once, as the download — it is the copy the user
     * asked to keep.
     */
    internal fun merge(
        downloaded: List<Song>,
        knownByVideoId: Map<String, Song>,
        cachedVideoIds: List<String>,
    ): List<Song> {
        val downloadedIds = downloaded.mapTo(hashSetOf()) { it.videoId }
        val cachedOnly = cachedVideoIds
            .mapNotNull(knownByVideoId::get)
            .filterNot { it.videoId in downloadedIds }
        return downloaded + cachedOnly
    }

    /**
     * Removes [song] from this device through whichever store holds it.
     *
     * A downloaded row goes through [Downloads.delete], which deletes the file
     * and drops its record — the cached copy (if any) is evicted or replaced
     * by the normal cache rules, and deleting a deliberate download on the
     * way past it would delete twice and surprise once. Anything read from the
     * cache instead is thrown away with [AudioCache.discard], which takes every
     * rendition of that recording before eviction does.
     */
    suspend fun delete(context: Context, song: Song): Boolean = withContext(Dispatchers.IO) {
        if (Downloads.delete(context, song.videoId)) {
            true
        } else {
            AudioCache.discard(Uri.parse("bitchord://watch?v=${song.videoId}"))
            true
        }
    }
}