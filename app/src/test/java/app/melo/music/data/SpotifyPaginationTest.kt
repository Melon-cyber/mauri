package app.melo.music.data

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.IOException

class SpotifyPaginationTest {
    private val id = "1234567890123456789012"
    private val url = "https://open.spotify.com/playlist/$id"
    private fun embed(count: Int = 100, token: Boolean = true): String {
        val rows = (0 until count).joinToString(",") { """{"uri":"spotify:track:$it","title":"Song $it","subtitle":"Artist"}""" }
        return """<script id="__NEXT_DATA__">{"props":{"pageProps":{"state":{"settings":{"session":{"accessToken":"${if (token) "anonymous-test-token" else ""}"}},"data":{"entity":{"title":"Large playlist","trackList":[$rows]}}}}}}</script>"""
    }
    private fun row(index: Int): JsonObject = JsonParser.parseString("""{"itemV2":{"data":{"__typename":"Track","uri":"spotify:track:$index","name":"Song $index","artists":{"items":[{"profile":{"name":"Artist"}}]},"duration":{"totalMilliseconds":180000},"albumOfTrack":{"coverArt":{"sources":[{"url":"https://i.scdn.co/cover-$index.jpg"}]}}}}}""").asJsonObject
    private fun page(offset: Int, total: Int, next: Int? = (offset + 50).takeIf { it < total }): JsonObject {
        val rows = JsonArray().apply { (offset until minOf(offset + 50, total)).forEach { add(row(it)) } }
        return JsonObject().apply { add("data", JsonObject().apply { add("playlistV2", JsonObject().apply {
            addProperty("__typename", "Playlist"); addProperty("uri", "spotify:playlist:$id"); addProperty("name", "Large playlist")
            add("content", JsonObject().apply {
                addProperty("totalCount", total); add("items", rows)
                add("pagingInfo", JsonObject().apply { if (next != null) addProperty("nextOffset", next) else add("nextOffset", com.google.gson.JsonNull.INSTANCE) })
            })
        }) }) }
    }
    private fun source(html: String = embed(), load: (Int) -> JsonObject) = object : SpotifyPageSource {
        override fun embed(id: String) = html
        override fun page(id: String, offset: Int, token: String): JsonObject {
            assertEquals("anonymous-test-token", token)
            return load(offset)
        }
    }
    @Test fun importsAll237TracksInOrderAndReportsVerifiedTotal() {
        val offsets = mutableListOf<Int>(); val progress = mutableListOf<String>()
        val result = fetchSpotifyPages(url, source { offset -> offsets += offset; page(offset, 237) }, progress::add)
        assertTrue(result.summary, result.complete)
        assertEquals(listOf(0, 50, 100, 150, 200), offsets)
        assertEquals(237, result.receivedItems); assertEquals(237, result.expectedItems)
        assertEquals((0 until 237).map { "spotify:track:$it" }, result.playlist.tracks.map { it.id })
        assertEquals("https://i.scdn.co/cover-236.jpg", result.playlist.tracks.last().artwork)
        assertEquals(180000, result.playlist.tracks.last().durationMs.toInt())
        assertTrue(progress.last().contains("237 из 237"))
        assertTrue(result.playlist.importNote.contains("полностью"))
        assertTrue(result.playlist.tracks.all { it.url.isEmpty() })
    }
    @Test fun paginatesBeyondOneThousandWithoutApplicationTruncation() {
        val result = fetchSpotifyPages(url, source { page(it, 1237) }, {})
        assertTrue(result.complete); assertEquals(1237, result.playlist.tracks.size)
    }
    @Test fun retainsLongestContiguousResultButRequiresConfirmationAfterNetworkFailure() {
        val result = fetchSpotifyPages(url, source { if (it == 150) throw IOException("Network lost") else page(it, 237) }, {})
        assertFalse(result.complete); assertEquals(150, result.receivedItems); assertEquals(237, result.expectedItems)
        assertEquals(150, result.playlist.tracks.size)
        assertTrue(result.playlist.importNote.startsWith("Неполный импорт"))
        assertNotEquals(result.playlist.id, result.partialCopy().id)
    }
    @Test fun rateLimitStopsImmediatelyWithoutAutomaticRetry() {
        var calls = 0
        val result = fetchSpotifyPages(url, source { calls++; throw RateLimitException(120) }, {})
        assertEquals(1, calls); assertFalse(result.complete); assertNull(result.expectedItems)
        assertEquals(100, result.playlist.tracks.size); assertTrue(result.reason.contains("120"))
    }
    @Test fun missingAnonymousSessionDoesNotPretendEmbedIsComplete() {
        val result = fetchSpotifyPages(url, source(embed(57, false)) { error("Must not request pages") }, {})
        assertFalse(result.complete); assertNull(result.expectedItems); assertEquals(57, result.receivedItems)
    }
    @Test fun detectsPrematureEndAndRepeatedOrSkippedOffsets() {
        listOf<Int?>(null, 0, 25, 100).forEach { next ->
            var calls = 0
            val result = fetchSpotifyPages(url, source { calls++; page(it, 237, next) }, {})
            assertFalse("next=$next", result.complete); assertEquals(1, calls)
            assertEquals(237, result.expectedItems)
        }
    }
    @Test fun detectsPlaylistChangingWhileItIsBeingRead() {
        val result = fetchSpotifyPages(url, source { page(it, if (it == 0) 237 else 238) }, {})
        assertFalse(result.complete); assertTrue(result.reason.contains("изменился"))
    }
    @Test fun countsUnavailableEntriesAndDuplicatesSeparatelyFromMissingPages() {
        val response = page(0, 4)
        val rows = response.getAsJsonObject("data").getAsJsonObject("playlistV2").getAsJsonObject("content").getAsJsonArray("items")
        rows.set(1, row(0))
        rows.set(2, JsonParser.parseString("""{"itemV2":{"data":{"__typename":"Episode"}}}"""))
        rows.set(3, JsonParser.parseString("""{"itemV2":{"data":null}}"""))
        val result = fetchSpotifyPages(url, source(embed(1)) { response }, {})
        assertTrue(result.summary, result.complete); assertEquals(4, result.receivedItems)
        assertEquals(1, result.playlist.tracks.size); assertEquals(1, result.duplicateItems); assertEquals(2, result.skippedItems)
        assertTrue(result.summary.contains("пропущено: 2"))
    }
    @Test fun rejectsErrorsMalformedTrackAndWrongPlaylistWithoutClaimingCompleteness() {
        val badTrack = page(0, 1).apply {
            getAsJsonObject("data").getAsJsonObject("playlistV2").getAsJsonObject("content").getAsJsonArray("items")[0].asJsonObject.getAsJsonObject("itemV2").getAsJsonObject("data").remove("name")
        }
        val wrongPlaylist = page(0, 1).apply { getAsJsonObject("data").getAsJsonObject("playlistV2").addProperty("uri", "spotify:playlist:other") }
        listOf(JsonParser.parseString("""{"errors":[{"message":"PersistedQueryNotFound"}]}""").asJsonObject, badTrack, wrongPlaylist).forEach {
            assertFalse(fetchSpotifyPages(url, source(embed(1)) { _ -> it }, {}).complete)
        }
    }
    @Test fun cancellationIsPropagatedInsteadOfBeingReportedAsPartialSuccess() {
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            fetchSpotifyPages(url, source { throw kotlinx.coroutines.CancellationException() }, {})
        }
    }
    @Test fun csvImportsMoreThan100SongsAndPreservesTheLastRow() {
        val csv = "Track URI,Track Name,Artist Name(s)\n" + (0 until 237).joinToString("\n") { "spotify:track:$it,Song $it,Artist" }
        val result = SpotifyImporter.parseCsv(csv)
        assertEquals(237, result.tracks.size); assertEquals("Song 236", result.tracks.last().title)
    }
    @Test fun httpContractUsesOnlySpotifyHostAndStopsOnRateLimit() {
        var count = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            count++
            val request = chain.request()
            assertEquals("api-partner.spotify.com", request.url.host)
            assertEquals("POST", request.method); assertEquals("Bearer session", request.header("Authorization"))
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            val json = JsonParser.parseString(buffer.readUtf8()).asJsonObject
            val vars = json.getAsJsonObject("variables")
            assertEquals(100, vars.get("offset").asInt); assertEquals(50, vars.get("limit").asInt)
            assertEquals("spotify:playlist:$id", vars.get("uri").asString)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(429).message("Rate limited")
                .header("Retry-After", "90").body("{}".toResponseBody()).build()
        }.build()
        val error = assertThrows(RateLimitException::class.java) { SpotifyHttpPages(client).page(id, 100, "session") }
        assertEquals(90L, error.retryAfterSeconds); assertEquals(1, count)
    }
    @Test fun liveLargePlaylistImportsPast100AndVerifiesTotal() {
        assumeTrue("Explicit opt-in network check", System.getProperty("melo.live") == "true")
        val result = SpotifyImporter.fetch("https://open.spotify.com/playlist/37i9dQZF1DX4WYpdgoIcn6")
        println("Large Spotify import: ${result.summary}")
        assertTrue(result.summary, result.complete)
        assertTrue("Fixture playlist must still have >100 positions", result.receivedItems > 100)
        assertEquals(result.expectedItems, result.receivedItems)
        assertTrue(result.playlist.tracks.size > 100)
        java.io.File("../artifacts/mauri-0.3.1/large-playlist-check.txt").apply { parentFile?.mkdirs() }
            .writeText(result.summary + "\n" + result.playlist.tracks.mapIndexed { i, t -> "${i + 1}\t${t.id}\t${t.artist}\t${t.title}" }.joinToString("\n"))
    }
}
