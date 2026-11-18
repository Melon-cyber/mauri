package app.melo.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class SpotifyImportResult(
    val playlist: Playlist,
    val receivedItems: Int,
    val expectedItems: Int?,
    val complete: Boolean,
    val skippedItems: Int = 0,
    val duplicateItems: Int = 0,
    val reason: String = "",
) {
    val summary: String get() = buildString {
        append(if (complete) "Список получен полностью. " else "Неполный импорт. ")
        append("Проверено позиций: $receivedItems")
        append(expectedItems?.let { " из $it. " } ?: ". Общее количество неизвестно. ")
        append("Песен: ${playlist.tracks.size}.")
        if (skippedItems > 0) append(" Недоступных записей, локальных файлов или подкастов пропущено: $skippedItems.")
        if (duplicateItems > 0) append(" Повторных песен объединено: $duplicateItems.")
        if (reason.isNotBlank()) append(" $reason")
    }

    internal fun withNote(): SpotifyImportResult = copy(playlist = playlist.copy(
        importNote = "$summary Аудио подбирается на YouTube при воспроизведении."))

    // A partial refresh must never replace an existing, more complete playlist.
    fun partialCopy(): Playlist {
        check(!complete)
        return playlist.copy(id = java.util.UUID.randomUUID().toString(), title = "${playlist.title} — неполный импорт")
    }
}

internal interface SpotifyPageSource {
    fun embed(id: String): String
    fun page(id: String, offset: Int, token: String): JsonObject
}

internal class SpotifyHttpPages(
    private val client: OkHttpClient = Network.client.newBuilder().followRedirects(false).build(),
) : SpotifyPageSource {
    override fun embed(id: String): String = Network.text("https://open.spotify.com/embed/playlist/$id")

    override fun page(id: String, offset: Int, token: String): JsonObject {
        // Public web-player query; no account credentials, app secrets or third-party proxy.
        // Spotify can change this query. Such a response must be treated as incomplete.
        val payload = JsonObject().apply {
            addProperty("operationName", "queryPlaylist")
            add("variables", JsonObject().apply {
                addProperty("uri", "spotify:playlist:$id")
                addProperty("offset", offset)
                addProperty("limit", 50)
                addProperty("includeItemV3", false)
            })
            add("extensions", JsonObject().apply { add("persistedQuery", JsonObject().apply {
                addProperty("version", 1)
                addProperty("sha256Hash", "c685ca91fb0019c87ff1225f84e1d85b26cd631fa6187f3f0900da182cb0953d")
            }) })
        }
        val request = Request.Builder().url("https://api-partner.spotify.com/pathfinder/v1/query")
            .header("User-Agent", Network.USER_AGENT).header("Origin", "https://open.spotify.com")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitException(response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60)
            if (!response.isSuccessful) throw IOException("Spotify ответил HTTP ${response.code}. Повторите импорт позже или используйте CSV.")
            val body = response.body ?: throw IOException("Spotify вернул пустой ответ")
            val text = body.charStream().use { reader ->
                val buffer = CharArray(8192); val out = StringBuilder()
                while (true) {
                    val count = reader.read(buffer); if (count < 0) break
                    require(out.length + count <= 5_000_000) { "Ответ Spotify слишком большой" }
                    out.append(buffer, 0, count)
                }
                out.toString()
            }
            JsonParser.parseString(text).asJsonObject
        }
    }
}

internal fun JsonObject.text(key: String): String = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString.orEmpty()
private fun JsonObject.intOrNull(key: String): Int? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.let { runCatching { it.asInt }.getOrNull() }
private fun JsonObject.art(): String = getAsJsonArray("sources")?.mapNotNull {
    (it as? JsonObject)?.text("url")?.takeIf { url -> url.startsWith("https://") }
}?.firstOrNull().orEmpty()

