package app.melo.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Lyrics lookup with several fallbacks:
 * 1. LRCLIB by clean title + main artist, then free-text search, then title only (checked against the artist);
 *    the same again for the YouTube Music recording that is really playing;
 * 2. YouTube Music's own lyrics tab (plain text) for the playing recording.
 * A candidate is accepted only if title and artist really match; synced lines are used only when the length fits,
 * otherwise the text is shown without timing instead of with wrong timing.
 */
class LyricsRepository(context: Context, private val music: MusicRepository? = null) {
    private val cache = File(context.cacheDir, "lyrics").apply { mkdirs() }
    /** Lyrics of downloaded songs: kept in app storage without expiry, so they open without network. */
    private val offline = File(context.filesDir, "lyrics-offline").apply { mkdirs() }
    private val gson = Gson()
    @Volatile private var retryAfter = 0L
    private val userAgent = "Mauri/${app.melo.music.BuildConfig.VERSION_NAME} (Android personal music player)"

    @Synchronized fun get(track: Track): Lyrics {
        if (track.isLocal && (track.title.isBlank() || track.artist == "На устройстве")) return Lyrics()
        val file = cacheFile(track)
        File(offline, file.name).takeIf { it.exists() }?.let(::parseStored)?.takeIf { !it.isEmptyResult() }?.let { return it }
        readCache(file)?.let { return it }

        val errors = mutableListOf<Exception>()
        val playing = if (track.isLocal) null else runCatching { music?.match(track) }.getOrNull()
        val wanted = listOfNotNull(TrackMatcher.canonical(track), playing?.let(TrackMatcher::canonical))
            .distinctBy { TrackMatcher.normalized(TrackMatcher.cleanTitle(it.title)) + "|" + TrackMatcher.normalized(TrackMatcher.primaryArtist(it.artist)) }
        // Timing must fit the audio that really plays, so compare with the played recording's length first.
        val reference = wanted.first().copy(durationMs = playing?.durationMs?.takeIf { it > 0 } ?: track.durationMs)
        val videoId = (playing ?: track).takeIf { it.source == "youtube" }?.id

        // 1. YouTube Music timing is made for exactly the recording we play, including long intros.
        var youtubePlain: Lyrics? = null
        if (videoId != null) {
            try {
                val timed = youtubeMusicTimedLyrics(videoId)
                if (timed != null && timed.lines.isNotEmpty() && plausibleSync(timed.lines, reference.durationMs)) {
                    runCatching { file.writeText(gson.toJson(timed)) }
                    return timed
                }
                youtubePlain = timed?.takeIf { it.plain.isNotBlank() }?.copy(lines = emptyList())
            } catch (e: InterruptedIOException) { throw e } catch (e: Exception) { errors += e }
        }

        val pool = linkedMapOf<Long, JsonObject>()
        var result: Lyrics? = null
        lrclib@ for (variant in wanted) {
            val title = TrackMatcher.cleanTitle(variant.title)
            val artist = TrackMatcher.primaryArtist(variant.artist)
            val queries = listOf(
                mapOf("track_name" to title, "artist_name" to artist),
                mapOf("q" to "$artist $title"),
                mapOf("track_name" to title),
            )
            for (query in queries) {
                try { lrclibSearch(query).forEach { item -> item.long("id")?.let { pool.putIfAbsent(it, item) } } }
                catch (e: InterruptedIOException) { throw e } catch (e: RateLimitException) { errors += e; break@lrclib } catch (e: Exception) { errors += e }
                val chosen = choose(reference, wanted, pool.values)
                if (chosen != null && chosen.lines.isNotEmpty()) { result = chosen; break@lrclib }
                if (chosen != null) result = chosen
            }
        }
        var found: Lyrics = result ?: youtubePlain ?: Lyrics()
        if (found.lines.isEmpty() && found.plain.isBlank()) {
            if (videoId != null) {
                try {
                    val fromYoutube = youtubeMusicLyrics(videoId)
                    if (fromYoutube != null) found = fromYoutube
                } catch (e: InterruptedIOException) { throw e } catch (e: Exception) { errors += e }
            }
        }
        val empty = found.lines.isEmpty() && found.plain.isBlank() && !found.instrumental
        if (empty && errors.isNotEmpty()) {
            // Nothing was found because a source failed: report it and do not remember the miss.
            throw errors.firstOrNull { it is RateLimitException } ?: errors.last()
        }
        runCatching { file.writeText(gson.toJson(found)) }
        return found
    }

