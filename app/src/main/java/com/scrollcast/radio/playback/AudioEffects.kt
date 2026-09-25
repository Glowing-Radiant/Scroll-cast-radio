package com.scrollcast.radio.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import com.scrollcast.radio.data.AudioPrefs
import com.scrollcast.radio.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Which enhancements this phone supports, so Settings only offers working ones. */
data class AudioCapabilities(
    val evenLoudness: Boolean = false,
    val bassBoost: Boolean = false,
    val equalizer: Boolean = false,
    /** The phone's own equalizer presets, e.g. "Rock", "Jazz". */
    val presets: List<String> = emptyList(),
)

/**
 * Optional sound processing on the player's audio session. The playback service attaches it to
 * the session; changes in [SettingsStore.audio] apply immediately, while listening.
 */
class AudioEffects(private val settings: SettingsStore, scope: CoroutineScope) {

    private var dynamics: DynamicsProcessing? = null
    private var bass: BassBoost? = null
    private var equalizer: Equalizer? = null

    private val _capabilities = MutableStateFlow(AudioCapabilities())
    val capabilities: StateFlow<AudioCapabilities> = _capabilities.asStateFlow()

    init {
        scope.launch(Dispatchers.Main) { settings.audio.collect(::apply) }
    }

    fun attach(sessionId: Int) {
        release()
        dynamics = if (Build.VERSION.SDK_INT >= 28) runCatching { createLeveler(sessionId) }.getOrNull() else null
        bass = runCatching { BassBoost(0, sessionId) }.getOrNull()
        equalizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
        val eq = equalizer
        _capabilities.value = AudioCapabilities(
            evenLoudness = dynamics != null,
            bassBoost = bass?.strengthSupported == true,
            equalizer = eq != null,
            presets = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }.orEmpty(),
        )
        apply(settings.audio.value)
    }

    fun release() {
        listOf(dynamics, bass, equalizer).forEach { runCatching { it?.release() } }
        dynamics = null
        bass = null
        equalizer = null
    }

    private fun apply(prefs: AudioPrefs) {
        runCatching { dynamics?.enabled = prefs.evenLoudness }
        runCatching {
            bass?.let {
                if (prefs.bassBoost) it.setStrength(BASS_STRENGTH)
                it.enabled = prefs.bassBoost
            }
        }
        runCatching {
            val eq = equalizer ?: return@runCatching
            when (prefs.equalizer) {
                AudioPrefs.EQ_OFF -> eq.enabled = false
                AudioPrefs.EQ_VOICE -> {
                    applyVoiceClarity(eq)
                    eq.enabled = true
                }
                else -> {
                    val preset = (0 until eq.numberOfPresets).firstOrNull { eq.getPresetName(it.toShort()) == prefs.equalizer }
                    if (preset == null) {
                        eq.enabled = false
                    } else {
                        eq.usePreset(preset.toShort())
                        eq.enabled = true
                    }
                }
            }
        }
    }

    /**
     * A gentle compressor plus make-up gain and a limiter: quiet stations come up, loud ones
     * come down, so hopping between stations doesn't mean reaching for the volume.
     */
    @androidx.annotation.RequiresApi(28)
    private fun createLeveler(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ false, 0,
            /* mbcInUse = */ true, 1,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        ).build()
        return DynamicsProcessing(0, sessionId, config).apply {
            setMbcBandAllChannelsTo(
                0,
                DynamicsProcessing.MbcBand(
                    /* enabled = */ true, /* cutoffFrequency = */ 20_000f,
                    /* attackTime = */ 10f, /* releaseTime = */ 250f,
                    /* ratio = */ 3f, /* threshold = */ -28f, /* kneeWidth = */ 6f,
                    /* noiseGateThreshold = */ -90f, /* expanderRatio = */ 1f,
                    /* preGain = */ 0f, /* postGain = */ 10f,
                ),
            )
            setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                    /* attackTime = */ 1f, /* releaseTime = */ 60f,
                    /* ratio = */ 10f, /* threshold = */ -2f, /* postGain = */ 0f,
                ),
            )
            enabled = false
        }
    }

    /** Lifts the speech range and trims rumble, for talk and news stations. */
    private fun applyVoiceClarity(eq: Equalizer) {
        val (min, max) = eq.bandLevelRange.let { it[0] to it[1] }
        for (band in 0 until eq.numberOfBands) {
            val centerHz = eq.getCenterFreq(band.toShort()) / 1000
            val level = when {
                centerHz < 150 -> -400
                centerHz < 400 -> -100
                centerHz < 1_000 -> 100
                centerHz < 4_000 -> 500
                else -> 200
            }.coerceIn(min.toInt(), max.toInt())
            eq.setBandLevel(band.toShort(), level.toShort())
        }
    }

    private companion object {
        const val BASS_STRENGTH: Short = 700 // of 1000
    }
}
