package app.melo.music.playback

import app.melo.music.data.QueueMode
import app.melo.music.data.Track
import app.melo.music.data.TrackMatcher
import kotlin.random.Random

object QueuePolicy {
    fun isPermutation(order: List<Int>, size: Int): Boolean = order.size == size && order.toSet() == (0 until size).toSet()

    fun create(tracks: List<Track>, current: Int, mode: QueueMode, seed: Long, history: List<Track> = emptyList()): List<Int> {
        if (tracks.isEmpty()) return emptyList()
        if (mode == QueueMode.ORDERED) return tracks.indices.toList()
        val selected = current.coerceIn(tracks.indices)
        return listOf(selected) + arrange(tracks.indices.filter { it != selected }, tracks, mode, seed, history)
    }

    fun reshuffle(order: List<Int>, current: Int, tracks: List<Track>, mode: QueueMode, seed: Long, history: List<Track> = emptyList()): List<Int> {
        val safe = order.takeIf { isPermutation(it, tracks.size) } ?: tracks.indices.toList()
        val split = safe.indexOf(current).takeIf { it >= 0 } ?: return safe
        val tail = safe.drop(split + 1)
        var shuffled = arrange(tail, tracks, mode, seed, history)
        if (shuffled == tail && tail.size > 1) shuffled = shuffled.drop(1) + shuffled.first()
        return safe.take(split + 1) + shuffled
    }

    private fun arrange(indices: List<Int>, tracks: List<Track>, mode: QueueMode, seed: Long, history: List<Track>): List<Int> {
        val randomized = indices.shuffled(Random(seed))
        if (mode != QueueMode.FRESH) return randomized
        val recentlyPlayed = history.take(12).map { it.id }.toSet()
        val candidates = randomized.sortedBy { if (tracks[it].id in recentlyPlayed) 1 else 0 }.toMutableList()
        val result = mutableListOf<Int>()
        while (candidates.isNotEmpty()) {
            val previousArtist = result.lastOrNull()?.let { TrackMatcher.normalized(tracks[it].artist) }
            val freshness = tracks[candidates.first()].id in recentlyPlayed
            val next = candidates.indexOfFirst { (tracks[it].id in recentlyPlayed) == freshness && TrackMatcher.normalized(tracks[it].artist) != previousArtist }.coerceAtLeast(0)
            result.add(candidates.removeAt(next))
        }
        return result
    }

    fun insertNext(order: List<Int>, current: Int, insertionIndex: Int): List<Int> {
        val remapped = order.map { if (it >= insertionIndex) it + 1 else it }.toMutableList()
        val oldCurrent = if (current >= insertionIndex) current + 1 else current
        val after = (remapped.indexOf(oldCurrent) + 1).coerceIn(0, remapped.size)
        remapped.add(after, insertionIndex)
        return remapped
    }

    fun moveUpcoming(order: List<Int>, current: Int, index: Int, delta: Int): List<Int> {
        val from = order.indexOf(index)
        val currentPosition = order.indexOf(current)
        val to = from + delta
        if (from <= currentPosition || to <= currentPosition || to !in order.indices) return order
        return order.toMutableList().apply { val item = removeAt(from); add(to, item) }
    }

    fun uniqueRecommendations(candidates: List<Track>, queued: List<Track>): List<Track> {
        fun key(track: Track) = TrackMatcher.normalized(track.title) + "|" + TrackMatcher.normalized(track.artist.substringBefore(','))
        val ids = queued.map { it.id }.toSet()
        val keys = queued.map(::key).toSet()
        return candidates.filter { it.id !in ids && key(it) !in keys && it.durationMs in 30_000..1_200_000 }
            .distinctBy(::key).take(20)
    }
}
