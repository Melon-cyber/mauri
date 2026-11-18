package app.melo.music.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class ListeningDelta(val track: Track, val from: Long, val milliseconds: Long, val plays: Int = 0)
/** Uses elapsed time, never media position: seeking and playback speed cannot inflate minutes. */
class ListeningMeter {
    private var tick: Long? = null
    private var track: Track? = null
    private var key: String? = null
    private var playing = false
    private var listened = 0L
    private var counted = false
    fun sample(elapsed: Long, wall: Long, nextTrack: Track?, nextKey: String?, isPlaying: Boolean): ListeningDelta? {
        val duration = tick?.let { (elapsed - it).coerceAtLeast(0) } ?: 0L
        val current = track
        var result: ListeningDelta? = null
        if (playing && current != null && duration > 0) {
            listened += duration
            val threshold = if (current.durationMs in 1..59999) (current.durationMs / 2).coerceAtLeast(1) else 30000L
            val play = !counted && listened >= threshold
            if (play) counted = true
            result = ListeningDelta(current, wall - duration, duration, if (play) 1 else 0)
        }
        if (nextKey != key) { listened = 0; counted = false }
        tick = elapsed; track = nextTrack; key = nextKey; playing = isPlaying
        return result
    }
    fun reset() { tick = null; track = null; key = null; playing = false; listened = 0; counted = false }
}
enum class StatsPeriod(val label: String, val days: Int?) { WEEK("7 дней", 7), MONTH("30 дней", 30), ALL("Всё время", null) }
data class TrackStat(val track: Track, val milliseconds: Long, val plays: Int)
data class ArtistStat(val artist: String, val milliseconds: Long)
data class DayStat(val day: LocalDate, val milliseconds: Long)
data class TrackHistory(val track: Track, val milliseconds: Long, val plays: Int, val lastDay: LocalDate)
data class YearRecap(val year: Int, val milliseconds: Long, val plays: Int, val uniqueTracks: Int, val uniqueArtists: Int, val tracks: List<TrackStat>, val artists: List<ArtistStat>, val months: Map<Int, Long>, val bestDay: DayStat?, val activeDays: Int)
data class StatsSummary(val milliseconds: Long = 0, val plays: Int = 0, val uniqueTracks: Int = 0, val tracks: List<TrackStat> = emptyList(), val artists: List<ArtistStat> = emptyList(), val days: List<DayStat> = emptyList())

