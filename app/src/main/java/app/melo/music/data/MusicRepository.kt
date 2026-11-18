package app.melo.music.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds a playable YouTube Music recording for a track.
 *
 * Stability rules:
 * - several query shapes are tried (clean title, main artist, original text, music videos) before giving up;
 * - a chosen recording is remembered on disk, so the same song keeps the same audio after a restart;
 * - if a recording cannot be streamed (removed, region or age restricted), the next good candidate is used;
 * - network failures are reported as network failures and never cached as "not found".
 */
class MusicRepository(private val storeDir: File? = null) {
    private val streams = ConcurrentHashMap<String, AudioStream>()
    private val matches = ConcurrentHashMap<String, Track>()
    private val extractorLock = Any()
    private val gson = Gson()
    private val matchFile get() = storeDir?.let { File(it, "track-matches-v1.json") }

    init {
        val country = Locale.getDefault().country.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: "US"
        NewPipe.init(ExtractorDownloader(), Localization("en", "US"), ContentCountry(country))
        loadMatches()
    }

    fun search(query: String, page: org.schabi.newpipe.extractor.Page? = null, filter: String = SONGS): SearchPage {
        if (page != null) return searchSource(query, page, filter)
        val songs = searchSource(query, null, SONGS)
        if (songs.tracks.size >= 8) return songs
        // Some songs exist only as music videos or uploads; add them instead of showing an empty or tiny result.
        val videos = runCatching { searchSource(query, null, MUSIC_VIDEOS) }.getOrNull() ?: return songs
        val extra = videos.tracks.filter { it.durationMs == 0L || it.durationMs in 30_000..1_200_000 }
        val merged = (songs.tracks + extra).distinctBy { it.id }
        return if (org.schabi.newpipe.extractor.Page.isValid(songs.nextPage)) SearchPage(merged, songs.nextPage, SONGS)
        else SearchPage(merged, videos.nextPage, MUSIC_VIDEOS)
    }

    private fun searchSource(query: String, page: org.schabi.newpipe.extractor.Page?, filter: String): SearchPage = synchronized(extractorLock) {
        require(query.isNotBlank())
        val handler = ServiceList.YouTube.searchQHFactory.fromQuery(query.trim(), listOf(filter), "")
        if (page == null) {
            val info = SearchInfo.getInfo(ServiceList.YouTube, handler)
            SearchPage(info.relatedItems.filterIsInstance<StreamInfoItem>().map(::fromItem), info.nextPage, filter)
        } else {
            val more = SearchInfo.getMoreItems(ServiceList.YouTube, handler, page)
            SearchPage(more.items.filterIsInstance<StreamInfoItem>().map(::fromItem), more.nextPage, filter)
        }
    }

    /** Recording that will actually be played for [track]. */
    fun match(track: Track): Track {
        matches[track.id]?.let { return it }
        if (track.source != "spotify") return track
        val result = candidates(track).firstOrNull()
            ?: throw IllegalStateException("Не найдено надёжное совпадение: ${track.artist} — ${track.title}. Попробуйте поиск вручную.")
        remember(track, result)
        return result
    }

    /** Ranked candidates for a track; throws the last network error if no search could be completed at all. */
    private fun candidates(track: Track, excluded: Set<String> = emptySet()): List<Track> {
        val wanted = wantedFor(track)
        val clean = TrackMatcher.cleanTitle(wanted.title)
        val names = TrackMatcher.artistNames(wanted.artist).ifEmpty { listOf(wanted.artist) }
        val queries = linkedSetOf(
            "${names.take(2).joinToString(" ")} $clean",
            "${names.first()} $clean",
            "${wanted.artist} ${wanted.title}",
        ).map { it.trim() }.filter { it.isNotBlank() }
        val plan = queries.map { it to SONGS } + (queries.first() to MUSIC_VIDEOS) + ("${queries.first()} audio" to VIDEOS)
        val found = mutableListOf<Track>()
        var lastError: Exception? = null
        var completed = 0
        for ((query, filter) in plan) {
            try {
                found += withRetry { searchSource(query, null, filter).tracks }.filter { it.id !in excluded && it.id != track.id }
                completed++
            } catch (e: InterruptedIOException) { throw e } catch (e: RateLimitException) { lastError = e; break } catch (e: ReCaptchaException) { lastError = e; break } catch (e: Exception) { lastError = e }
            val ranked = TrackMatcher.ranked(wanted, found)
            if (ranked.isNotEmpty() && TrackMatcher.score(wanted, ranked.first()) >= 0.80) return ranked
        }
        val ranked = TrackMatcher.ranked(wanted, found)
        if (ranked.isEmpty() && completed == 0 && lastError != null) throw lastError
        return ranked
    }

    /** For a YouTube upload titled "Artist - Song", the channel name is not the artist; use the title parts instead. */
    private fun wantedFor(track: Track): Track = TrackMatcher.canonical(track)