    private fun cacheFile(track: Track): File {
        val key = MessageDigest.getInstance("SHA-256").digest("v4|${track.id}|${track.artist}|${track.title}|${track.durationMs}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cache, "$key.json")
    }

    /** Drops saved lyrics, e.g. after the listener picked another recording with other timing. */
    @Synchronized fun forget(track: Track) {
        val file = cacheFile(track)
        val hadOffline = File(offline, file.name).delete()
        file.delete()
        if (hadOffline) runCatching { keepOffline(track) }
    }

    /** Saves the lyrics of a downloaded song for listening without network. Quietly does nothing if none are found. */
    @Synchronized fun keepOffline(track: Track) {
        val lyrics = get(track)
        if (!lyrics.isEmptyResult()) File(offline, cacheFile(track).name).writeText(gson.toJson(lyrics))
    }

    /** Removes the offline copy when the download is deleted. */
    @Synchronized fun dropOffline(track: Track) { File(offline, cacheFile(track).name).delete() }

    private fun Lyrics.isEmptyResult() = lines.isEmpty() && plain.isBlank() && !instrumental

    private fun parseStored(file: File): Lyrics? {
        val cached = runCatching { gson.fromJson(file.readText(), Lyrics::class.java) }.getOrNull() ?: return null
        @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
        return Lyrics(cached.lines?.filterNotNull()?.filter { it.text != null } ?: emptyList(), cached.plain ?: "", cached.instrumental, cached.source ?: "LRCLIB",
            cached.alignable?.filterNotNull()?.filter { it.text != null } ?: emptyList(), cached.expectedDurationMs, cached.playedDurationMs)
    }

    private fun readCache(file: File): Lyrics? {
        if (!file.exists()) return null
        val safe = parseStored(file) ?: return null
        val empty = safe.lines.isEmpty() && safe.plain.isBlank() && !safe.instrumental
        val age = System.currentTimeMillis() - file.lastModified()
        // Found lyrics stay for 60 days; a miss is retried after 12 hours because sources keep growing.
        return safe.takeIf { age < if (empty) 12 * 60 * 60_000L else 60L * 24 * 60 * 60_000L }
    }

    private fun lrclibSearch(params: Map<String, String>): List<JsonObject> {
        if (System.currentTimeMillis() < retryAfter) throw RateLimitException((retryAfter - System.currentTimeMillis()) / 1000 + 1)
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder().apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val data = try { withRetry { Network.text(url.toString(), userAgent) } } catch (e: RateLimitException) {
            retryAfter = System.currentTimeMillis() + e.retryAfterSeconds * 1000
            throw e
        }
        return JsonParser.parseString(data).asJsonArray.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
    }

    private fun choose(reference: Track, wanted: List<Track>, items: Collection<JsonObject>): Lyrics? {
        data class Candidate(val item: JsonObject, val score: Double, val durationGap: Double?)
        val accepted = items.mapNotNull { item ->
            val title = item.string("trackName")
            val artist = item.string("artistName")
            val titleScore = wanted.maxOf { TrackMatcher.titleSimilarity(it.title, title) }
            val artistScore = wanted.maxOf { maxOf(TrackMatcher.artistSimilarity(it.artist, artist), TrackMatcher.artistSimilarity(artist, it.artist)) }
            if (titleScore < 0.7 || artistScore < 0.5) return@mapNotNull null
            val seconds = item.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble
            val gap = if (reference.durationMs > 0 && seconds != null && seconds > 0) abs(seconds * 1000 - reference.durationMs) / 1000.0 else null
            val timing = gap?.let { (1.0 - it / 20.0).coerceAtLeast(0.0) } ?: 0.5
            Candidate(item, titleScore * 0.45 + artistScore * 0.35 + timing * 0.20, gap)
        }
        if (accepted.isEmpty()) return null
        val synced = accepted.filter { it.item.string("syncedLyrics").isNotBlank() && (it.durationGap == null || it.durationGap <= 6.0) }
            .filter { plausibleSync(parseLrc(it.item.string("syncedLyrics")), reference.durationMs) }
            .maxByOrNull { it.score }
        if (synced != null) {
            val lines = parseLrc(synced.item.string("syncedLyrics"))
            if (lines.isNotEmpty()) return Lyrics(lines, synced.item.string("plainLyrics").ifBlank { lines.joinToString("\n") { it.text } }, false, "LRCLIB")
        }
        val plain = accepted.filter { it.item.string("plainLyrics").isNotBlank() || it.item.string("syncedLyrics").isNotBlank() }.maxByOrNull { it.score }
        if (plain != null) {
            val text = plain.item.string("plainLyrics").ifBlank { parseLrc(plain.item.string("syncedLyrics")).joinToString("\n") { it.text } }
            // Timed text exists, but for a recording of another length: keep it ready for one-tap alignment.
            val other = accepted.filter { it.item.string("syncedLyrics").isNotBlank() }
                .mapNotNull { c -> val seconds = c.item.get("duration")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 0.0
                    parseLrc(c.item.string("syncedLyrics")).takeIf { plausibleSync(it, (seconds * 1000).toLong()) }?.let { Triple(c, it, (seconds * 1000).toLong()) } }
                .maxByOrNull { it.first.score }
            if (text.isNotBlank()) return Lyrics(plain = text, source = "LRCLIB", alignable = other?.second.orEmpty(),
                expectedDurationMs = other?.third ?: 0, playedDurationMs = reference.durationMs)
        }
        return if (accepted.maxBy { it.score }.item.get("instrumental")?.takeIf { it.isJsonPrimitive }?.asBoolean == true) Lyrics(instrumental = true) else null
    }

    /** Line-by-line timed lyrics the YouTube Music app shows for a recording; plain text when it has no timing; null when none. */
    private fun youtubeMusicTimedLyrics(videoId: String): Lyrics? {
        val context = JsonObject().apply {
            add("client", JsonObject().apply {
                addProperty("clientName", "ANDROID_MUSIC"); addProperty("clientVersion", ANDROID_MUSIC_VERSION)
                addProperty("androidSdkVersion", 34); addProperty("hl", "en"); addProperty("gl", "US")
            })
        }
        val agent = "com.google.android.apps.youtube.music/$ANDROID_MUSIC_VERSION (Linux; U; Android 14) gzip"
        val next = youtubeMusic("next", JsonObject().apply { add("context", context); addProperty("videoId", videoId) }, agent)
        val browseId = find(next, "browseEndpoint").mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject?.string("browseId")?.takeIf { id -> id.startsWith("MPLYt") } }.firstOrNull() ?: return null
        val page = youtubeMusic("browse", JsonObject().apply { add("context", context); addProperty("browseId", browseId) }, agent)
        val credit = find(page, "sourceMessage").mapNotNull { it.takeIf(JsonElement::isJsonPrimitive)?.asString }.firstOrNull().orEmpty().removePrefix("Source:").trim()
        val source = if (credit.isNotBlank()) "YouTube Music ($credit)" else "YouTube Music"
        val timed = find(page, "timedLyricsData").firstOrNull { it.isJsonArray }?.asJsonArray
        if (timed != null) {
            val lines = timed.mapNotNull { entry ->
                val item = entry.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@mapNotNull null
                val start = item.getAsJsonObject("cueRange")?.string("startTimeMilliseconds")?.toLongOrNull() ?: return@mapNotNull null
                val text = item.string("lyricLine").trim().let { line -> if (line.all { c -> c == '\u266A' || c.isWhitespace() }) "" else line }
                LyricLine(start, text)
            }.sortedBy { it.timeMs }
            if (lines.any { it.text.isNotBlank() }) return Lyrics(lines, lines.filter { it.text.isNotBlank() }.joinToString("\n") { it.text }, false, source)
        }
        val shelf = find(page, "musicDescriptionShelfRenderer").firstOrNull()?.takeIf(JsonElement::isJsonObject)?.asJsonObject
        val text = shelf?.getAsJsonObject("description")?.getAsJsonArray("runs")?.joinToString("") { it.asJsonObject.string("text") }.orEmpty().trim()
        return if (text.isNotBlank()) Lyrics(plain = text, source = source) else null
    }

