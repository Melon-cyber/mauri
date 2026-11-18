package app.melo.music.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class QueueMode(val label: String, val announcement: String) {
    ORDERED("По порядку", "Перемешивание выключено"),
    SHUFFLE("Вразброс", "Вразброс: все песни в случайном порядке"),
    FRESH("Свежий микс", "Свежий микс: давно не звучавшие песни раньше");
    fun nextShuffle(): QueueMode = when (this) { ORDERED -> SHUFFLE; SHUFFLE -> FRESH; FRESH -> ORDERED }
}
enum class LyricBackdrop(val label: String) { COVER("Под обложку"), DARK("Тёмный"), RED("Красный") }
enum class StartScreen(val label: String) { HOME("Главная"), LIBRARY("Коллекция"), SEARCH("Поиск") }

/** Equalizer presets: gain in dB for low (<250 Hz), low-mid, mid (1–4 kHz) and high (>4 kHz) frequencies. */
enum class EqPreset(val label: String, val low: Float, val lowMid: Float, val mid: Float, val high: Float) {
    OFF("Выкл", 0f, 0f, 0f, 0f),
    BASS("Басы", 6f, 2.5f, 0f, 0f),
    VOICE("Голос", -2f, 0f, 3.5f, 1.5f),
    BRIGHT("Ярче", -1f, 0f, 1.5f, 4.5f),
    NIGHT("Ночью", -4f, 1f, 1.5f, -3f);
    fun gainAt(hz: Float): Float = when {
        hz < 250f -> low
        hz < 1_000f -> lowMid
        hz < 4_000f -> mid
        else -> high
    }
}

data class ListeningSettings(
    val autoplay: Boolean = false,
    val lyricSize: Float = 30f,
    val lyricOffsets: Map<String, Long> = emptyMap(),
    val speed: Float = 1f,
    val recentSearches: List<String> = emptyList(),
    val wifiDownloads: Boolean = true,
    val statisticsEnabled: Boolean = true,
    val lyricBackdrop: LyricBackdrop = LyricBackdrop.COVER,
    val lyricCentered: Boolean = false,
    val lyricFollow: Boolean = true,
    val lyricMotion: Boolean = true,
    val lyricKeepAwake: Boolean = false,
    val lyricGap: Float = 24f,
    val lyricDim: Float = .42f,
    val startScreen: StartScreen = StartScreen.HOME,
    val crossfadeSeconds: Int = 0,
    val normalizeVolume: Boolean = false,
    val equalizer: EqPreset = EqPreset.OFF,
    val lyricTranslate: Boolean = false,
    /** Hand-made sync points per song (see [LyricTiming]). */
    val lyricAnchors: Map<String, List<LyricAnchor>> = emptyMap(),
    /** Shuffle state chosen by the listener; used when a playlist is started with "Слушать". */
    val queueMode: QueueMode = QueueMode.ORDERED,
)

