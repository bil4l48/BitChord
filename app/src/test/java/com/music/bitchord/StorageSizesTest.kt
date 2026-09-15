package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.storage.StorageSizes
import com.music.bitchord.playback.CachedMusic
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The numbers behind the Storage & cache page.
 *
 * The Android-side readers of [StorageSizes] (Downloads' record, the two Media3
 * caches, Coil) return real figures only on a device, so what is testable here
 * is the arithmetic shared with them: [StorageSizes.folderBytes], the Totals
 * sum, and the byte formatter the page prints. The one behavior that *can* be
 * checked whole — what Cached Music reports after its backing cache is emptied
 * — is merged here through [CachedMusic.merge].
 */
class StorageSizesTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ---- The recursive folder sum -------------------------------------------

    @Test
    fun `folderBytes adds every file under the folder, however deep`() {
        val root = temp.newFolder("downloads")
        File(root, "one.flac").writeBytes(ByteArray(10))
        val sub = File(root, "offline-hls").apply { mkdirs() }
        File(sub, "playlist.m3u8").writeBytes(ByteArray(2))
        File(sub, "seg0").writeBytes(ByteArray(5))
        File(sub, "seg1").writeBytes(ByteArray(3))

        assertEquals(20L, StorageSizes.folderBytes(root))
    }

    @Test
    fun `folderBytes is 0 for a missing path and for an empty folder`() {
        assertEquals(0L, StorageSizes.folderBytes(File("no/such/path/here")))
        assertEquals(0L, StorageSizes.folderBytes(temp.newFolder("empty")))
    }

    @Test
    fun `folderBytes counts a lone file as its own length`() {
        val file = temp.newFile("solo.apk").apply { writeBytes(ByteArray(7)) }
        assertEquals(7L, StorageSizes.folderBytes(file))
    }

    // ---- The total -----------------------------------------------------------

    @Test
    fun `totals sums every category the page reports`() {
        val totals = StorageSizes.Totals(
            downloadsBytes = 100,
            cachedAudioBytes = 200,
            artworkBytes = 300,
            canvasBytes = 400,
            updatesBytes = 500,
        )

        assertEquals(1500L, totals.totalBytes)
    }

    @Test
    fun `totals of nothing is zero`() {
        assertEquals(0L, StorageSizes.Totals().totalBytes)
    }

    // ---- The formatter -------------------------------------------------------

    @Test
    fun `formatBytes reads naturally at each magnitude`() {
        assertEquals("0 B", StorageSizes.formatBytes(0))
        assertEquals("512 B", StorageSizes.formatBytes(512))
        assertEquals("1 KB", StorageSizes.formatBytes(1024))
        assertEquals("513 KB", StorageSizes.formatBytes(513 * 1024))
        assertEquals("5 MB", StorageSizes.formatBytes(5 * 1024 * 1024))
        assertEquals("2 GB", StorageSizes.formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatBytes rounds a partial megabyte to one decimal`() {
        assertEquals("1.5 MB", StorageSizes.formatBytes((1536 * 1024).toLong()))
    }

    // ---- Cached Music after a cache clear ------------------------------------

    @Test
    fun `a cleared audio cache leaves downloads as Cached Music's whole listing`() {
        val downloaded = Song(videoId = "download1", title = "Kept", artist = "A", thumbnailUrl = null)
        val cacheOnly = Song(videoId = "zzzyyyyxc11", title = "Gone", artist = "B", thumbnailUrl = null)

        // What Cached Music reads after AudioCache.clear: no cached ids remain,
        // and the merged listing collapses to the downloads that were never touched.
        val listed = CachedMusic.merge(
            downloaded = listOf(downloaded),
            knownByVideoId = mapOf(cacheOnly.videoId to cacheOnly),
            cachedVideoIds = emptyList(),
        )

        assertEquals(listOf(downloaded), listed)
    }
}