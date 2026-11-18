package app.melo.music

import android.graphics.Bitmap
import android.content.ComponentName
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.melo.music.data.*
import app.melo.music.playback.MusicService
import app.melo.music.ui.*
import org.junit.Rule
import org.junit.rules.ExternalResource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class, qualifiers = "w412dp-h915dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@android.annotation.TargetApi(34)
class MeloUiTest {
    @get:Rule(order = 0) val service = object : ExternalResource() {
        override fun before() {
            val app = ApplicationProvider.getApplicationContext<MeloApplication>()
            shadowOf(app).declareComponentUnbindable(ComponentName(app, MusicService::class.java))
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as MeloApplication
    private val model get() = androidx.lifecycle.ViewModelProvider(compose.activity)[MeloViewModel::class.java]
    private val tracks get() = listOf("Night Drive", "Afterglow", "Slow Motion", "Blue Hour", "City Lights").mapIndexed { i, name ->
        Track("preview-$i", name, listOf("Mauri Sessions", "Evening Radio", "North Coast")[i % 3], 189000, "android.resource://app.melo.music/${R.drawable.night_drive}")
    }
    @Test fun rendersFreshHome() {
        compose.onNodeWithText("С возвращением").assertIsDisplayed()
        compose.onNodeWithText("Избранное").assertIsDisplayed()
        capture("01-home")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun collectionActionsFitCompactPhone() {
        compose.runOnUiThread { model.tab(Tab.LIBRARY) }
        compose.onNodeWithText("Импорт").assertIsDisplayed()
        compose.onNodeWithText("Загрузки").assertIsDisplayed()
        capture("09-compact-library")
    }
    @Test fun collectionFindsSongsByArtistOfflineAndRespectsFilters() {
        app.library.putPlaylist(Playlist(id="discovery",title="Мой плейлист",tracks=tracks))
        compose.runOnUiThread { model.tab(Tab.LIBRARY) }
        compose.onNode(hasSetTextAction()).performTextInput("Mauri Sessions")
        compose.onNodeWithText("Night Drive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Песни · 2").assertExists()
        capture("08-library-search")
        compose.onNodeWithText("Без сети").performScrollTo().performClick()
        compose.onNodeWithText("Night Drive").assertDoesNotExist()
        compose.onNodeWithText("Ничего не найдено в коллекции").performScrollTo().assertIsDisplayed()
        capture("08-library-discovery")
        compose.onNodeWithContentDescription("Очистить поиск в коллекции").performScrollTo().performClick()
        compose.onNodeWithText("Всё").performScrollTo().performClick()
        compose.onNodeWithText("Мой плейлист").performScrollTo().assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun partialSpotifyImportRequiresExplicitSaveAndPreservesOriginal() {
        val existing = Playlist(id = "spotify-test", title = "Мой Spotify", tracks = tracks)
        app.library.putPlaylist(existing)
        val result = SpotifyImportResult(existing.copy(tracks = tracks.take(2)), 100, 237, false, reason = "Не удалось получить следующую часть Spotify.")
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { model.acceptSpotifyImport(result) } }
        compose.onNodeWithText("Получена только часть плейлиста").assertIsDisplayed()
        compose.onNodeWithText("Сохранить 2 трека").assertIsDisplayed()
        org.junit.Assert.assertEquals(listOf(existing), app.library.library.value.playlists)
        capture("07-partial-import")
        compose.onNodeWithText("Не сохранять").performClick()
        org.junit.Assert.assertNull(model.screen.value.pendingImport)
        org.junit.Assert.assertEquals(listOf(existing), app.library.library.value.playlists)
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { model.acceptSpotifyImport(result) } }
        compose.onNodeWithText("Сохранить 2 трека").performClick()
        compose.waitUntil(5000) { app.library.library.value.playlists.size == 2 }
        val lists = LibraryStore(app).library.value.playlists
        org.junit.Assert.assertEquals(existing, lists.first { it.id == existing.id })
        org.junit.Assert.assertEquals(2, lists.first { it.id != existing.id }.tracks.size)
    }
    @Test fun completeSpotifyImportUpdatesExistingPlaylistWithAll237Songs() {
        val songs = (0 until 237).map { Track("spotify:track:$it", "Song $it", "Artist", source = "spotify") }
        val playlist = Playlist(id = "spotify-large", title = "Полный список", tracks = songs)
        app.library.putPlaylist(playlist.copy(tracks = songs.take(100)))
        compose.runOnUiThread { kotlinx.coroutines.runBlocking { model.acceptSpotifyImport(SpotifyImportResult(playlist, 237, 237, true)) } }
        org.junit.Assert.assertNull(model.screen.value.pendingImport)
        org.junit.Assert.assertEquals(songs, LibraryStore(app).library.value.playlists.single().tracks)
        compose.onNodeWithText("237 треков").assertIsDisplayed()
    }
    @Test fun navigatesSearchImportAndLibrary() {
        compose.onNodeWithContentDescription("Найти музыку").performClick()
        compose.onNodeWithText("Найди своё звучание").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Imagine Dragons")
        compose.onNodeWithText("Imagine Dragons").assertExists()
        compose.onNodeWithText("Коллекция", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Импорт").performClick()
        compose.onNodeWithText("Из Spotify — в Mauri").assertIsDisplayed()
        app.library.putPlaylist(Playlist(id = "test-playlist", title = "Мои находки", tracks = tracks))
        org.junit.Assert.assertSame(app.library.library, model.library)
        org.junit.Assert.assertEquals(1, model.library.value.playlists.size)
        compose.onNodeWithText("Коллекция", useUnmergedTree = true).performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Мои находки").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Перемешивание").assertIsDisplayed()
        compose.onNodeWithText("По порядку").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performTextInput("Afterglow")
        compose.onNodeWithText("Night Drive").assertDoesNotExist()
        compose.onAllNodesWithText("Afterglow").assertCountEquals(2)
    }
    @Test fun rendersPlaylistWithShuffleAndSort() {
        app.library.putPlaylist(Playlist(id = "night", title = "Ночной маршрут", tracks = tracks, artwork = tracks.first().artwork, pinned = true))
        compose.runOnUiThread { model.openPlaylist("night") }
        org.junit.Assert.assertEquals(1, model.library.value.playlists.size)
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("Перемешивание").assertIsDisplayed().performClick()
        compose.waitUntil(5000) { app.settings.state.value.queueMode == QueueMode.SHUFFLE }
        compose.onNodeWithText("Вразброс").assertIsDisplayed()
        capture("02-playlist")
    }
    private fun playerState() = PlaybackState(ready = true, track = tracks.first(), queue = tracks, duration = 189000, position = 43000, mode = QueueMode.FRESH, shuffle = true, order = listOf(0, 3, 1, 4, 2), entries = tracks.mapIndexed { i, t -> QueueEntry(i, "uid-$i", t) }, context = "Ночной маршрут")
    private fun showPlayer() {
        val m = model; val state = playerState()
        compose.runOnUiThread { compose.activity.setContent { MeloTheme { FullPlayer(state, LyricsState(state.track!!.id, lyrics = Lyrics(lines = listOf(LyricLine(0, "Город медленно засыпает"), LyricLine(40000, "А музыка остаётся с нами"), LyricLine(60000, "Дальше только тёплый свет"), LyricLine(72000, "Фонари уходят за поворот"), LyricLine(84000, ""), LyricLine(96000, "Я слышу город между строк"), LyricLine(108000, "И ночь поёт вполголоса"), LyricLine(120000, "Пока не кончится дорога")))), Library(), m, {}) } } }
    }
    @Test fun rendersFullPlayerAndTimer() {
        showPlayer()
        compose.onNodeWithText("Night Drive").assertIsDisplayed()
        compose.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
        capture("03-player")
        compose.onNodeWithText("Таймер").performClick()
        compose.onNodeWithText("После текущего трека").assertIsDisplayed()
    }
    @Test fun rendersSynchronizedLyricsAndSettings() {
        showPlayer()
        compose.onNodeWithText("Текст песни").performClick()
        compose.onNodeWithText("А музыка остаётся с нами").assertIsDisplayed()
        capture("04-lyrics")
        revealLyricControls()
        compose.onNodeWithContentDescription("Настройки текста").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("+0,5 с").performScrollTo().performClick()
        org.junit.Assert.assertSame(app.settings.state, model.settings)
        compose.waitUntil(5000) { app.settings.state.value.lyricOffsets[tracks.first().id] == 500L }
    }
    @Test fun lyricAppearanceIsAccessibleFromGeneralSettingsAndPersists() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNodeWithText("Оформление текста").performScrollTo().performClick()
        compose.onNodeWithText("Красный").performScrollTo().performClick()
        compose.onNodeWithContentDescription("По центру").performScrollTo().performClick()
        org.junit.Assert.assertEquals(LyricBackdrop.RED, AppSettings(app).state.value.lyricBackdrop)
        org.junit.Assert.assertTrue(AppSettings(app).state.value.lyricCentered)
        capture("11-lyrics-settings")
    }
    @Test fun generalSettingsStoreStartScreenAndExposePlaybackPreferences() {
        compose.onNodeWithContentDescription("Настройки").performClick()
        compose.onNode(hasText("Коллекция") and hasAnyAncestor(isDialog())).performClick()
        org.junit.Assert.assertEquals(StartScreen.LIBRARY,AppSettings(app).state.value.startScreen)
        compose.onNodeWithText("При запуске").assertIsDisplayed()
        capture("13-general-settings")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun plainLyricsStayScrollableWithoutHidingPlaybackAtLargestSize() {
        app.settings.lyricSize(42f)
        val m=model;val state=playerState()
        compose.runOnUiThread { compose.activity.setContent { MeloTheme { FullPlayer(state,LyricsState(state.track!!.id,lyrics=Lyrics(plain=List(12){"Обычный текст без временных меток."}.joinToString("\n"))),Library(),m,{}) } } }
        compose.onNodeWithText("Текст песни").performClick()
        compose.onNodeWithText("Без синхронизации · LRCLIB").assertIsDisplayed()
        revealLyricControls()
        compose.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
        capture("12-lyrics-plain")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun immersiveLyricsKeepCurrentLineAndPlaybackControlsOnCompactPhone() {
        showPlayer()
        compose.onNodeWithText("Текст песни").performClick()
        compose.onNodeWithText("А музыка остаётся с нами").assertIsSelected().assertIsDisplayed()
        // Full screen means only the text: no controls until the screen is tapped.
        compose.onNodeWithContentDescription("Настройки текста").assertDoesNotExist()
        compose.onNodeWithContentDescription("Воспроизвести").assertDoesNotExist()
        capture("10-lyrics-compact")
        revealLyricControls()
        compose.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
        compose.onNodeWithContentDescription("Настройки текста").assertIsDisplayed()
        compose.onNodeWithText("А музыка остаётся с нами").performTouchInput { swipeUp() }
        compose.onNodeWithText("К текущей строке").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Вернуться к обложке").performClick()
        compose.onNodeWithText("Текст песни").assertIsDisplayed()
    }
    @Test fun queueDisplaysActualShuffledOrder() {
        val m = model
        compose.runOnUiThread { compose.activity.setContent { MeloTheme { QueueSheet(playerState(), m, {}) } } }
        compose.onNodeWithText("Blue Hour").assertIsDisplayed()
        val blue = compose.onNodeWithText("Blue Hour").fetchSemanticsNode().boundsInRoot.top
        val after = compose.onNodeWithText("Afterglow").fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(blue < after)
        capture("05-queue")
        compose.onNodeWithContentDescription("Изменить очередь: Afterglow").performClick()
        compose.onNodeWithText("Поднять выше").assertIsDisplayed()
        compose.onNodeWithText("Играть следующей").assertIsDisplayed()
    }
    @Test fun queueSongCanBeDraggedAboveAnother() {
        val m = model
        compose.runOnUiThread { compose.activity.setContent { MeloTheme { QueueSheet(playerState(), m, {}) } } }
        fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top
        org.junit.Assert.assertTrue(top("Blue Hour") < top("Afterglow"))
        val distance = top("Afterglow") - top("Blue Hour") + 30f
        compose.onNodeWithContentDescription("Перетащить: Afterglow", useUnmergedTree = true).performTouchInput {
            down(center)
            repeat(12) { moveBy(androidx.compose.ui.geometry.Offset(0f, -distance / 12f)) }
            up()
        }
        compose.waitForIdle()
        org.junit.Assert.assertTrue("Afterglow should now be above Blue Hour", top("Afterglow") < top("Blue Hour"))
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi") fun playerFitsCompactPhone() {
        showPlayer()
        compose.onNodeWithContentDescription("Воспроизвести").assertIsDisplayed()
        compose.onNodeWithText("Текст песни").assertIsDisplayed()
        compose.onNodeWithText("Очередь · 5").performClick()
        compose.onNodeWithText("Вразброс").assertIsDisplayed()
        compose.onAllNodesWithText("Свежий микс").onLast().assertIsDisplayed()
        capture("06-compact-queue")
    }
    private fun revealLyricControls() {
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(androidx.compose.ui.geometry.Offset(width / 2f, 12f)) }
        compose.waitForIdle()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File("../artifacts/mauri-0.3/screenshots").apply { mkdirs() }
        compose.runOnIdle {
            val view = android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown } ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
}
