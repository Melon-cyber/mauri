package app.melo.music.playback

import android.content.Context
import app.melo.music.data.QueueMode
import app.melo.music.data.Track
import com.google.gson.Gson
import androidx.media3.common.Player

data class PlaybackSnapshot(
    val tracks: List<Track> = emptyList(), val index: Int = 0, val position: Long = 0,
    val order: List<Int> = emptyList(), val mode: QueueMode = QueueMode.ORDERED,
    val repeat: Int = Player.REPEAT_MODE_OFF, val context: String = "Твоя музыка",
)

class PlaybackPersistence(context: Context) {
    private val prefs = context.getSharedPreferences("playback", 0)
    private val gson = Gson()
    fun read(): PlaybackSnapshot {
        val modern = runCatching { gson.fromJson(prefs.getString("snapshot-v2", null), PlaybackSnapshot::class.java) }.getOrNull()
        val snapshot = modern ?: runCatching {
            val tracks = gson.fromJson(prefs.getString("queue", null), Array<Track>::class.java)?.toList().orEmpty()
            PlaybackSnapshot(tracks, prefs.getInt("index", 0), prefs.getLong("position", 0), tracks.indices.toList())
        }.getOrDefault(PlaybackSnapshot())
        if (snapshot.tracks.isEmpty()) return PlaybackSnapshot()
        return snapshot.copy(index = snapshot.index.coerceIn(snapshot.tracks.indices), position = snapshot.position.coerceAtLeast(0),
            order = snapshot.order.takeIf { QueuePolicy.isPermutation(it, snapshot.tracks.size) } ?: snapshot.tracks.indices.toList(),
            repeat = snapshot.repeat.takeIf { it in 0..2 } ?: Player.REPEAT_MODE_OFF)
    }
    fun save(snapshot: PlaybackSnapshot) {
        prefs.edit().putString("snapshot-v2", gson.toJson(snapshot)).remove("queue").remove("index").remove("position").apply()
    }
}
