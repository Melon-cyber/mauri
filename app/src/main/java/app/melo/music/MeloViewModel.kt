package app.melo.music

import android.app.Application
import android.content.ComponentName
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.melo.music.data.*
import app.melo.music.playback.MusicService
import app.melo.music.playback.toMediaItem
import app.melo.music.playback.toTrack
import app.melo.music.playback.PlaybackCommands
import app.melo.music.playback.playbackOrder
import app.melo.music.playback.QueuePolicy
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.*
import app.melo.music.playback.PlaybackPersistence

enum class Tab { HOME, SEARCH, LIBRARY, IMPORT, DOWNLOADS, STATS }
data class ScreenState(
    val tab: Tab = Tab.HOME,
    val query: String = "",
    val searchedQuery: String = "",
    val results: List<Track> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val hasMore: Boolean = false,
    val importing: Boolean = false,
    val importUrl: String = "",
    val importError: String? = null,
    val importProgress: String = "",
    val pendingImport: SpotifyImportResult? = null,
    val selectedPlaylist: String? = null,
    val fullPlayer: Boolean = false,
    val message: String? = null,
    val radioLoading: Boolean = false,
)
data class QueueEntry(val index: Int, val uid: String, val track: Track)
data class PlaybackState(
    val ready: Boolean = false,
    val track: Track? = null,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val position: Long = 0,
    val duration: Long = 0,
    val shuffle: Boolean = false,
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val error: String? = null,
    val mode: QueueMode = QueueMode.ORDERED,
    val order: List<Int> = emptyList(),
    val entries: List<QueueEntry> = emptyList(),
    val context: String = "Твоя музыка",
    val sleepUntil: Long = 0,
    val sleepAfterTrack: Boolean = false,
    val radioLoading: Boolean = false,
    val radioFailed: Boolean = false,
    val now: Long = 0,
)
data class VersionsState(val trackId: String = "", val loading: Boolean = false, val options: List<Track> = emptyList(), val currentId: String? = null, val error: String? = null)
data class TranslationState(val trackId: String = "", val loading: Boolean = false, val lines: Map<String, String> = emptyMap(), val message: String? = null)
data class LyricsState(val trackId: String = "", val loading: Boolean = false, val lyrics: Lyrics = Lyrics(), val error: String? = null)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MeloViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MeloApplication
    val library = app.library.library
    val settings = app.settings.state
    val downloads = app.offline.state
    private val mutablePeriod = MutableStateFlow(StatsPeriod.WEEK)
    val statsPeriod = mutablePeriod.asStateFlow()
    val statistics = combine(app.statistics.revision, mutablePeriod) { _, period -> period }
        .mapLatest { withContext(Dispatchers.IO) { app.statistics.summary(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StatsSummary())
    private val mutableScreen = MutableStateFlow(ScreenState(tab = when(settings.value.startScreen) { StartScreen.HOME -> Tab.HOME; StartScreen.LIBRARY -> Tab.LIBRARY; StartScreen.SEARCH -> Tab.SEARCH }))
    val screen = mutableScreen.asStateFlow()
    private val mutablePlayback = MutableStateFlow(PlaybackPersistence(app).read().let { saved ->
        PlaybackState(track = saved.tracks.getOrNull(saved.index), queue = saved.tracks, index = saved.index, position = saved.position,
            duration = saved.tracks.getOrNull(saved.index)?.durationMs ?: 0, order = saved.order, mode = saved.mode, shuffle = saved.mode != QueueMode.ORDERED, repeat = saved.repeat, context = saved.context)
    })
    val playback = mutablePlayback.asStateFlow()
    private val mutableLyrics = MutableStateFlow(LyricsState())
    val lyrics = mutableLyrics.asStateFlow()
    private val mutableTranslation = MutableStateFlow(TranslationState())
    val translation = mutableTranslation.asStateFlow()
    private var translationJob: kotlinx.coroutines.Job? = null
    private val translator by lazy { LyricsTranslator(app) }
    fun lyricTranslate(value: Boolean) = app.settings.lyricTranslate(value)
    fun loadTranslation(state: LyricsState) {
        val lines = state.lyrics.lines.map { it.text }.ifEmpty { state.lyrics.plain.lines() }.filter { it.isNotBlank() }
        if (state.trackId.isBlank() || lines.isEmpty()) return
        val current = translation.value
        if (current.trackId == state.trackId && (current.loading || current.lines.isNotEmpty())) return
        translationJob?.cancel()
        mutableTranslation.value = TranslationState(state.trackId, loading = true)
        translationJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { translator.translate(state.trackId, lines) }
                mutableTranslation.value = TranslationState(state.trackId, lines = result.lines, message = if (result.alreadyRussian) "Песня уже на русском" else null)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableTranslation.value = TranslationState(state.trackId, message = "Перевод сейчас недоступен")
            }
        }
    }
    private val mutableVersions = MutableStateFlow(VersionsState())
    val versions = mutableVersions.asStateFlow()
    private var versionsJob: kotlinx.coroutines.Job? = null
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var lyricsJob: Job? = null
    private var nextPage: org.schabi.newpipe.extractor.Page? = null
    private var nextFilter: String = MusicRepository.SONGS
    private var loadedQuery = ""
    private var radioJob: Job? = null
    private val controllerFuture = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java))).buildAsync()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { syncPlayer() }
        override fun onPlayerError(error: PlaybackException) {
            val detail = generateSequence(error as Throwable) { it.cause }.last().message.orEmpty()
            mutablePlayback.update { it.copy(error = when {
                detail.contains("совпадение") -> detail
                error.errorCode in listOf(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT) -> "Не удалось подключиться. Проверьте интернет и доступность YouTube."
                else -> "Этот поток не удалось воспроизвести. Повторите попытку или выберите другую запись в поиске."
            }, buffering = false) }
        }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mutablePlayback.update { it.copy(error = null) }
            mediaItem?.toTrack()?.let(::loadLyrics)
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) { app.offline.observe() }
        controllerFuture.addListener({
            runCatching { controllerFuture.get() }.onSuccess { player ->
                controller = player
                player.addListener(playerListener)
                syncPlayer()
                player.currentMediaItem?.toTrack()?.let(::loadLyrics)
            }.onFailure { mutablePlayback.update { state -> state.copy(error = "Не удалось запустить плеер. Перезапустите приложение.") } }
        }, ContextCompat.getMainExecutor(app))
        viewModelScope.launch {
            while (isActive) {
                delay(500)
                syncPlayer()
            }
        }
    }

    private fun syncPlayer() {
        val p = controller ?: return
        val extras = p.sessionExtras
        val mode = runCatching { QueueMode.valueOf(extras.getString("mode") ?: if (p.shuffleModeEnabled) "SHUFFLE" else "ORDERED") }.getOrDefault(QueueMode.ORDERED)
        val uids = (0 until p.mediaItemCount).map { i -> p.getMediaItemAt(i).mediaMetadata.extras?.getString("queueUid") ?: "i$i" }
        val published = extras.getStringArrayList("order")?.let { list -> val at = uids.withIndex().associate { it.value to it.index }; list.mapNotNull { at[it] } }
        val order = published?.takeIf { QueuePolicy.isPermutation(it, p.mediaItemCount) } ?: p.playbackOrder()
        mutablePlayback.update { old -> old.copy(
            ready = true, track = p.currentMediaItem?.toTrack(), queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).toTrack() },
            index = p.currentMediaItemIndex.coerceAtLeast(0), playing = p.isPlaying,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            position = p.currentPosition.coerceAtLeast(0), duration = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: p.currentMediaItem?.mediaMetadata?.extras?.getLong("duration") ?: 0,
            shuffle = p.shuffleModeEnabled, repeat = p.repeatMode,
            mode = mode, order = order,
            entries = (0 until p.mediaItemCount).map { i -> QueueEntry(i, p.getMediaItemAt(i).mediaMetadata.extras?.getString("queueUid") ?: "$i", p.getMediaItemAt(i).toTrack()) },
            context = extras.getString("context") ?: "Твоя музыка", sleepUntil = extras.getLong("sleepUntil"),
            sleepAfterTrack = !extras.getString("sleepTrack").isNullOrEmpty(), radioLoading = extras.getBoolean("radioLoading"), radioFailed = extras.getBoolean("radioFailed"),
            now = if (extras.getLong("sleepUntil") > 0) System.currentTimeMillis() else 0,
        ) }
    }

    fun tab(tab: Tab) = mutableScreen.update { it.copy(tab = tab, selectedPlaylist = null) }
    fun continueListening() {
        if (playback.value.track != null) { if (!playback.value.playing) togglePlay(); fullPlayer(true); return }
        val playlist = library.value.playlists.sortedByDescending { it.pinned }.firstOrNull { it.tracks.isNotEmpty() }
        val tracks = playlist?.tracks ?: library.value.likes.ifEmpty { library.value.history }
        if (tracks.isEmpty()) { tab(Tab.IMPORT); return }
        play(tracks, context = playlist?.title ?: "Твоя музыка"); fullPlayer(true)
    }
    fun download(track: Track) {
        if (downloads.value.any { it.track.id == track.id && it.status == OfflineStatus.FAILED }) app.music.invalidate(track)
        runCatching { app.offline.enqueue(track, app.backgroundScope, settings.value.wifiDownloads) { withContext(Dispatchers.IO) { app.music.resolve(it) } } }
            .onSuccess { app.backgroundScope.launch { runCatching { app.lyrics.keepOffline(track) } } }
            .onFailure { message("Не удалось сохранить загрузку. Проверь свободное место.") }
    }
    fun downloadAll(tracks: List<Track>) { tracks.distinctBy { it.id }.forEach(::download); message("Песни добавлены в загрузки") }
    fun removeDownload(id: String) = ioAction { downloads.value.firstOrNull { it.track.id == id }?.track?.let(app.lyrics::dropOffline); app.offline.remove(id) }
    fun wifiDownloads(value: Boolean) = app.settings.wifiDownloads(value)
    fun statisticsEnabled(value: Boolean) = app.settings.statisticsEnabled(value)
    fun statsPeriod(value: StatsPeriod) { mutablePeriod.value = value }
    fun clearStatistics() = ioAction("Статистика очищена") { app.statistics.clear() }
    fun query(value: String) = mutableScreen.update { it.copy(query = value) }
    fun search(query: String = screen.value.query, more: Boolean = false) {
        val clean = query.trim()
        if (clean.isBlank()) return
        if (more && (!screen.value.hasMore || screen.value.searching)) return
        searchJob?.cancel()
        app.settings.rememberSearch(clean)
        val page = if (more) nextPage else null
        mutableScreen.update { it.copy(tab = Tab.SEARCH, query = clean, searchedQuery = clean, searching = true, searchError = null, results = if (more) it.results else emptyList(), selectedPlaylist = null) }
        searchJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { app.music.search(clean, page, if (more) nextFilter else MusicRepository.SONGS) }
                loadedQuery = clean
                nextPage = result.nextPage
                nextFilter = result.filter
                mutableScreen.update { it.copy(results = ((if (more) it.results else emptyList()) + result.tracks).distinctBy { track -> track.id }, searching = false, hasMore = org.schabi.newpipe.extractor.Page.isValid(result.nextPage)) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableScreen.update { it.copy(searching = false, searchError = "Не удалось выполнить поиск. Проверьте интернет и доступность YouTube Music.") }
            }
        }
    }
    fun loadMore() = search(loadedQuery, true)
    /** Voice request: songs from the own collection first, otherwise the first YouTube Music results. */
    fun playFromSearch(query: String) {
        val clean = query.trim()
        viewModelScope.launch {
            val local = Voice.matchLibrary(library.value, clean)
            if (local.isNotEmpty()) { play(local, 0, QueueMode.ORDERED, "По запросу «$clean»"); return@launch }
            if (clean.isBlank()) { continueListening(); return@launch }
            val found = runCatching { withContext(Dispatchers.IO) { app.music.search(clean).tracks } }.getOrDefault(emptyList())
            if (found.isNotEmpty()) play(found.take(20), 0, QueueMode.ORDERED, "По запросу «$clean»") else search(clean)
        }
    }
    fun importUrl(value: String) = mutableScreen.update { it.copy(importUrl = value, importError = null) }
    fun receiveShare(text: String) = mutableScreen.update { it.copy(tab = Tab.IMPORT, importUrl = text, selectedPlaylist = null) }
    fun importSpotify() {
        if (screen.value.importing) return
        val url = screen.value.importUrl
        mutableScreen.update { it.copy(importing = true, importError = null, pendingImport = null, importProgress = "Получаем плейлист…") }
        viewModelScope.launch {
            try {
                val taskContext = currentCoroutineContext()
                val result = withContext(Dispatchers.IO) { LinkImporter.fetch(url) { progress ->
                    taskContext.ensureActive()
                    mutableScreen.update { it.copy(importProgress = progress) }
                } }
                acceptSpotifyImport(result)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableScreen.update { it.copy(importing = false, importProgress = "", importError = e.message ?: "Не удалось импортировать плейлист") }
            }
        }
    }
    internal suspend fun acceptSpotifyImport(result: SpotifyImportResult) {
        if (!result.complete) {
            mutableScreen.update { it.copy(importing = false, importProgress = "", pendingImport = result, tab = Tab.IMPORT, selectedPlaylist = null) }
        } else {
            withContext(Dispatchers.IO) {
                val previous = app.library.library.value.playlists.firstOrNull { it.id == result.playlist.id }
                app.library.putPlaylist(result.playlist.copy(pinned = previous?.pinned ?: false, createdAt = previous?.createdAt ?: result.playlist.createdAt))
            }
            imported(result.playlist)
        }
    }
    fun dismissPartialImport() = mutableScreen.update { it.copy(pendingImport = null) }
    fun savePartialImport() {
        val result = screen.value.pendingImport ?: return
        mutableScreen.update { it.copy(pendingImport = null) }
        importTask { result.partialCopy() }
    }
    private fun imported(playlist: Playlist) = mutableScreen.update {
        it.copy(importing = false, importProgress = "", pendingImport = null, tab = Tab.LIBRARY, selectedPlaylist = playlist.id, message = "В библиотеке: ${trackCountLabel(playlist.tracks.size)}")
    }
    fun importCsv(text: String, title: String) = importTask { SpotifyImporter.parseCsv(text, title) }
    private fun importTask(fetch: () -> Playlist) {
        if (screen.value.importing) return
        mutableScreen.update { it.copy(importing = true, importError = null, pendingImport = null, importProgress = "") }
        viewModelScope.launch {
            try {
                val playlist = withContext(Dispatchers.IO) { fetch().also { app.library.putPlaylist(it) } }
                imported(playlist)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                mutableScreen.update { it.copy(importing = false, importError = e.message ?: "Не удалось импортировать плейлист") }
            }
        }
    }
    fun openPlaylist(id: String?) = mutableScreen.update { it.copy(tab = Tab.LIBRARY, selectedPlaylist = id) }
    fun createPlaylist(title: String) = ioAction { app.library.putPlaylist(Playlist(title = title.trim().take(120))) }
    fun createPlaylistWithTrack(title: String, track: Track) = ioAction("Плейлист создан") { app.library.putPlaylist(Playlist(title = title.trim().take(120), tracks = listOf(track))) }
    fun deletePlaylist(id: String) { ioAction { app.library.deletePlaylist(id) }; openPlaylist(null) }
    fun renamePlaylist(id: String, title: String) = ioAction { app.library.renamePlaylist(id, title) }
    fun pinPlaylist(id: String) = ioAction { app.library.pinPlaylist(id) }
    fun movePlaylistTrack(id: String, trackId: String, delta: Int) = ioAction { app.library.moveTrack(id, trackId, delta) }
    fun duplicatePlaylist(playlist: Playlist) = ioAction("Копия сохранена") { app.library.putPlaylist(playlist.copy(id = java.util.UUID.randomUUID().toString(), title = "${playlist.title} — копия", sourceUrl = "", pinned = false)) }
    fun addToPlaylist(id: String, track: Track) = ioAction("Трек добавлен") { app.library.addToPlaylist(id, track) }
    fun removeFromPlaylist(id: String, trackId: String) = ioAction { app.library.removeFromPlaylist(id, trackId) }
    fun like(track: Track) = ioAction { app.library.toggleLike(track) }
    private fun ioAction(success: String? = null, action: () -> Unit) = viewModelScope.launch {
        try { withContext(Dispatchers.IO) { action() }; success?.let(::message) }
        catch (e: Exception) { message("Не удалось сохранить изменения: ${e.message}") }
    }
    fun message(text: String?) = mutableScreen.update { it.copy(message = text) }
    fun fullPlayer(open: Boolean) = mutableScreen.update { it.copy(fullPlayer = open) }
    fun play(tracks: List<Track>, index: Int = 0, mode: QueueMode? = null, context: String = "Твоя музыка") {
        radioJob?.cancel()
        startPlayback(tracks, index, mode ?: playback.value.mode, context)
    }
    private fun startPlayback(tracks: List<Track>, index: Int, mode: QueueMode, context: String) {
        if (tracks.isEmpty()) return
        val p = controller ?: run { message(playback.value.error ?: "Плеер ещё запускается. Попробуйте через секунду."); return }
        mutablePlayback.update { it.copy(error = null) }
        p.setMediaItems(tracks.map { it.toMediaItem() }, index.coerceIn(0, tracks.lastIndex), 0)
        command(PlaybackCommands.MODE, Bundle().apply { putString("mode", mode.name); putBoolean("freshStart", true) })
        command(PlaybackCommands.CONTEXT, Bundle().apply { putString("label", context) })
        p.prepare(); p.play()
    }
    /** Self-updating playlists for the home screen: daily mix, often played, forgotten favourites, new. */
    val smartMixes: StateFlow<List<SmartMix>> = combine(library, app.statistics.revision) { lib, _ -> lib }
        .mapLatest { lib -> withContext(Dispatchers.IO) { runCatching { SmartMixes.build(lib, app.statistics.history()) }.getOrDefault(emptyList()) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val mutableRecap = MutableStateFlow<YearRecap?>(null)
    val recap = mutableRecap.asStateFlow()
    /** Error trace from the last time the app closed by itself, offered once for a bug report. */
    private val mutableCrash = MutableStateFlow(CrashLog.read(application))
    val crashReport = mutableCrash.asStateFlow()
    fun dismissCrash() { CrashLog.clear(getApplication()); mutableCrash.value = null }
    fun loadRecap(year: Int = java.time.LocalDate.now().year) {
        viewModelScope.launch { mutableRecap.value = withContext(Dispatchers.IO) { runCatching { app.statistics.year(year) }.getOrNull() } }
    }
    fun closeRecap() { mutableRecap.value = null }
    fun playMix(mix: SmartMix) {
        if (mix.kind != MixKind.DAILY) { play(mix.tracks, 0, QueueMode.ORDERED, mix.title); fullPlayer(true); return }
        radioJob?.cancel()
        radioJob = viewModelScope.launch {
            message("Собираем микс дня…")
            // A few new songs by the same artists, if the network answers quickly; the mix works without them too.
            val extra = withTimeoutOrNull(7_000) { withContext(Dispatchers.IO) {
                runCatching { app.music.recommend(mix.tracks, mix.tracks.first(), mix.tracks + library.value.history.take(40)) }.getOrDefault(emptyList())
            } }.orEmpty().take(8)
            val combined = buildList {
                val news = extra.toMutableList()
                mix.tracks.forEachIndexed { i, track -> add(track); if (i % 3 == 2 && news.isNotEmpty()) add(news.removeAt(0)) }
                addAll(news)
            }.distinctBy { it.id }
            startPlayback(combined, 0, QueueMode.ORDERED, "Микс дня")
            fullPlayer(true)
            message(null)
        }
    }
    fun playShuffled(tracks: List<Track>, fresh: Boolean = false, context: String = "Твоя музыка") {
        if (tracks.isEmpty()) return
        val recent = library.value.history.take(12).map { it.id }.toSet()
        val candidates = if (fresh) tracks.indices.filter { tracks[it].id !in recent }.ifEmpty { tracks.indices.toList() } else tracks.indices.toList()
        play(tracks, candidates.random(), if (fresh) QueueMode.FRESH else QueueMode.SHUFFLE, context)
    }
    fun playMyMix() = playShuffled((library.value.likes + library.value.history).distinctBy { it.id }, true, "Твой свежий микс")
    fun togglePlay() {
        val p = controller ?: return
        if (p.playWhenReady && p.playbackState != Player.STATE_ENDED) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED) p.prepare()
            p.play()
        }
    }
    fun next() { controller?.seekToNextMediaItem(); controller?.prepare() }
    fun previous() { controller?.let { if (it.currentPosition > 3000) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(position: Long) { controller?.seekTo(position.coerceAtLeast(0)) }
    fun seekBy(milliseconds: Long) { controller?.let { p ->
        p.seekTo((p.currentPosition + milliseconds).coerceIn(0L, p.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
    } }
    /** Spotify-style shuffle button: each tap moves to the next state (off → shuffle → fresh mix → off). */
    fun shuffle() = chooseMode(playback.value.mode.nextShuffle())
    /** Sets the shuffle state for the playing queue and remembers it for the next playlist. */
    fun chooseMode(mode: QueueMode, announce: Boolean = true) {
        app.settings.queueMode(mode)
        if (playback.value.track != null) setMode(mode)
        if (announce) message(mode.announcement)
    }
    /** Starts a list in the remembered shuffle state, as the big "Слушать" button does in Spotify. */
    fun playInMode(tracks: List<Track>, context: String, mode: QueueMode = app.settings.state.value.queueMode) = when (mode) {
        QueueMode.ORDERED -> play(tracks, mode = QueueMode.ORDERED, context = context)
        else -> playShuffled(tracks, mode == QueueMode.FRESH, context)
    }
    fun setMode(mode: QueueMode) = command(PlaybackCommands.MODE, Bundle().apply { putString("mode", mode.name) })
    fun reshuffle() = command(PlaybackCommands.RESHUFFLE)
    fun repeat() { controller?.let { it.repeatMode = when (it.repeatMode) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }
    fun queue(track: Track) { command(PlaybackCommands.LAST, Bundle().apply { putString("track", Gson().toJson(track)) }); message("Добавлено в конец очереди") }
    fun playNext(track: Track) { command(PlaybackCommands.NEXT, Bundle().apply { putString("track", Gson().toJson(track)) }); message("Сыграет следующим") }
    fun queueIndex(index: Int) { controller?.seekToDefaultPosition(index); controller?.prepare(); controller?.play() }
    fun removeQueue(index: Int) { controller?.removeMediaItem(index) }
    fun moveQueue(uid: String, delta: Int) {
        val index = (0 until (controller?.mediaItemCount ?: 0)).firstOrNull { controller?.getMediaItemAt(it)?.mediaMetadata?.extras?.getString("queueUid") == uid } ?: return
        command(PlaybackCommands.MOVE, Bundle().apply { putInt("index", index); putInt("delta", delta) })
    }
    fun clearQueue() = command(PlaybackCommands.CLEAR)
    fun saveQueueAsPlaylist(title: String) = ioAction("Очередь сохранена") {
        val state = playback.value
        val order = state.order.ifEmpty { state.queue.indices.toList() }
        app.library.putPlaylist(Playlist(title = title.trim().ifBlank { "Моя очередь" }, tracks = order.mapNotNull { state.queue.getOrNull(it) }.distinctBy { it.id }))
    }
    fun timer(minutes: Int = 0, afterTrack: Boolean = false) = command(PlaybackCommands.TIMER, Bundle().apply { putInt("minutes", minutes); putBoolean("afterTrack", afterTrack) })
    fun autoplay(value: Boolean) = app.settings.autoplay(value)
    fun speed(value: Float) = app.settings.speed(value)
    fun crossfade(seconds: Int) = app.settings.crossfade(seconds)
    fun normalizeVolume(value: Boolean) = app.settings.normalizeVolume(value)
    fun equalizer(value: EqPreset) = app.settings.equalizer(value)
    fun lyricSize(value: Float) = app.settings.lyricSize(value)
    fun lyricBackdrop(value: LyricBackdrop) = app.settings.lyricBackdrop(value)
    fun startScreen(value: StartScreen) = app.settings.startScreen(value)
    fun lyricCentered(value: Boolean) = app.settings.lyricCentered(value)
    fun lyricFollow(value: Boolean) = app.settings.lyricFollow(value)
    fun lyricMotion(value: Boolean) = app.settings.lyricMotion(value)
    fun lyricKeepAwake(value: Boolean) = app.settings.lyricKeepAwake(value)
    fun lyricGap(value: Float) = app.settings.lyricGap(value)
    fun lyricDim(value: Float) = app.settings.lyricDim(value)
    fun resetLyrics() = app.settings.resetLyrics()
    fun lyricOffset(trackId: String, value: Long) = app.settings.lyricOffset(trackId, value)
    /** Saves hand-made sync points; the fine shift starts again from zero so the points are exact. */
    fun saveLyricSync(trackId: String, anchors: List<LyricAnchor>) { app.settings.lyricAnchors(trackId, anchors); app.settings.lyricOffset(trackId, 0) }
    fun clearLyricSync(trackId: String) = app.settings.clearLyricSync(trackId)
    fun clearRecentSearches() = app.settings.clearSearches()
    private fun command(action: String, args: Bundle = Bundle.EMPTY) {
        val p = controller ?: return
        val future = p.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        future.addListener({
            runCatching { future.get() }.onSuccess { if (it.resultCode != SessionResult.RESULT_SUCCESS) message("Не удалось применить настройку") }
            syncPlayer()
        }, ContextCompat.getMainExecutor(app))
    }
    fun radio(track: Track) {
        radioJob?.cancel()
        mutableScreen.update { it.copy(radioLoading = true) }
        radioJob = viewModelScope.launch {
            try {
                val tracks = withContext(Dispatchers.IO) { QueuePolicy.uniqueRecommendations(app.music.radio(track), listOf(track)) }
                if (tracks.isEmpty()) { message("Для этой песни пока нет подходящего радио"); return@launch }
                startPlayback(listOf(track) + tracks, 0, QueueMode.ORDERED, "Радио · ${track.title}")
                fullPlayer(true)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { message("Радио пока недоступно. Проверьте подключение.") }
            finally { mutableScreen.update { it.copy(radioLoading = false) } }
        }
    }
    fun playlistRadio(playlist: Playlist) {
        val anchor=playlist.tracks.firstOrNull { !it.isLocal } ?: run { message("Нужны песни из музыкального каталога"); return }
        radioJob?.cancel()
        mutableScreen.update { it.copy(radioLoading=true) }
        radioJob=viewModelScope.launch {
            try {
                val tracks=withContext(Dispatchers.IO) { app.music.recommend(playlist.tracks,anchor,playlist.tracks+app.library.library.value.history.take(30)) }
                if(tracks.isEmpty()) {message("Новых подходящих песен знакомых исполнителей пока нет");return@launch}
                startPlayback(tracks,0,QueueMode.ORDERED,"По плейлисту · ${playlist.title}");fullPlayer(true)
            } catch(e:CancellationException){throw e} catch(_:Exception){message("Подбор пока недоступен. Попробуй позже.")}
            finally {mutableScreen.update {it.copy(radioLoading=false)}}
        }
    }
    fun retry() {
        playback.value.track?.let { app.music.invalidate(it) }
        mutablePlayback.update { it.copy(error = null) }
        controller?.prepare(); controller?.play()
    }
    fun loadLyrics(track: Track) {
        if (lyrics.value.trackId == track.id && lyrics.value.error == null) return
        lyricsJob?.cancel()
        mutableLyrics.value = LyricsState(track.id, loading = true)
        lyricsJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { app.lyrics.get(track) }
                mutableLyrics.value = LyricsState(track.id, lyrics = result)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                val reason = if (e is RateLimitException) e.message else "Сервисы текстов сейчас не ответили. Проверьте интернет и нажмите «Повторить»."
                mutableLyrics.value = LyricsState(track.id, error = reason ?: "Текст пока недоступен")
            }
        }
    }
    fun loadVersions(track: Track) {
        versionsJob?.cancel()
        mutableVersions.value = VersionsState(track.id, loading = true, currentId = app.music.current(track)?.id)
        versionsJob = viewModelScope.launch {
            try {
                val options = withContext(Dispatchers.IO) { app.music.versions(track) }
                mutableVersions.value = VersionsState(track.id, options = options, currentId = app.music.current(track)?.id)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {
                mutableVersions.value = VersionsState(track.id, error = "Не удалось загрузить версии. Проверьте интернет.")
            }
        }
    }
    fun chooseVersion(track: Track, recording: Track) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { app.music.choose(track, recording); app.lyrics.forget(track) }
            mutableVersions.update { it.copy(currentId = recording.id) }
            if (playback.value.track?.id == track.id) {
                command(PlaybackCommands.RELOAD)
                mutableLyrics.value = LyricsState()
                loadLyrics(track)
            }
            message("Теперь играет выбранная версия")
        }
    }
    /** Plays the recording whose length fits the timed lyrics (usually the album version instead of a long video). */
    fun useMatchingVersion(track: Track, expectedMs: Long) {
        viewModelScope.launch {
            message("Ищем подходящую запись…")
            val options = runCatching { withContext(Dispatchers.IO) { app.music.versions(track) } }.getOrDefault(emptyList())
            val current = app.music.current(track)?.id
            val fit = options.filter { it.id != current && it.durationMs > 0 }.minByOrNull { kotlin.math.abs(it.durationMs - expectedMs) }
                ?.takeIf { kotlin.math.abs(it.durationMs - expectedMs) <= 5_000 }
            if (fit == null) message("Подходящую запись не нашли — подгоните текст вручную") else chooseVersion(track, fit)
        }
    }
    fun exportLibrary(): String = app.library.export()
    fun exportStatistics(): String = app.statistics.exportBackup()
    fun restoreStatistics(json: String) = ioAction("Статистика восстановлена") { app.statistics.restoreBackup(json) }
    fun restoreLibrary(json: String) = ioAction("Библиотека восстановлена") { app.library.restore(json) }
    fun exportEverything(): String = FullBackup.export(app.library, app.statistics, app.settings)
    fun restoreEverything(json: String) = ioAction("Всё перенесено: библиотека, статистика и настройки") { FullBackup.restore(json, app.library, app.statistics, app.settings) }
    fun importLocal(uris: List<Uri>) = ioAction("Локальные треки добавлены") {
        val tracks = uris.map { uri ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                val filename = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "Аудиофайл" } ?: "Аудиофайл"
                Track("local:$uri", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: filename,
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "На устройстве",
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                    url = uri.toString(), source = "local")
            } finally { retriever.release() }
        }
        val old = library.value.playlists.firstOrNull { it.id == "local" }
        app.library.putPlaylist(Playlist(id = "local", title = "На устройстве", tracks = (old?.tracks.orEmpty() + tracks).distinctBy { it.id }))
    }
    override fun onCleared() {
        controller?.removeListener(playerListener)
        MediaController.releaseFuture(controllerFuture)
        super.onCleared()
    }
}
