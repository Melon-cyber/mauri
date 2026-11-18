package app.melo.music.data

import java.util.UUID

data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long = 0,
    val artwork: String = "",
    val url: String = "",
    val source: String = "youtube",
) {
    val isLocal: Boolean get() = source == "local"
}

data class Playlist(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val tracks: List<Track> = emptyList(),
    val artwork: String = "",
    val sourceUrl: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val importNote: String = "",
    val pinned: Boolean = false,
)

data class Library(
    val likes: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val history: List<Track> = emptyList(),
)

data class LyricLine(val timeMs: Long, val text: String)
/**
 * [alignable]: timed lines that belong to another recording of the song (e.g. the album version while a long
 * music video plays). They are shown as plain text until the listener aligns them or switches the recording.
 */
data class Lyrics(val lines: List<LyricLine> = emptyList(), val plain: String = "", val instrumental: Boolean = false, val source: String = "LRCLIB",
                  val alignable: List<LyricLine> = emptyList(), val expectedDurationMs: Long = 0, val playedDurationMs: Long = 0)
data class AudioStream(val url: String, val mimeType: String, val bitrate: Int, val expiresAt: Long)
data class SearchPage(val tracks: List<Track>, val nextPage: org.schabi.newpipe.extractor.Page? = null, val filter: String = "music_songs")

fun durationLabel(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

fun trackCountLabel(count: Int): String {
    val noun = when {
        count % 100 in 11..14 -> "треков"
        count % 10 == 1 -> "трек"
        count % 10 in 2..4 -> "трека"
        else -> "треков"
    }
    return "$count $noun"
}
