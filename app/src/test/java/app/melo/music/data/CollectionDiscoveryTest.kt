package app.melo.music.data

import org.junit.Assert.*
import org.junit.Test

class CollectionDiscoveryTest {
    private val a = Track("a", "Natural", "Imagine Dragons")
    private val b = Track("b", "Afterglow", "North Coast")
    private val local = Track("local", "Local Song", "Phone", source = "local")
    private val library = Library(likes = listOf(a), history = listOf(b), playlists = listOf(
        Playlist(id="new",title="Evening",tracks=listOf(b),createdAt=200),
        Playlist(id="pinned",title="Night",tracks=listOf(a,local),createdAt=100,pinned=true)))
    @Test fun searchFindsArtistAndSongsAcrossPlaylistsLikesAndHistoryWithoutDuplicates() {
        assertEquals(listOf(a), library.collectionTracks("DRAGONS", CollectionFilter.ALL, emptySet()))
        assertEquals(listOf("pinned"), library.collectionPlaylists("natural", CollectionFilter.ALL, CollectionOrder.RECENT, emptySet()).map { it.id })
        assertEquals(3, library.collectionTracks("", CollectionFilter.ALL, emptySet()).size)
    }
    @Test fun offlineRequiresEveryTrackForPlaylistsButFiltersIndividualSongs() {
        assertTrue(library.collectionPlaylists("", CollectionFilter.DOWNLOADED, CollectionOrder.RECENT, emptySet()).isEmpty())
        assertEquals(listOf("pinned"), library.collectionPlaylists("", CollectionFilter.DOWNLOADED, CollectionOrder.RECENT, setOf("a")).map { it.id })
        assertEquals(listOf(local), library.collectionTracks("", CollectionFilter.DOWNLOADED, emptySet()))
    }
    @Test fun pinnedFilterExcludesHistoryAndSortingKeepsPinnedFirst() {
        assertEquals(listOf(a,local), library.collectionTracks("", CollectionFilter.PINNED, emptySet()))
        assertEquals("pinned", library.collectionPlaylists("", CollectionFilter.ALL, CollectionOrder.TITLE, emptySet()).first().id)
    }
}
