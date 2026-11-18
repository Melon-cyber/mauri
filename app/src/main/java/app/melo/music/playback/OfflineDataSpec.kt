package app.melo.music.playback
import android.net.Uri
import androidx.media3.datasource.DataSpec
import app.melo.music.data.OfflineStore
import app.melo.music.data.Track

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun resolvePlaybackDataSpec(spec: DataSpec, offline: OfflineStore, online: (Track) -> Uri): DataSpec {
    if (spec.uri.scheme != "melo") return spec
    val track = spec.uri.toTrack()
    offline.refresh()
    val file = offline.localFile(track)
    return spec.withUri(if (file != null) Uri.fromFile(file) else online(track))
}
