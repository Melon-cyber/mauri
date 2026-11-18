package app.melo.music.data

import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun readsMultipleTimestampsFractionsAndMetadata() {
        val result = LyricsRepository.parseLrc("[ar:Artist]\n[00:12.3][01:02.045]Repeated line\n[00:03]Early\n[00:13.40]Next")
        assertEquals(listOf(3000L, 12300L, 13400L, 62045L), result.map { it.timeMs })
        assertEquals("Repeated line", result.last().text)
    }
    @Test fun emptyLrcDoesNotInventTiming() { assertTrue(LyricsRepository.parseLrc("plain lyrics").isEmpty()) }
    @Test fun durationFormatsLongTracksAndUnknownLength() {
        assertEquals("0:00", durationLabel(-1))
        assertEquals("3:09", durationLabel(189466))
        assertEquals("61:00", durationLabel(3_660_000))
    }
    @Test fun russianTrackCountsHaveCorrectEndings() {
        assertEquals("1 трек", trackCountLabel(1))
        assertEquals("11 треков", trackCountLabel(11))
        assertEquals("21 трек", trackCountLabel(21))
        assertEquals("53 трека", trackCountLabel(53))
    }
}
