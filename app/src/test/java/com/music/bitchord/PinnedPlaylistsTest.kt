package com.music.bitchord

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.ui.strayPinnedPlaylistIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pin Playlists — the id set and the rules around it.
 *
 * The pins live in [AppSettings] as a comma-joined string of playlist browse
 * ids in a preference, which is read-only here: SharedPreferences is Android,
 * this launches out of it. The memory copy ([AppSettings.pinnedPlaylists]) is
 * the contract, and [AppSettings.encodePinnedPlaylists] / [decodePinnedPlaylists]
 * are the exact serialization that survives a restart — so the round trip is
 * what the persistence test checks. Shelf cleanup rides on the database-free
 * [strayPinnedPlaylistIds] rule.
 */
class PinnedPlaylistsTest {

    @Before
    fun freshPins() {
        AppSettings.pinnedPlaylists.value = emptyList()
    }

    // ---- Pin / unpin --------------------------------------------------------

    @Test
    fun `pinning a playlist adds it and reports pinned`() {
        assertTrue(AppSettings.togglePinnedPlaylist("VLmixes01"))
        assertEquals(listOf("VLmixes01"), AppSettings.pinnedPlaylists.value)
    }

    @Test
    fun `pinning the same playlist again unpins it`() {
        AppSettings.togglePinnedPlaylist("VLmixes01")

        assertFalse(AppSettings.togglePinnedPlaylist("VLmixes01"))
        assertEquals(emptyList<String>(), AppSettings.pinnedPlaylists.value)
    }

    @Test
    fun `several pins keep the order they were pinned in`() {
        AppSettings.togglePinnedPlaylist("VLa")
        AppSettings.togglePinnedPlaylist("VLb")
        AppSettings.togglePinnedPlaylist("VLc")

        assertEquals(listOf("VLa", "VLb", "VLc"), AppSettings.pinnedPlaylists.value)
    }

    @Test
    fun `a sixth pin is refused and nothing is evicted`() {
        repeat(5) { AppSettings.togglePinnedPlaylist("VL$it") }

        assertFalse(AppSettings.togglePinnedPlaylist("VLoverflow"))
        assertEquals(5, AppSettings.pinnedPlaylists.value.size)
        assertEquals(listOf("VL0", "VL1", "VL2", "VL3", "VL4"), AppSettings.pinnedPlaylists.value)
    }

    @Test
    fun `unpinPlaylists drops only the named playlists`() {
        AppSettings.togglePinnedPlaylist("VLa")
        AppSettings.togglePinnedPlaylist("VLb")
        AppSettings.togglePinnedPlaylist("VLc")

        AppSettings.unpinPlaylists(listOf("VLb", "VLneverPinned"))

        assertEquals(listOf("VLa", "VLc"), AppSettings.pinnedPlaylists.value)
    }

    @Test
    fun `unpinning an empty set changes nothing`() {
        AppSettings.togglePinnedPlaylist("VLa")

        AppSettings.unpinPlaylists(emptyList())

        assertEquals(listOf("VLa"), AppSettings.pinnedPlaylists.value)
    }

    // ---- Persistence --------------------------------------------------------

    @Test
    fun `pinned order survives a restart through the stored encoding`() {
        AppSettings.togglePinnedPlaylist("VLa")
        AppSettings.togglePinnedPlaylist("VLb")
        val stored = AppSettings.encodePinnedPlaylists(AppSettings.pinnedPlaylists.value)

        // A fresh process starts from the preference string alone.
        AppSettings.pinnedPlaylists.value = emptyList()
        val restart = AppSettings.decodePinnedPlaylists(stored)

        assertEquals(listOf("VLa", "VLb"), restart)
    }

    // ---- Cleanup when a pinned playlist leaves the library ------------------

    private fun card(browseId: String) =
        ShelfItem(title = "Playlist", subtitle = "Channel", thumbnailUrl = null, videoId = null, browseId = browseId)

    private fun pageWith(vararg shelves: HomeShelf): LibraryPage =
        LibraryPage(likedSongs = emptyList(), librarySongs = emptyList(), shelves = shelves.toList())

    @Test
    fun `pins pointing at playlists on a shelf are kept`() {
        val playlists = HomeShelf("Playlists", listOf(card("VLkeep1"), card("VLkeep2")))
        val page = pageWith(playlists)

        assertEquals(emptyList<String>(), strayPinnedPlaylistIds(listOf("VLkeep1", "VLkeep2"), page))
    }

    @Test
    fun `pins naming nothing on the fresh library are reported for cleanup`() {
        val playlists = HomeShelf("Playlists", listOf(card("VLhere")))
        val page = pageWith(playlists)

        assertEquals(listOf("VLgone"), strayPinnedPlaylistIds(listOf("VLhere", "VLgone"), page))
    }

    @Test
    fun `a pin found on any shelf counts as present (not only the playlists row)`() {
        val liked = HomeShelf("Liked Music", listOf(card("VLliked")))
        val page = pageWith(liked)

        assertEquals(emptyList<String>(), strayPinnedPlaylistIds(listOf("VLliked"), page))
    }
}