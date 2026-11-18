package app.melo.music.playback

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.melo.music.MainActivity
import app.melo.music.MeloApplication
import app.melo.music.R
import app.melo.music.data.Track
import app.melo.music.data.durationLabel
import coil.imageLoader
import coil.request.ImageRequest

/**
 * Home-screen widget: cover, title, artist, like, a progress bar with time,
 * and buttons for −10 s, previous, play/pause, next and +10 s.
 * Buttons talk to the player through a MediaController, so they work whether the app is open or not.
 */
class MauriWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // Without a running player show the last saved song, so the widget is never empty.
        val saved = runCatching { PlaybackPersistence(context).read() }.getOrNull()
        val track = saved?.tracks?.getOrNull(saved.index)
        val liked = track != null && runCatching { (context.applicationContext as MeloApplication).library.library.value.likes.any { it.id == track.id } }.getOrDefault(false)
        render(context, WidgetState(track, false, saved?.position ?: 0, track?.durationMs ?: 0, liked), null)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in ACTIONS) return super.onReceive(context, intent)
        val pending = goAsync()
        val app = context.applicationContext
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, MusicService::class.java))).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull()
            if (controller == null) { pending.finish(); return@addListener }
            runCatching { perform(app, controller, action!!) }
            // Give the session a moment to receive the command before disconnecting.
            Handler(Looper.getMainLooper()).postDelayed({ controller.release(); pending.finish() }, 1_500)
        }, ContextCompat.getMainExecutor(app))
    }

    private fun perform(context: Context, c: MediaController, action: String) {
        when (action) {
            PLAY_PAUSE -> if (c.isPlaying) c.pause() else {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
                c.play()
            }
            PREVIOUS -> c.seekToPrevious()
            NEXT -> c.seekToNext()
            BACK -> c.seekTo((c.currentPosition - 10_000).coerceAtLeast(0))
            FORWARD -> {
                val duration = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                c.seekTo(if (duration != null) (c.currentPosition + 10_000).coerceAtMost(duration - 1_000) else c.currentPosition + 10_000)
            }
            LIKE -> c.currentMediaItem?.toTrack()?.let { track ->
                val app = context.applicationContext as MeloApplication
                Thread { runCatching { app.library.toggleLike(track) } }.start()
            }
        }
    }

    data class WidgetState(val track: Track?, val playing: Boolean, val position: Long, val duration: Long, val liked: Boolean)

    companion object {
        private const val PLAY_PAUSE = "app.melo.music.widget.PLAY_PAUSE"
        private const val PREVIOUS = "app.melo.music.widget.PREVIOUS"
        private const val NEXT = "app.melo.music.widget.NEXT"
        private const val BACK = "app.melo.music.widget.BACK"
        private const val FORWARD = "app.melo.music.widget.FORWARD"
        private const val LIKE = "app.melo.music.widget.LIKE"
        private val ACTIONS = setOf(PLAY_PAUSE, PREVIOUS, NEXT, BACK, FORWARD, LIKE)

        private var lastArtUrl: String? = null
        private var lastArt: Bitmap? = null

        fun hasWidgets(context: Context): Boolean =
            AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, MauriWidget::class.java)).isNotEmpty()

        /** Full redraw (song, cover, like, buttons). The cover is loaded in the background and cached. */
        suspend fun update(context: Context, state: WidgetState) {
            if (!hasWidgets(context)) return
            val url = state.track?.artwork.orEmpty()
            val art = when {
                url.isBlank() -> null
                url == lastArtUrl -> lastArt
                else -> runCatching {
                    val app = context.applicationContext as MeloApplication
                    val request = ImageRequest.Builder(context).data(app.offline.cover(url)).allowHardware(false).size(220).build()
                    (context.imageLoader.execute(request).drawable as? BitmapDrawable)?.bitmap
                }.getOrNull().also { lastArtUrl = url; lastArt = it }
            }
            render(context, state, art)
        }

        /** Light update every second while playing: only the progress bar and time. */
        fun progress(context: Context, position: Long, duration: Long) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MauriWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            setProgress(views, position, duration)
            manager.partiallyUpdateAppWidget(ids, views)
        }

        private fun setProgress(views: RemoteViews, position: Long, duration: Long) {
            val safe = duration.coerceAtLeast(0)
            views.setProgressBar(R.id.widget_progress, 1000, if (safe > 0) ((position.coerceIn(0, safe) * 1000) / safe).toInt() else 0, false)
            views.setTextViewText(R.id.widget_time, durationLabel(position))
            views.setTextViewText(R.id.widget_duration, if (safe > 0) durationLabel(safe) else "")
        }

        private fun render(context: Context, state: WidgetState, art: Bitmap?) {
            val views = RemoteViews(context.packageName, R.layout.widget_player)
            val track = state.track
            views.setTextViewText(R.id.widget_title, track?.title?.takeIf { it.isNotBlank() } ?: "Mauri")
            views.setTextViewText(R.id.widget_artist, track?.artist?.takeIf { it.isNotBlank() } ?: "Нажмите, чтобы открыть")
            if (art != null) views.setImageViewBitmap(R.id.widget_art, art) else views.setImageViewResource(R.id.widget_art, R.drawable.mauri_foreground)
            views.setImageViewResource(R.id.widget_play, if (state.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
            views.setContentDescription(R.id.widget_play, if (state.playing) "Пауза" else "Воспроизвести")
            views.setImageViewResource(R.id.widget_like, if (state.liked) R.drawable.ic_w_heart_filled else R.drawable.ic_w_heart)
            views.setContentDescription(R.id.widget_like, if (state.liked) "Убрать из избранного" else "В избранное")
            setProgress(views, state.position, state.duration)
            val open = PendingIntent.getActivity(context, 10, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            views.setOnClickPendingIntent(R.id.widget_art, open)
            views.setOnClickPendingIntent(R.id.widget_title, open)
            views.setOnClickPendingIntent(R.id.widget_artist, open)
            views.setOnClickPendingIntent(R.id.widget_play, button(context, PLAY_PAUSE, 11))
            views.setOnClickPendingIntent(R.id.widget_prev, button(context, PREVIOUS, 12))
            views.setOnClickPendingIntent(R.id.widget_next, button(context, NEXT, 13))
            views.setOnClickPendingIntent(R.id.widget_back10, button(context, BACK, 14))
            views.setOnClickPendingIntent(R.id.widget_fwd10, button(context, FORWARD, 15))
            views.setOnClickPendingIntent(R.id.widget_like, button(context, LIKE, 16))
            AppWidgetManager.getInstance(context).updateAppWidget(ComponentName(context, MauriWidget::class.java), views)
        }

        private fun button(context: Context, action: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(context, code, Intent(context, MauriWidget::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
