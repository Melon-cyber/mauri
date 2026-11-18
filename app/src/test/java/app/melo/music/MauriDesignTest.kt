package app.melo.music
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.session.MediaSessionService
import androidx.test.core.app.ApplicationProvider
import app.melo.music.data.*
import app.melo.music.playback.*
import app.melo.music.ui.resourceArtwork
import com.google.gson.Gson
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=MeloApplication::class,qualifiers="w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@android.annotation.TargetApi(34)
class MauriDesignTest {
    @get:Rule(order=0) val fixture=object:ExternalResource() {
        var service:org.robolectric.android.controller.ServiceController<MusicService>?=null
        override fun before() {
            val app=ApplicationProvider.getApplicationContext<MeloApplication>()
            fun songs(prefix:String,n:Int)= (0 until n).map { i -> Track("$prefix-$i",if(prefix=="night" && i==7) "Natural" else "Песня ${i+1}",if(prefix=="night" && i==7) "Imagine Dragons" else "Mauri Sessions",189000,resourceArtwork(R.drawable.night_drive)) }
            val night=songs("night",24); val road=songs("road",32)
            app.library.putPlaylist(Playlist(id="night",title="Ночной маршрут",tracks=night,artwork=resourceArtwork(R.drawable.night_drive)))
            app.library.putPlaylist(Playlist(id="quiet",title="Тихий вечер",tracks=songs("quiet",18),artwork=resourceArtwork(R.drawable.collection_arch)))
            app.library.putPlaylist(Playlist(id="road",title="В дороге",tracks=road,artwork=resourceArtwork(R.drawable.collection_road)))
            app.library.putPlaylist(Playlist(id="best",title="Лучшее",tracks=songs("best",57),artwork=resourceArtwork(R.drawable.collection_moon)))
            songs("liked",128).forEach(app.library::toggleLike)
            val downloadDir=app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!.apply{mkdirs()}
            val entries=road.map { track -> val name=OfflineStore.safeFileName(track.id); File(downloadDir,name).writeBytes(ByteArray(1024)); OfflineTrack(track,-1,name,OfflineStatus.READY,1024,1024) }
            File(app.filesDir,"offline-v1.json").writeText(Gson().toJson(entries))
            PlaybackPersistence(app).save(PlaybackSnapshot(night,7,43000,night.indices.toList(),context="Ночной маршрут"))
            val day=LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            app.statistics.record(listOf(ListeningDelta(night[7],day,42*60000L,14),ListeningDelta(night[8],day,18*60000L,6)))
            service=Robolectric.buildService(MusicService::class.java).create()
            shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,MusicService::class.java),service!!.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE)))
        }
        override fun after(){service?.destroy()}
    }
    @get:Rule(order=1) val compose=createAndroidComposeRule<MainActivity>()
    @Test fun populatedHomeMatchesChosenStructure() {
        compose.onNodeWithText("С возвращением").assertIsDisplayed()
        compose.onNodeWithText("Лучшее").assertIsDisplayed()
        compose.onNodeWithText("Избранное").assertIsDisplayed()
        compose.onNodeWithText("Тихий вечер").assertIsDisplayed()
        capture("home-populated")
    }
    @Test fun downloadsAreNavigableAndShowRealStoredStatus() {
        compose.onNodeWithText("Коллекция",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Загрузки").performClick()
        compose.onNodeWithText("Музыка без сети").assertIsDisplayed()
        compose.onNodeWithText("Загружать по Wi-Fi").assertIsDisplayed()
        capture("downloads")
    }
    @Test fun statisticsDisplayDataAndClearOnlyStatistics() {
        compose.onNodeWithText("Коллекция",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Твой ритм").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("1 ч 0 мин").fetchSemanticsNodes().isNotEmpty()}
        capture("statistics")
        compose.onNodeWithText("30 дней").performClick()
        compose.onNodeWithText("Всё время").performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Очистить статистику"))
        compose.onNodeWithText("Очистить статистику").performClick()
        compose.onNodeWithText("Очистить").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithText("У каждой песни есть история").fetchSemanticsNodes().isNotEmpty()}
        val app=compose.activity.application as MeloApplication
        org.junit.Assert.assertEquals(4,app.library.library.value.playlists.size)
    }
    @Test @Config(qualifiers="w320dp-h640dp-xhdpi") fun compactHomeKeepsNavigationAndScrollableCollections() {
        compose.onNodeWithText("Коллекция",useUnmergedTree=true).assertIsDisplayed()
        compose.onNode(hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(hasText("Тихий вечер"))
        compose.onNodeWithText("Тихий вечер").assertIsDisplayed()
        capture("home-compact")
    }
    private fun capture(name:String){
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val dir=File("../artifacts/mauri-0.3/design").apply{mkdirs()}
            File(dir,"$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
}
