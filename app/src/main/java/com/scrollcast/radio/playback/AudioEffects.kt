package com.scrollcast.radio.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Build
import androidx.annotation.RequiresApi
import com.scrollcast.radio.data.AudioPrefs
import com.scrollcast.radio.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/** Which enhancements this phone supports, so Settings only offers working ones. */
data class AudioCapabilities(
    val evenLoudness: Boolean = false,
    val bassBoost: Boolean = false,
    val equalizer: Boolean = false,
    /** The phone's own equalizer presets, e.g. "Rock", "Jazz". */
    val presets: List<String> = emptyList(),
)

/**
 * Optional sound processing on the player's audio session. Changes in [SettingsStore.audio]
 * apply immediately, while listening.
 *
 * On Android 9+ everything runs inside ONE [DynamicsProcessing] effect: loudness levelling in
 * its compressor, and bass boost, voice clarity and the phone's presets as curves in its
 * pre-equalizer. Mixing it with the vendor BassBoost/Equalizer effects breaks on some phones:
 * two effects claim volume control and the output goes silent.
 */
class AudioEffects(private val settings: SettingsStore, scope: CoroutineScope) {

    private var processor: DynamicsProcessing? = null
    /** Phone presets captured as (frequency Hz, gain dB) points, replayed through [processor]. */
    private var presetCurves: Map<String, List<Pair<Float, Float>>> = emptyMap()

    // Android 8.x only (no DynamicsProcessing): the separate vendor effects, no levelling.
    private var legacyBass: BassBoost? = null
    private var legacyEqualizer: Equalizer? = null

    private val _capabilities = MutableStateFlow(AudioCapabilities())
    val capabilities: StateFlow<AudioCapabilities> = _capabilities.asStateFlow()

    init {
        scope.launch(Dispatchers.Main) { settings.audio.collect(::apply) }
    }

    fun attach(sessionId: Int) {
        release()
        if (Build.VERSION.SDK_INT >= 28) {
            presetCurves = readPresetCurves(sessionId)
            processor = runCatching { createProcessor(sessionId) }.getOrNull()
        }
        if (processor != null) {
            _capabilities.value = AudioCapabilities(
                evenLoudness = true,
                bassBoost = true,
                equalizer = true,
                presets = presetCurves.keys.toList(),
            )
        } else {
            legacyBass = runCatching { BassBoost(0, sessionId) }.getOrNull()
            legacyEqualizer = runCatching { Equalizer(0, sessionId) }.getOrNull()
            val eq = legacyEqualizer
            _capabilities.value = AudioCapabilities(
                evenLoudness = false,
                bassBoost = legacyBass?.strengthSupported == true,
                equalizer = eq != null,
                presets = eq?.let { e -> (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }.orEmpty(),
            )
        }
        apply(settings.audio.value)
    }

    fun release() {
        listOf(processor, legacyBass, legacyEqualizer).forEach { runCatching { it?.release() } }
        processor = null
        legacyBass = null
        legacyEqualizer = null
    }

    private fun apply(prefs: AudioPrefs) {
        val dp = processor
        if (dp != null && Build.VERSION.SDK_INT >= 28) applyProcessor(dp, prefs) else applyLegacy(prefs)
    }

    @RequiresApi(28)
    private fun applyProcessor(dp: DynamicsProcessing, prefs: AudioPrefs) = runCatching {
        val preset = presetCurves[prefs.equalizer]
        EqCurves.BAND_EDGES.forEachIndexed { band, cutoff ->
            val hz = EqCurves.bandCenter(band)
            var gain = 0f
            if (prefs.bassBoost) gain += EqCurves.bassBoost(hz)
            gain += when {
                prefs.equalizer == AudioPrefs.EQ_VOICE -> EqCurves.voiceClarity(hz)
                preset != null -> EqCurves.interpolate(preset, hz)
                else -> 0f
            }
            dp.setPreEqBandAllChannelsTo(band, DynamicsProcessing.EqBand(true, cutoff, gain))
        }
        dp.setMbcBandAllChannelsTo(0, leveler(prefs.evenLoudness))
        dp.enabled = prefs.evenLoudness || prefs.bassBoost || prefs.equalizer != AudioPrefs.EQ_OFF
    }

    private fun applyLegacy(prefs: AudioPrefs) {
        runCatching {
            legacyBass?.let {
                if (prefs.bassBoost) it.setStrength(LEGACY_BASS_STRENGTH)
                it.enabled = prefs.bassBoost
            }
        }
        runCatching {
            val eq = legacyEqualizer ?: return@runCatching
            when (prefs.equalizer) {
                AudioPrefs.EQ_OFF -> eq.enabled = false
                AudioPrefs.EQ_VOICE -> {
                    val (min, max) = eq.bandLevelRange.let { it[0] to it[1] }
                    for (band in 0 until eq.numberOfBands) {
                        val hz = eq.getCenterFreq(band.toShort()) / 1000f
                        val level = (EqCurves.voiceClarity(hz) * 100).toInt().coerceIn(min.toInt(), max.toInt())
                        eq.setBandLevel(band.toShort(), level.toShort())
                    }
                    eq.enabled = true
                }
                else -> {
                    val preset = (0 until eq.numberOfPresets).firstOrNull { eq.getPresetName(it.toShort()) == prefs.equalizer }
                    if (preset != null) eq.usePreset(preset.toShort())
                    eq.enabled = preset != null
                }
            }
        }
    }

    /** Pre-EQ for tone shaping, one compressor band for levelling, and a safety limiter. */
    @RequiresApi(28)
    private fun createProcessor(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            /* channelCount = */ 2,
            /* preEqInUse = */ true, EqCurves.BAND_EDGES.size,
            /* mbcInUse = */ true, 1,
            /* postEqInUse = */ false, 0,
            /* limiterInUse = */ true,
        ).build()
        return DynamicsProcessing(0, sessionId, config).apply {
            setLimiterAllChannelsTo(
                DynamicsProcessing.Limiter(
                    /* inUse = */ true, /* enabled = */ true, /* linkGroup = */ 0,
                    /* attackTime = */ 1f, /* releaseTime = */ 60f,
                    /* ratio = */ 10f, /* threshold = */ -1f, /* postGain = */ 0f,
                ),
            )
            enabled = false
        }
    }

