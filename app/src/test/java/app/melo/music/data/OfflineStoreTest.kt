package app.melo.music.data
import android.app.DownloadManager
import androidx.test.core.app.ApplicationProvider
import app.melo.music.MeloApplication
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = MeloApplication::class)
class OfflineStoreTest {
    private val app get() = ApplicationProvider.getApplicationContext<MeloApplication>()
    private class Backend : DownloadBackend {
        val states = mutableMapOf<Long, TransferState>(); var next = 0L; var wifi = false
        override fun enqueue(url: String, title: String, fileName: String, wifiOnly: Boolean): Long { wifi=wifiOnly; return (++next).also { states[it]=TransferState(DownloadManager.STATUS_PENDING) } }
        override fun query(id: Long) = states[id]
        override fun remove(id: Long) { states.remove(id) }
    }
    private suspend fun waitFor(store: OfflineStore, status: OfflineStatus) { withTimeout(5000) { while(store.state.value.firstOrNull()?.status != status) delay(10) } }
    @Test fun onlyCompleteFilesArePlayableAndSurviveRestartWithoutResolvingAgain() = runBlocking {
        val backend=Backend(); val store=OfflineStore(app,backend); val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val track=Track("safe/../id","Track","Artist"); var resolutions=0
        try {
            store.enqueue(track,scope,true) { resolutions++; AudioStream("https://example.com/audio","audio/webm",160,Long.MAX_VALUE) }
            waitFor(store,OfflineStatus.WAITING); assertTrue(backend.wifi); assertNull(store.localFile(track))
            val entry=store.state.value.single(); assertFalse(entry.fileName.contains('/'))
            File(store.directory,entry.fileName).writeBytes(byteArrayOf(1,2,3,4))
            backend.states[entry.downloadId]=TransferState(DownloadManager.STATUS_SUCCESSFUL,4,4)
            store.refresh(); assertEquals(OfflineStatus.READY,store.state.value.single().status)
            val restored=OfflineStore(app,backend)
            assertArrayEquals(byteArrayOf(1,2,3,4),restored.localFile(track)!!.readBytes())
            restored.enqueue(track,scope,true) { error("Must not resolve a downloaded song") }
            assertEquals(1,resolutions)
            restored.localFile(track)!!.delete(); restored.refresh()
            assertEquals(OfflineStatus.FAILED,restored.state.value.single().status)
            restored.remove(track.id); assertTrue(restored.state.value.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun failureIsRetryableAndMissingFileNeverGetsReadyBadge() = runBlocking {
        val backend=Backend(); val store=OfflineStore(app,backend); val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO); val track=Track("a","A","Artist")
        try {
            store.enqueue(track,scope,false) { error("Expired source") }; waitFor(store,OfflineStatus.FAILED)
            store.enqueue(track,scope,false) { AudioStream("https://example.com/new","audio/webm",160,Long.MAX_VALUE) }; waitFor(store,OfflineStatus.WAITING)
            assertFalse(backend.wifi)
            backend.states[store.state.value.single().downloadId]=TransferState(DownloadManager.STATUS_SUCCESSFUL)
            store.refresh(); assertEquals(OfflineStatus.FAILED,store.state.value.single().status)
        } finally { scope.cancel() }
    }
    @Test fun cancelDuringPreparationCannotResurrectDownload() = runBlocking {
        val backend=Backend(); val store=OfflineStore(app,backend); val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO); val deferred=CompletableDeferred<AudioStream>()
        try {
            store.enqueue(Track("a","A","Artist"),scope,true) { deferred.await() }
            store.remove("a"); deferred.complete(AudioStream("https://example.com/audio","audio/webm",160,Long.MAX_VALUE)); delay(50)
            assertTrue(store.state.value.isEmpty()); assertEquals(0L,backend.next)
        } finally { scope.cancel() }
    }
}
