package app.melo.music.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException

/**
 * Imports a public playlist by link from Spotify, Yandex Music or Apple Music.
 * Only the song list (title, artist, length, cover) is taken; audio is found later on YouTube Music,
 * exactly as for Spotify. Imported songs keep source "spotify", which in Mauri means "metadata to be matched".
 */
object LinkImporter {
    private val anyUrl = Regex("https?://[^\\s<>\"]+")

    fun fetch(input: String, progress: (String) -> Unit = {}): SpotifyImportResult {
        val clean = input.trim()
        val url = anyUrl.find(clean)?.value?.trimEnd(')', ']', '.', ',')?.let { runCatching { it.toHttpUrl() }.getOrNull() }
        return when {
            url != null && url.host.startsWith("music.yandex.") -> yandex(url.toString(), progress)
            url != null && url.host == "music.apple.com" -> apple(url.toString(), progress)
            else -> SpotifyImporter.fetch(clean, progress)
        }
    }

    // ---------- Yandex Music ----------
    private val yandexUser = Regex("/users/([^/]+)/playlists/(\\d+)")
    private val yandexUuid = Regex("/playlists/([A-Za-z0-9.\\-]+)")

    private fun yandex(link: String, progress: (String) -> Unit): SpotifyImportResult {
        val path = link.toHttpUrl().encodedPath
        val api = yandexUser.find(path)?.let { "https://api.music.yandex.net/users/${it.groupValues[1]}/playlists/${it.groupValues[2]}" }
            ?: yandexUuid.find(path)?.let { "https://api.music.yandex.net/playlist/${it.groupValues[1]}" }
            ?: error("Нужна ссылка именно на плейлист Яндекс Музыки")
        progress("Получаем плейлист Яндекс Музыки…")
        val body = Network.text(api)
        val root = JsonParser.parseString(body).asJsonObject
        if (root.has("error")) throw IOException("Плейлист закрыт или удалён")
        val data = root.getAsJsonObject("result") ?: throw IOException("Яндекс Музыка не вернула плейлист")
        val items = data.getAsJsonArray("tracks") ?: throw IOException("Плейлист закрыт или пуст")
        var skipped = 0
        val tracks = items.mapNotNull { element ->
            val track = element.obj()?.getAsJsonObject("track") ?: element.obj()
            val title = track?.str("title").orEmpty()
            val artists = track?.getAsJsonArray("artists")?.mapNotNull { it.obj()?.str("name") }?.filter { it.isNotBlank() }.orEmpty()
            if (track == null || title.isBlank() || artists.isEmpty() || track.get("available")?.takeIf { it.isJsonPrimitive }?.asBoolean == false) { skipped++; return@mapNotNull null }
            val version = track.str("version")
            Track(
                id = "yandex:${track.str("id").ifBlank { TrackMatcher.normalized("$artists $title") }}",
                title = if (version.isNotBlank()) "$title ($version)" else title,
                artist = artists.joinToString(", "),
                durationMs = track.get("durationMs")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0,
                artwork = track.str("coverUri").takeIf { it.isNotBlank() }?.let { "https://" + it.replace("%%", "400x400") }.orEmpty(),
                source = "spotify",
            )
        }.distinctBy { it.id }
        val expected = data.get("trackCount")?.takeIf { it.isJsonPrimitive }?.asInt
        val owner = data.getAsJsonObject("owner")?.str("login").orEmpty()
        val playlist = Playlist(
            id = "yandex-${owner}-${data.str("kind").ifBlank { data.str("playlistUuid") }}",
            title = data.str("title").ifBlank { "Плейлист Яндекс Музыки" },
            tracks = tracks,
            artwork = data.getAsJsonObject("cover")?.str("uri")?.takeIf { it.isNotBlank() }?.let { "https://" + it.replace("%%", "400x400") } ?: tracks.firstOrNull()?.artwork.orEmpty(),
            sourceUrl = link,
            importNote = "Импорт из Яндекс Музыки",
        )
        val received = items.size()
        return SpotifyImportResult(playlist, received, expected, expected == null || received >= expected, skippedItems = skipped)
    }

    // ---------- Apple Music ----------
    private fun apple(link: String, progress: (String) -> Unit): SpotifyImportResult {
        require(link.contains("/playlist/")) { "Нужна ссылка именно на плейлист Apple Music" }
        progress("Получаем плейлист Apple Music…")
        val html = Network.text(link)
        val json = Regex("<script type=\"application/json\" id=\"serialized-server-data\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: throw IOException("Apple Music не отдал список песен. Возможно, плейлист закрыт.")
        val songs = mutableListOf<JsonObject>()
        fun walk(element: JsonElement) {
            when {
                element.isJsonObject -> {
                    val o = element.asJsonObject
                    if (o.has("artistName") && o.has("title") && o.getAsJsonObject("contentDescriptor")?.str("kind") == "song") songs += o
                    else o.entrySet().forEach { walk(it.value) }
                }
                element.isJsonArray -> element.asJsonArray.forEach(::walk)
            }
        }
        walk(JsonParser.parseString(json))
        val tracks = songs.mapNotNull { song ->
            val title = song.str("title"); val artist = song.str("artistName")
            if (title.isBlank() || artist.isBlank()) return@mapNotNull null
            val id = song.getAsJsonObject("contentDescriptor")?.getAsJsonObject("identifiers")?.str("storeAdamID").orEmpty()
            val art = song.getAsJsonObject("artwork")?.getAsJsonObject("dictionary")?.str("url").orEmpty()
                .replace("{w}", "400").replace("{h}", "400").replace("{c}", "bb").replace("{f}", "jpg")
            Track(id = "apple:${id.ifBlank { TrackMatcher.normalized("$artist $title") }}", title = title, artist = artist,
                durationMs = song.get("duration")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0, artwork = art, source = "spotify")
        }.distinctBy { it.id }
        if (tracks.isEmpty()) throw IOException("В плейлисте Apple Music не нашлось песен")
        val title = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")?.replace("&#39;", "'")?.removeSuffix(" on Apple Music")?.trim().orEmpty()
        val cover = Regex("<meta property=\"og:image\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val id = Regex("pl\\.[A-Za-z0-9.\\-]+").find(link)?.value ?: TrackMatcher.normalized(title)
        val playlist = Playlist(id = "apple-$id", title = title.ifBlank { "Плейлист Apple Music" }, tracks = tracks, artwork = cover, sourceUrl = link, importNote = "Импорт из Apple Music")
        // The public page lists up to about 100 songs; the count of a longer playlist is not shown there.
        return SpotifyImportResult(playlist, tracks.size, null, true)
    }

    private fun JsonElement.obj(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
