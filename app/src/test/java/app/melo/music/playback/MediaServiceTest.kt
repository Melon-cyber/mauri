package app.melo.music.playback

import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import app.melo.music.data.Track
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class MediaServiceTest {
    @Test fun connectsControllerToServiceAndTransfersQueue() {
        val app = ApplicationProvider.getApplicationContext<MeloApplication>()
        val component = ComponentName(app, MusicService::class.java)
        val service = Robolectric.buildService(MusicService::class.java).create()
        val binder = service.get().onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
        assertNotNull(binder)
        shadowOf(app).setComponentNameAndServiceForBindService(component, binder)
        val future = MediaController.Builder(app, SessionToken(app, component)).buildAsync()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!future.isDone && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            val controller = future.get(1, TimeUnit.SECONDS)
            assertTrue(controller.isConnected)
            controller.setMediaItems(listOf(Track("1", "One", "Artist", url = "https://music.youtube.com/watch?v=12345678901").toMediaItem(), Track("2", "Two", "Artist", url = "https://music.youtube.com/watch?v=12345678902").toMediaItem()))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, controller.mediaItemCount)
            assertEquals("One", controller.currentMediaItem?.mediaMetadata?.title)
            fun command(action: String, args: Bundle = Bundle.EMPTY) {
                val result = controller.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(SessionResult.RESULT_SUCCESS, result.get(1, TimeUnit.SECONDS).resultCode)
                shadowOf(Looper.getMainLooper()).idle()
            }
            command(PlaybackCommands.MODE, Bundle().apply { putString("mode", "SHUFFLE"); putBoolean("freshStart", true) })
            assertTrue(controller.shuffleModeEnabled)
            assertEquals("SHUFFLE", controller.sessionExtras.getString("mode"))
            command(PlaybackCommands.NEXT, Bundle().apply { putString("track", com.google.gson.Gson().toJson(Track("3", "Next", "Artist"))) })
            assertEquals(3, controller.mediaItemCount)
            val order = controller.playbackOrder()
            assertEquals("Next", controller.getMediaItemAt(order[order.indexOf(controller.currentMediaItemIndex) + 1]).mediaMetadata.title)
            val nextIndex = order[1]
            command(PlaybackCommands.MOVE, Bundle().apply { putInt("index", nextIndex); putInt("delta", 1) })
            assertEquals(nextIndex, controller.playbackOrder().last())
            command(PlaybackCommands.TIMER, Bundle().apply { putInt("minutes", 15) })
            assertTrue(controller.sessionExtras.getLong("sleepUntil") > System.currentTimeMillis())
            command(PlaybackCommands.TIMER, Bundle().apply { putBoolean("afterTrack", true) })
            assertNotNull(controller.sessionExtras.getString("sleepTrack"))
            controller.seekToDefaultPosition(controller.playbackOrder()[1])
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(controller.sessionExtras.getString("sleepTrack"))
            assertFalse(controller.playWhenReady)
            command(PlaybackCommands.CLEAR)
            assertEquals(1, controller.mediaItemCount)
            assertEquals(listOf(0), PlaybackPersistence(app).read().order)
        } finally {
            MediaController.releaseFuture(future)
            shadowOf(Looper.getMainLooper()).idle()
            service.destroy()
        }
    }
}
