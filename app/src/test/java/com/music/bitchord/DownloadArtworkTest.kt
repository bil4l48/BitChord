package com.music.bitchord

import com.music.bitchord.download.DownloadArtwork
import com.music.bitchord.download.SavedSongMetadata
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The new bits of the download record: the rule that turns a saved cover into
 * an offline thumbnail, and the tolerance that lets records written before
 * artwork was kept on the app's side keep decoding.
 *
 * Pinned because both fail quietly. A thumbnail that keeps pointing at a
 * network url when a local cover exists just renders blank offline — correct
 * enough to not crash, wrong enough that nobody reports it. And a metadata
 * record that stopped decoding would take every *existing* download with it,
 * which is the one failure this feature must never land.
 */
class DownloadArtworkTest {

    private val remote = "https://lh3.googleusercontent.com/example-w100-h100"

    private val tolerantJson = Json { ignoreUnknownKeys = true }
    private val oldFormatJson = Json { encodeDefaults = false }

    @Test
    fun `local cover is used while it still exists`() {
        val local = "file:///data/data/com.music.bitchord/files/artwork/video.jpg"
        assertEquals(
            local,
            DownloadArtwork.offlineThumbnail(remote, local, artworkExists = true),
        )
    }

    @Test
    fun `remote thumbnail is kept when nothing was saved`() {
        assertEquals(
            remote,
            DownloadArtwork.offlineThumbnail(remote, null, artworkExists = true),
        )
    }

    @Test
    fun `remote thumbnail is kept when the saved cover is gone`() {
        val local = "file:///data/data/com.music.bitchord/files/artwork/video.jpg"
        assertEquals(remote, DownloadArtwork.offlineThumbnail(remote, local, artworkExists = false))
    }

    @Test
    fun `no thumbnail at all stays none`() {
        assertNull(DownloadArtwork.offlineThumbnail(null, null, artworkExists = true))
        assertNull(DownloadArtwork.offlineThumbnail(null, "file:///gone.jpg", artworkExists = false))
    }

    @Test
    fun `a saved cover beats an empty remote url`() {
        val local = "file:///data/data/com.music.bitchord/files/artwork/video.jpg"
        assertEquals(local, DownloadArtwork.offlineThumbnail("", local, artworkExists = true))
    }

    @Test
    fun `record with an artwork path round-trips`() {
        val meta = SavedSongMetadata(
            videoId = "abc123",
            title = "Kesariya",
            artist = "Arijit Singh",
            thumbnailUrl = remote,
            albumName = "Brahmastra",
            uri = "content://media/external/audio/media/7",
            artworkPath = "file:///data/data/com.music.bitchord/files/artwork/abc123.jpg",
        )
        val json = Json.encodeToString(SavedSongMetadata.serializer(), meta)
        val decoded = Json.decodeFromString(SavedSongMetadata.serializer(), json)
        assertEquals(meta, decoded)
        assertTrue(json.contains("artworkPath"))
    }

    @Test
    fun `a record written before artwork was stored still decodes`() {
        // The exact serialized shape of a download saved before this feature:
        // defaults omitted, so artworkPath is simply not there.
        val old = SavedSongMetadata(
            videoId = "def456",
            title = "Old",
            artist = "Previous Art",
            thumbnailUrl = remote,
            uri = "content://media/external/audio/media/9",
        )
        val json = oldFormatJson.encodeToString(SavedSongMetadata.serializer(), old)
        assertFalse("old records carry no artworkPath", json.contains("artworkPath"))

        val decoded = tolerantJson
            .decodeFromString(SavedSongMetadata.serializer(), json)
        assertEquals("def456", decoded.videoId)
        assertEquals("Old", decoded.title)
        assertEquals(remote, decoded.thumbnailUrl)
        assertNull("old records fall back to no local cover", decoded.artworkPath)
    }
}