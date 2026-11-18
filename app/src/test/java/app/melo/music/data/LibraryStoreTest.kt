package app.melo.music.data

import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class LibraryStoreTest {
    private val context get() = ApplicationProvider.getApplicationContext<MeloApplication>()
    @Before fun reset() { context.filesDir.listFiles()?.filter { it.name.startsWith("library-v1") }?.forEach { it.delete() } }
    @Test fun likesAndPlaylistsSurviveReopeningAndDeduplicateTracks() {
        val track = Track("1", "Song", "Artist")
        val first = LibraryStore(context)
        first.toggleLike(track)
        first.putPlaylist(Playlist(id = "p", title = "My playlist"))
        first.addToPlaylist("p", track)
        first.addToPlaylist("p", track)
        val reopened = LibraryStore(context)
        assertEquals(listOf(track), reopened.library.value.likes)
        assertEquals(listOf(track), reopened.library.value.playlists.single().tracks)
        reopened.toggleLike(track)
        assertTrue(LibraryStore(context).library.value.likes.isEmpty())
    }
    @Test fun backupMergesWithoutLosingExistingLibrary() {
        val a = LibraryStore(context)
        a.toggleLike(Track("1", "First", "Artist"))
        val backup = a.export()
        a.toggleLike(Track("2", "Second", "Artist"))
        a.restore(backup)
        assertEquals(setOf("1", "2"), a.library.value.likes.map { it.id }.toSet())
    }
    @Test fun brokenFileFallsBackWithoutCrashing() {
        java.io.File(context.filesDir, "library-v1.json").writeText("{broken")
        assertEquals(Library(), LibraryStore(context).library.value)
    }
    @Test fun playlistEditsAndArtworkSurviveBackup() {
        val store = LibraryStore(context)
        val tracks = (1..3).map { Track("$it", "Song $it", "Artist") }
        store.putPlaylist(Playlist(id = "p", title = "Old", tracks = tracks))
        store.renamePlaylist("p", "New"); store.pinPlaylist("p"); store.moveTrack("p", "3", -1)
        store.enrichArtwork("3", "https://example.com/cover.jpg")
        val playlist = store.library.value.playlists.single()
        assertEquals("New", playlist.title); assertTrue(playlist.pinned); assertEquals(listOf("1", "3", "2"), playlist.tracks.map { it.id })
        store.restore(store.export())
        assertEquals(playlist, LibraryStore(context).library.value.playlists.single())
        assertEquals("https://example.com/cover.jpg", playlist.tracks[1].artwork)
    }
    @Test fun malformedBackupCannotReplaceHealthyLibrary() {
        val store = LibraryStore(context)
        store.toggleLike(Track("1", "Keep me", "Artist"))
        listOf("{\"likes\":null,\"playlists\":[],\"history\":[]}", "{\"likes\":[{\"id\":\"bad\",\"title\":null}],\"playlists\":[],\"history\":[]}").forEach { json ->
            assertTrue(runCatching { store.restore(json) }.isFailure)
            assertEquals("Keep me", store.library.value.likes.single().title)
        }
    }
    @Test fun largeImportedPlaylistSurvivesReopeningAndBackupWithout100TrackLimit() {
        val store = LibraryStore(context)
        val tracks = (0 until 1237).map { Track("spotify:track:$it", "Song $it", "Artist", source = "spotify") }
        store.putPlaylist(Playlist(id = "large", title = "Large playlist", tracks = tracks))
        assertEquals(tracks, LibraryStore(context).library.value.playlists.single().tracks)
        val backup = store.export()
        store.deletePlaylist("large"); store.restore(backup)
        assertEquals(tracks, LibraryStore(context).library.value.playlists.single().tracks)
    }
}
