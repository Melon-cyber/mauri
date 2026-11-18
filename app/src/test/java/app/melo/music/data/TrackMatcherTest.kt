package app.melo.music.data

import org.junit.Assert.*
import org.junit.Test

class TrackMatcherTest {
    private val target = Track("spotify:1", "Natural", "Imagine Dragons", 189466, source = "spotify")
    @Test fun choosesSongOverLiveCoverAndWrongArtist() {
        val official = Track("1", "Natural", "Imagine Dragons - Topic", 189000)
        val candidates = listOf(Track("2", "Natural (live)", "Imagine Dragons", 240000), Track("3", "Natural cover", "Someone", 189000), Track("4", "Natural", "Other artist", 189000), official)
        assertEquals("1", TrackMatcher.best(target, candidates)?.id)
    }
    @Test fun refusesUnrelatedResults() {
        assertNull(TrackMatcher.best(target, listOf(Track("2", "Song", "Someone Else", 120000))))
    }
    @Test fun preservesExplicitVersionRequest() {
        val wanted = target.copy(title = "Natural (live)")
        assertTrue(TrackMatcher.score(wanted, target.copy(title = "Natural (live)")) > TrackMatcher.score(wanted, target))
    }
    @Test fun doesNotSilentlyReplaceRequestedLiveVersionWithStudioRecording() {
        assertNull(TrackMatcher.best(target.copy(title = "Natural (live)"), listOf(target)))
    }
    @Test fun doesNotAcceptSameTitleByUnrelatedArtist() {
        val wanted = Track("spotify:2", "Honeypie", "JAWNY", 135000)
        assertNull(TrackMatcher.best(wanted, listOf(Track("unrelated", "HoneyPie", "Sober Sound Record", 135000))))
    }
    @Test fun matchesVideoTitleContainingArtistAndFeaturingCredits() {
        val wanted = Track("spotify:3", "nuts", "Lil Peep, rainy bear", 85000)
        val video = Track("video", "Lil Peep - nuts (feat. rainy bear) (Official Audio)", "Lil Peep", 85000)
        assertEquals(video, TrackMatcher.best(wanted, listOf(video)))
    }
    @Test fun normalizesCyrillicAndAccentsWithoutLosingWords() {
        assertEquals("земфира искала", TrackMatcher.normalized("Земфира — Искала (Official Audio)"))
        assertEquals("beyonce", TrackMatcher.normalized("Beyoncé"))
    }
}
