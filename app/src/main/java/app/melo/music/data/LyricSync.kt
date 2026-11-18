package app.melo.music.data

/** One sync point made by hand: the line that starts at [lyricMs] in the lyrics is sung at [playMs] in this recording. */
data class LyricAnchor(val lyricMs: Long, val playMs: Long)

/**
 * Maps the player clock to the lyrics clock.
 *
 * Without anchors it is a plain shift by [offset]. With anchors (tapped by the listener while the song plays)
 * the mapping follows the recording piece by piece, so a long intro, a skit in the middle of a music video,
 * a cut verse or a slightly faster live take all stay in time:
 * - before the first anchor and after the last one the text keeps the shift of the nearest anchor;
 * - between two anchors whose spacing is almost the same in both clocks (a tempo difference) the time is stretched;
 * - otherwise (something was inserted or cut) the text keeps the earlier shift and waits for / jumps to the next anchor.
 * [offset] is a fine shift applied on top.
 */
class LyricTiming(private val offset: Long = 0, anchors: List<LyricAnchor> = emptyList()) {
    private val points = clean(anchors)
    val anchored: Boolean get() = points.isNotEmpty()

    fun toLyric(play: Long): Long {
        if (points.isEmpty()) return play + offset
        val first = points.first(); val last = points.last()
        val base = when {
            play <= first.playMs -> play + first.lyricMs - first.playMs
            play >= last.playMs -> play + last.lyricMs - last.playMs
            else -> {
                val i = points.indexOfLast { it.playMs <= play }
                val a = points[i]; val b = points[i + 1]
                if (stretch(a, b)) a.lyricMs + (play - a.playMs) * (b.lyricMs - a.lyricMs) / (b.playMs - a.playMs)
                else minOf(play + a.lyricMs - a.playMs, b.lyricMs)
            }
        }
        return base + offset
    }

    /** Where in the recording a lyric moment is sung (used when a line is tapped to jump to it). */
    fun toPlay(lyric: Long): Long {
        val l = lyric - offset
        if (points.isEmpty()) return l.coerceAtLeast(0)
        val first = points.first(); val last = points.last()
        val play = when {
            l <= first.lyricMs -> l - first.lyricMs + first.playMs
            l >= last.lyricMs -> l - last.lyricMs + last.playMs
            else -> {
                val i = points.indexOfLast { it.lyricMs <= l }
                val a = points[i]; val b = points[i + 1]
                if (stretch(a, b)) a.playMs + (l - a.lyricMs) * (b.playMs - a.playMs) / (b.lyricMs - a.lyricMs)
                else minOf(l - a.lyricMs + a.playMs, b.playMs)
            }
        }
        return play.coerceAtLeast(0)
    }

    companion object {
        /** Taps come a little after the singer starts; this much is taken back from each tap. */
        const val TAP_REACTION_MS = 250L
        const val MAX_ANCHORS = 64

        private fun stretch(a: LyricAnchor, b: LyricAnchor): Boolean {
            val lyric = (b.lyricMs - a.lyricMs).toDouble(); val play = (b.playMs - a.playMs).toDouble()
            return play > 0 && lyric / play in 0.9..1.1
        }

        /** Sorted by recording time, both clocks strictly growing; broken points are dropped. */
        fun clean(anchors: List<LyricAnchor>): List<LyricAnchor> {
            val out = ArrayList<LyricAnchor>()
            anchors.filter { it.lyricMs >= 0 && it.playMs >= 0 }.sortedBy { it.playMs }.forEach { p ->
                val prev = out.lastOrNull()
                if (prev == null || (p.playMs > prev.playMs && p.lyricMs > prev.lyricMs)) out += p
            }
            return out
        }

        /**
         * Adds a fresh tap. It wins over older points that disagree with it: the same line, a tap less than
         * 1.2 s away, or a point that would put the lines in the wrong order.
         */
        fun add(anchors: List<LyricAnchor>, tap: LyricAnchor): List<LyricAnchor> {
            val kept = anchors.filterNot {
                it.lyricMs == tap.lyricMs || kotlin.math.abs(it.playMs - tap.playMs) < 1_200 ||
                    (it.lyricMs < tap.lyricMs) != (it.playMs < tap.playMs)
            }
            return clean(kept + tap).takeLast(MAX_ANCHORS)
        }
    }
}
