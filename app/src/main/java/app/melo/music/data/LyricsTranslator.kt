package app.melo.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import okhttp3.FormBody
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Russian translation of lyric lines (Google Translate web endpoint), cached per song. */
class LyricsTranslator(context: Context) {
    private val cache = File(context.cacheDir, "translations").apply { mkdirs() }
    private val gson = Gson()

    /** Result: original line → Russian line. [alreadyRussian] is true when the song is in Russian already. */
    data class Translation(val lines: Map<String, String>, val alreadyRussian: Boolean)

    @Synchronized fun translate(trackId: String, lines: List<String>): Translation {
        val unique = lines.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (unique.isEmpty()) return Translation(emptyMap(), false)
        val key = MessageDigest.getInstance("SHA-256").digest((trackId + "\n" + unique.joinToString("\n")).toByteArray()).joinToString("") { "%02x".format(it) }
        val file = File(cache, "$key.json")
        if (file.exists()) runCatching {
            val stored: Map<String, String> = gson.fromJson(file.readText(), object : TypeToken<Map<String, String>>() {}.type)
            return Translation(stored.filterKeys { it != LANGUAGE_KEY }, stored[LANGUAGE_KEY] == "ru")
        }
        val result = linkedMapOf<String, String>()
        var language = ""
        // Send in parts of up to ~3500 characters, one line per text line, so lines stay aligned.
        val chunks = mutableListOf<List<String>>(); var current = mutableListOf<String>(); var size = 0
        unique.forEach { line -> if (size + line.length > 3_500 && current.isNotEmpty()) { chunks += current; current = mutableListOf(); size = 0 }; current += line; size += line.length + 1 }
        if (current.isNotEmpty()) chunks += current
        for (chunk in chunks) {
            val (translated, detected) = request(chunk.joinToString("\n"))
            if (language.isBlank()) language = detected
            if (detected == "ru") break
            val parts = translated.split("\n").map { it.trim() }
            if (parts.size == chunk.size) chunk.zip(parts).forEach { (a, b) -> result[a] = b }
            else chunk.forEach { line -> result[line] = request(line).first.trim() }
        }
        runCatching { file.writeText(gson.toJson(result + (LANGUAGE_KEY to language))) }
        return Translation(result, language == "ru")
    }

    private fun request(text: String): Pair<String, String> {
        val body = FormBody.Builder().add("q", text).build()
        val request = okhttp3.Request.Builder()
            .url("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=ru&dt=t")
            .header("User-Agent", Network.USER_AGENT).post(body).build()
        return Network.client.newCall(request).execute().use { response ->
            if (response.code == 429) throw RateLimitException(60)
            if (!response.isSuccessful) throw IOException("Перевод недоступен (HTTP ${response.code})")
            val root = JsonParser.parseString(response.body?.string() ?: throw IOException("Пустой ответ")).asJsonArray
            val text = root[0].asJsonArray.joinToString("") { seg -> seg.asJsonArray[0].takeIf { it.isJsonPrimitive }?.asString.orEmpty() }
            val detected = root.getOrNull(2)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            text to detected
        }
    }

    private fun com.google.gson.JsonArray.getOrNull(i: Int) = if (i < size()) get(i) else null

    companion object { private const val LANGUAGE_KEY = "\u0000language" }
}
