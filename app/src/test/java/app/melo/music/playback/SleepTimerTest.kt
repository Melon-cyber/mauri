package app.melo.music.playback
import org.junit.Assert.*
import org.junit.Test
class SleepTimerTest {
    @Test fun deadlineFadesThenStops() {
        val timer = SleepTimerState(untilMs = 10000)
        assertEquals(1f, timer.volume(6000)); assertEquals(.5f, timer.volume(8500)); assertEquals(0f, timer.volume(10000))
        assertFalse(timer.shouldStop(9999)); assertTrue(timer.shouldStop(10000)); assertEquals(0L, timer.remaining(12000))
    }
    @Test fun afterTrackStopsOnTransitionOrEndButNotOtherEvents() {
        val timer = SleepTimerState(afterTrackId = "queue-instance-1")
        assertFalse(timer.shouldStop(100000)); assertFalse(timer.shouldStop(100000, "other"))
        assertTrue(timer.shouldStop(100000, "queue-instance-1")); assertTrue(timer.shouldStop(100000, ended = true))
        assertFalse(SleepTimerState().shouldStop(100000, ended = true))
    }
}
