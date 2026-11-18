package app.melo.music.playback

import app.melo.music.data.*
import org.junit.Assert.*
import org.junit.Test

class QueuePolicyTest {
    private val tracks = (0..19).map { Track("$it", "Song $it", "Artist ${it % 4}", 180000) }
    @Test fun shuffledCyclesContainEverySongExactlyOnceAndStartWithCurrent() {
        for (seed in 0L..99L) for (mode in listOf(QueueMode.SHUFFLE, QueueMode.FRESH)) {
            val order = QueuePolicy.create(tracks, 7, mode, seed)
            assertTrue(QueuePolicy.isPermutation(order, tracks.size)); assertEquals(7, order.first())
        }
    }
    @Test fun reshufflePreservesPastAndCurrentButChangesUpcoming() {
        val order = QueuePolicy.create(tracks, 7, QueueMode.SHUFFLE, 3)
        val current = order[5]
        val next = QueuePolicy.reshuffle(order, current, tracks, QueueMode.SHUFFLE, 9)
        assertEquals(order.take(6), next.take(6)); assertNotEquals(order.drop(6), next.drop(6)); assertTrue(QueuePolicy.isPermutation(next, tracks.size))
    }
    @Test fun freshMixDefersRecentSongs() {
        val history = tracks.take(5)
        val order = QueuePolicy.create(tracks, 19, QueueMode.FRESH, 1, history).drop(1)
        assertTrue(order.take(14).all { it >= 5 }); assertEquals((0..4).toSet(), order.takeLast(5).toSet())
    }
    @Test fun playNextRemapsStorageIndicesWithoutChangingExistingPlaybackOrder() {
        assertEquals(listOf(0, 2, 3, 1, 4), QueuePolicy.insertNext(listOf(0, 2, 1, 3), 2, 3))
    }
    @Test fun cannotReorderPastOrMoveUpcomingBeforeCurrent() {
        val order = listOf(2, 0, 3, 1)
        assertEquals(order, QueuePolicy.moveUpcoming(order, 0, 2, 1))
        assertEquals(order, QueuePolicy.moveUpcoming(order, 0, 3, -1))
        assertEquals(listOf(2, 0, 1, 3), QueuePolicy.moveUpcoming(order, 0, 1, -1))
    }
    @Test fun radioFiltersDuplicatesAndUnsuitableDurations() {
        val original = tracks.first()
        val duplicate = original.copy(id = "different")
        val candidates = listOf(original, duplicate, tracks[1], tracks[1], tracks[2].copy(durationMs = 1000))
        assertEquals(listOf(tracks[1]), QueuePolicy.uniqueRecommendations(candidates, listOf(original)))
    }
}
