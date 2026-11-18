package app.melo.music.data

/** Uses only candidates supplied by the music song catalog. */
object RecommendationPolicy {
    private val unwanted = Regex("(?i)(reaction\\s+to|реакци[яи]\\s+на|разбор\\s+песни|обзор\\s+клипа|podcast\\s+episode|full\\s+album|полный\\s+альбом|\\b\\d+\\s+hours?\\b|интервью\\s+с)")
    fun artistNames(track: Track): List<String> = if(track.isLocal) emptyList() else track.artist.split(',', ';')
        .map { it.removeSuffix(" - Topic").trim() }.filter { it.length in 2..120 && TrackMatcher.normalized(it) !in setOf("various artists","unknown","неизвестный исполнитель", "youtube", "music") }
    fun artists(context: List<Track>, anchor: Track): List<String> {
        val counts=context.flatMap(::artistNames).groupingBy { TrackMatcher.normalized(it) }.eachCount()
        val ordered=context.flatMap(::artistNames).distinctBy { TrackMatcher.normalized(it) }.sortedByDescending { counts[TrackMatcher.normalized(it)] ?: 0 }
        return (artistNames(anchor)+ordered).distinctBy { TrackMatcher.normalized(it) }.take(4)
    }
    private fun artistMatches(track: Track, artist: String): Boolean {
        val wanted=TrackMatcher.normalized(artist)
        return artistNames(track).any { TrackMatcher.normalized(it)==wanted }
    }
    private fun keys(track: Track): Set<String> = artistNames(track).map { artist ->
        val name=TrackMatcher.normalized(artist)
        name+"|"+TrackMatcher.normalized(track.title).removePrefix("$name ").removeSuffix(" $name")
    }.toSet()
    fun select(context: List<Track>, anchor: Track, excluded: List<Track>, catalog: Map<String,List<Track>>): List<Track> {
        val seenIds=excluded.map { it.id }.toMutableSet()
        val seenKeys=excluded.flatMap { keys(it) }.toMutableSet()
        val pools=artists(context,anchor).map { artist -> catalog[artist].orEmpty().filter { candidate ->
            candidate.source=="youtube" && candidate.url.startsWith("https://") && candidate.durationMs in 30_000..1_200_000 &&
                artistMatches(candidate,artist) && !unwanted.containsMatchIn(candidate.title)
        }.toMutableList() }
        val result=mutableListOf<Track>()
        while(result.size<20 && pools.any { it.isNotEmpty() }) {
            pools.forEach { pool ->
                while(pool.isNotEmpty()) {
                    val candidate=pool.removeAt(0)
                    val candidateKeys=keys(candidate)
                    if(candidate.id !in seenIds && candidateKeys.none { it in seenKeys }) { result+=candidate;seenIds+=candidate.id;seenKeys+=candidateKeys;break }
                }
            }
        }
        return result.take(20)
    }
}