    fun resolve(track: Track): AudioStream {
        val first = match(track)
        streams[first.id]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return it }
        val failure = try {
            return stream(first)
        } catch (e: InterruptedIOException) { throw e } catch (e: RateLimitException) { throw e } catch (e: ReCaptchaException) { throw e } catch (e: IOException) {
            throw e // Network problem: the recording itself may be fine, do not replace it.
        } catch (e: Exception) { e }
        // The recording exists but cannot be played (removed, blocked in the region, age restricted…): try another good version.
        val alternatives = runCatching { candidates(track, excluded = setOf(first.id)) }.getOrDefault(emptyList())
        for (alternative in alternatives.take(3)) {
            try {
                val audio = stream(alternative)
                remember(track, alternative)
                return audio
            } catch (e: InterruptedIOException) { throw e } catch (e: IOException) { throw e } catch (_: Exception) { }
        }
        throw IllegalStateException("Эта запись недоступна, и замену найти не удалось. Попробуйте другую версию через поиск.", failure)
    }

    private fun stream(playable: Track): AudioStream {
        streams[playable.id]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return it }
        val info = withRetry { synchronized(extractorLock) { StreamInfo.getInfo(ServiceList.YouTube, playable.url) } }
        val usable = info.audioStreams.filter { it.isUrl && it.content.isNotBlank() }
        val audio = (usable.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }.ifEmpty { usable })
            .maxByOrNull { it.averageBitrate } ?: error("Для этого трека нет доступного аудиопотока")
        return AudioStream(audio.content, audio.format?.mimeType ?: "audio/webm", audio.averageBitrate, System.currentTimeMillis() + 60 * 60_000)
            .also { streams[playable.id] = it }
    }

    /** One quiet retry for short network hiccups; rate limits and cancellations are passed through immediately. */
    private fun <T> withRetry(block: () -> T): T = try { block() } catch (e: InterruptedIOException) { throw e } catch (e: RateLimitException) { throw e } catch (e: IOException) {
        Thread.sleep(700)
        block()
    }

    /** Recording that plays now for this track, if it is known without a network request. */
    fun current(track: Track): Track? = matches[track.id] ?: track.takeIf { it.source == "youtube" }

    /** Versions the listener can pick from: the current recording first, then songs and music videos that fit. */
    fun versions(track: Track): List<Track> {
        val wanted = wantedFor(track)
        val clean = TrackMatcher.cleanTitle(wanted.title)
        val names = TrackMatcher.artistNames(wanted.artist).ifEmpty { listOf(wanted.artist) }
        val query = "${names.take(2).joinToString(" ")} $clean".trim()
        val found = mutableListOf<Track>()
        var failure: Exception? = null
        for (filter in listOf(SONGS, MUSIC_VIDEOS)) {
            try { found += withRetry { searchSource(query, null, filter).tracks } }
            catch (e: InterruptedIOException) { throw e } catch (e: Exception) { failure = e }
        }
        if (found.isEmpty() && failure != null) throw failure
        val ranked = found.filter { it.durationMs == 0L || it.durationMs in 30_000..1_200_000 }
            .distinctBy { it.id }
            .map { it to TrackMatcher.score(wanted, it) }
            .filter { it.second >= 0.30 }
            .sortedByDescending { it.second }
            .map { it.first }
        return (listOfNotNull(current(track)) + ranked).distinctBy { it.id }.take(10)
    }

    /** The listener's own choice wins over automatic matching and is remembered across restarts. */
    fun choose(track: Track, recording: Track) {
        streams.remove(matches[track.id]?.id ?: track.id)
        remember(track, recording)
    }

    fun invalidate(track: Track) {
        streams.remove(matches[track.id]?.id ?: track.id)
        streams.remove(track.id)
    }

    private fun remember(track: Track, playable: Track) {
        matches[track.id] = playable
        saveMatches()
    }

    private fun loadMatches() {
        val file = matchFile ?: return
        runCatching {
            if (!file.exists()) return
            val type = object : TypeToken<Map<String, Track>>() {}.type
            val stored: Map<String, Track> = gson.fromJson(file.readText(), type) ?: emptyMap()
            stored.forEach { (key, value) -> if (key.isNotBlank() && value.id.isNotBlank() && value.url.startsWith("https://")) matches[key] = value }
        }
    }

    @Synchronized private fun saveMatches() {
        val file = matchFile ?: return
        runCatching {
            val snapshot = matches.entries.toList().takeLast(MAX_STORED).associate { it.key to it.value }
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(gson.toJson(snapshot))
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
        }
    }

    fun radio(seed: Track): List<Track> = recommend(listOf(seed), seed, listOf(seed))
    fun recommend(context: List<Track>, anchor: Track, excluded: List<Track>): List<Track> {
        val catalog=linkedMapOf<String,List<Track>>()
        for(artist in RecommendationPolicy.artists(context,anchor)) {
            try { catalog[artist]=searchSource(artist, null, SONGS).tracks }
            catch(e: RateLimitException) { if(catalog.isEmpty()) throw e else break }
            catch(e: ReCaptchaException) { if(catalog.isEmpty()) throw e else break }
            catch(e: InterruptedIOException) { throw e }
            catch(_: Exception) { /* Do not fall back to general videos. */ }
        }
        return RecommendationPolicy.select(context,anchor,excluded,catalog)
    }
    private fun fromItem(item: StreamInfoItem): Track = Track(
        id = item.url.substringAfter("v=", item.url).substringBefore('&'),
        title = item.name,
        artist = item.uploaderName.removeSuffix(" - Topic"),
        durationMs = item.duration.coerceAtLeast(0) * 1000,
        artwork = item.thumbnails.maxByOrNull { it.width }?.url ?: "",
        url = item.url,
    )

    companion object {
        const val SONGS = YoutubeSearchQueryHandlerFactory.MUSIC_SONGS
        const val MUSIC_VIDEOS = YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS
        const val VIDEOS = YoutubeSearchQueryHandlerFactory.VIDEOS
        private const val MAX_STORED = 20_000
    }
}
