package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.random.Random

/**
 * One track in the familiar or discovery pool a Surprise Me queue draws from.
 *
 * [weight] raises the entry's chance of being picked within its pool; 1.0 is
 * the neutral weight. It is a relative preference, not a share — a listener's
 * top track standing at ten is ten times as likely to be drawn from the
 * familiar pool as one they played once.
 */
data class SurpriseCandidate(
    val song: Song,
    val weight: Double = 1.0,
)

/**
 * Turns the two Surprise Me pools into one queue: roughly half tracks the
 * listener already knows, half they have never listened to, arriving in an
 * order that feels shuffled rather than alternated.
 *
 * This is a draw, not a feed, so it lives apart from the feed-order queue
 * builders and stays testable on its own — the caller only ever hands over
 * [SurpriseCandidate]s (history, downloads and radio feeds are assembled by
 * [SurpriseMe]). The rules:
 *
 *  * exact balance when both pools can supply it, each pool's shortfall made
 *    up by the other when one runs out of entries;
 *  * weighted picks inside a pool — higher [SurpriseCandidate.weight] first;
 *  * no repeats, and no discovery entry that is the same recording as a
 *    familiar one, near-duplicate video cuts included.
 */
object SurpriseMixer {

    /**
     * The mix never needs more than one pass of a pool; the caller can stop
     * gathering once a pool reaches this size.
     */
    const val POOL_CAP = 120

    fun mix(
        familiar: List<SurpriseCandidate>,
        discovery: List<SurpriseCandidate>,
        targetSize: Int,
        random: Random = Random.Default,
    ): List<Song> {
        if (targetSize <= 0) return emptyList()
        val fam = familiarPool(familiar)
        val disc = discoveryPool(fam, discovery)
        val wanted = targetSize.coerceAtMost(fam.size + disc.size)

        // Roughly half of every slot goes to each pool. The pool with more to
        // give covers the other's shortfall, so a small familiar pool is used
        // in full and the queue still reaches its size.
        var fCount = minOf(fam.size, (wanted + 1) / 2)
        val shortfall = wanted - fCount
        val dCount = minOf(disc.size, shortfall)
        fCount = wanted - dCount

        val familiars = weighted(fam, fCount, random)
        val discoveries = weighted(disc, dCount, random)

        // Interleave by what remains of each pool: with equal pools this is a
        // coin toss per slot (which is the "not an alternation" part), and as
        // one pool pulls ahead its chance of being chosen next falls, so the
        // two tail off together instead of one side finishing before the mix.
        val out = buildList {
            while (familiars.isNotEmpty() || discoveries.isNotEmpty()) {
                val takeFamiliar = when {
                    familiars.isEmpty() -> false
                    discoveries.isEmpty() -> true
                    else -> random.nextFloat() * (familiars.size + discoveries.size) < familiars.size
                }
                if (takeFamiliar) add(familiars.removeAt(random.nextInt(familiars.size)))
                else add(discoveries.removeAt(random.nextInt(discoveries.size)))
            }
        }
        return out
    }

    /** [familiar] with repeats folded — one entry per track id, heaviest kept. */
    private fun familiarPool(entries: List<SurpriseCandidate>): List<SurpriseCandidate> {
        val best = LinkedHashMap<String, SurpriseCandidate>()
        for (entry in entries) {
            val previous = best[entry.song.videoId]
            if (previous == null || entry.weight > previous.weight) best[entry.song.videoId] = entry
        }
        return best.values.toList()
    }

    /**
     * [discovery] with whatever repeats a familiar recording dropped — that is
     * the discovery half's whole point, and repeat guard has to catch the
     * same song under a different video cut, which an id match alone misses.
     */
    private fun discoveryPool(
        familiar: List<SurpriseCandidate>,
        entries: List<SurpriseCandidate>,
    ): List<SurpriseCandidate> {
        val seen = HashSet<String>()
        return buildList {
            for (entry in entries) {
                if (!seen.add(entry.song.videoId)) continue
                if (familiar.any { QueueBuilder.isSameRecording(it.song, entry.song) }) continue
                add(entry)
            }
        }
    }

    /**
     * [count] entries drawn from [pool] by weight, without replacement.
     *
     * Drawing is a ranking rather than count repeated weighted draws: every
     * entry is ordered once by a weighted pick from what is left, then the
     * survivors are the front of that ranking — [count] single weighted picks
     * that add up to the same distribution without the O(n²) per-draw scan.
     */
    private fun weighted(pool: List<SurpriseCandidate>, count: Int, random: Random): MutableList<Song> {
        val ordered = ranking(pool, random)
        return MutableList(count.coerceAtMost(ordered.size)) { ordered[it].song }
    }

    /** Every entry of [pool] once, earlier being a higher pick, by weight. */
    private fun ranking(pool: List<SurpriseCandidate>, random: Random): List<SurpriseCandidate> {
        val remaining = pool.toMutableList()
        val ranked = ArrayList<SurpriseCandidate>(remaining.size)
        while (remaining.isNotEmpty()) {
            val total = remaining.sumOf { positive(it.weight) }
            var roll = random.nextDouble() * total
            var index = 0
            var acc = positive(remaining[0].weight)
            while (roll > acc) {
                index++
                acc += positive(remaining[index].weight)
            }
            ranked += remaining.removeAt(index)
        }
        return ranked
    }

    private fun positive(weight: Double): Double = if (weight.isFinite() && weight > 0.0) weight else 1.0
}