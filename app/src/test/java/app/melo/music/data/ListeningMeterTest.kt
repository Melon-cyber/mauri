package app.melo.music.data
import org.junit.Assert.*
import org.junit.Test
class ListeningMeterTest {
    private val track = Track("song", "Song", "Artist", 180000)
    @Test fun countsOnlyElapsedPlayingTimeAndCountsSessionOnce() {
        val meter = ListeningMeter()
        assertNull(meter.sample(0, 0, track, "a", true))
        assertEquals(10000L, meter.sample(10000, 10000, track, "a", false)!!.milliseconds)
        assertNull(meter.sample(50000, 50000, track, "a", false))
        assertNull(meter.sample(60000, 60000, track, "a", true))
        val counted = meter.sample(80000, 80000, track, "a", true)!!
        assertEquals(20000L, counted.milliseconds); assertEquals(1, counted.plays)
        assertEquals(0, meter.sample(100000, 100000, track, "a", true)!!.plays)
    }
    @Test fun queueTransitionAttributesIntervalToDepartingTrack() {
        val meter = ListeningMeter(); val next = track.copy(id = "other")
        meter.sample(0, 100000, track, "a", true)
        val last = meter.sample(5000, 105000, next, "b", true)!!
        assertEquals(track, last.track); assertEquals(100000L, last.from)
        assertEquals(next, meter.sample(10000, 110000, next, "b", true)!!.track)
    }
    @Test fun repeatStartsNewCountButMetadataDoesNot() {
        val meter = ListeningMeter(); meter.sample(0, 0, track, "a", true)
        assertEquals(1, meter.sample(30000, 30000, track.copy(artwork = "new"), "a", true)!!.plays)
        assertEquals(0, meter.sample(31000, 31000, track, "repeat-b", true)!!.plays)
        assertEquals(1, meter.sample(61000, 61000, track, "repeat-b", true)!!.plays)
    }
    @Test fun shortSongsUseHalfDurationAndResetCannotAddOldTime() {
        val meter = ListeningMeter(); val short = track.copy(durationMs = 4000)
        meter.sample(0, 0, short, "a", true)
        assertEquals(1, meter.sample(2000, 2000, short, "a", true)!!.plays)
        meter.reset(); assertNull(meter.sample(100000, 100000, track, "b", true))
    }
}
