package app.melo.music.playback

import app.melo.music.MeloApplication
import app.melo.music.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class MediaItemsTest {
    @Test fun encodesMetadataAndRestoresUnicodeAndSpecialCharacters() {
        val track = Track("spotify:track:123", "Песня & \"ты\" #1", "Исполнитель / Artist", 180500, "https://example.com/a?b=c&d=e", source = "spotify")
        val media = track.toMediaItem()
        assertEquals(track, media.toTrack())
        assertEquals(track, media.localConfiguration!!.uri.toTrack())
        assertEquals("melo", media.localConfiguration!!.uri.scheme)
    }
    @Test fun localAudioKeepsAndroidDocumentUri() {
        val track = Track("local:1", "Local", "Artist", url = "content://media/document/audio%3A12", source = "local")
        assertEquals("content", track.toMediaItem().localConfiguration!!.uri.scheme)
        assertEquals(track, track.toMediaItem().toTrack())
    }
}
