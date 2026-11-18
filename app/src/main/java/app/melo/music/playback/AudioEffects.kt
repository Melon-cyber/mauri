package app.melo.music.playback

import android.content.Context
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import app.melo.music.data.EqPreset
import app.melo.music.data.ListeningSettings

/**
 * System audio effects on the player's audio session: a simple preset equalizer and volume levelling
 * (a gentle compressor with a limiter, Android 9+). Any device that refuses an effect simply plays without it.
 */
internal class AudioEffects(@Suppress("unused") private val context: Context) {
    private var session = 0
    private var equalizer: Equalizer? = null
    private var dynamics: DynamicsProcessing? = null
    private var applied: Pair<EqPreset, Boolean>? = null

    fun attach(audioSessionId: Int, settings: ListeningSettings) {
        if (audioSessionId == session) return apply(settings)
        release()
        session = audioSessionId
        applied = null
        apply(settings)
    }

    fun apply(settings: ListeningSettings) {
        if (session == 0) return
        val wanted = settings.equalizer to settings.normalizeVolume
        if (wanted == applied) return
        applied = wanted
        applyEqualizer(settings.equalizer)
        applyLevelling(settings.normalizeVolume)
    }

    private fun applyEqualizer(preset: EqPreset) {
        if (preset == EqPreset.OFF) { equalizer?.enabled = false; return }
        runCatching {
            val eq = equalizer ?: Equalizer(0, session).also { equalizer = it }
            val range = eq.bandLevelRange
            for (band in 0 until eq.numberOfBands) {
                val hz = eq.getCenterFreq(band.toShort()) / 1000f
                val level = (preset.gainAt(hz) * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                eq.setBandLevel(band.toShort(), level.toShort())
            }
            eq.enabled = true
        }.onFailure { equalizer = null }
    }

    private fun applyLevelling(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!enabled) { dynamics?.enabled = false; return }
        runCatching {
            val processor = dynamics ?: run {
                val config = DynamicsProcessing.Config.Builder(DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2, false, 0, true, 1, false, 0, true).build()
                // One full-range band: loud parts are pressed down 3:1 above -26 dB, everything is lifted back by 6 dB.
                val band = DynamicsProcessing.MbcBand(true, 20_000f, 5f, 120f, 3f, -26f, 6f, -90f, 1f, 0f, 6f)
                val mbc = DynamicsProcessing.Mbc(true, true, 1).apply { setBand(0, band) }
                config.setMbcAllChannelsTo(mbc)
                config.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1.5f, 0f))
                DynamicsProcessing(0, session, config).also { dynamics = it }
            }
            processor.enabled = true
        }.onFailure { dynamics = null }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { dynamics?.release() }
        equalizer = null; dynamics = null
    }
}
