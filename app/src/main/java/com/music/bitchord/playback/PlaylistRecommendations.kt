package com.music.bitchord.playback

import android.content.Context
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.download.Downloads
import kotlin.random.Random

/**
 * Builds the "You Might Like" section of a playlist the account owns.
 *
 * Recommendations are drawn overwhelmingly from content the playlist already
 * holds: YouTube's own suggested (the round-out tracks the page carries) and
 * the radio feeds seeded from the playlist's own tracks. It is a playlist's
 * answer to the global [SurpriseMe] shuffle — same mixer, same de-duplication
 * against near-duplicate recordings — but the pool is the playlist, not the
 * listener's history, so a stranger's playlist keeps YouTube's static
 * Suggested and nothing here changes for it.
 *
 * The rules:
 *
 *  * nothing already on the playlist, or a near-duplicate recording of it;
 *  * no repeats within the section;
 *  * Refresh varies the set — the radio seeds are redrawn each call and
 *    tracks already shown are simply de-emphasised, so the next set favours
 *    fresh picks while never running dry;
 *  * offline there is no network call at all — only device music that shares
 *    an artist with the playlist is offered, and when none does the section
 *    is empty rather than invented (see [localCandidates]).
 *
 * [select] and [localCandidates] are pure and unit-tested; [build] only goes
 * to the network when it is allowed to, then hands what it gathered over.
 */
object PlaylistRecommendations {

    /** How many tracks a "You Might Like" section holds. */
    const val DEFAULT_SIZE = 10

    private const val RADIO_SEEDS = 3

    /** A group of tracks per seed is enough for the mixer's cap; no more of a
     *  single feed is ever drawn on. */
    private const val MAX_PER_SEED = 40

    /** YouTube's own round-out suggestions carry the most weight. */
    private const val SUGGESTED_WEIGHT = 1.6

    /** A related-track feed that wanders outside the playlist's territory. */
    private const val DISCOVERED_WEIGHT = 1.2

    /** A related track whose artist is already on the playlist — the heart of
     *  "You Might Like", so it gets the truck that [SurpriseMe] uses for
     *  familiar territory. */
    private const val IN_TERRITORY_WEIGHT = 2.0

    /** A track the account already saw in an earlier Refresh. Never hidden —
     *  when the pool runs low there is nothing better to show — simply made
     *  less likely than anything never shown, so Refresh keeps varying while
     *  it can. */
    private const val PREVIOUSLY_SHOWN_WEIGHT = 0.35

    /**
     * Builds a fresh "You Might Like" set for [playlist].
     *
     * Offline, [suggested] is whatever the cached page carried (usually
     * nothing) and the only discovery is device music in the playlist's
     * own territory, so an offline Refresh can still give the user
     * something when their library is related, and an honest empty result
     * when it is not — the caller renders that as the empty state. No
     * network request is ever made while offline; failing feeds simply feed
     * the mix less.
     */
    suspend fun build(
        context: Context,
        playlist: List<Song>,
        suggested: List<Song>,
        previouslyShown: Set<String>,
        targetSize: Int = DEFAULT_SIZE,
        random: Random = Random.Default,
    ): List<Song> {
        val offline = AppSettings.meteredConnection.value == null
        if (playlist.isEmpty()) return emptyList()
        val discovered = if (offline) {
            val local = buildList {
                Downloads.getDownloadedSongs(context).let(::addAll)
                if (LocalMediaRepository.hasStoragePermission(context)) {
                    LocalMediaRepository.getLocalMusic(context).let(::addAll)
                }
            }
            localCandidates(playlist, local)
        } else {
            onlineCandidates(playlist, random)
        }
        return select(playlist, suggested, discovered, previouslyShown, targetSize, random)
    }

