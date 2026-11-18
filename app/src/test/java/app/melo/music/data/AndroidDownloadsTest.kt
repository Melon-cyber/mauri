package app.melo.music.data
import android.app.DownloadManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import app.melo.music.playback.resolvePlaybackDataSpec
import app.melo.music.playback.toMediaItem
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26,34], application = MeloApplication::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidDownloadsTest {
    @Test fun systemRequestUsesAppStorageWifiPolicyAndVisibleNotification() {
        val app=ApplicationProvider.getApplicationContext<MeloApplication>()
        val backend=AndroidDownloads(app)
        val id=backend.enqueue("https://example.com/music.webm","Music",OfflineStore.safeFileName("a"),true)
        val manager=shadowOf(app.getSystemService(DownloadManager::class.java))
        val request=shadowOf(manager.getRequest(id))
        assertEquals("https://example.com/music.webm", request.uri.toString())
        assertEquals(DownloadManager.Request.NETWORK_WIFI,request.allowedNetworkTypes)
        assertFalse(request.allowedOverMetered)
        assertTrue(request.destination.toString().contains(app.packageName))
        assertTrue(request.destination.toString().endsWith(OfflineStore.safeFileName("a")))
        assertEquals(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,request.notificationVisibility)
        request.setStatus(DownloadManager.STATUS_RUNNING); request.setBytesSoFar(256); request.setTotalSize(1024)
        assertEquals(256L, backend.query(id)!!.bytes)
        backend.remove(id); assertNull(backend.query(id))
    }
    @Test fun downloadedFileIsReadByLocalMediaDataSourceWithoutNetwork() {
        val app=ApplicationProvider.getApplicationContext<MeloApplication>()
        val track=Track("spotify:track:offline-test","Offline","Artist",source="spotify")
        val name=OfflineStore.safeFileName(track.id)
        val directory=app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)!!.apply{mkdirs()}
        val file=java.io.File(directory,name).apply { writeBytes(ByteArray(1024) { (it % 127).toByte() }) }
        java.io.File(app.filesDir,"offline-v1.json").writeText(com.google.gson.Gson().toJson(listOf(OfflineTrack(track,-1,name,OfflineStatus.READY,1024,1024))))
        val store=OfflineStore(app)
        val factory=androidx.media3.datasource.ResolvingDataSource.Factory(androidx.media3.datasource.DefaultDataSource.Factory(app)) { spec ->
            resolvePlaybackDataSpec(spec,store){error("An offline song must not resolve any network URL")}
        }
        val source=factory.createDataSource()
        try {
            val uri=track.toMediaItem().localConfiguration!!.uri
            assertEquals(1024L,source.open(androidx.media3.datasource.DataSpec(uri)))
            val bytes=ByteArray(128); assertEquals(128,source.read(bytes,0,bytes.size))
            assertArrayEquals(file.readBytes().take(128).toByteArray(),bytes)
        } finally { source.close() }
    }
}
