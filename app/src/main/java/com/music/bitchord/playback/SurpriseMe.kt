package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.download.Downloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Builds the queue behind the Play tab's "Surprise Me" action.
 *
 * One half of the queue is what the listener already knows — the tracks a
 * whole-history Replay ranks, plus their downloads — and the other half is
 * discovery *inside their turf*, not a global shuffle. Online, discovery is
 * YouTube Music's own recommendations and the radio feeds seeded from tracks
 * the listener actually plays, so it stays within the genres, languages and
 * artists the listener listens to. Offline there is no network call at all:
 * only device music they have never played is used, weighted toward the
 * artists the Replay already knows.
 *
 * [SurpriseMixer] does the balancing, de-duplication and shuffling; this
 * object only gathers the two pools.
 */
object SurpriseMe {

    /** How many tracks a Surprise Me queue holds. */
    const val TARGET_SIZE = 30

    private const val MAX_FAMILIAR = 100
    private const val MAX_DISCOVERY = 60
    private const val RADIO_SEEDS = 3
    private const val DOWNLOADED_BOOST = 2.0
    private const val IN_TERRITORY_BOOST = 2.0

    suspend fun build(context: Context, targetSize: Int = TARGET_SIZE): List<Song> =
        withContext(Dispatchers.Default) {
            val offline = AppSettings.meteredConnection.value == null
            coroutineScope {
                // Every pool is gathered concurrently: the two stats reads, the
                // downloads, and the full device scan all hit their own storage
                // at the same time instead of one after another, and online the
                // discovery feeds wait together too.
                val stats = async { ListeningStats.summary(ReplayPeriod.ALL_TIME) }
                val plays = async { ListeningStats.playCounts() }
                val downloaded = async { Downloads.getDownloadedSongs(context) }
                val localMusic = async {
                    if (LocalMediaRepository.hasStoragePermission(context)) {
                        LocalMediaRepository.getLocalMusic(context)
                    } else {
                        emptyList()
                    }
                }

                val territory = LinkedHashMap<String, Unit>()
                val familiar = LinkedHashMap<String, SurpriseCandidate>()

                // What the listener actually played, most-played first. Their artists
                // are the territory everything else is judged against.
                stats.await().songs.take(MAX_FAMILIAR).forEach { ranked ->
                    familiar[ranked.song.videoId] =
                        SurpriseCandidate(ranked.song, familiarWeight(ranked.plays, downloaded = false))
                    QueueBuilder.artistSet(ranked.song.artist).forEach { territory.putIfAbsent(it, Unit) }
                }

                // Downloads: ones they have played are familiar; the rest are discovery.
                val playCounts = plays.await()
                val downloadedSongs = downloaded.await()
                downloadedSongs.filter { it.videoId in playCounts }.forEach { song ->
                    familiar[song.videoId] = SurpriseCandidate(
                        song,
                        familiarWeight(playCounts[song.videoId] ?: 1, downloaded = true),
                    )
                }

                val knownIds = familiar.keys + playCounts.keys
                val discovery = LinkedHashMap<String, SurpriseCandidate>()
                fun gather(song: Song) {
                    if (song.videoId in knownIds) return
                    if (song.videoId in discovery) return
                    val inTerritory = QueueBuilder.artistSet(song.artist).any { it in territory }
                    discovery[song.videoId] = SurpriseCandidate(
                        song,
                        if (inTerritory) IN_TERRITORY_BOOST else 1.0,
                    )
                }
                downloadedSongs.filterNot { it.videoId in knownIds }.forEach(::gather)
                localMusic.await().forEach(::gather)

                // Online only: recommendation and related feeds, never reached while
                // offline. Each request failing is fine — the pools it would have fed
                // are already populated, and the mix works from what made it. They run
                // together so the feeds are one wait instead of four round trips in a
                // row, which is what made Surprise Me feel slow.
                if (!offline) {
                    val picks = async { YtMusicRepository.quickPicks(knownIds).getOrNull() }
                    val radios = familiar.keys.shuffled().take(RADIO_SEEDS).map { seed ->
                        seed to async { YtMusicRepository.radio(seed).getOrNull() }
                    }
                    picks.await()?.take(MAX_DISCOVERY)?.forEach { gather(it) }
                    radios.forEach { (seed, related) ->
                        related.await()
                            ?.filterNot { it.videoId == seed }
                            ?.take(MAX_DISCOVERY)
                            ?.forEach { gather(it) }
                    }
                }

                return@coroutineScope SurpriseMixer.mix(
                    familiar = familiar.values.toList(),
                    discovery = discovery.values.toList(),
                    targetSize = targetSize,
                )
            }
        }

    private fun familiarWeight(plays: Int, downloaded: Boolean): Double =
        1.0 + min(plays, 10) + if (downloaded) DOWNLOADED_BOOST else 0.0
}