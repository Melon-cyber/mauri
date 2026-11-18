package app.melo.music.playback

data class SleepTimerState(val untilMs: Long = 0, val afterTrackId: String? = null) {
    fun isActive(): Boolean = untilMs > 0 || afterTrackId != null
    fun remaining(now: Long): Long = (untilMs - now).coerceAtLeast(0)
    fun shouldStop(now: Long, departedId: String? = null, ended: Boolean = false): Boolean =
        (untilMs > 0 && now >= untilMs) || (afterTrackId != null && (departedId == afterTrackId || ended))
    fun volume(now: Long): Float = if (untilMs <= 0) 1f else (remaining(now) / 3000f).coerceIn(0f, 1f)
}
