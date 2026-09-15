package com.music.bitchord

import com.music.bitchord.playback.armedQueueIntact
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard behind bailing an in-flight crossfade when the session queue is
 * edited under it. The standby player holds a copy taken at arm time; adopting
 * a stale copy would silently un-apply the listener's shuffle, resurrect
 * removed rows, or drop queued tracks — so anything but a pure append (which
 * [reconcileQueue][com.music.bitchord.playback.CrossfadeController] carries
 * across) must read as diverged.
 */
class CrossfadeQueueGuardTest {

    @Test
    fun `an untouched queue reads as intact`() {
        assertTrue(armedQueueIntact(listOf("a", "b", "c"), listOf("a", "b", "c")))
    }

    @Test
    fun `appended autoplay tracks read as intact`() {
        assertTrue(armedQueueIntact(listOf("a", "b"), listOf("a", "b", "x", "y")))
    }

    @Test
    fun `a shuffled queue reads as diverged`() {
        assertFalse(armedQueueIntact(listOf("a", "b", "c", "d"), listOf("a", "c", "b", "d")))
    }

    @Test
    fun `a removed row reads as diverged`() {
        assertFalse(armedQueueIntact(listOf("a", "b", "c"), listOf("a", "c")))
    }

    @Test
    fun `an inserted row reads as diverged`() {
        assertFalse(armedQueueIntact(listOf("a", "b", "c"), listOf("a", "x", "b", "c")))
    }

    @Test
    fun `a replaced queue reads as diverged`() {
        assertFalse(armedQueueIntact(listOf("a", "b"), listOf("x", "y", "z")))
    }

    @Test
    fun `an empty queue reads as diverged once armed`() {
        assertFalse(armedQueueIntact(listOf("a", "b"), emptyList()))
    }
}
