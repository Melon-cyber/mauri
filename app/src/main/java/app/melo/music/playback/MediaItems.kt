package app.melo.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import app.melo.music.data.Track

fun Track.toMediaItem(): MediaItem {
    val uri = if (isLocal) Uri.parse(url) else Uri.Builder().scheme("melo").authority("play")
        .appendQueryParameter("id", id).appendQueryParameter("title", title)
        .appendQueryParameter("artist", artist).appendQueryParameter("duration", durationMs.toString())
        .appendQueryParameter("artwork", artwork).appendQueryParameter("url", url).appendQueryParameter("source", source).build()
    return MediaItem.Builder().setMediaId(id).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setArtworkUri(artwork.takeIf { it.isNotBlank() }?.let(Uri::parse))
            .setExtras(Bundle().apply { putString("url", url); putString("source", source); putLong("duration", durationMs); putString("queueUid", java.util.UUID.randomUUID().toString()) })
            .build()).build()
}

fun MediaItem.toTrack(): Track = Track(
    id = mediaId, title = mediaMetadata.title?.toString().orEmpty(), artist = mediaMetadata.artist?.toString().orEmpty(),
    artwork = mediaMetadata.artworkUri?.toString().orEmpty(), durationMs = mediaMetadata.extras?.getLong("duration") ?: 0,
    url = mediaMetadata.extras?.getString("url").orEmpty(), source = mediaMetadata.extras?.getString("source") ?: "youtube",
)

fun Uri.toTrack(): Track = Track(
    id = getQueryParameter("id").orEmpty(), title = getQueryParameter("title").orEmpty(), artist = getQueryParameter("artist").orEmpty(),
    durationMs = getQueryParameter("duration")?.toLongOrNull() ?: 0, artwork = getQueryParameter("artwork").orEmpty(),
    url = getQueryParameter("url").orEmpty(), source = getQueryParameter("source") ?: "youtube",
)
