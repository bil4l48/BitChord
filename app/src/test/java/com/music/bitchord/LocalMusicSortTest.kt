package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.LocalMusicSort
import com.music.bitchord.ui.screens.sortedForArtists
import com.music.bitchord.ui.screens.sortedForLibrary
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The ordering rules behind every sortable collection. Worth pinning because
 * a sort that silently inverts is a library that looks shuffled — no error, no
 * crash, just rows the listener stopped trusting.
 */
class LocalMusicSortTest {

    private fun song(videoId: String, title: String, added: Long? = null) =
        Song(videoId = videoId, title = title, artist = "Artist", thumbnailUrl = null, localDateAddedSeconds = added)

    private val songs = listOf(
        song("b", "Beta"),
        song("a", "Alpha"),
        song("c", "Gamma"),
    )

    @Test
    fun `default order keeps the list exactly as it arrived`() {
        assertEquals(songs, songs.sortedForLibrary(LocalMusicSort.DEFAULT))
    }

    @Test
    fun `title sorts A to Z then Z to A`() {
        assertEquals(
            listOf("Alpha", "Beta", "Gamma"),
            songs.sortedForLibrary(LocalMusicSort.TITLE_ASC).map { it.title },
        )
        assertEquals(
            listOf("Gamma", "Beta", "Alpha"),
            songs.sortedForLibrary(LocalMusicSort.TITLE_DESC).map { it.title },
        )
    }

    @Test
    fun `date added sorts newest first and oldest first`() {
        val dated = listOf(
            song("1", "Oldest", added = 1L),
            song("2", "Newest", added = 3L),
            song("3", "Middle", added = 2L),
        )
        assertEquals(
            listOf("Newest", "Middle", "Oldest"),
            dated.sortedForLibrary(LocalMusicSort.DATE_ADDED).map { it.title },
        )
        assertEquals(
            listOf("Oldest", "Middle", "Newest"),
            dated.sortedForLibrary(LocalMusicSort.DATE_ADDED_OLDEST).map { it.title },
        )
    }

    @Test
    fun `play counts sort most and least played first`() {
        val counts = mapOf("b" to 9, "a" to 0, "c" to 3)
        assertEquals(
            listOf("Beta", "Gamma", "Alpha"),
            songs.sortedForLibrary(LocalMusicSort.PLAYS_MOST, counts).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            songs.sortedForLibrary(LocalMusicSort.PLAYS_LEAST, counts).map { it.title },
        )
    }

    @Test
    fun `tracks that were never played count as zero`() {
        // "a" and "c" are missing from the counts map entirely — a track with no
        // entry must read as zero rather than as a bigger-than-anything number.
        val counts = mapOf("b" to 5)
        assertEquals(
            listOf("Beta", "Alpha", "Gamma"),
            songs.sortedForLibrary(LocalMusicSort.PLAYS_MOST, counts).map { it.title },
        )
        assertEquals(
            listOf("Alpha", "Gamma", "Beta"),
            songs.sortedForLibrary(LocalMusicSort.PLAYS_LEAST, counts).map { it.title },
        )
    }

    @Test
    fun `artist groupings sort by the sum of their tracks' plays`() {
        // Two artist groups: "Band" holds "Alpha", "Beta" and "Gamma" (sum of
        // plays 10) and "Solo" holds just "Delta" (2). The group with the bigger
        // total leads the most-listened pass and trails the least-listened one;
        // the tracks inside a group keep their own order.
        val entries: List<Map.Entry<String, List<Song>>> = linkedMapOf(
            "Band" to listOf(song("a", "Alpha"), song("b", "Beta"), song("c", "Gamma")),
            "Solo" to listOf(song("d", "Delta")),
        ).entries.toList()
        val counts = mapOf("a" to 1, "b" to 5, "c" to 4, "d" to 2)
        assertEquals(
            listOf("Alpha", "Beta", "Gamma", "Delta"),
            entries.sortedForArtists(LocalMusicSort.PLAYS_MOST, counts).flatMap { it.value }.map { it.title },
        )
        assertEquals(
            listOf("Delta", "Alpha", "Beta", "Gamma"),
            entries.sortedForArtists(LocalMusicSort.PLAYS_LEAST, counts).flatMap { it.value }.map { it.title },
        )
    }

    @Test
    fun `artist groupings sort by name in both title directions`() {
        val entries: List<Map.Entry<String, List<Song>>> = linkedMapOf(
            "Zulu" to listOf(song("z", "Zulu Song")),
            "Alpha Band" to listOf(song("a", "Alpha Song")),
        ).entries.toList()
        assertEquals(
            listOf("Alpha Band", "Zulu"),
            entries.sortedForArtists(LocalMusicSort.TITLE_ASC, emptyMap()).map { it.key },
        )
        assertEquals(
            listOf("Zulu", "Alpha Band"),
            entries.sortedForArtists(LocalMusicSort.TITLE_DESC, emptyMap()).map { it.key },
        )
    }
}