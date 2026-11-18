package app.melo.music.data

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One file to move Mauri to a new phone: library (playlists, likes, history), listening statistics and settings.
 * Downloaded audio is not included (it is large and can be downloaded again).
 * Restoring merges like the separate backups do: playlists are combined and repeated statistics are not doubled.
 */
object FullBackup {
    const val FORMAT = "mauri-full-backup"

    fun export(library: LibraryStore, statistics: ListeningStats, settings: AppSettings): String = JsonObject().apply {
        addProperty("format", FORMAT)
        addProperty("version", 1)
        addProperty("createdAt", System.currentTimeMillis())
        add("library", JsonParser.parseString(library.export()))
        add("statistics", JsonParser.parseString(statistics.exportBackup()))
        add("settings", settings.exportJson())
    }.toString()

    fun restore(json: String, library: LibraryStore, statistics: ListeningStats, settings: AppSettings) {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull() ?: error("Это не файл переноса Mauri")
        require(root.get("format")?.takeIf { it.isJsonPrimitive }?.asString == FORMAT) { "Это не файл переноса Mauri. Для отдельных копий используйте восстановление библиотеки или статистики." }
        root.get("library")?.takeIf { it.isJsonObject || it.isJsonArray }?.let { library.restore(it.toString()) }
        root.get("statistics")?.takeIf { it.isJsonObject || it.isJsonArray }?.let { statistics.restoreBackup(it.toString()) }
        root.get("settings")?.takeIf { it.isJsonObject }?.let { settings.restoreJson(it.asJsonObject) }
    }
}
