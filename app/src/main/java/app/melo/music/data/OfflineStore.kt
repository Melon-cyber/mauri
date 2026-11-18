package app.melo.music.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.AtomicFile
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.security.MessageDigest

enum class OfflineStatus { RESOLVING, WAITING, DOWNLOADING, READY, FAILED }
data class OfflineTrack(val track: Track, val downloadId: Long = -1, val fileName: String = "", val status: OfflineStatus = OfflineStatus.RESOLVING, val bytes: Long = 0, val total: Long = 0, val error: String = "")
data class TransferState(val status: Int, val bytes: Long = 0, val total: Long = 0, val reason: Int = 0)
interface DownloadBackend {
    fun enqueue(url: String, title: String, fileName: String, wifiOnly: Boolean): Long
    fun query(id: Long): TransferState?
    fun remove(id: Long)
}
class AndroidDownloads(private val context: Context) : DownloadBackend {
    private val manager = context.getSystemService(DownloadManager::class.java)
    override fun enqueue(url: String, title: String, fileName: String, wifiOnly: Boolean): Long {
        require(Uri.parse(url).scheme == "https")
        return manager.enqueue(DownloadManager.Request(Uri.parse(url)).setTitle(title).setDescription("Mauri · музыка без сети")
            .addRequestHeader("User-Agent", Network.USER_AGENT).setAllowedOverMetered(!wifiOnly).setAllowedOverRoaming(false)
            .setAllowedNetworkTypes(if (wifiOnly) DownloadManager.Request.NETWORK_WIFI else DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName))
    }
    override fun query(id: Long): TransferState? = manager.query(DownloadManager.Query().setFilterById(id))?.use { c ->
        if (!c.moveToFirst()) null else TransferState(c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)), c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
    }
    override fun remove(id: Long) { manager.remove(id) }
}