    /**
     * Rejects broken "synced" files: lines that run past the end of the song, or machine-made timing
     * where the first line starts at 0:00 and the rest follow at one fixed step.
     */
    private fun plausibleSync(lines: List<LyricLine>, durationMs: Long): Boolean {
        val sung = lines.filter { it.text.isNotBlank() }
        if (sung.size < 2) return false
        if (durationMs > 0 && sung.last().timeMs > durationMs + 2_000) return false
        val gaps = sung.zipWithNext { a, b -> b.timeMs - a.timeMs }
        if (gaps.any { it < 0 }) return false
        val common = gaps.groupingBy { it / 100 }.eachCount().maxOf { it.value }
        val evenlySpaced = gaps.size >= 8 && common >= gaps.size * 0.6
        return !(sung.first().timeMs == 0L && evenlySpaced)
    }

    /** Plain lyrics from the "Lyrics" tab YouTube Music shows for a recording. */
    private fun youtubeMusicLyrics(videoId: String): Lyrics? {
        val context = JsonObject().apply {
            add("client", JsonObject().apply {
                addProperty("clientName", "WEB_REMIX"); addProperty("clientVersion", "1.20251110.01.00")
                addProperty("hl", "en"); addProperty("gl", "US")
            })
        }
        val next = youtubeMusic("next", JsonObject().apply { add("context", context); addProperty("videoId", videoId); addProperty("isAudioOnly", true) })
        val browseId = find(next, "browseEndpoint").mapNotNull { it.asJsonObject.string("browseId").takeIf { id -> id.startsWith("MPLYt") } }.firstOrNull() ?: return null
        val page = youtubeMusic("browse", JsonObject().apply { add("context", context); addProperty("browseId", browseId) })
        val shelf = find(page, "musicDescriptionShelfRenderer").firstOrNull()?.asJsonObject ?: return null
        val text = shelf.getAsJsonObject("description")?.getAsJsonArray("runs")?.joinToString("") { it.asJsonObject.string("text") }.orEmpty().trim()
        if (text.isBlank()) return null
        val credit = shelf.getAsJsonObject("footer")?.getAsJsonArray("runs")?.joinToString("") { it.asJsonObject.string("text") }.orEmpty()
            .removePrefix("Source:").trim()
        return Lyrics(plain = text, source = if (credit.isNotBlank()) "YouTube Music ($credit)" else "YouTube Music")
    }

