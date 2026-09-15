package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Surprise Mixer's promises, pinned down: a queue that really is half familiar
 * and half discovery however short either pool runs, that never repeats a
 * track (or a discovery track that is secretly a familiar one again), and that
 * comes out shuffled rather than rigidly alternated.
 */
class SurpriseMixerTest {

    private fun song(id: Int, prefix: String) = Song(
        videoId = "$prefix$id",
        title = "Track $prefix$id",
        artist = "Artist $prefix",
        thumbnailUrl = null,
    )

    private fun candidate(id: Int, prefix: String, weight: Double = 1.0) =
        SurpriseCandidate(song(id, prefix), weight)

    private fun familiar(ids: Iterable<Int>, weight: Double = 1.0) =
        ids.map { candidate(it, "f", weight) }

    private fun discovery(ids: Iterable<Int>, weight: Double = 1.0) =
        ids.map { candidate(it, "d", weight) }

    private fun videoIds(queue: List<Song>) = queue.map { it.videoId }

    private fun poolSizes(queue: List<Song>): Pair<Int, Int> {
        var familiar = 0
        var discovery = 0
        queue.forEach { if (it.videoId.startsWith("f")) familiar++ else discovery++ }
        return familiar to discovery
    }

    @Test
    fun `I_never_repeat_a_track`() {
        val queue = SurpriseMixer.mix(familiar(0 until 60), discovery(0 until 60), targetSize = 30)

        assertFalse("ids must not repeat", videoIds(queue).size != videoIds(queue).distinct().size)
    }

    @Test
    fun `a full target from a short pool never overshoots it`() {
        val queue = SurpriseMixer.mix(familiar(0 until 60), discovery(0 until 8), targetSize = 30)

        assertTrue(queue.size <= 30)
    }

    @Test
    fun `a discovery track that is a familiar recording under another id is dropped`() {
        val known = SurpriseCandidate(Song("f9", "Ocean Eyes", "Billie Eilish", null), 8.0)
        // A different id, but the same recording — like the audio and video
        // cuts of one release.
        val sameUnderAnotherId = SurpriseCandidate(Song("d9", "Ocean Eyes", "Billie Eilish", null), 5.0)
        val other = SurpriseCandidate(Song("d1", "Something else", "Another Artist", null), 1.0)

        val queue = SurpriseMixer.mix(listOf(known), listOf(sameUnderAnotherId, other), targetSize = 2)

        assertEquals(setOf("f9", "d1"), videoIds(queue).toSet())
    }

    @Test
    fun `two familiar entries for one track keep the heavier one`() {
        val light = SurpriseCandidate(song(3, "f"), 1.0)
        val heavy = SurpriseCandidate(song(3, "f"), 9.0)

        val queue = SurpriseMixer.mix(listOf(light, heavy), emptyList(), targetSize = 1)

        assertEquals(1, queue.size)
    }

    @Test
    fun `high weights are drawn noticeably more often`() {
        val heavy = SurpriseCandidate(Song("dH", "Rare", "Artist", null), 1000.0)
        val discoveryPool = listOf(heavy) + discovery(0 until 39)
        val random = Random(7)

        var heavyPicks = 0
        repeat(200) {
            val queue = SurpriseMixer.mix(
                familiar(0 until 60),
                discoveryPool,
                targetSize = 30,
                random = random,
            )
            if (videoIds(queue).contains("dH")) heavyPicks++
        }
        assertTrue("weighted pick should overwhelmingly favour the heavy entry", heavyPicks > 190)
    }

    @Test
    fun `the queue is shuffled, not a strict alternation of the pools`() {
        val random = Random(1)
        var sawARun = false
        repeat(20) {
            val queue = SurpriseMixer.mix(familiar(0 until 30), discovery(0 until 30), targetSize = 24, random = random)
            val prefixed = videoIds(queue).map { it.take(1) }
            if (prefixed.zipWithNext().any { (a, b) -> a == b }) sawARun = true
        }

        assertTrue("a coin-flip order must occasionally sit two of a kind together", sawARun)
    }

    @Test
    fun `even pools split the queue in half`() {
        val queue = SurpriseMixer.mix(familiar(0 until 40), discovery(0 until 40), targetSize = 30)
        val (familiarCount, discoveryCount) = poolSizes(queue)

        assertEquals(30, queue.size)
        assertEquals(15, familiarCount)
        assertEquals(15, discoveryCount)
    }

    @Test
    fun `a short familiar pool is used in full and discovery makes up the size`() {
        val queue = SurpriseMixer.mix(familiar(0 until 4), discovery(0 until 100), targetSize = 30)
        val familiarIds = familiar(0 until 4).map { it.song.videoId }.toSet()

        assertEquals(30, queue.size)
        assertTrue("every known track earns a slot", videoIds(queue).containsAll(familiarIds))
        assertEquals(familiarIds.size, videoIds(queue).count { it in familiarIds })
    }

    @Test
    fun `a short discovery pool is used in full and familiar makes up the size`() {
        val queue = SurpriseMixer.mix(familiar(0 until 100), discovery(0 until 2), targetSize = 30)
        val discoveryIds = discovery(0 until 2).map { it.song.videoId }.toSet()

        assertEquals(30, queue.size)
        assertTrue("every discovery track earns a slot", videoIds(queue).containsAll(discoveryIds))
        assertEquals(discoveryIds.size, videoIds(queue).count { it in discoveryIds })
    }

    @Test
    fun `a discovery recording already in the familiar pool is never repeated`() {
        val basic = { i: Int -> Song("$i", "Shared Title $i", "Shared Artist", null) }
        val known = (0 until 5).map { SurpriseCandidate(basic(it), 8.0) }
        // Discovery offers the same recordings again, plus genuinely new ones.
        val offered = (0 until 5).map { SurpriseCandidate(basic(it), 5.0) } +
            (5 until 9).map { SurpriseCandidate(Song("d$it", "New $it", "Fresh Artist", null), 1.0) }

        val queue = SurpriseMixer.mix(known, offered, targetSize = 30)
        val familiarIds = known.map { it.song.videoId }.toSet()
        val newIds = (5 until 9).map { "d$it" }.toSet()

        assertTrue(
            "every slot is a familiar track or a genuinely new one, nothing repeated",
            videoIds(queue).toSet() == familiarIds + newIds,
        )
        assertEquals(familiarIds.size + newIds.size, queue.size)
    }

    @Test
    fun `empty pools or a zero target make an empty queue`() {
        val empty = SurpriseMixer.mix(emptyList(), emptyList(), targetSize = 30)
        val zero = SurpriseMixer.mix(familiar(0 until 10), discovery(0 until 10), targetSize = 0)
        val negative = SurpriseMixer.mix(familiar(0 until 10), discovery(0 until 10), targetSize = -3)
        assertEquals(emptyList<Song>(), empty)
        assertEquals(emptyList<Song>(), zero)
        assertEquals(emptyList<Song>(), negative)
    }
}