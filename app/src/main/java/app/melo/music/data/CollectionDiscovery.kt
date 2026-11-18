package app.melo.music.data

enum class CollectionFilter(val label: String) { ALL("Всё"), DOWNLOADED("Без сети"), PINNED("Закреплённые") }
enum class CollectionOrder(val label: String) { RECENT("Недавно добавленные"), TITLE("По названию"), SIZE("По числу песен") }

fun Track.matchesCollection(query: String): Boolean = title.contains(query.trim(), true) || artist.contains(query.trim(), true)
fun Library.collectionTracks(query: String, filter: CollectionFilter, ready: Set<String>, downloaded: List<Track> = emptyList()): List<Track> {
    val lists = if (filter == CollectionFilter.PINNED) playlists.filter { it.pinned } else playlists
    return (lists.flatMap { it.tracks } + if (filter == CollectionFilter.PINNED) emptyList() else likes + history + downloaded)
        .distinctBy { it.id }.filter { it.matchesCollection(query) && (filter != CollectionFilter.DOWNLOADED || it.isLocal || it.id in ready) }
}
fun Library.collectionPlaylists(query: String, filter: CollectionFilter, order: CollectionOrder, ready: Set<String>): List<Playlist> {
    val lists = playlists.filter { p ->
        (p.title.contains(query.trim(), true) || p.tracks.any { it.matchesCollection(query) }) &&
            when (filter) { CollectionFilter.ALL -> true; CollectionFilter.PINNED -> p.pinned
                CollectionFilter.DOWNLOADED -> p.tracks.isNotEmpty() && p.tracks.all { it.isLocal || it.id in ready } }
    }
    val comparator = when (order) { CollectionOrder.RECENT -> compareByDescending<Playlist> { it.createdAt }
        CollectionOrder.TITLE -> compareBy { it.title.lowercase(java.util.Locale.ROOT) }; CollectionOrder.SIZE -> compareByDescending { it.tracks.size } }
    return lists.sortedWith(compareByDescending<Playlist> { it.pinned }.then(comparator))
}
