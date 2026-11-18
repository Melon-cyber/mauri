package app.melo.music.data
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class ListeningSettingsTest {
    @Test fun preferencesAndPerSongOffsetsSurviveReopen() {
        val context = ApplicationProvider.getApplicationContext<MeloApplication>()
        val settings = AppSettings(context)
        settings.autoplay(true); settings.speed(1.25f); settings.lyricSize(34f)
        // Offsets go up to 15 minutes so a long music video can be aligned with album-version lyrics.
        settings.lyricOffset("a", 1500); settings.lyricOffset("b", -99_999_999); settings.lyricOffset("c", -185_000)
        val restored = AppSettings(context).state.value
        assertTrue(restored.autoplay); assertEquals(1.25f, restored.speed); assertEquals(34f, restored.lyricSize)
        assertEquals(1500L, restored.lyricOffsets["a"]); assertEquals(-MAX_OFFSET, restored.lyricOffsets["b"]); assertEquals(-185_000L, restored.lyricOffsets["c"])
    }
    @Test fun recentSearchesAreBoundedDeduplicatedAndClearable() {
        val context = ApplicationProvider.getApplicationContext<MeloApplication>()
        val settings = AppSettings(context)
        (1..20).forEach { settings.rememberSearch("Query $it") }
        settings.rememberSearch("QUERY 19")
        assertEquals(12, settings.state.value.recentSearches.size)
        assertEquals("QUERY 19", settings.state.value.recentSearches.first())
        assertEquals(1, settings.state.value.recentSearches.count { it.equals("query 19", true) })
        settings.clearSearches(); assertTrue(AppSettings(context).state.value.recentSearches.isEmpty())
    }
    @Test fun playlistFilterKeepsOriginalOrderAndSortsOnlyView() {
        val tracks = listOf(Track("1", "Zulu", "Альфа", 3000), Track("2", "Alpha", "Beta", 1000), Track("3", "Beta", "Gamma", 2000))
        assertEquals(listOf(tracks.first()), tracks.filterAndSort("АЛЬФА", TrackSort.ORIGINAL))
        assertEquals(listOf("2", "3", "1"), tracks.filterAndSort("", TrackSort.TITLE).map { it.id })
        assertEquals(listOf("2", "3", "1"), tracks.filterAndSort("", TrackSort.DURATION).map { it.id })
        assertEquals(listOf("1", "2", "3"), tracks.map { it.id })
    }
}
