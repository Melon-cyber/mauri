package app.melo.music.data

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class LibraryStore(context: Context) {
    private val gson = Gson()
    private val file = AtomicFile(File(context.filesDir, "library-v1.json"))
    private val mutable = MutableStateFlow(read())
    val library = mutable.asStateFlow()

    private fun read(): Library = runCatching {
        file.openRead().bufferedReader().use { decode(it.readText()) }
    }.getOrDefault(Library())

    @Synchronized private fun update(change: (Library) -> Library) {
        val next = change(mutable.value)
        val stream = file.startWrite()
        try {
            stream.write(gson.toJson(next).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
            mutable.value = next
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun toggleLike(track: Track) = update { current ->
        current.copy(likes = if (current.likes.any { it.id == track.id }) current.likes.filterNot { it.id == track.id } else listOf(track) + current.likes)
    }
    fun addHistory(track: Track) = update { it.copy(history = (listOf(track) + it.history.filterNot { old -> old.id == track.id }).take(100)) }
    fun putPlaylist(playlist: Playlist) = update { it.copy(playlists = listOf(playlist) + it.playlists.filterNot { old -> old.id == playlist.id }) }
    fun deletePlaylist(id: String) = update { it.copy(playlists = it.playlists.filterNot { p -> p.id == id }) }
    fun renamePlaylist(id: String, title: String) = update { it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(title = title.trim().take(120).ifBlank { p.title }) else p }) }
    fun pinPlaylist(id: String) = update { it.copy(playlists = it.playlists.map { p -> if (p.id == id) p.copy(pinned = !p.pinned) else p }) }
    fun moveTrack(id: String, trackId: String, delta: Int) = update { state ->
        state.copy(playlists = state.playlists.map { p ->
            if (p.id != id) p else {
                val from = p.tracks.indexOfFirst { it.id == trackId }
                val to = from + delta
                if (from < 0 || to !in p.tracks.indices) p else p.copy(tracks = p.tracks.toMutableList().apply { add(to, removeAt(from)) })
            }
        })
    }
    fun enrichArtwork(trackId: String, artwork: String) {
        if (artwork.isBlank()) return
        fun List<Track>.enrich() = map { if (it.id == trackId) it.copy(artwork = artwork) else it }
        update { it.copy(likes = it.likes.enrich(), history = it.history.enrich(), playlists = it.playlists.map { p -> p.copy(tracks = p.tracks.enrich()) }) }
    }
    fun addToPlaylist(id: String, track: Track) = update { state ->
        state.copy(playlists = state.playlists.map { if (it.id == id && it.tracks.none { t -> t.id == track.id }) it.copy(tracks = it.tracks + track) else it })
    }
    fun removeFromPlaylist(id: String, trackId: String) = update { state -> state.copy(playlists = state.playlists.map { if (it.id == id) it.copy(tracks = it.tracks.filterNot { t -> t.id == trackId }) else it }) }
    fun export(): String = gson.toJson(mutable.value)
    fun restore(json: String) {
        require(json.length <= 10_000_000) { "Файл слишком большой" }
        val parsed = decode(json)
        update { current -> current.copy(
            likes = (current.likes + parsed.likes).distinctBy { it.id },
            playlists = (current.playlists + parsed.playlists).distinctBy { it.id },
        ) }
    }

    private fun decode(json: String): Library {
        require(json.length <= 10_000_000) { "Файл слишком большой" }
        val root = JsonParser.parseString(json).asJsonObject
        fun JsonObject.string(key: String, required: Boolean = false): String {
            val value = get(key)
            if (value == null || value.isJsonNull) {
                require(!required) { "В резервной копии отсутствует $key" }
                return ""
            }
            require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Неверное поле $key" }
            return value.asString.also { require(it.length <= 4096 && (!required || it.isNotBlank())) { "Некорректное поле $key" } }
        }
        fun JsonObject.array(key: String): JsonArray {
            val value = get(key)
            require(value != null && value.isJsonArray) { "Неверный формат библиотеки: $key" }
            return value.asJsonArray
        }
        fun tracks(array: JsonArray): List<Track> {
            require(array.size() <= 50_000) { "Слишком много треков" }
            return array.map { element ->
                val t = element.asJsonObject
                val source = t.string("source").ifBlank { "youtube" }
                require(source in listOf("youtube", "spotify", "local")) { "Неизвестный источник трека" }
                val url = t.string("url")
                if (source == "local") require(url.startsWith("content://")) { "Неверная ссылка на локальный файл" }
                Track(t.string("id", true), t.string("title", true), t.string("artist"),
                    t.get("durationMs")?.takeUnless { it.isJsonNull }?.asLong?.coerceAtLeast(0) ?: 0,
                    t.string("artwork"), url, source)
            }.distinctBy { it.id }
        }
        val playlists = root.array("playlists")
        require(playlists.size() <= 2_000) { "Слишком много плейлистов" }
        return Library(
            likes = tracks(root.array("likes")),
            playlists = playlists.map { element ->
                val p = element.asJsonObject
                Playlist(p.string("id", true), p.string("title", true), tracks(p.array("tracks")),
                    p.string("artwork"), p.string("sourceUrl"), p.get("createdAt")?.takeUnless { it.isJsonNull }?.asLong ?: System.currentTimeMillis(), p.string("importNote"),
                    p.get("pinned")?.takeUnless { it.isJsonNull }?.asBoolean ?: false)
            }.distinctBy { it.id },
            history = tracks(root.array("history")).take(100),
        )
    }
}