    private fun youtubeMusic(endpoint: String, body: JsonObject, agent: String = Network.USER_AGENT): JsonObject {
        val request = okhttp3.Request.Builder().url("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .header("User-Agent", agent).header("Origin", "https://music.youtube.com").header("Referer", "https://music.youtube.com/")
            .header("Cookie", "CONSENT=YES+cb; SOCS=CAI")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return withRetry {
            Network.client.newCall(request).execute().use { response ->
                if (response.code == 429) throw RateLimitException(60)
                if (!response.isSuccessful) throw IOException("YouTube Music ответил HTTP ${response.code}")
                JsonParser.parseString(response.body?.string() ?: throw IOException("Пустой ответ")).asJsonObject
            }
        }
    }

    private fun find(element: JsonElement, key: String): Sequence<JsonElement> = sequence {
        when {
            element.isJsonObject -> for ((name, value) in element.asJsonObject.entrySet()) {
                if (name == key) yield(value)
                yieldAll(find(value, key))
            }
            element.isJsonArray -> for (value in element.asJsonArray) yieldAll(find(value, key))
        }
    }

    private fun <T> withRetry(block: () -> T): T = try { block() } catch (e: InterruptedIOException) { throw e } catch (e: RateLimitException) { throw e } catch (e: IOException) {
        Thread.sleep(600)
        block()
    }

    private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    private fun JsonObject.long(name: String): Long? = get(name)?.takeIf { it.isJsonPrimitive }?.asLong

    companion object {
        private const val ANDROID_MUSIC_VERSION = "7.27.52"
        fun parseLrc(text: String): List<LyricLine> {
            val pattern = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
            return text.lineSequence().flatMap { line ->
                val matches = pattern.findAll(line).toList()
                val words = line.replace(pattern, "").trim()
                matches.map { m ->
                    val fraction = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0
                    LyricLine((m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + fraction, words)
                }
            }.sortedBy { it.timeMs }.toList()
        }
    }
}
