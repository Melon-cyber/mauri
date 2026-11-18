package app.melo.music.data

import java.time.LocalDate
import kotlin.random.Random

enum class MixKind { DAILY, FREQUENT, FORGOTTEN, NEW }

/** A playlist Mauri builds by itself from the library and listening history; it changes as you listen. */
data class SmartMix(val kind: MixKind, val title: String, val subtitle: String, val tracks: List<Track>)

object SmartMixes {
    private fun key(track: Track) = TrackMatcher.normalized(track.title) + "|" + TrackMatcher.normalized(TrackMatcher.primaryArtist(track.artist))

    fun build(library: Library, history: List<TrackHistory>, today: LocalDate = LocalDate.now()): List<SmartMix> {
        val collection = (library.likes + library.playlists.flatMap { it.tracks }).filterNot { it.isLocal && it.url.isBlank() }.distinctBy { it.id }
        val byKey = history.groupBy { key(it.track) }
        fun stats(track: Track) = byKey[key(track)]

        // Often played: the most played songs of the last 30 days.
        val monthAgo = today.minusDays(30)
        val frequent = history.filter { it.lastDay >= monthAgo && it.plays >= 2 }.sortedByDescending { it.plays * 60_000L + it.milliseconds / 4 }.map { it.track }.distinctBy(::key).take(50)

        // Forgotten favourites: in the collection or once loved, but not heard for more than 30 days.
        val forgotten = buildList {
            addAll(history.filter { it.plays >= 2 && it.lastDay < monthAgo }.sortedByDescending { it.plays }.map { it.track })
            addAll(collection.filter { track -> stats(track)?.all { it.lastDay < monthAgo } ?: false })
        }.distinctBy(::key).take(50)

        // New in the collection: playlists created in the last 30 days and the latest likes.
        val since = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
        val fresh = (library.playlists.filter { it.createdAt >= since }.flatMap { it.tracks } + library.likes.take(30)).distinctBy(::key).take(60)

        val daily = daily(collection, history, today)
        return listOfNotNull(
            SmartMix(MixKind.DAILY, "Микс дня", "Любимые исполнители и то, что давно не звучало", daily).takeIf { it.tracks.size >= 5 },
            SmartMix(MixKind.FREQUENT, "Часто слушаю", "Самое любимое за месяц", frequent).takeIf { it.tracks.size >= 3 },
            SmartMix(MixKind.FORGOTTEN, "Давно не слушал", "Любимое, что не звучало больше месяца", forgotten).takeIf { it.tracks.size >= 3 },
            SmartMix(MixKind.NEW, "Новое в коллекции", "Свежие плейлисты и последние лайки", fresh).takeIf { it.tracks.size >= 3 },
        )
    }

    /**
     * The same list all day, a new one tomorrow: songs of your top artists, mixed with forgotten favourites,
     * with artists alternating so the same voice rarely plays twice in a row.
     */
    fun daily(collection: List<Track>, history: List<TrackHistory>, today: LocalDate = LocalDate.now()): List<Track> {
        if (collection.isEmpty() && history.isEmpty()) return emptyList()
        val random = Random(today.toEpochDay())
        val recent = history.filter { it.lastDay >= today.minusDays(2) }.map { key(it.track) }.toSet()
        val artistWeight = history.groupBy { TrackMatcher.normalized(TrackMatcher.primaryArtist(it.track.artist)) }.mapValues { (_, items) -> items.sumOf { it.milliseconds } }
        val pool = (collection + history.map { it.track }).distinctBy(::key)
        val scored = pool.map { track ->
            val artist = TrackMatcher.normalized(TrackMatcher.primaryArtist(track.artist))
            val weight = (artistWeight[artist] ?: 0L).toDouble()
            val freshness = if (key(track) in recent) .25 else 1.0
            track to (Math.log1p(weight / 60_000.0) + 1.0) * freshness * (0.6 + random.nextDouble())
        }.sortedByDescending { it.second }.map { it.first }.take(40)
        // Alternate artists.
        val queue = scored.toMutableList(); val result = mutableListOf<Track>()
        while (queue.isNotEmpty() && result.size < 30) {
            val last = result.lastOrNull()?.let { TrackMatcher.normalized(TrackMatcher.primaryArtist(it.artist)) }
            val index = queue.indexOfFirst { TrackMatcher.normalized(TrackMatcher.primaryArtist(it.artist)) != last }.coerceAtLeast(0)
            result += queue.removeAt(index)
        }
        return result
    }
}
