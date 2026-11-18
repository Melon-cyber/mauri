package app.melo.music.data

/** Finds songs in the own collection for a spoken request like "Imagine Dragons Believer" or a playlist name. */
object Voice {
    fun matchLibrary(library: Library, query: String): List<Track> {
        val needle = TrackMatcher.normalized(query)
        if (needle.isBlank()) return emptyList()
        library.playlists.firstOrNull { TrackMatcher.normalized(it.title) == needle }?.let { return it.tracks }
        if (needle in setOf("избранное", "любимое", "favorites", "favourites")) return library.likes
        val words = needle.split(" ").filter { it.length > 1 }
        val all = (library.likes + library.playlists.flatMap { it.tracks } + library.history).distinctBy { it.id }
        val scored = all.map { track ->
            val text = TrackMatcher.normalized("${track.artist} ${track.title}")
            track to words.count { it in text }
        }.filter { it.second == words.size && words.isNotEmpty() }
        return scored.map { it.first }.take(50)
    }
}
