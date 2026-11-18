package app.melo.music.data

enum class TrackSort(val label: String) { ORIGINAL("Как в плейлисте"), TITLE("По названию"), ARTIST("По исполнителю"), DURATION("По длительности") }

fun List<Track>.filterAndSort(query: String, sort: TrackSort): List<Track> {
    val needle = TrackMatcher.normalized(query)
    val filtered = if (needle.isBlank()) this else filter { needle in TrackMatcher.normalized("${it.title} ${it.artist}") }
    return when (sort) {
        TrackSort.ORIGINAL -> filtered
        TrackSort.TITLE -> filtered.sortedBy { TrackMatcher.normalized(it.title) }
        TrackSort.ARTIST -> filtered.sortedBy { TrackMatcher.normalized(it.artist) }
        TrackSort.DURATION -> filtered.sortedBy { it.durationMs }
    }
}
