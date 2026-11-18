package app.melo.music.data

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class LiveSourcesTest {
    @Test fun songRadioReturnsRealDistinctRecommendations() {
        assumeTrue("Explicit opt-in network check", System.getProperty("melo.live") == "true")
        val music = MusicRepository()
        val seed = music.search("Imagine Dragons Natural").tracks.first()
        val radio = app.melo.music.playback.QueuePolicy.uniqueRecommendations(music.radio(seed), listOf(seed))
        println("Radio: ${seed.title} -> ${radio.size} recommendations")
        assertTrue("Related-song source returned no songs", radio.size >= 3)
        assertFalse(radio.any { it.id == seed.id })
        assertTrue(radio.all { it.url.startsWith("https://") })
        java.io.File("../artifacts/radio-check.txt").writeText("Seed: ${seed.title}\n" + radio.joinToString("\n") { "${it.artist} — ${it.title}\t${it.url}" })
    }
    @Test fun spotifyImportMatchesRealSongsAndProvidesAudioBytes() {
        assumeTrue("Explicit opt-in network check", System.getProperty("melo.live") == "true")
        val result = SpotifyImporter.fetch(requireNotNull(System.getProperty("melo.playlist")))
        assertTrue(result.summary, result.complete)
        val playlist = result.playlist
        assertTrue(playlist.tracks.isNotEmpty())
        println("Spotify import: ${playlist.tracks.size} tracks")
        val music = MusicRepository()
        val count = (System.getProperty("melo.matchCount") ?: "3").toInt().coerceIn(1, 500)
        val report = mutableListOf("title\tartist\tmatched_title\tmatched_artist\tscore\turl")
        val failed = mutableListOf<String>()
        playlist.tracks.take(count).forEachIndexed { index, track ->
            if (index > 0) Thread.sleep(500)
            val match = runCatching { music.match(track) }.getOrElse {
                failed.add(track.title)
                report.add("${track.title}\t${track.artist}\tNOT_FOUND\t\t0\t")
                return@forEachIndexed
            }
            val score = TrackMatcher.score(track, match)
            println("Match ${index + 1}: ${track.title} -> ${match.title}; score=${"%.2f".format(score)}")
            assertTrue(score >= 0.55)
            report.add(listOf(track.title, track.artist, match.title, match.artist, "%.3f".format(java.util.Locale.ROOT, score), match.url).joinToString("\t") { it.replace('\t', ' ').replace('\n', ' ') })
            if (index == 0) {
                val stream = music.resolve(track)
                val request = okhttp3.Request.Builder().url(stream.url).header("User-Agent", Network.USER_AGENT).header("Range", "bytes=0-1023").build()
                Network.client.newCall(request).execute().use { response ->
                    assertTrue("Audio HTTP ${response.code}", response.isSuccessful)
                    assertTrue(response.body!!.source().readByteArray(128).isNotEmpty())
                    println("Audio stream: HTTP ${response.code}, ${response.header("Content-Type")}, ${stream.bitrate} kbit/s")
                }
                val fullRequest = okhttp3.Request.Builder().url(stream.url).header("User-Agent", Network.USER_AGENT).header("Range", "bytes=0-").build()
                val audioFile = java.io.File("../artifacts/mauri-0.3/offline-audio-check.webm").apply { parentFile.mkdirs() }
                Network.client.newCall(fullRequest).execute().use { response ->
                    assertTrue("Full audio HTTP ${response.code}", response.isSuccessful)
                    val expected = response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull() ?: response.body!!.contentLength()
                    response.body!!.byteStream().use { input -> audioFile.outputStream().use { output ->
                        val buffer = ByteArray(8192); var total = 0L
                        while (true) { val bytes = input.read(buffer); if (bytes < 0) break; total += bytes; require(total <= 30_000_000) { "Audio exceeds test limit" }; output.write(buffer, 0, bytes) }
                    } }
                    if (expected > 0) assertEquals("Complete audio file", expected, audioFile.length())
                }
                assertTrue("Audio file must contain more than just a header", audioFile.length() > 128_000)
                audioFile.inputStream().use { assertEquals(128, it.read(ByteArray(128))) }
                println("Downloaded complete audio and read it locally: ${audioFile.length()} bytes")
            }
        }
        val directory = java.io.File("../artifacts").apply { mkdirs() }
        java.io.File(directory, "playlist-matches.tsv").writeText(report.joinToString("\n"))
        println("Matched ${playlist.tracks.take(count).size - failed.size}/${playlist.tracks.take(count).size}; unresolved ${failed.size}")
        assertTrue("Unresolved tracks: ${failed.joinToString()}", failed.isEmpty())
    }
}