class OfflineStore(private val context: Context, private val backend: DownloadBackend = AndroidDownloads(context)) {
    private val gson = Gson()
    private val manifest = AtomicFile(File(context.filesDir, "offline-v1.json"))
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    private val jobs = mutableMapOf<String, Job>()
    private val permits = Semaphore(2)
    val directory: File get() = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("Хранилище недоступно")
    private fun read(): List<OfflineTrack> = runCatching { manifest.openRead().bufferedReader().use { gson.fromJson(it.readText(), Array<OfflineTrack>::class.java).toList() } }.getOrDefault(emptyList())
    @Synchronized private fun change(transform: (List<OfflineTrack>) -> List<OfflineTrack>) {
        val next = transform(mutable.value)
        val output = manifest.startWrite()
        try { output.write(gson.toJson(next).toByteArray()); manifest.finishWrite(output); mutable.value = next }
        catch (e: Exception) { manifest.failWrite(output); throw e }
    }
    private fun replace(entry: OfflineTrack) = change { listOf(entry) + it.filterNot { old -> old.track.id == entry.track.id } }
    fun localFile(track: Track): File? {
        val entry = state.value.find { it.track.id == track.id } ?: return null
        if (entry.status != OfflineStatus.READY || entry.fileName != safeFileName(track.id)) return null
        return File(directory, entry.fileName).takeIf { it.isFile && it.length() > 0 }
    }
    fun cover(url: String): String = if (url.isBlank()) url else File(context.filesDir, "offline-art/${hash(url)}.jpg").takeIf { it.isFile && it.length() > 0 }?.toURI()?.toString() ?: url
    @Synchronized fun enqueue(track: Track, scope: CoroutineScope, wifiOnly: Boolean, resolve: suspend (Track) -> AudioStream) {
        if (track.isLocal || jobs[track.id]?.isActive == true || state.value.any { it.track.id == track.id && it.status in listOf(OfflineStatus.READY, OfflineStatus.WAITING, OfflineStatus.DOWNLOADING) && (it.status != OfflineStatus.READY || localFile(track) != null) }) return
        val previous = state.value.find { it.track.id == track.id }
        if (previous != null && previous.downloadId >= 0) backend.remove(previous.downloadId)
        replace(OfflineTrack(track))
        val job = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try { permits.withPermit {
                val audio = resolve(track)
                ensureActive()
                val fileName = safeFileName(track.id)
                val target = File(directory, fileName)
                directory.mkdirs()
                require(directory.usableSpace > 20L * 1024 * 1024) { "Недостаточно свободного места" }
                if (target.exists()) target.delete()
                synchronized(this@OfflineStore) {
                    ensureActive()
                    val old = state.value.find { it.track.id == track.id }
                    if (old?.downloadId != null && old.downloadId >= 0) backend.remove(old.downloadId)
                    val id = backend.enqueue(audio.url, "${track.artist} — ${track.title}", fileName, wifiOnly)
                    replace(OfflineTrack(track, id, fileName, OfflineStatus.WAITING))
                }
                cacheCover(track.artwork)
            } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                ensureActive()
                runCatching { replace(OfflineTrack(track, status = OfflineStatus.FAILED, error = if (e.message?.contains("места") == true) e.message!! else "Не удалось подготовить загрузку. Попробуй ещё раз.")) }
            }
        }
        jobs[track.id] = job
        job.start()
    }
    suspend fun observe() { while (currentCoroutineContext().isActive) { runCatching { refresh() }; delay(1000) } }
    @Synchronized fun refresh() {
        val next = state.value.map { entry ->
            if (entry.status == OfflineStatus.RESOLVING && jobs[entry.track.id]?.isActive != true) entry.copy(status = OfflineStatus.FAILED, error = "Подготовка прервалась. Нажми «Повторить».")
            else if (entry.downloadId < 0 || entry.status == OfflineStatus.FAILED) entry
            else runCatching {
                val transfer = backend.query(entry.downloadId)
                when {
                    transfer == null -> entry.copy(status = OfflineStatus.FAILED, error = "Загрузка удалена системой. Скачай заново.")
                    transfer.status == DownloadManager.STATUS_SUCCESSFUL -> if (File(directory, entry.fileName).length() > 0) entry.copy(status = OfflineStatus.READY, bytes = File(directory, entry.fileName).length(), total = File(directory, entry.fileName).length(), error = "") else entry.copy(status = OfflineStatus.FAILED, error = "Файл не найден. Скачай заново.")
                    transfer.status == DownloadManager.STATUS_FAILED -> entry.copy(status = OfflineStatus.FAILED, error = if (transfer.reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) "Недостаточно места" else "Загрузка прервалась. Проверь сеть и повтори.")
                    else -> entry.copy(status = if (transfer.status == DownloadManager.STATUS_RUNNING) OfflineStatus.DOWNLOADING else OfflineStatus.WAITING, bytes = transfer.bytes, total = transfer.total.coerceAtLeast(0))
                }
            }.getOrDefault(entry)
        }
        if (next != state.value) change { next }
    }
    @Synchronized fun remove(id: String) {
        jobs.remove(id)?.cancel()
        val entry = state.value.find { it.track.id == id }
        if (entry != null) {
            if (entry.downloadId >= 0) backend.remove(entry.downloadId)
            if (entry.fileName == safeFileName(id)) File(directory, entry.fileName).delete()
        }
        change { it.filterNot { e -> e.track.id == id } }
    }
    private fun cacheCover(url: String) {
        if (!url.startsWith("https://")) return
        runCatching { Network.client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) return@use
            val data = response.body?.byteStream()?.use { input ->
                val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
                while (out.size() <= 3_000_000) { val count = input.read(chunk); if (count < 0) break; out.write(chunk, 0, count) }
                out.toByteArray()
            } ?: return@use
            if (data.size > 3_000_000) return@use
            val dir = File(context.filesDir, "offline-art").apply { mkdirs() }
            val file = AtomicFile(File(dir, "${hash(url)}.jpg"))
            val out = file.startWrite(); try { out.write(data); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out) }
        } }
    }
    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
        fun safeFileName(id: String) = "mauri-${hash(id)}.audio"
    }
}