/** Lyric shift per song; large enough to align a music video whose song starts minutes in. */
const val MAX_OFFSET = 15 * 60_000L

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("mauri-settings", 0)
    private val gson = Gson()
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private fun read(): ListeningSettings {
        val offsets = runCatching {
            gson.fromJson<Map<String, Long>>(prefs.getString("lyricOffsets", "{}"), object : TypeToken<Map<String, Long>>() {}.type)
        }.getOrNull().orEmpty().filterValues { it in -MAX_OFFSET..MAX_OFFSET }
        val searches = runCatching { gson.fromJson(prefs.getString("searches", "[]"), Array<String>::class.java).toList() }.getOrDefault(emptyList())
        fun number(key: String, default: Float, min: Float, max: Float): Float = prefs.getFloat(key, default).takeIf { it.isFinite() }?.coerceIn(min, max) ?: default
        return ListeningSettings(prefs.getBoolean("autoplay", false), number("lyricSize", 30f, 22f, 42f), offsets, prefs.getFloat("speed", 1f).coerceIn(.5f, 2f), searches.take(12), prefs.getBoolean("wifiDownloads", true), prefs.getBoolean("statisticsEnabled", true),
            runCatching { LyricBackdrop.valueOf(prefs.getString("lyricBackdrop", "COVER")!!) }.getOrDefault(LyricBackdrop.COVER),
            prefs.getBoolean("lyricCentered", false), prefs.getBoolean("lyricFollow", true), prefs.getBoolean("lyricMotion", true), prefs.getBoolean("lyricKeepAwake", false),
            number("lyricGap", 24f, 12f, 36f), number("lyricDim", .42f, .25f, .8f),
            runCatching { StartScreen.valueOf(prefs.getString("startScreen", "HOME")!!) }.getOrDefault(StartScreen.HOME),
            prefs.getInt("crossfadeSeconds", 0).coerceIn(0, 10), prefs.getBoolean("normalizeVolume", false),
            runCatching { EqPreset.valueOf(prefs.getString("equalizer", "OFF")!!) }.getOrDefault(EqPreset.OFF),
            prefs.getBoolean("lyricTranslate", false),
            lyricAnchors = readAnchors(prefs.getString("lyricAnchors", "{}")),
            queueMode = runCatching { QueueMode.valueOf(prefs.getString("queueMode", "ORDERED")!!) }.getOrDefault(QueueMode.ORDERED))
    }
    private fun readAnchors(json: String?): Map<String, List<LyricAnchor>> = runCatching {
        gson.fromJson<Map<String, List<LyricAnchor>>>(json ?: "{}", object : TypeToken<Map<String, List<LyricAnchor>>>() {}.type)
    }.getOrNull().orEmpty().mapValues { (_, v) -> LyricTiming.clean(v.orEmpty().filterNotNull()) }.filterValues { it.isNotEmpty() }
    private fun readAnchors(json: com.google.gson.JsonElement?): Map<String, List<LyricAnchor>> = if (json == null || !json.isJsonObject) emptyMap() else readAnchors(json.toString())
    @Synchronized private fun update(transform: (ListeningSettings) -> ListeningSettings) {
        val next = transform(mutable.value)
        prefs.edit().putBoolean("autoplay", next.autoplay).putFloat("lyricSize", next.lyricSize)
            .putString("lyricOffsets", gson.toJson(next.lyricOffsets)).putFloat("speed", next.speed)
            .putString("searches", gson.toJson(next.recentSearches)).apply()
        prefs.edit().putBoolean("wifiDownloads", next.wifiDownloads).putBoolean("statisticsEnabled", next.statisticsEnabled).apply()
        prefs.edit().putString("lyricBackdrop", next.lyricBackdrop.name).putBoolean("lyricCentered", next.lyricCentered)
            .putBoolean("lyricFollow", next.lyricFollow).putBoolean("lyricMotion", next.lyricMotion).putBoolean("lyricKeepAwake", next.lyricKeepAwake)
            .putFloat("lyricGap", next.lyricGap).putFloat("lyricDim", next.lyricDim).apply()
        prefs.edit().putString("startScreen", next.startScreen.name).putInt("crossfadeSeconds", next.crossfadeSeconds)
            .putBoolean("normalizeVolume", next.normalizeVolume).putString("equalizer", next.equalizer.name).putBoolean("lyricTranslate", next.lyricTranslate)
            .putString("lyricAnchors", gson.toJson(next.lyricAnchors)).putString("queueMode", next.queueMode.name).apply()
        mutable.value = next
    }
    fun autoplay(value: Boolean) = update { it.copy(autoplay = value) }
    fun lyricSize(value: Float) = update { it.copy(lyricSize = if (value.isFinite()) value.coerceIn(22f, 42f) else 30f) }
    fun lyricBackdrop(value: LyricBackdrop) = update { it.copy(lyricBackdrop = value) }
    fun startScreen(value: StartScreen) = update { it.copy(startScreen = value) }
    fun lyricCentered(value: Boolean) = update { it.copy(lyricCentered = value) }
    fun lyricFollow(value: Boolean) = update { it.copy(lyricFollow = value) }
    fun lyricMotion(value: Boolean) = update { it.copy(lyricMotion = value) }
    fun lyricKeepAwake(value: Boolean) = update { it.copy(lyricKeepAwake = value) }
    fun lyricGap(value: Float) = update { it.copy(lyricGap = if(value.isFinite()) value.coerceIn(12f,36f) else 24f) }
    fun lyricDim(value: Float) = update { it.copy(lyricDim = if(value.isFinite()) value.coerceIn(.25f,.8f) else .42f) }
    fun resetLyrics() = update { it.copy(lyricSize=30f,lyricBackdrop=LyricBackdrop.COVER,lyricCentered=false,lyricFollow=true,lyricMotion=true,lyricKeepAwake=false,lyricGap=24f,lyricDim=.42f) }
    fun lyricOffset(trackId: String, value: Long) = update { it.copy(lyricOffsets = (it.lyricOffsets + (trackId to value.coerceIn(-MAX_OFFSET, MAX_OFFSET))).entries.toList().takeLast(500).associate { e -> e.toPair() }) }
    fun lyricAnchors(trackId: String, anchors: List<LyricAnchor>) = update {
        val clean = LyricTiming.clean(anchors)
        it.copy(lyricAnchors = ((it.lyricAnchors - trackId) + (if (clean.isEmpty()) emptyMap() else mapOf(trackId to clean))).entries.toList().takeLast(300).associate { e -> e.toPair() })
    }
    /** Forgets every hand-made sync for one song: points and shift. */
    fun clearLyricSync(trackId: String) = update { it.copy(lyricAnchors = it.lyricAnchors - trackId, lyricOffsets = it.lyricOffsets - trackId) }
    fun queueMode(value: QueueMode) = update { it.copy(queueMode = value) }
    fun speed(value: Float) = update { it.copy(speed = value.coerceIn(.5f, 2f)) }
    fun rememberSearch(value: String) = update { it.copy(recentSearches = (listOf(value.trim().take(200)) + it.recentSearches.filterNot { q -> q.equals(value, true) }).filter { q -> q.isNotBlank() }.take(12)) }
    fun clearSearches() = update { it.copy(recentSearches = emptyList()) }
    fun wifiDownloads(value: Boolean) = update { it.copy(wifiDownloads = value) }
    fun statisticsEnabled(value: Boolean) = update { it.copy(statisticsEnabled = value) }
    /** Settings for the full transfer file (recent searches stay on the old phone). */
    fun exportJson(): com.google.gson.JsonObject {
        val s = mutable.value
        return com.google.gson.JsonObject().apply {
            addProperty("autoplay", s.autoplay); addProperty("lyricSize", s.lyricSize); add("lyricOffsets", gson.toJsonTree(s.lyricOffsets))
            addProperty("speed", s.speed); addProperty("wifiDownloads", s.wifiDownloads); addProperty("statisticsEnabled", s.statisticsEnabled)
            addProperty("lyricBackdrop", s.lyricBackdrop.name); addProperty("lyricCentered", s.lyricCentered); addProperty("lyricFollow", s.lyricFollow)
            addProperty("lyricMotion", s.lyricMotion); addProperty("lyricKeepAwake", s.lyricKeepAwake); addProperty("lyricGap", s.lyricGap); addProperty("lyricDim", s.lyricDim)
            addProperty("startScreen", s.startScreen.name); addProperty("crossfadeSeconds", s.crossfadeSeconds); addProperty("normalizeVolume", s.normalizeVolume); addProperty("equalizer", s.equalizer.name)
            add("lyricAnchors", gson.toJsonTree(s.lyricAnchors)); addProperty("queueMode", s.queueMode.name)
        }
    }

    /** Applies a transferred settings block; unknown or broken values keep the current setting. */
    fun restoreJson(o: com.google.gson.JsonObject) {
        fun b(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
        fun f(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asFloat?.takeIf { it.isFinite() }
        fun t(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asString
        val offsets = runCatching { gson.fromJson<Map<String, Long>>(o.get("lyricOffsets"), object : TypeToken<Map<String, Long>>() {}.type) }.getOrNull().orEmpty()
            .filterValues { it in -MAX_OFFSET..MAX_OFFSET }
        update { s -> s.copy(
            autoplay = b("autoplay") ?: s.autoplay,
            lyricSize = f("lyricSize")?.coerceIn(22f, 42f) ?: s.lyricSize,
            lyricOffsets = (s.lyricOffsets + offsets).entries.toList().takeLast(500).associate { it.toPair() },
            speed = f("speed")?.coerceIn(.5f, 2f) ?: s.speed,
            wifiDownloads = b("wifiDownloads") ?: s.wifiDownloads,
            statisticsEnabled = b("statisticsEnabled") ?: s.statisticsEnabled,
            lyricBackdrop = t("lyricBackdrop")?.let { runCatching { LyricBackdrop.valueOf(it) }.getOrNull() } ?: s.lyricBackdrop,
            lyricCentered = b("lyricCentered") ?: s.lyricCentered, lyricFollow = b("lyricFollow") ?: s.lyricFollow,
            lyricMotion = b("lyricMotion") ?: s.lyricMotion, lyricKeepAwake = b("lyricKeepAwake") ?: s.lyricKeepAwake,
            lyricGap = f("lyricGap")?.coerceIn(12f, 36f) ?: s.lyricGap, lyricDim = f("lyricDim")?.coerceIn(.25f, .8f) ?: s.lyricDim,
            startScreen = t("startScreen")?.let { runCatching { StartScreen.valueOf(it) }.getOrNull() } ?: s.startScreen,
            crossfadeSeconds = f("crossfadeSeconds")?.toInt()?.coerceIn(0, 10) ?: s.crossfadeSeconds,
            normalizeVolume = b("normalizeVolume") ?: s.normalizeVolume,
            equalizer = t("equalizer")?.let { runCatching { EqPreset.valueOf(it) }.getOrNull() } ?: s.equalizer,
            lyricAnchors = (s.lyricAnchors + readAnchors(o.get("lyricAnchors"))).entries.toList().takeLast(300).associate { it.toPair() },
            queueMode = t("queueMode")?.let { runCatching { QueueMode.valueOf(it) }.getOrNull() } ?: s.queueMode,
        ) }
    }

    fun lyricTranslate(value: Boolean) = update { it.copy(lyricTranslate = value) }
    fun crossfade(seconds: Int) = update { it.copy(crossfadeSeconds = seconds.coerceIn(0, 10)) }
    fun normalizeVolume(value: Boolean) = update { it.copy(normalizeVolume = value) }
    fun equalizer(value: EqPreset) = update { it.copy(equalizer = value) }
}
