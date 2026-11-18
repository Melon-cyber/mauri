package app.melo.music.playback

import androidx.media3.common.C
import androidx.media3.common.Player

fun Player.playbackOrder(): List<Int> {
    val timeline = currentTimeline
    if (timeline.isEmpty) return (0 until mediaItemCount).toList()
    val result = mutableListOf<Int>()
    var index = timeline.getFirstWindowIndex(shuffleModeEnabled)
    while (index != C.INDEX_UNSET && result.size < mediaItemCount && index !in result) {
        result.add(index)
        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffleModeEnabled)
    }
    return result.takeIf { QueuePolicy.isPermutation(it, mediaItemCount) } ?: (0 until mediaItemCount).toList()
}