class ListeningStats(context: Context) : SQLiteOpenHelper(context, "listening-v1.db", null, 1) {
    private val gson = Gson()
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()
    var generation: Long = 0; private set
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE daily (day TEXT NOT NULL, track_id TEXT NOT NULL, metadata TEXT NOT NULL, artist TEXT NOT NULL, ms INTEGER NOT NULL, plays INTEGER NOT NULL, PRIMARY KEY(day, track_id))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun record(deltas: List<ListeningDelta>, zone: ZoneId = ZoneId.systemDefault(), expectedGeneration: Long = generation) {
        if (expectedGeneration != generation) return
        if (deltas.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            deltas.forEach { delta ->
                if (delta.milliseconds <= 0) return@forEach
                var at = delta.from
                var remaining = delta.milliseconds
                while (remaining > 0) {
                    val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
                    val end = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val part = minOf(remaining, (end - at).coerceAtLeast(1))
                    val old = db.query("daily", arrayOf("ms", "plays"), "day=? AND track_id=?", arrayOf(day.toString(), delta.track.id), null, null, null).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else 0L to 0 }
                    val values = ContentValues().apply {
                        put("day", day.toString()); put("track_id", delta.track.id); put("metadata", gson.toJson(delta.track)); put("artist", delta.track.artist)
                        put("ms", old.first + part); put("plays", old.second + if (part == remaining) delta.plays else 0)
                    }
                    db.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_REPLACE)
                    at += part; remaining -= part
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        mutableRevision.value++
    }
    @Synchronized fun summary(period: StatsPeriod, today: LocalDate = LocalDate.now()): StatsSummary {
        val from = period.days?.let { today.minusDays(it - 1L).toString() } ?: "0000-01-01"
        val args = arrayOf(from, today.toString())
        val rows = mutableListOf<TrackStat>()
        readableDatabase.rawQuery("SELECT metadata,SUM(ms),SUM(plays) FROM daily WHERE day>=? AND day<=? GROUP BY track_id ORDER BY SUM(ms) DESC", args).use { c -> while (c.moveToNext()) runCatching { rows.add(TrackStat(gson.fromJson(c.getString(0), Track::class.java), c.getLong(1), c.getInt(2))) } }
        val days = mutableMapOf<String, Long>()
        readableDatabase.rawQuery("SELECT day,SUM(ms) FROM daily WHERE day>=? AND day<=? GROUP BY day", args).use { c -> while (c.moveToNext()) days[c.getString(0)] = c.getLong(1) }
        val artists = rows.groupBy { it.track.artist }.map { (name, items) -> ArtistStat(name, items.sumOf { it.milliseconds }) }.sortedByDescending { it.milliseconds }.take(10)
        val chartDays = period.days ?: 7
        return StatsSummary(rows.sumOf { it.milliseconds }, rows.sumOf { it.plays }, rows.size, rows.take(10), artists,
            (chartDays - 1 downTo 0).map { offset -> val day = today.minusDays(offset.toLong()); DayStat(day, days[day.toString()] ?: 0) })
    }
    /** Every song ever counted: total time, plays and the last day it was heard. */
    @Synchronized fun history(): List<TrackHistory> {
        val rows = mutableListOf<TrackHistory>()
        readableDatabase.rawQuery("SELECT metadata,SUM(ms),SUM(plays),MAX(day) FROM daily GROUP BY track_id", null).use { c ->
            while (c.moveToNext()) runCatching { rows.add(TrackHistory(gson.fromJson(c.getString(0), Track::class.java), c.getLong(1), c.getInt(2), LocalDate.parse(c.getString(3)))) }
        }
        return rows
    }

    /** Numbers for the "year in music" story. */
    @Synchronized fun year(year: Int): YearRecap {
        val args = arrayOf("$year-01-01", "$year-12-31")
        val tracks = mutableListOf<TrackStat>()
        readableDatabase.rawQuery("SELECT metadata,SUM(ms),SUM(plays) FROM daily WHERE day>=? AND day<=? GROUP BY track_id ORDER BY SUM(ms) DESC", args).use { c ->
            while (c.moveToNext()) runCatching { tracks.add(TrackStat(gson.fromJson(c.getString(0), Track::class.java), c.getLong(1), c.getInt(2))) }
        }
        val days = mutableListOf<DayStat>()
        readableDatabase.rawQuery("SELECT day,SUM(ms) FROM daily WHERE day>=? AND day<=? GROUP BY day", args).use { c ->
            while (c.moveToNext()) runCatching { days.add(DayStat(LocalDate.parse(c.getString(0)), c.getLong(1))) }
        }
        val artists = tracks.groupBy { it.track.artist.substringBefore(",").trim() }.map { (name, items) -> ArtistStat(name, items.sumOf { it.milliseconds }) }.sortedByDescending { it.milliseconds }
        val months = days.groupBy { it.day.monthValue }.mapValues { (_, items) -> items.sumOf { it.milliseconds } }
        return YearRecap(year, tracks.sumOf { it.milliseconds }, tracks.sumOf { it.plays }, tracks.size, artists.size, tracks.take(5), artists.take(5), months, days.maxByOrNull { it.milliseconds }, days.count { it.milliseconds > 0 })
    }

    @Synchronized fun clear() { writableDatabase.delete("daily", null, null); generation++; mutableRevision.value++ }
    @Synchronized fun exportBackup(): String {
        val rows = com.google.gson.JsonArray()
        readableDatabase.rawQuery("SELECT day,metadata,ms,plays FROM daily ORDER BY day,track_id", null).use { c -> while (c.moveToNext()) {
            rows.add(com.google.gson.JsonObject().apply {
                addProperty("day", c.getString(0)); add("track", com.google.gson.JsonParser.parseString(c.getString(1)))
                addProperty("ms", c.getLong(2)); addProperty("plays", c.getInt(3))
            })
        } }
        return com.google.gson.JsonObject().apply { addProperty("format", "mauri-statistics-v1"); add("rows", rows) }.toString()
            .also { require(it.length <= 10_000_000) { "Копия статистики слишком большая" } }
    }
    @Synchronized fun restoreBackup(json: String) {
        require(json.length <= 10_000_000) { "Файл слишком большой" }
        val root = com.google.gson.JsonParser.parseString(json).asJsonObject
        require(root.get("format")?.asString == "mauri-statistics-v1") { "Нужна копия статистики Mauri" }
        val rows = root.getAsJsonArray("rows") ?: error("В файле нет статистики")
        require(rows.size() <= 50_000) { "Слишком много записей" }
        data class Row(val day: String, val track: Track, val ms: Long, val plays: Int)
        val parsed = rows.map { value ->
            val r = value.asJsonObject
            val day = LocalDate.parse(r.get("day").asString).toString()
            val t = gson.fromJson(r.get("track"), Track::class.java)
            require(!t.id.isNullOrBlank() && !t.title.isNullOrBlank() && t.id.length <= 4096 && t.title.length <= 4096 && t.artist != null && t.artist.length <= 4096) { "Повреждена запись песни" }
            require(t.source in listOf("spotify", "youtube", "local")) { "Неизвестный источник" }
            val ms = r.get("ms").asLong; val plays = r.get("plays").asInt
            require(ms in 0..86_400_000L && plays in 0..1_000_000) { "Некорректное время или число прослушиваний" }
            Row(day, t, ms, plays)
        }
        require(parsed.distinctBy { it.day to it.track.id }.size == parsed.size) { "Повторные записи в копии" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            parsed.forEach { row ->
                val old = db.query("daily", arrayOf("ms", "plays"), "day=? AND track_id=?", arrayOf(row.day, row.track.id), null, null, null).use { c -> if (c.moveToFirst()) c.getLong(0) to c.getInt(1) else 0L to 0 }
                val values = ContentValues().apply {
                    put("day", row.day); put("track_id", row.track.id); put("metadata", gson.toJson(row.track)); put("artist", row.track.artist)
                    put("ms", maxOf(old.first, row.ms)); put("plays", maxOf(old.second, row.plays))
                }
                db.insertWithOnConflict("daily", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        mutableRevision.value++
    }
}
