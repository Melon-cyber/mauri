package app.melo.music.playback
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import app.melo.music.data.*
import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class PlaybackPersistenceTest {
    @Test fun migratesOldQueueAndRestoresModernShuffleExactly() {
        val context = ApplicationProvider.getApplicationContext<MeloApplication>()
        val prefs = context.getSharedPreferences("playback", 0)
        val tracks = (0..3).map { Track("$it", "Song $it", "Artist") }
        prefs.edit().clear().putString("queue", Gson().toJson(tracks)).putInt("index", 2).putLong("position", 12345).commit()
        val store = PlaybackPersistence(context)
        val migrated = store.read()
        assertEquals(tracks, migrated.tracks); assertEquals(2, migrated.index); assertEquals(12345L, migrated.position)
        val modern = migrated.copy(order = listOf(1, 2, 0, 3), mode = QueueMode.FRESH, repeat = 2, context = "Микс")
        store.save(modern)
        assertEquals(modern, PlaybackPersistence(context).read()); assertFalse(prefs.contains("queue"))
    }
    @Test fun repairsOutOfRangePositionAndInvalidOrder() {
        val context = ApplicationProvider.getApplicationContext<MeloApplication>()
        val store = PlaybackPersistence(context)
        store.save(PlaybackSnapshot(listOf(Track("1", "One", "Artist")), 99, -10, listOf(9), repeat = 9))
        val restored = store.read()
        assertEquals(0, restored.index); assertEquals(0L, restored.position); assertEquals(listOf(0), restored.order); assertEquals(0, restored.repeat)
    }
}
