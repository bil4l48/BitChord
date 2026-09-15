package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.CachedMusic
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How the Cached Music listing is put together: downloads first, then fully
 * cached recordings the app can still name, with nothing counted twice.
 *
 * The pure half of [CachedMusic.songs] — the two local stores are read off
 * Android (Downloads folder, [AudioCache]), so those reads are not this test's
 * subject; what is is that a track on both sides comes out once, that a cache
 * key without a name comes out not at all, and that downloads lead.
 */
class CachedMusicTest {

    private fun song(videoId: String, title: String = "Song $videoId") =
        Song(videoId = videoId, title = title, artist = "Artist", thumbnailUrl = null)

    // ---- The list itself ----------------------------------------------------

    @Test
    fun `downloads stay first and keep their running order`() {
        val one = song("aaabbbccc11", "First")
        val two = song("aaabbbccc22", "Second")
        val byId = mapOf("zzzyyyxxx99" to song("zzzyyyxxx99"))

        val listed = CachedMusic.merge(
            downloaded = listOf(one, two),
            knownByVideoId = byId,
            cachedVideoIds = listOf("zzzyyyxxx99"),
        )

        assertEquals(listOf(one, two, byId.getValue("zzzyyyxxx99")), listed)
    }

    @Test
    fun `a cached track that is also downloaded appears once as the download`() {
        val downloaded = song("aaabbbccc11", "Downloaded copy")
        val cachedKnown = song("aaabbbccc11", "Named copy")

        val listed = CachedMusic.merge(
            downloaded = listOf(downloaded),
            knownByVideoId = mapOf("aaabbbccc11" to cachedKnown),
            cachedVideoIds = listOf("aaabbbccc11"),
        )

        assertEquals(listOf(downloaded), listed)
    }

    @Test
    fun `cache ids the app has no metadata for are skipped`() {
        val listed = CachedMusic.merge(
            downloaded = emptyList(),
            knownByVideoId = emptyMap(),
            cachedVideoIds = listOf("nobodyknowsthis"),
        )

        assertEquals(emptyList<Song>(), listed)
    }

    @Test
    fun `nothing on either side is nothing in the list`() {
        val listed = CachedMusic.merge(
            downloaded = emptyList(),
            knownByVideoId = emptyMap(),
            cachedVideoIds = emptyList(),
        )

        assertEquals(emptyList<Song>(), listed)
    }

    @Test
    fun `cached-only songs follow in the order the cache names them`() {
        val first = song("zzzyyyxxx01")
        val second = song("zzzyyyxxx02")
        val byId = mapOf("zzzyyyxxx01" to first, "zzzyyyxxx02" to second)

        val listed = CachedMusic.merge(
            downloaded = emptyList(),
            knownByVideoId = byId,
            cachedVideoIds = listOf("zzzyyyxxx01", "zzzyyyxxx02"),
        )

        assertEquals(listOf(first, second), listed)
    }

    @Test
    fun `a mixed device lists downloads first then the cache additions`() {
        val downloaded = song("aaabbbccc11", "Downloaded")
        val cached = song("zzzyyyxxx99", "Cached only")
        val byId = mapOf(
            "zzzyyyxxx99" to cached,
            "aaabbbccc11" to song("aaabbbccc11", "Downloaded, again from cache"),
            "qqqqqqqqqq1" to song("qqqqqqqqqq1", "Cached only, second"),
        )

        val listed = CachedMusic.merge(
            downloaded = listOf(downloaded),
            knownByVideoId = byId,
            cachedVideoIds = listOf("qqqqqqqqqq1", "zzzyyyxxx99", "aaabbbccc11"),
        )

        assertEquals(listOf(downloaded, byId.getValue("qqqqqqqqqq1"), cached), listed)
    }
}