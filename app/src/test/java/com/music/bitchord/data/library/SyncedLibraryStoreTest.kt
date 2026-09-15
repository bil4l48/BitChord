package com.music.bitchord.data.library

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The offline library cache: what is written comes back readable, an empty
 * fetch can never wipe a good copy, track lists keep the playlist/album split,
 * each listener identity stays in its own file, and dropping a playlist from
 * the shelves drops it from the cache too.
 */
class SyncedLibraryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun song(id: String, title: String = "Song $id") =
        Song(videoId = id, title = title, artist = "Artist", thumbnailUrl = null)

    private fun shelf(title: String, vararg ids: String) = HomeShelf(
        title = title,
        items = ids.map { id ->
            ShelfItem(title = id, subtitle = "", thumbnailUrl = null, videoId = null, browseId = id)
        },
    )

    private fun page(liked: List<Song>, shelves: List<HomeShelf>) =
        LibraryPage(likedSongs = liked, librarySongs = emptyList(), shelves = shelves)

    private fun <T> withStore(block: suspend (File) -> T): T {
        val dir = tmp.newFolder("cache")
        SyncedLibraryStore.setDirectoryForTest(dir)
        return runBlocking { block(dir) }
    }

    @Test
    fun `shelves and liked songs survive a save and load`() = withStore {
        SyncedLibraryStore.savePage(
            page(listOf(song("s1"), song("s2")), listOf(shelf("Playlists", "VL1"))),
            "acc:pro",
        )
        val cached = SyncedLibraryStore.cached("acc:pro")
        assertTrue(cached != null)
        assertEquals(listOf("s1", "s2"), cached!!.likedSongs.map { it.videoId })
        assertEquals(listOf("VL1"), cached.shelves.flatMap { it.items }.mapNotNull { it.browseId })
    }

    @Test
    fun `an empty library never wipes a stored one`() = withStore {
        SyncedLibraryStore.savePage(page(listOf(song("s1")), listOf(shelf("Playlists", "VL1"))), "acc:pro")
        SyncedLibraryStore.savePage(page(emptyList(), emptyList()), "acc:pro")
        val cached = SyncedLibraryStore.cached("acc:pro")!!
        assertEquals(listOf("s1"), cached.likedSongs.map { it.videoId })
    }

    @Test
    fun `playlist and album track orders are kept apart`() = withStore {
        SyncedLibraryStore.savePage(
            page(emptyList(), listOf(shelf("Playlists", "VL1"), shelf("Albums", "MPREb9"))),
            "acc:pro",
        )
        SyncedLibraryStore.savePlaylist("VL1", listOf(song("a"), song("b")), "acc:pro")
        SyncedLibraryStore.savePlaylist("MPREb9", listOf(song("x")), "acc:pro")
        val cached = SyncedLibraryStore.cached("acc:pro")!!
        assertEquals(listOf("a", "b"), cached.playlists["VL1"]!!.map { it.videoId })
        assertEquals(listOf("x"), cached.albums["MPREb9"]!!.map { it.videoId })
    }

    @Test
    fun `each identity keeps its own file`() = withStore { dir ->
        SyncedLibraryStore.savePage(page(listOf(song("alice")), emptyList()), "acc:personal")
        SyncedLibraryStore.savePage(page(listOf(song("brand")), emptyList()), "acc:brand")
        assertEquals(listOf("alice"), SyncedLibraryStore.cached("acc:personal")!!.likedSongs.map { it.videoId })
        assertEquals(listOf("brand"), SyncedLibraryStore.cached("acc:brand")!!.likedSongs.map { it.videoId })
        assertEquals(2, dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }!!.size)
    }

    @Test
    fun `track lists pruned when their playlist leaves the shelves`() = withStore {
        SyncedLibraryStore.savePage(
            page(emptyList(), listOf(shelf("Playlists", "VL1", "VL2"))),
            "acc:pro",
        )
        SyncedLibraryStore.savePlaylist("VL1", listOf(song("a")), "acc:pro")
        SyncedLibraryStore.savePlaylist("VL2", listOf(song("b")), "acc:pro")
        SyncedLibraryStore.savePage(page(emptyList(), listOf(shelf("Playlists", "VL1"))), "acc:pro")
        val cached = SyncedLibraryStore.cached("acc:pro")!!
        assertEquals(setOf("VL1"), cached.playlists.keys)
    }

    @Test
    fun `nothing to read when nothing has been synced`() = withStore {
        SyncedLibraryStore.savePage(page(listOf(song("s")), emptyList()), "someone:else")
        assertNull(SyncedLibraryStore.cached("acc:pro"))
    }
}