internal fun fetchSpotifyPages(
    url: String,
    source: SpotifyPageSource,
    progress: (String) -> Unit,
): SpotifyImportResult {
    val id = url.substringAfterLast('/')
    progress("Получаем плейлист…")
    val html = source.embed(id)
    val fallback = SpotifyImporter.parseEmbed(html, url)
    val script = Regex("<script\\b[^>]*\\bid=[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        .find(html)!!.groupValues[1]
    val state = JsonParser.parseString(script).asJsonObject.getAsJsonObject("props").getAsJsonObject("pageProps").getAsJsonObject("state")
    val fallbackRows = state.getAsJsonObject("data").getAsJsonObject("entity").getAsJsonArray("trackList")
    val fallbackItems = fallbackRows.size()
    val fallbackSongs = fallbackRows.count { it.isJsonObject && it.asJsonObject.text("uri").startsWith("spotify:track:") && it.asJsonObject.text("title").isNotBlank() }
    val token = state.getAsJsonObject("settings")?.getAsJsonObject("session")?.text("accessToken").orEmpty()
    var playlist = fallback
    var total: Int? = null
    var received = 0
    var skipped = 0
    var duplicates = 0
    val tracks = linkedMapOf<String, Track>()
    var offset = 0
    var reason = "Не удалось проверить полный список Spotify. Повторите импорт или используйте CSV."
    if (token.isNotBlank()) {
        while (true) {
            progress(if (total == null) "Проверяем полный список…" else "Получено позиций: $received из $total")
            val response = try { source.page(id, offset, token) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) {
                reason = if (e is RateLimitException) e.message.orEmpty() else "Не удалось получить следующую часть Spotify. Проверьте сеть, повторите импорт или используйте CSV."
                break
            }
            try {
                require(response.getAsJsonArray("errors")?.size().let { it == null || it == 0 })
                val entity = response.getAsJsonObject("data")?.getAsJsonObject("playlistV2") ?: error("No playlist")
                require(entity.text("__typename") == "Playlist" && entity.text("uri") == "spotify:playlist:$id")
                val content = entity.getAsJsonObject("content") ?: error("No content")
                val count = content.intOrNull("totalCount") ?: error("No count")
                require(count >= 0)
                if (total != null && total != count) {
                    reason = "Плейлист изменился во время переноса. Повторите импорт, чтобы получить согласованный список."
                    break
                }
                total = count
                if (count > 50_000) { reason = "В плейлисте больше 50 000 позиций — это предел текущего импорта."; break }
                val rows = content.getAsJsonArray("items") ?: error("No items")
                require(rows.size() <= 50 && offset + rows.size() <= count)
                val pageTracks = mutableListOf<Track>()
                var pageSkipped = 0
                rows.forEach { row ->
                    val data = row.asJsonObject.getAsJsonObject("itemV2")?.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
                    if (data == null || data.text("__typename") in setOf("Episode", "LocalTrack", "NotFound", "RestrictedContent", "Unavailable")) {
                        pageSkipped++; return@forEach
                    }
                    require(data.text("__typename") == "Track")
                    val uri = data.text("uri"); val name = data.text("name")
                    require(uri.startsWith("spotify:track:") && name.isNotBlank())
                    val artists = data.getAsJsonObject("artists")?.getAsJsonArray("items") ?: error("No artists")
                    pageTracks += Track(uri, name, artists.mapNotNull { it.asJsonObject.getAsJsonObject("profile")?.text("name")?.takeIf(String::isNotBlank) }.joinToString(", "),
                        data.getAsJsonObject("duration")?.get("totalMilliseconds")?.asLong?.coerceAtLeast(0) ?: 0,
                        data.getAsJsonObject("albumOfTrack")?.getAsJsonObject("coverArt")?.art().orEmpty().ifBlank { fallback.artwork }, source = "spotify")
                }
                val art = entity.getAsJsonObject("images")?.getAsJsonArray("items")?.firstOrNull()?.asJsonObject?.art().orEmpty()
                playlist = playlist.copy(title = entity.text("name").ifBlank { playlist.title }, artwork = art.ifBlank { playlist.artwork })
                pageTracks.forEach { if (tracks.putIfAbsent(it.id, it) != null) duplicates++ }
                received += rows.size(); skipped += pageSkipped
                progress("Получено позиций: $received из $count")
                val next = content.getAsJsonObject("pagingInfo")?.intOrNull("nextOffset")
                if (received == count && next == null) {
                    require(tracks.isNotEmpty()) { "В плейлисте нет доступных песен" }
                    return SpotifyImportResult(playlist.copy(tracks = tracks.values.toList()), received, count, true, skipped, duplicates).withNote()
                }
                if (rows.size() == 0 || next == null || next != received || next <= offset || received >= count) break
                offset = next
            } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {
                reason = "Spotify вернул неполный или изменённый ответ. Повторите импорт или используйте CSV."
                break
            }
        }
    }
    // Prefer the longest contiguous list available; neither source is claimed complete.
    return if (received >= fallbackItems && tracks.isNotEmpty()) {
        SpotifyImportResult(playlist.copy(tracks = tracks.values.toList()), received, total, false, skipped, duplicates, reason).withNote()
    } else {
        SpotifyImportResult(fallback, fallbackItems, total?.takeIf { it >= fallbackItems }, false,
            skippedItems = fallbackItems - fallbackSongs, duplicateItems = fallbackSongs - fallback.tracks.size, reason = reason).withNote()
    }
}
