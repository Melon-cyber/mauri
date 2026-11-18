package app.melo.music.playback

object PlaybackCommands {
    const val MODE = "mauri.mode"
    const val RESHUFFLE = "mauri.reshuffle"
    const val NEXT = "mauri.play-next"
    const val LAST = "mauri.add-last"
    const val MOVE = "mauri.move"
    const val CLEAR = "mauri.clear"
    const val TIMER = "mauri.timer"
    const val CONTEXT = "mauri.context"
    const val RELOAD = "mauri.reload"
    val all = listOf(MODE, RESHUFFLE, NEXT, LAST, MOVE, CLEAR, TIMER, CONTEXT, RELOAD)
}
