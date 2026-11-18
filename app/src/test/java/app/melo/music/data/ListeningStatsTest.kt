package app.melo.music.data
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.*
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class ListeningStatsTest {
    private val app get() = ApplicationProvider.getApplicationContext<MeloApplication>()
    @Test fun statisticsBackupRestoresDaysAndDoesNotDoubleCountOnRepeatedImport() {
        val store = ListeningStats(app); val today = LocalDate.now()
        val track = Track("backup", "Song", "Artist")
        val start = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        store.record(listOf(ListeningDelta(track,start,120000,2)))
        val copy = store.exportBackup(); store.clear(); store.restoreBackup(copy); store.restoreBackup(copy)
        assertEquals(120000L,store.summary(StatsPeriod.ALL,today).milliseconds)
        assertEquals(2,store.summary(StatsPeriod.ALL,today).plays)
        store.record(listOf(ListeningDelta(track,start,30000,1))); store.restoreBackup(copy)
        assertEquals(150000L,store.summary(StatsPeriod.ALL,today).milliseconds)
        store.close()
    }
    @Test fun malformedBackupIsRejectedBeforeChangingAnyStatistics() {
        val store = ListeningStats(app)
        store.record(listOf(ListeningDelta(Track("a","A","Artist"),System.currentTimeMillis(),10000,1)))
        val json = com.google.gson.JsonParser.parseString(store.exportBackup()).asJsonObject
        json.getAsJsonArray("rows").add(com.google.gson.JsonObject())
        assertThrows(Exception::class.java) { store.restoreBackup(json.toString()) }
        assertEquals(10000L,store.summary(StatsPeriod.ALL).milliseconds)
        store.close()
    }
    @Test fun midnightSplitPeriodBoundsAndTopTracksAreAccurate() {
        val zone = ZoneId.of("Asia/Qyzylorda"); val today = LocalDate.of(2026,11,12)
        val store = ListeningStats(app)
        val track = Track("a", "Natural", "Imagine Dragons", 180000)
        val midnight = today.atStartOfDay(zone).toInstant().toEpochMilli()
        store.record(listOf(ListeningDelta(track, midnight - 1000, 2000, 1)), zone)
        val summary = store.summary(StatsPeriod.WEEK, today)
        assertEquals(2000L, summary.milliseconds); assertEquals(1, summary.plays); assertEquals(1, summary.uniqueTracks)
        assertEquals(1000L, summary.days.last().milliseconds); assertEquals(1000L, summary.days[5].milliseconds)
        store.record(listOf(ListeningDelta(track.copy(id="b",title="Older"), today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(), 60000, 1)), zone)
        assertEquals(2000L, store.summary(StatsPeriod.WEEK, today).milliseconds)
        assertEquals(62000L, store.summary(StatsPeriod.ALL, today).milliseconds)
        assertEquals("b", store.summary(StatsPeriod.ALL, today).tracks.first().track.id)
        store.close()
        assertEquals(62000L, ListeningStats(app).use { it.summary(StatsPeriod.ALL, today).milliseconds })
    }
    @Test fun clearingRejectsStalePendingWrites() {
        val store = ListeningStats(app); val before = store.generation
        store.clear()
        store.record(listOf(ListeningDelta(Track("a","A","Artist"),System.currentTimeMillis(),10000)), expectedGeneration=before)
        assertEquals(0L, store.summary(StatsPeriod.ALL).milliseconds)
        store.close()
    }
}
