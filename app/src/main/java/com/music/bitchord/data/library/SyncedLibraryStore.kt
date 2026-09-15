package com.music.bitchord.data.library

import android.content.Context
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * The signed-in library as it last came back from a successful sync, kept on
 * disk so the Library shelves — and, once a playlist or album has been opened
 * or fully covered by a background fill, its deliberate track order — still have
 * something to draw when YouTube Music cannot be reached.
 *
 * Everything written through here follows the practices [ListeningStats] set:
 * JSON files written aside and atomically renamed, unknown keys tolerated and
 * defaults encoded, so the cache keeps being readable after the app moves on.
 *
 * One file per listener identity (`accountId:profileId`); the same Google
 * account holds a different library on every one of its channels, and the two
 * must never blur into a single cache.
 */
@Serializable
data class CachedLibrary(
    val likedSongs: List<Song> = emptyList(),
    val librarySongs: List<Song> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    /**
     * Track lists keyed by browse id, kept so a playlist or album page survives
     * offline. Split by kind only so a corrupt id can't hide in the wrong side.
     */
    val playlists: Map<String, List<Song>> = emptyMap(),
    val albums: Map<String, List<Song>> = emptyMap(),
    val savedAt: Long = 0L,
) {
    /** The browse ids whose tracks the listeners saw on the Library tab. */
    val ids: Set<String> get() = playlists.keys + albums.keys
}

/**
 * The disk cache behind the offline Library. Pure [java.io.File] work once
 * [init] has named a directory, which is also what lets the unit tests pin it
 * at a temporary folder instead.
 */
object SyncedLibraryStore {

    private const val DIRECTORY = "synced-library"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Guards [savePage] / [savePlaylist], which both read-modify-write one file. */
    private val writeLock = Mutex()

    private var directory: File? = null

    fun init(context: Context) {
        directory = File(context.filesDir, DIRECTORY)
    }

    /** Test-only seam: point the cache at a throwaway directory. */
    internal fun setDirectoryForTest(dir: File) {
        directory = dir
    }

    /** The cached library for [identity], or null when nothing has been synced. */
    suspend fun cached(identity: String?): CachedLibrary? = withContext(Dispatchers.IO) {
        val file = fileFor(identity) ?: return@withContext null
        if (!file.exists()) return@withContext null
        runCatching { file.readText() }.getOrNull()
            ?.let { runCatching { json.decodeFromString<CachedLibrary>(it) }.getOrNull() }
    }

    /**
     * Replaces the cache's page structure with the one just fetched, and prunes
     * track lists whose browse id no longer appears on any shelf — a playlist
     * dropped online must not keep showing up offline.
     *
     * Not called for an empty page: a partially-failing fetch can look like an
     * empty library, and writing that over a good cache would be the one thing
     * that makes the offline shelves forget what the listener actually had.
     */
    suspend fun savePage(page: LibraryPage, identity: String?) {
        if (page.isEmpty) return
        withContext(Dispatchers.IO) {
            val file = fileFor(identity) ?: return@withContext
            writeLock.withLock {
                val previous = read(file)
                val keptIds = page.shelves.flatMap { it.items }
                    .mapNotNull { it.browseId }
                    .toSet()
                val merged = CachedLibrary(
                    likedSongs = page.likedSongs,
                    librarySongs = page.librarySongs,
                    shelves = page.shelves,
                    playlists = previous.playlists.filterKeys { it in keptIds },
                    albums = previous.albums.filterKeys { it in keptIds },
                    savedAt = System.currentTimeMillis(),
                )
                write(file, merged)
            }
        }
    }

    /**
     * Records a playlist's or album's track order, in the order the page served
     * it. Called behind an opened page and behind a library-wide background fill.
     */
    suspend fun savePlaylist(browseId: String, songs: List<Song>, identity: String?) {
        withContext(Dispatchers.IO) {
            val file = fileFor(identity) ?: return@withContext
            writeLock.withLock {
                val previous = read(file)
                val merged = when {
                    browseId.startsWith("VL") || browseId.startsWith("PL") ->
                        previous.copy(playlists = previous.playlists + (browseId to songs))
                    browseId.startsWith("MPREb") ->
                        previous.copy(albums = previous.albums + (browseId to songs))
                    else -> return@withContext
                }.copy(savedAt = System.currentTimeMillis())
                write(file, merged)
            }
        }
    }

    /** Drops the cache for [identity], e.g. when that identity has no library. */
    suspend fun clear(identity: String?) {
        withContext(Dispatchers.IO) {
            fileFor(identity)?.delete()
        }
    }

    private fun read(file: File): CachedLibrary =
        runCatching { json.decodeFromString<CachedLibrary>(file.readText()) }.getOrDefault(CachedLibrary())

    private fun write(file: File, library: CachedLibrary) {
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(json.encodeToString(CachedLibrary.serializer(), library))
            if (!temporary.renameTo(file)) {
                if (file.delete() && temporary.renameTo(file)) Unit else temporary.delete()
            }
        }
    }

    private fun fileFor(identity: String?): File? {
        val dir = directory ?: return null
        val key = identity ?: return null
        return File(dir, "${sha1(key)}.json")
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}