package app.melo.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrl

object SpotifyImporter {
    private val playlistId = Regex("^[a-zA-Z0-9]{22}$")
    private val urlPattern = Regex("https://(?:open\\.spotify\\.com|spotify\\.link)/[^\\s<>]+")

    fun canonicalUrl(input: String): String {
        val clean = input.trim()
        if (clean.startsWith("spotify:playlist:")) {
            val id = clean.substringAfterLast(':')
            require(playlistId.matches(id)) { "Некорректная ссылка Spotify" }
            return "https://open.spotify.com/playlist/$id"
        }
        val candidate = urlPattern.find(clean)?.value?.trimEnd(')', ']', '.', ',') ?: error("Вставьте ссылку на публичный плейлист Spotify")
        var url = candidate.toHttpUrl()
        if (url.host == "spotify.link") {
            Network.client.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", Network.USER_AGENT).build()).execute().use { url = it.request.url }
        }
        require(url.scheme == "https" && url.host == "open.spotify.com") { "Поддерживаются только ссылки Spotify" }
        val parts = url.pathSegments.filter { it.isNotBlank() }.let { if (it.firstOrNull()?.startsWith("intl-") == true) it.drop(1) else it }
        val index = parts.indexOf("playlist")
        val id = parts.getOrNull(index + 1)
        require(index >= 0 && id != null && playlistId.matches(id)) { "Нужна ссылка именно на плейлист Spotify" }
        return "https://open.spotify.com/playlist/$id"
    }

    fun fetch(input: String, progress: (String) -> Unit = {}): SpotifyImportResult =
        fetchSpotifyPages(canonicalUrl(input), SpotifyHttpPages(), progress)

    fun parseEmbed(html: String, sourceUrl: String): Playlist {
        val script = Regex("<script\\b[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .find(html)?.groupValues?.get(1) ?: error("Spotify не отдал список. Убедитесь, что плейлист публичный, или импортируйте CSV.")
        val root = JsonParser.parseString(script).asJsonObject
        val entity = root.getAsJsonObject("props")?.getAsJsonObject("pageProps")?.getAsJsonObject("state")?.getAsJsonObject("data")?.getAsJsonObject("entity")
            ?: error("Spotify изменил формат страницы. Используйте импорт CSV.")
        val art = entity.getAsJsonObject("coverArt")?.getAsJsonArray("sources")?.mapNotNull { runCatching { it.asJsonObject.string("url") }.getOrNull() }?.lastOrNull().orEmpty()
        val raw = entity.getAsJsonArray("trackList") ?: error("Плейлист закрыт или недоступен")
        val tracks = raw.mapNotNull { value ->
            val t = value.asJsonObject
            val id = t.string("uri")
            if (!id.startsWith("spotify:track:") || t.string("title").isBlank()) null else Track(
                id = id, title = t.string("title"), artist = t.string("subtitle"),
                durationMs = t.get("duration")?.takeUnless { it.isJsonNull }?.asLong ?: 0,
                artwork = art, source = "spotify",
            )
        }.distinctBy { it.id }
        require(tracks.isNotEmpty()) { "Spotify не вернул треки. Проверьте публичность плейлиста или используйте CSV." }
        val title = entity.string("title").ifBlank { entity.string("name").ifBlank { "Из Spotify" } }
        return Playlist(
            id = "spotify-${sourceUrl.substringAfterLast('/').substringBefore('?')}",
            title = title, tracks = tracks, artwork = art, sourceUrl = sourceUrl,
            importNote = "Получено: ${trackCountLabel(tracks.size)} с публичной страницы Spotify. Проверьте количество: для больших плейлистов Spotify может отдавать только часть. CSV позволяет перенести полный список. Совпадение на YouTube подбирается при первом воспроизведении.",
        )
    }

    private fun JsonObject.string(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    fun parseCsv(text: String, title: String = "Импорт из CSV"): Playlist {
        require(text.length <= 10_000_000) { "CSV слишком большой" }
        val separator = if (text.lineSequence().firstOrNull().orEmpty().count { it == ';' } > text.lineSequence().firstOrNull().orEmpty().count { it == ',' }) ';' else ','
        val rows = csvRows(text.removePrefix("\uFEFF"), separator)
        require(rows.size >= 2) { "В CSV нет треков" }
        val header = rows.first().map { it.lowercase().replace(Regex("[^a-zа-я]"), "") }
        fun column(vararg names: String): Int = header.indexOfFirst { it in names }
        val titleCol = column("trackname", "name", "title", "название", "названиетрека")
        val artistCol = column("artistnames", "artistname", "artists", "artist", "исполнитель")
        val uriCol = column("trackuri", "uri", "spotifyuri", "trackurl")
        val durationCol = column("durationms", "duration")
        require(titleCol >= 0 && artistCol >= 0) { "Нужны колонки Track Name и Artist Name(s) (например, CSV Exportify)" }
        val tracks = rows.drop(1).mapNotNull { row ->
            val name = row.getOrNull(titleCol).orEmpty().trim()
            val artist = row.getOrNull(artistCol).orEmpty().trim().replace(';', ',')
            if (name.isBlank()) null else Track(
                id = row.getOrNull(uriCol)?.takeIf { it.startsWith("spotify:track:") } ?: "csv:${TrackMatcher.normalized("$artist $name")}",
                title = name, artist = artist, durationMs = row.getOrNull(durationCol)?.toLongOrNull() ?: 0, source = "spotify",
            )
        }.distinctBy { it.id }
        require(tracks.isNotEmpty()) { "В CSV нет подходящих треков" }
        return Playlist(title = title, tracks = tracks, importNote = "Импортировано: ${trackCountLabel(tracks.size)} из CSV. Аудио подбирается на YouTube при воспроизведении.")
    }

    internal fun csvRows(text: String, separator: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < text.length) {
            val c = text[index]
            when {
                c == '"' && quoted && text.getOrNull(index + 1) == '"' -> { cell.append('"'); index++ }
                c == '"' -> quoted = !quoted
                c == separator && !quoted -> { row.add(cell.toString()); cell.clear() }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && text.getOrNull(index + 1) == '\n') index++
                    row.add(cell.toString()); cell.clear()
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            index++
        }
        require(!quoted) { "В CSV не закрыты кавычки" }
        row.add(cell.toString())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }
}