    /**
     * Levelling on: a gentle compressor plus make-up gain, so quiet stations come up and loud
     * ones come down. Off: a transparent band (ratio 1, no gain).
     */
    @RequiresApi(28)
    private fun leveler(on: Boolean) = DynamicsProcessing.MbcBand(
        /* enabled = */ true, /* cutoffFrequency = */ 20_000f,
        /* attackTime = */ 10f, /* releaseTime = */ 250f,
        /* ratio = */ if (on) 3f else 1f,
        /* threshold = */ if (on) -28f else 0f,
        /* kneeWidth = */ 6f,
        /* noiseGateThreshold = */ -90f, /* expanderRatio = */ 1f,
        /* preGain = */ 0f,
        /* postGain = */ if (on) 10f else 0f,
    )

    /** Reads the phone's equalizer presets as curves, then releases that equalizer again. */
    private fun readPresetCurves(sessionId: Int): Map<String, List<Pair<Float, Float>>> = runCatching {
        val eq = Equalizer(0, sessionId)
        try {
            (0 until eq.numberOfPresets).associate { p ->
                eq.usePreset(p.toShort())
                eq.getPresetName(p.toShort()) to (0 until eq.numberOfBands).map { b ->
                    eq.getCenterFreq(b.toShort()) / 1000f to eq.getBandLevel(b.toShort()) / 100f
                }
            }
        } finally {
            eq.release()
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val LEGACY_BASS_STRENGTH: Short = 700 // of 1000
    }
}

/** Tone curves in dB by frequency, shared by the processor's pre-EQ bands. */
object EqCurves {
    /** Upper edge (Hz) of each pre-EQ band. */
    val BAND_EDGES = floatArrayOf(60f, 150f, 350f, 700f, 1_500f, 3_000f, 6_000f, 12_000f, 20_000f)

    fun bandCenter(band: Int): Float {
        val low = if (band == 0) 20f else BAND_EDGES[band - 1]
        return sqrt(low * BAND_EDGES[band])
    }

    /** Fuller low end without muddying voices. */
    fun bassBoost(hz: Float): Float = when {
        hz < 120f -> 8f
        hz < 250f -> 5f
        hz < 500f -> 2f
        else -> 0f
    }

    /** Lifts the speech range and trims rumble, for talk and news stations. */
    fun voiceClarity(hz: Float): Float = when {
        hz < 150f -> -4f
        hz < 400f -> -1f
        hz < 1_000f -> 1f
        hz < 4_000f -> 5f
        else -> 2f
    }

    /** Gain at [hz] from (Hz, dB) points, interpolated on a log-frequency scale. */
    fun interpolate(points: List<Pair<Float, Float>>, hz: Float): Float {
        val sorted = points.sortedBy { it.first }
        if (sorted.isEmpty()) return 0f
        if (hz <= sorted.first().first) return sorted.first().second
        if (hz >= sorted.last().first) return sorted.last().second
        val upper = sorted.indexOfFirst { it.first >= hz }
        val (f0, g0) = sorted[upper - 1]
        val (f1, g1) = sorted[upper]
        val t = (log10(hz) - log10(f0)) / (log10(f1) - log10(f0))
        return g0 + (g1 - g0) * t
    }
}
