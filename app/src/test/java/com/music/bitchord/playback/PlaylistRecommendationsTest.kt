package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaylistRecommendationsTest {

    private fun song(
        videoId: String,
        title: String = "Track $videoId",
        artist: String = "Artist $videoId",
    ) = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = null,
    )

    @Test
    fun `empty playlist yields no recommendations`() {
        val picks = PlaylistRecommendations.select(
            playlist = emptyList(),
            suggested = listOf(song("s1")),
            discovered = listOf(song("d1")),
            previouslyShown = emptySet(),
            targetSize = PlaylistRecommendations.DEFAULT_SIZE,
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `never recommends a track already on the playlist`() {
        val onPlaylist = song("v-playlist", title = "Already There", artist = "The Band")
        val picks = PlaylistRecommendations.select(
            playlist = listOf(onPlaylist),
            suggested = listOf(onPlaylist.copy(videoId = "v-suggested")),
            discovered = listOf(onPlaylist, song("away", artist = "Other")),
            previouslyShown = emptySet(),
            targetSize = 10,
            random = Random(7),
        )
        assertFalse(picks.any { it.videoId == onPlaylist.videoId })
        assertFalse(picks.any { it.videoId == "v-suggested" })
    }

    @Test
    fun `drops a near duplicate recording of a playlist track`() {
        val onPlaylist = song("v1", title = "Kesariya", artist = "Arijit Singh, Pritam")
        val nearDuplicate = song("v2", title = "Kesariya", artist = "Pritam")
        val picks = PlaylistRecommendations.select(
            playlist = listOf(onPlaylist),
            suggested = emptyList(),
            discovered = listOf(nearDuplicate, song("fresh", artist = "Someone Else")),
            previouslyShown = emptySet(),
            targetSize = 10,
            random = Random(3),
        )
        assertFalse("near-duplicate recording must not be offered", picks.any { it.videoId == nearDuplicate.videoId })
    }

    @Test
    fun `section never repeats the same recording even across different video cuts`() {
        val playlist = listOf(song("p1", artist = "Lata Mangeshkar"))
        val sameSongTwice = listOf(
            song("a1", title = "Mera Dil", artist = "Lata Mangeshkar"),
            song("a2", title = "Mera Dil", artist = "Kishore Kumar, Lata Mangeshkar"),
        )
        val picks = PlaylistRecommendations.select(
            playlist = playlist,
            suggested = emptyList(),
            discovered = sameSongTwice,
            previouslyShown = emptySet(),
            targetSize = 10,
            random = Random(11),
        )
        assertEquals(1, picks.size)
    }

    @Test
    fun `in-territory discoveries are preferred to out-of-territory ones`() {
        val playlist = listOf(song("p1", artist = "Lata Mangeshkar"))
        val near = (1..10).map { song("near$it", artist = "Lata Mangeshkar") }
        val far = (1..10).map { song("far$it", artist = "Someone Else") }
        val nearTotal = 10 * 2.0
        val farTotal = 10 * 1.2
        val expectedNear = 10 * nearTotal / (nearTotal + farTotal)
        val observed = mutableListOf<Int>()
        repeat(200) { seed ->
            val picks = PlaylistRecommendations.select(
                playlist = playlist,
                suggested = emptyList(),
                discovered = near + far,
                previouslyShown = emptySet(),
                targetSize = 10,
                random = Random(seed),
            )
            observed += picks.count { it.videoId.startsWith("near") }
        }
        val average = observed.average()
        // Weighted picks pull the in-territory tracks ahead of their count in
        // the pool — the exact number is the random draw's, so assert the
        // margin around the expected share rather than an exact figure.
        assertTrue(
            "territory tracks should average ≈$expectedNear, got $average",
            average > expectedNear - 0.75 && average < expectedNear + 0.75,
        )
        assertTrue("territory tracks should be a clear majority, got $average", average > 5.0)
    }

    @Test
    fun `previously shown track is de-emphasised`() {
        val heavy = PlaylistRecommendations.shownWeight("v", 2.0, emptySet())
        val shown = PlaylistRecommendations.shownWeight("v", 2.0, setOf("v"))
        assertTrue(shown < heavy)
    }

    @Test
    fun `refresh with previously shown tracks favours never-seen candidates`() {
        val playlist = listOf(song("p1", artist = "The Seeds"))
        val stale = song("stale", artist = "The Seeds")
        val discovered = (1..40).map { song("f$it", artist = "The Seeds") } + stale
        var stalePicks = 0
        var staleWithoutHistory = 0
        repeat(4000) { seed ->
            val withHistory = PlaylistRecommendations.select(
                playlist = playlist,
                suggested = emptyList(),
                discovered = discovered,
                previouslyShown = setOf(stale.videoId),
                targetSize = 1,
                random = Random(seed),
            )
            if (withHistory.firstOrNull()?.videoId == stale.videoId) stalePicks++
            val withoutHistory = PlaylistRecommendations.select(
                playlist = playlist,
                suggested = emptyList(),
                discovered = discovered,
                previouslyShown = emptySet(),
                targetSize = 1,
                random = Random(seed),
            )
            if (withoutHistory.firstOrNull()?.videoId == stale.videoId) staleWithoutHistory++
        }
        // Unshown the stale track holds its own against 40 equals; once shown
        // its weight drops to ~35% and it falls clearly behind. A claimed
        // repeat with still-plenty-fresh alternatives would break the section's
        // core promise, so the gap is asserted, not the exact counts.
        assertTrue(
            "stale picked $stalePicks with history vs $staleWithoutHistory without",
            stalePicks <= staleWithoutHistory * 2 / 3,
        )
        assertTrue("control should have picked the stale track sometimes", staleWithoutHistory > 0)
    }

    @Test
    fun `same inputs and random seed build the same section`() {
        val playlist = (1..5).map { song("p$it", artist = "Art $it") }
        val suggested = (1..6).map { song("s$it", artist = "Art ${it % 3}") }
        val discovered = (1..30).map { song("d$it", artist = "Art ${it % 4}") }
        fun build(random: Random) = PlaylistRecommendations.select(
            playlist = playlist,
            suggested = suggested,
            discovered = discovered,
            previouslyShown = setOf("s1", "d1"),
            targetSize = 10,
            random = random,
        )
        assertEquals(build(Random(42)).map { it.videoId }, build(Random(42)).map { it.videoId })
    }

    @Test
    fun `local candidates are only device tracks that share an artist with the playlist`() {
        val playlist = listOf(song("p1", artist = "Lata Mangeshkar"))
        val local = listOf(
            song("m1", artist = "Lata Mangeshkar"),
            song("m2", artist = "Other"),
            song("p1", artist = "Lata Mangeshkar"),  // already on the playlist
        )
        val picks = PlaylistRecommendations.localCandidates(playlist, local)
        assertEquals(listOf("m1"), picks.map { it.videoId })
    }

    @Test
    fun `local candidates are empty when nothing on the device is related`() {
        val playlist = listOf(song("p1", artist = "Lata Mangeshkar"))
        val picks = PlaylistRecommendations.localCandidates(
            playlist,
            listOf(song("m1", artist = "Someone Unrelated")),
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `local candidates are empty when the playlist cannot seed a territory`() {
        val picks = PlaylistRecommendations.localCandidates(
            playlist = listOf(song("p1", artist = "")),
            local = listOf(song("m1", artist = "Lata Mangeshkar")),
        )
        assertTrue(picks.isEmpty())
    }

    @Test
    fun `radio seeds fan out across distinct artists`() {
        val playlist = (1..20).map { song("p$it", artist = if (it % 2 == 0) "Even Band $it" else "Odd Band $it") }
        val seeds = PlaylistRecommendations.randomSeedTracks(playlist, Random(5))
        assertEquals(3, seeds.size)
        assertEquals(3, seeds.map { QueueBuilder.artistSet(it.artist).first() }.toSet().size)
        assertEquals(3, seeds.map { it.videoId }.toSet().size)
    }

    @Test
    fun `radio seeds are a subset of the playlist`() {
        val playlist = (1..3).map { song("p$it", artist = "Band $it") }
        val seeds = PlaylistRecommendations.randomSeedTracks(playlist, Random(9))
        assertTrue(seeds.map { it.videoId }.all { id -> playlist.any { it.videoId == id } })
    }
}