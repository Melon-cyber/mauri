package app.melo.music.data

import org.junit.Assert.*
import org.junit.Test

class SpotifyImporterTest {
    @Test fun parsesPlaylistMetadataWithoutTreatingPreviewAsFullAudio() {
        val html = """<script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":{"state":{"data":{"entity":{"title":"My playlist","coverArt":{"sources":[{"url":"https://i.scdn.co/cover.jpg"}]},"trackList":[{"uri":"spotify:track:abc","title":"Natural","subtitle":"Imagine Dragons","duration":189466,"audioPreview":{"url":"https://preview.invalid/30seconds.mp3"}},{"uri":"spotify:episode:skip","title":"Podcast"}]}}}}}}</script>"""
        val playlist = SpotifyImporter.parseEmbed(html, "https://open.spotify.com/playlist/1234567890123456789012")
        assertEquals("My playlist", playlist.title)
        assertEquals(1, playlist.tracks.size)
        assertEquals("Imagine Dragons", playlist.tracks.single().artist)
        assertEquals(189466L, playlist.tracks.single().durationMs)
        assertEquals("spotify", playlist.tracks.single().source)
        assertTrue(playlist.tracks.single().url.isEmpty())
        assertTrue(playlist.importNote.contains("часть"))
    }
    @Test fun acceptsSharedTextAndLocalizedSpotifyUrls() {
        assertEquals("https://open.spotify.com/playlist/1234567890123456789012", SpotifyImporter.canonicalUrl("Listen https://open.spotify.com/intl-ru/playlist/1234567890123456789012?si=abc"))
        assertEquals("https://open.spotify.com/playlist/1234567890123456789012", SpotifyImporter.canonicalUrl("spotify:playlist:1234567890123456789012"))
    }
    @Test fun rejectsNonSpotifyAndNonPlaylistLinks() {
        listOf("https://open.spotify.com.evil.example/playlist/1234567890123456789012", "https://open.spotify.com/track/1234567890123456789012", "file:///etc/passwd", "https://127.0.0.1/playlist/1234567890123456789012").forEach { input ->
            assertTrue(input, runCatching { SpotifyImporter.canonicalUrl(input) }.isFailure)
        }
    }
    @Test fun csvPreservesQuotesCommasNewlinesAndFullList() {
        val csv = "Track URI,Track Name,Artist Name(s),Duration (ms)\r\nspotify:track:a,\"Hello, \"\"world\"\"\",Artist,123000\r\nspotify:track:b,\"A song\nwith a newline\",Other,150000\r\n"
        val result = SpotifyImporter.parseCsv(csv)
        assertEquals(2, result.tracks.size)
        assertEquals("Hello, \"world\"", result.tracks.first().title)
        assertEquals("A song\nwith a newline", result.tracks[1].title)
        assertEquals(123000L, result.tracks.first().durationMs)
    }
    @Test fun csvHandlesBomSemicolonsAndDuplicates() {
        val csv = "\uFEFFTrack Name;Artist Name(s)\nNatural;Imagine Dragons\nNatural;Imagine Dragons\n"
        assertEquals(1, SpotifyImporter.parseCsv(csv).tracks.size)
    }
    @Test(expected = IllegalArgumentException::class) fun csvRejectsBrokenQuotes() {
        SpotifyImporter.parseCsv("Track Name,Artist\n\"unfinished,Artist")
    }
}
