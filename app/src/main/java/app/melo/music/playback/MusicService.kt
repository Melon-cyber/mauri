package app.melo.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.*
import app.melo.music.MainActivity
import app.melo.music.MeloApplication
import app.melo.music.data.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.gson.Gson
import kotlinx.coroutines.*
import kotlin.random.Random

@androidx.annotation.OptIn(UnstableApi::class)
class MusicService : MediaLibraryService() {
    private var session: MediaLibraryService.MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var persistence: PlaybackPersistence
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as MeloApplication
    private val gson = Gson()
    private var mode = QueueMode.ORDERED
    private var contextLabel = "Твоя музыка"
    private var sleep = SleepTimerState()
    private var fadeVolume = 1f
    private var previousId: String? = null
    private var recordedId: String? = null
    private var radioLoading = false
    private var radioFailed = false
    private var lastAutoplaySeed: String? = null
    private var recommendationContext: List<Track> = emptyList()
    private var recommendationGeneration = 0L
    private var lastExtras = ""
    private val meter = ListeningMeter()
    private var statisticsGeneration = -1L
    private val pendingListening = mutableListOf<ListeningDelta>()
    private var playSession = java.util.UUID.randomUUID().toString()

    override fun onCreate() {
        super.onCreate()
        persistence = PlaybackPersistence(this)
        val http = OkHttpDataSource.Factory(Network.client).setUserAgent(Network.USER_AGENT)
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this, http)) { spec ->
            resolvePlaybackDataSpec(spec, app.offline) { requested ->
                val audio = app.music.resolve(requested)
                val matched = app.music.match(requested)
                if (requested.source == "spotify" && matched.artwork.isNotBlank() && requested.artwork != matched.artwork) {
                    scope.launch {
                        for (index in 0 until player.mediaItemCount) {
                            val item = player.getMediaItemAt(index)
                            if (item.mediaId == requested.id && item.mediaMetadata.artworkUri?.toString() != matched.artwork) {
                                val metadata = item.mediaMetadata.buildUpon().setArtworkUri(android.net.Uri.parse(matched.artwork)).build()
                                player.replaceMediaItem(index, item.buildUpon().setMediaMetadata(metadata).build())
                            }
                        }
                        withContext(Dispatchers.IO) { app.library.enrichArtwork(requested.id, matched.artwork) }
                    }
                }
                android.net.Uri.parse(audio.url)
            }
        }
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        restore()
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                sampleListening()
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_REPEAT_MODE_CHANGED,
                        Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED, Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_POSITION_DISCONTINUITY)) save()
                maybeAutoplay()
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (previousId != mediaItem?.mediaMetadata?.extras?.getString("queueUid") || reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) playSession = java.util.UUID.randomUUID().toString()
                sampleListening()
                flushListening()
                val nextId = mediaItem?.mediaMetadata?.extras?.getString("queueUid")
                val departed = previousId?.takeIf { it != nextId || reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED }
                if (sleep.shouldStop(System.currentTimeMillis(), departedId = departed)) finishSleep()
                previousId = nextId
                recordedId = null
                preloadNext()
                radioFailed = false
                publish()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && sleep.afterTrackId != null) finishSleep()
            }
        })
        previousId = player.currentMediaItem?.mediaMetadata?.extras?.getString("queueUid")
        audio = AudioEffects(this)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) { audio.attach(audioSessionId, app.settings.state.value) }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_TIMELINE_CHANGED))
                    refreshWidget()
            }
        })
        refreshWidget()
        // Keep the like icon in step with the library, and move the widget's progress bar once a second while playing.
        scope.launch { app.library.library.collect { refreshWidget() } }
        scope.launch {
            while (isActive) {
                delay(1_000)
                if (player.isPlaying && MauriWidget.hasWidgets(this@MusicService))
                    runCatching { MauriWidget.progress(this@MusicService, player.currentPosition, player.duration.takeIf { it != C.TIME_UNSET } ?: 0) }
            }
        }
        audio.attach(player.audioSessionId, app.settings.state.value)
        scope.launch { app.settings.state.collect { audio.apply(it) } }
        scope.launch {
            // Fade the end of a song out and the next one in. A single player cannot overlap two songs,
            // so this is a smooth fade, not two songs playing at once.
            while (isActive) {
                delay(80)
                player.volume = fadeVolume(app.settings.state.value.crossfadeSeconds)
            }
        }
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibraryService.MediaLibrarySession.Builder(this, player, callback).setSessionActivity(pending).build()
        publish()
        scope.launch {
            var ticks = 0
            while (isActive) {
                delay(1000)
                tickSleep()
                sampleListening()
                val track = player.currentMediaItem?.toTrack()
                if (player.isPlaying && player.currentPosition >= 3000 && track != null && track.id != recordedId) {
                    recordedId = track.id
                    withContext(Dispatchers.IO) { app.library.addHistory(track) }
                }
                if (++ticks % 5 == 0) flushListening()
                if (ticks % 10 == 0) save()
            }
        }
        scope.launch { app.settings.state.collect { settings ->
            sampleListening()
            if (player.playbackParameters.speed != settings.speed) player.setPlaybackSpeed(settings.speed)
            maybeAutoplay()
        } }
    }

    private fun sampleListening() {
        val generation = app.statistics.generation
        if (generation != statisticsGeneration) {
            meter.reset(); pendingListening.clear(); statisticsGeneration = generation
        }
        if (!app.settings.state.value.statisticsEnabled) {
            meter.sample(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), null, null, false)?.let { pendingListening.add(it) }
            flushListening(); meter.reset(); return
        }
        meter.sample(android.os.SystemClock.elapsedRealtime(), System.currentTimeMillis(), player.currentMediaItem?.toTrack(),
            "${player.currentMediaItem?.mediaMetadata?.extras?.getString("queueUid")}:$playSession", player.isPlaying)?.let { pendingListening.add(it) }
    }
    private fun flushListening() {
        if (pendingListening.isEmpty()) return
        val deltas = pendingListening.toList(); pendingListening.clear()
        val generation = statisticsGeneration
        app.backgroundScope.launch { runCatching { app.statistics.record(deltas, expectedGeneration = generation) } }
    }

    // ---------- Android Auto / media browsing: Favourites, Recent and playlists ----------
    private fun folder(id: String, title: String, subtitle: String = ""): MediaItem = MediaItem.Builder().setMediaId(id)
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).setIsBrowsable(true).setIsPlayable(false)
            .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS).build()).build()

    private fun containerTracks(id: String): List<Track> {
        val library = app.library.library.value
        return when {
            id == "likes" -> library.likes
            id == "recent" -> library.history
            id.startsWith("pl:") -> library.playlists.firstOrNull { it.id == id.removePrefix("pl:") }?.tracks.orEmpty()
            else -> emptyList()
        }
    }

    private fun containerTitle(id: String): String = when {
        id == "likes" -> "Избранное"
        id == "recent" -> "Недавно звучало"
        id.startsWith("pl:") -> app.library.library.value.playlists.firstOrNull { it.id == id.removePrefix("pl:") }?.title ?: "Плейлист"
        else -> "Твоя музыка"
    }

    /** Browsable song: its id names the list and position, so choosing it plays the whole list from there. */
    private fun browseSong(container: String, index: Int, track: Track): MediaItem = MediaItem.Builder().setMediaId("$container|$index")
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist)
            .setArtworkUri(track.artwork.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).setIsBrowsable(false).setIsPlayable(true)
            .setMediaType(androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC).build()).build()

    private fun children(parentId: String): List<MediaItem> {
        val library = app.library.library.value
        return when (parentId) {
            ROOT -> listOfNotNull(
                folder("likes", "Избранное", trackCountLabel(library.likes.size)).takeIf { library.likes.isNotEmpty() },
                folder("recent", "Недавно звучало").takeIf { library.history.isNotEmpty() },
                folder("playlists", "Плейлисты", "${library.playlists.size}").takeIf { library.playlists.isNotEmpty() },
            )
            "playlists" -> library.playlists.sortedByDescending { it.pinned }.map { folder("pl:${it.id}", it.title, trackCountLabel(it.tracks.size)) }
            else -> containerTracks(parentId).take(500).mapIndexed { i, t -> browseSong(parentId, i, t) }
        }
    }

    private fun resolveBrowse(items: List<MediaItem>): MediaSession.MediaItemsWithStartPosition? {
        val single = items.singleOrNull()?.mediaId ?: return null
        if (!single.contains('|')) return null
        val container = single.substringBeforeLast('|')
        val index = single.substringAfterLast('|').toIntOrNull() ?: return null
        val tracks = containerTracks(container).takeIf { index in it.indices } ?: return null
        contextLabel = containerTitle(container)
        return MediaSession.MediaItemsWithStartPosition(tracks.map { it.toMediaItem() }, index, 0)
    }

    private val callback = object : MediaLibraryService.MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Mauri"), params))

        override fun onGetChildren(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: MediaLibraryService.LibraryParams?): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> {
            val all = children(parentId)
            val from = (page.coerceAtLeast(0) * pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
            val to = (from + pageSize.coerceAtLeast(1)).coerceAtMost(all.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(com.google.common.collect.ImmutableList.copyOf(all.subList(from, to)), params))
        }

        override fun onGetItem(session: MediaLibraryService.MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId.contains('|')) {
                val container = mediaId.substringBeforeLast('|'); val index = mediaId.substringAfterLast('|').toIntOrNull() ?: -1
                containerTracks(container).getOrNull(index)?.let { browseSong(container, index, it) }
            } else children(ROOT).firstOrNull { it.mediaId == mediaId }
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
        }

        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            resolveBrowse(mediaItems)?.let { return Futures.immediateFuture(it) }
            // Voice search from Android Auto / the assistant arrives as an item that only carries the spoken query.
            val spoken = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (spoken != null) {
                val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                scope.launch {
                    val local = Voice.matchLibrary(app.library.library.value, spoken)
                    val tracks = local.ifEmpty { runCatching { withContext(Dispatchers.IO) { app.music.search(spoken).tracks.take(20) } }.getOrDefault(emptyList()) }
                    if (tracks.isEmpty()) future.setException(IllegalStateException("Ничего не найдено"))
                    else { contextLabel = "По запросу «$spoken»"; future.set(MediaSession.MediaItemsWithStartPosition(tracks.map { it.toMediaItem() }, 0, 0)) }
                }
                return future
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null || !item.mediaId.contains('|')) item
                else containerTracks(item.mediaId.substringBeforeLast('|')).getOrNull(item.mediaId.substringAfterLast('|').toIntOrNull() ?: -1)?.toMediaItem() ?: item
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName) return super.onConnect(session, controller)
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
            PlaybackCommands.all.forEach { commands.add(SessionCommand(it, Bundle.EMPTY)) }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands.build()).build()
        }
        override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            val success = runCatching {
                when (customCommand.customAction) {
                    PlaybackCommands.MODE -> setMode(QueueMode.valueOf(args.getString("mode") ?: "ORDERED"), args.getBoolean("freshStart"))
                    PlaybackCommands.RESHUFFLE -> reshuffle()
                    PlaybackCommands.NEXT, PlaybackCommands.LAST -> {
                        val json = args.getString("track") ?: error("Missing track")
                        require(json.length < 16_384)
                        val track = gson.fromJson(json, Track::class.java)
                        require(track.id.isNotBlank() && track.title.isNotBlank())
                        enqueue(track, customCommand.customAction == PlaybackCommands.NEXT)
                    }
                    PlaybackCommands.MOVE -> move(args.getInt("index"), args.getInt("delta"))
                    PlaybackCommands.CLEAR -> clearUpcoming()
                    PlaybackCommands.TIMER -> setTimer(args.getInt("minutes", 0), args.getBoolean("afterTrack"))
                    PlaybackCommands.RELOAD -> reloadCurrent()
                    PlaybackCommands.CONTEXT -> { contextLabel = args.getString("label")?.take(140) ?: "Твоя музыка"; recommendationContext=tracks(); recommendationGeneration++; lastAutoplaySeed = null; radioFailed = false }
                    else -> error("Unknown command")
                }
                save(); publish()
            }.isSuccess
            return Futures.immediateFuture(SessionResult(if (success) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE))
        }
    }

    private fun tracks(): List<Track> = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toTrack() }
    private fun applyOrder(order: List<Int>) {
        if (QueuePolicy.isPermutation(order, player.mediaItemCount)) player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }
    private fun setMode(requested: QueueMode, freshStart: Boolean) {
        val before = player.playbackOrder()
        mode = requested
        if (requested == QueueMode.ORDERED) player.shuffleModeEnabled = false else {
            val order = if (freshStart) QueuePolicy.create(tracks(), player.currentMediaItemIndex, mode, Random.nextLong(), app.library.library.value.history)
                else QueuePolicy.reshuffle(before, player.currentMediaItemIndex, tracks(), mode, Random.nextLong(), app.library.library.value.history)
            applyOrder(order)
            player.shuffleModeEnabled = true
        }
    }
    private var widgetJob: kotlinx.coroutines.Job? = null
    private fun refreshWidget() {
        widgetJob?.cancel()
        widgetJob = scope.launch {
            val track = player.currentMediaItem?.toTrack()
            val liked = track != null && app.library.library.value.likes.any { it.id == track.id }
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: track?.durationMs ?: 0
            runCatching { MauriWidget.update(this@MusicService, MauriWidget.WidgetState(track, player.isPlaying, player.currentPosition, duration, liked)) }
        }
    }

    private lateinit var audio: AudioEffects
    private fun fadeVolume(seconds: Int): Float {
        if (seconds <= 0 || !player.isPlaying) return 1f
        val fade = seconds * 1000L
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return 1f
        val fadeIn = minOf(fade / 2, 2_500L)
        val inGain = if (position < fadeIn) (position.toFloat() / fadeIn).coerceIn(.05f, 1f) else 1f
        val hasNext = player.nextMediaItemIndex != C.INDEX_UNSET && player.repeatMode != Player.REPEAT_MODE_ONE
        val outGain = if (hasNext && duration - position < fade) ((duration - position).toFloat() / fade).coerceIn(0f, 1f) else 1f
        return minOf(inGain, outGain)
    }

    private var preloadJob: kotlinx.coroutines.Job? = null
    /**
     * While a song plays, quietly find the next one's audio and lyrics, so the switch is instant
     * even on a weak connection. Downloaded songs and local files need nothing.
     */
    private fun preloadNext() {
        val index = player.nextMediaItemIndex
        if (index == C.INDEX_UNSET) return
        val next = player.getMediaItemAt(index).toTrack()
        if (next.isLocal) return
        preloadJob?.cancel()
        preloadJob = scope.launch(Dispatchers.IO) {
            delay(4_000) // let the current song start first
            if (app.offline.localFile(next) == null) runCatching { app.music.resolve(next) }
            runCatching { app.lyrics.get(next) }
        }
    }

    /** Loads the current song again from its (possibly newly chosen) source, keeping the position. */
    private fun reloadCurrent() {
        val index = player.currentMediaItemIndex
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        if (uri.scheme != "melo") return
        val position = player.currentPosition
        val fresh = uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filter { it != "rev" }.forEach { name -> appendQueryParameter(name, uri.getQueryParameter(name)) }
            appendQueryParameter("rev", System.currentTimeMillis().toString())
        }.build()
        player.replaceMediaItem(index, item.buildUpon().setUri(fresh).build())
        player.seekTo(index, position)
        player.prepare()
    }

    private fun reshuffle() {
        if (mode == QueueMode.ORDERED) mode = QueueMode.SHUFFLE
        applyOrder(QueuePolicy.reshuffle(player.playbackOrder(), player.currentMediaItemIndex, tracks(), mode, Random.nextLong(), app.library.library.value.history))
        player.shuffleModeEnabled = true
    }
    private fun enqueue(track: Track, next: Boolean) {
        val before = player.playbackOrder()
        val oldCurrent = player.currentMediaItemIndex
        val insertion = if (next && player.mediaItemCount > 0) oldCurrent + 1 else player.mediaItemCount
        player.addMediaItem(insertion, track.toMediaItem())
        if (mode != QueueMode.ORDERED) applyOrder(if (next) QueuePolicy.insertNext(before, oldCurrent, insertion) else before + insertion)
    }
    private fun move(index: Int, delta: Int) {
        if (delta == 0 || kotlin.math.abs(delta) > player.mediaItemCount) return
        val order = player.playbackOrder()
        val updated = QueuePolicy.moveUpcoming(order, player.currentMediaItemIndex, index, delta)
        if (order == updated) return
        if (mode == QueueMode.ORDERED) player.moveMediaItem(index, index + delta) else applyOrder(updated)
    }
    private fun clearUpcoming() {
        if (player.mediaItemCount == 0) return
        val current = player.currentMediaItemIndex
        if (current + 1 < player.mediaItemCount) player.removeMediaItems(current + 1, player.mediaItemCount)
        if (current > 0) player.removeMediaItems(0, current)
        if (mode != QueueMode.ORDERED) applyOrder(listOf(0))
        lastAutoplaySeed = player.currentMediaItem?.mediaId
    }

    private fun setTimer(minutes: Int, afterTrack: Boolean) {
        player.volume = fadeVolume
        fadeVolume = player.volume
        sleep = when {
            afterTrack && player.currentMediaItem != null -> SleepTimerState(afterTrackId = player.currentMediaItem!!.mediaMetadata.extras?.getString("queueUid"))
            minutes in 1..480 -> SleepTimerState(untilMs = System.currentTimeMillis() + minutes * 60_000L)
            else -> SleepTimerState()
        }
    }
    private fun tickSleep() {
        val now = System.currentTimeMillis()
        if (sleep.shouldStop(now)) finishSleep()
        else if (sleep.untilMs > 0 && player.playWhenReady) player.volume = fadeVolume * sleep.volume(now)
    }
    private fun finishSleep() {
        player.pause()
        player.volume = fadeVolume
        sleep = SleepTimerState()
        publish(); save()
    }

    private fun maybeAutoplay() {
        if (!app.settings.state.value.autoplay || radioLoading || player.mediaItemCount == 0 || !player.playWhenReady || sleep.afterTrackId != null) return
        val order = player.playbackOrder()
        if (order.size - order.indexOf(player.currentMediaItemIndex) - 1 > 1) return
        val last = player.getMediaItemAt(order.last()).toTrack()
        if (last.isLocal || last.id == lastAutoplaySeed) return
        lastAutoplaySeed = last.id
        val count = player.mediaItemCount
        val identity = (0 until count).map { player.getMediaItemAt(it).mediaMetadata.extras?.getString("queueUid") }
        val sourceContext = recommendationContext.ifEmpty { tracks() }.toList()
        val generation = recommendationGeneration
        val excluded = tracks() + app.library.library.value.history.take(30)
        radioLoading = true; radioFailed = false; publish()
        scope.launch {
            try {
                val recommendations = withContext(Dispatchers.IO) { app.music.recommend(sourceContext,last,excluded) }
                if (generation != recommendationGeneration || player.mediaItemCount != count || !app.settings.state.value.autoplay || sleep.afterTrackId != null ||
                    identity != (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaMetadata.extras?.getString("queueUid") }) return@launch
                val additions = QueuePolicy.uniqueRecommendations(recommendations, tracks() + app.library.library.value.history.take(30))
                if (additions.isEmpty()) { radioFailed = true; return@launch }
                val previousOrder = player.playbackOrder()
                val ended = player.playbackState == Player.STATE_ENDED
                player.addMediaItems(additions.map { it.toMediaItem() })
                if (mode != QueueMode.ORDERED) applyOrder(previousOrder + (count until player.mediaItemCount).toList())
                if (ended && player.playWhenReady) { player.seekToDefaultPosition(count); player.prepare(); player.play() }
                save()
            } catch (error: CancellationException) { throw error } catch (_: Exception) { radioFailed = true }
            finally { radioLoading = false; publish(); maybeAutoplay() }
        }
    }

    private fun restore() {
        val saved = persistence.read()
        mode = saved.mode
        contextLabel = saved.context
        if (saved.tracks.isNotEmpty()) {
            recommendationContext=saved.tracks
            player.setMediaItems(saved.tracks.map { it.toMediaItem() }, saved.index, saved.position)
            if (mode != QueueMode.ORDERED) { applyOrder(saved.order); player.shuffleModeEnabled = true }
            player.repeatMode = saved.repeat
        }
        player.setPlaybackSpeed(app.settings.state.value.speed)
    }
    private fun save() {
        if (!::player.isInitialized) return
        persistence.save(PlaybackSnapshot(tracks(), player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0), player.playbackOrder(), mode, player.repeatMode, contextLabel))
    }
    private fun publish() {
        val fingerprint = "$mode|$contextLabel|${sleep.untilMs}|${sleep.afterTrackId}|$radioLoading|$radioFailed"
        if (lastExtras == fingerprint) return
        lastExtras = fingerprint
        session?.setSessionExtras(Bundle().apply {
            putString("mode", mode.name); putString("context", contextLabel)
            putLong("sleepUntil", sleep.untilMs); putString("sleepTrack", sleep.afterTrackId)
            putBoolean("radioLoading", radioLoading); putBoolean("radioFailed", radioFailed)
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibraryService.MediaLibrarySession? =
        session.takeIf { controllerInfo.packageName == packageName || controllerInfo.isTrusted || controllerInfo.packageName in CAR_PACKAGES }

    companion object {
        private const val ROOT = "root"
        /** Android Auto and Google's car apps, allowed to browse and control playback. */
        private val CAR_PACKAGES = setOf("com.google.android.projection.gearhead", "com.google.android.carassistant", "com.google.android.googlequicksearchbox")
    }
    override fun onTaskRemoved(rootIntent: Intent?) { if (!player.playWhenReady) stopSelf() }
    override fun onDestroy() {
        sampleListening(); flushListening()
        save()
        scope.cancel()
        if (::audio.isInitialized) audio.release()
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
