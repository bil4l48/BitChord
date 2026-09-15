package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.QueueShuffle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reordering behind the shuffle toggle. Playing the queue is ExoPlayer's
 * job; getting the queue into the order it should play in is this one's.
 */
class QueueShuffleTest {

    /** Runs the moves the way [androidx.media3.common.Player] would. */
    private fun applied(current: List<String>, from: Int, target: List<String>): List<String> {
        val ids = current.toMutableList()
        QueueShuffle.moves(current, from, target).forEach { (at, to) ->
            ids.add(to, ids.removeAt(at))
        }
        return ids
    }

    @Test
    fun `the queue ends up in the order asked for`() {
        val queue = listOf("a", "b", "c", "d", "e")
        assertEquals(
            listOf("a", "b", "e", "c", "d"),
            applied(queue, from = 2, target = listOf("e", "c", "d")),
        )
    }

    @Test
    fun `everything up to and including the playing track is left alone`() {
        val queue = listOf("a", "b", "c", "d")
        // Shuffle at index 1 may only touch what comes after it.
        val out = applied(queue, from = 2, target = listOf("d", "c"))
        assertEquals(listOf("a", "b"), out.take(2))
        assertEquals(listOf("a", "b", "d", "c"), out)
    }

    @Test
    fun `an order already in place costs no moves`() {
        val queue = listOf("a", "b", "c", "d")
        assertTrue(QueueShuffle.moves(queue, from = 1, target = listOf("b", "c", "d")).isEmpty())
    }

    @Test
    fun `a queue holding the same track twice keeps both copies`() {
        val queue = listOf("a", "b", "c", "b")
        assertEquals(
            listOf("a", "b", "b", "c"),
            applied(queue, from = 1, target = listOf("b", "b", "c")),
        )
    }

    @Test
    fun `tracks the target does not name trail behind the ones it does`() {
        val queue = listOf("a", "b", "c", "d", "e")
        // "e" was queued after the shuffle, so the restored order says nothing
        // about it — it stays at the end rather than displacing anything.
        assertEquals(
            listOf("a", "d", "b", "c", "e"),
            applied(queue, from = 1, target = listOf("d", "b", "c")),
        )
    }

    @Test
    fun `a track that has since been removed is skipped`() {
        val queue = listOf("a", "b", "c")
        assertEquals(
            listOf("a", "c", "b"),
            applied(queue, from = 1, target = listOf("c", "gone", "b")),
        )
    }

    private fun song(id: String) = Song(
        videoId = id,
        title = "Title $id",
        artist = "Artist",
        thumbnailUrl = null,
    )

    @Test
    fun `starting a queue while shuffled leads with the picked track`() {
        val songs = ('a'..'e').map { song(it.toString()) }
        repeat(20) {
            val start = (songs.indices).random()
            val ordered = QueueShuffle.startingOrder(songs, start)
            // The picked track leads, and nothing is lost or duplicated: the
            // rest is the same multiset in a shuffled order.
            assertEquals(songs[start], ordered.first())
            assertEquals(songs.size, ordered.size)
            assertEquals(songs.map { it.videoId }.sorted(), ordered.map { it.videoId }.sorted())
        }
    }

    @Test
    fun `a started queue restores to playlist order through moves`() {
        // The toggle-off path rearranges with the same moves machinery tested
        // above, so a started queue must round-trip: shuffled order back to
        // the playlist order it was built from.
        val songs = ('a'..'e').map { song(it.toString()) }
        val ids = songs.map { it.videoId }
        repeat(20) {
            val ordered = QueueShuffle.startingOrder(songs, 0).map { it.videoId }
            assertEquals(ids, applied(ordered, from = 1, target = ids.drop(1)))
        }
    }

    @Test
    fun `shuffling then restoring returns the original running order`() {
        val original = ('a'..'j').map { it.toString() }
        repeat(50) {
            val from = 1
            val shuffled = applied(original, from, original.drop(from).shuffled())
            assertEquals(original.take(from), shuffled.take(from))
            assertEquals(original.sorted(), shuffled.sorted())
            assertEquals(original, applied(shuffled, from, original.drop(from)))
        }
    }
}