    /**
     * The pure heart of the section: takes the gathered pools and returns the
     * final set. Seeded by the same [SurpriseMixer] the global shuffle draws
     * from, so the balancing, weighting and near-duplicate dropping are the
     * proven ones — this only adds the two rules Surprise Me does not know:
     * nothing already in [playlist], and a lighter hand on what was shown in
     * an earlier Refresh.
     */
    fun select(
        playlist: List<Song>,
        suggested: List<Song>,
        discovered: List<Song>,
        previouslyShown: Set<String>,
        targetSize: Int,
        random: Random = Random.Default,
    ): List<Song> {
        if (playlist.isEmpty()) return emptyList()
        val territory = HashSet<String>()
        playlist.forEach { QueueBuilder.artistSet(it.artist).forEach(territory::add) }
        val playlistIds = playlist.mapTo(HashSet()) { it.videoId }

        fun alreadyOn(song: Song): Boolean =
            song.videoId in playlistIds || playlist.any { QueueBuilder.isSameRecording(it, song) }
        fun base(song: Song): Double =
            if (QueueBuilder.artistSet(song.artist).any { t -> t in territory }) {
                IN_TERRITORY_WEIGHT
            } else {
                DISCOVERED_WEIGHT
            }

        val familiar = suggested
            .filterNot(::alreadyOn)
            .map { SurpriseCandidate(it, shownWeight(it.videoId, SUGGESTED_WEIGHT, previouslyShown)) }
        val newDiscoveries = discovered
            .filterNot(::alreadyOn)
            .map { SurpriseCandidate(it, shownWeight(it.videoId, base(it), previouslyShown)) }

        return SurpriseMixer.mix(
            familiar = familiar,
            discovery = newDiscoveries,
            targetSize = targetSize,
            random = random,
        )
            .filterNot(::alreadyOn)
            .fold(emptyList<Song>()) { kept, song ->
                // Two feeds can serve the same recording under different video
                // cuts, and the mixer's no-repeats rule is per id only. The
                // section promises no duplicate recommendations, so the same
                // recording arriving twice keeps only its first appearance.
                if (kept.any { QueueBuilder.isSameRecording(it, song) }) kept else kept + song
            }
            .take(targetSize)
    }

    /**
     * The de-emphasis a track already seen in an earlier Refresh gets in the
     * next one. Not zero — when the pool runs low a repeat is better than a
     * silence — just enough lighter than everything unseen that fresh picks
     * surface first while the choice is real.
     */
    internal fun shownWeight(videoId: String, base: Double, previouslyShown: Set<String>): Double =
        base * if (videoId in previouslyShown) PREVIOUSLY_SHOWN_WEIGHT else 1.0

    /**
     * What the device itself can offer a playlist offline: [local] tracks that
     * are not on the playlist and share an artist with it. Empty when the
     * device has nothing related — the correct offline answer rather than a
     * made-up one.
     */
    fun localCandidates(playlist: List<Song>, local: List<Song>): List<Song> {
        if (playlist.isEmpty()) return emptyList()
        val territory = HashSet<String>()
        playlist.forEach { QueueBuilder.artistSet(it.artist).forEach(territory::add) }
        val playlistIds = playlist.mapTo(HashSet()) { it.videoId }
        if (territory.isEmpty()) return emptyList()
        return local.filter { song ->
            song.videoId !in playlistIds &&
                QueueBuilder.artistSet(song.artist).any { it in territory }
        }
    }

    /**
     * Online discovery: radio feeds seeded by tracks already on the playlist,
     * [RADIO_SEEDS] across distinct artists so one band cannot flood the
     * section. Each seed's own video comes back leading its own feed, so it
     * is dropped here — it is already on the playlist by definition.
     */
    internal suspend fun onlineCandidates(playlist: List<Song>, random: Random): List<Song> {
        val gathered = LinkedHashMap<String, Song>()
        randomSeedTracks(playlist, random).forEach { seed ->
            YtMusicRepository.radio(seed.videoId).getOrNull()
                ?.filterNot { it.videoId == seed.videoId }
                ?.take(MAX_PER_SEED)
                ?.forEach { song -> gathered.putIfAbsent(song.videoId, song) }
        }
        return gathered.values.toList()
    }

    /**
     * [RADIO_SEEDS] tracks drawn from [playlist] to seed radio feeds, one per
     * distinct artist so the feeds fan out across the playlist instead of
     * one act saturating them.
     */
    internal fun randomSeedTracks(playlist: List<Song>, random: Random): List<Song> {
        val used = HashSet<String>()
        return buildList {
            for (song in playlist.shuffled(random)) {
                val artist = QueueBuilder.artistSet(song.artist).firstOrNull() ?: continue
                if (used.add(artist)) add(song)
                if (size == RADIO_SEEDS) break
            }
        }
    }
}