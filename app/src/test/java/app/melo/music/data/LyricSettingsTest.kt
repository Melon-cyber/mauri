package app.melo.music.data

import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import app.melo.music.ui.activeLyricIndex
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=MeloApplication::class)
class LyricSettingsTest {
    private val app get()=ApplicationProvider.getApplicationContext<MeloApplication>()
    @Test fun oldPreferencesAndNewLyricOptionsSurviveReopeningWithoutChangingStatisticsSettings() {
        val prefs=app.getSharedPreferences("mauri-settings",0)
        prefs.edit().putFloat("lyricSize",28f).putBoolean("statisticsEnabled",false).commit()
        val s=AppSettings(app)
        assertEquals(28f,s.state.value.lyricSize)
        s.lyricBackdrop(LyricBackdrop.RED);s.lyricSize(40f);s.lyricCentered(true);s.lyricMotion(false)
        s.lyricFollow(false);s.lyricKeepAwake(true);s.lyricGap(30f);s.lyricDim(.7f);s.lyricOffset("track",1500)
        s.startScreen(StartScreen.LIBRARY)
        val reopened=AppSettings(app).state.value
        assertEquals(s.state.value,reopened);assertFalse(reopened.statisticsEnabled)
        s.resetLyrics()
        assertEquals(1500L,s.state.value.lyricOffsets["track"])
        assertFalse(s.state.value.statisticsEnabled);assertEquals(LyricBackdrop.COVER,s.state.value.lyricBackdrop)
        assertEquals(StartScreen.LIBRARY,AppSettings(app).state.value.startScreen)
    }
    @Test fun corruptEnumAndNonFiniteSizesFallBackToUsableValues() {
        app.getSharedPreferences("mauri-settings",0).edit().putString("lyricBackdrop","missing").putFloat("lyricSize",Float.NaN).commit()
        val s=AppSettings(app);assertEquals(30f,s.state.value.lyricSize);assertEquals(LyricBackdrop.COVER,s.state.value.lyricBackdrop)
        s.lyricSize(100f);assertEquals(42f,s.state.value.lyricSize)
    }
    @Test fun syncHonorsOffsetEmptyLyricsPausesAndDuplicateTimestamps() {
        val lines=listOf(LyricLine(1000,"First"),LyricLine(5000,"Second"),LyricLine(5000,"Translation"),LyricLine(9000,""))
        assertEquals(-1,activeLyricIndex(lines,0,0));assertEquals(0,activeLyricIndex(lines,4500,0))
        assertEquals(2,activeLyricIndex(lines,4500,500));assertEquals(0,activeLyricIndex(lines,5000,-500))
        assertEquals(3,activeLyricIndex(lines,10000,0));assertEquals(-1,activeLyricIndex(emptyList(),0,0))
    }
}
