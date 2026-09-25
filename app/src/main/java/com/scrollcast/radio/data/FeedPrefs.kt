package com.scrollcast.radio.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How strongly a preference shapes the feed. */
enum class Mix {
    /** Every station matches. */
    Only,
    /** Most stations match; the rest come from anywhere. */
    Bias,
    /** The preference is ignored. */
    Mixed;

    /** Share of the feed that should match the preference. */
    val share: Double
        get() = when (this) {
            Only -> 1.0
            Bias -> 0.7
            Mixed -> 0.0
        }
}

/**
 * The user's feed settings. A null region or language means "automatic": the detected
 * country and the device language.
 */
data class FeedPrefs(
    val countryCode: String? = null,
    val regionMix: Mix = Mix.Bias,
    val language: String? = null,
    val languageMix: Mix = Mix.Mixed,
    val genre: String? = null,
    /** Free-text mood such as "romantic hindi"; while set it reshapes the whole feed. */
    val mood: String? = null,
)

/** Settings with "automatic" and the mood resolved to concrete query values. */
data class ResolvedPrefs(
    val countryCode: String?,
    val regionMix: Mix,
    val language: String?,
    val languageMix: Mix,
    val genre: String?,
    /** False when [genre] came from a typed mood and should match tags loosely. */
    val genreExact: Boolean = true,
    /** The typed mood, kept for a name-search fallback when nothing else matches. */
    val moodKeyword: String? = null,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _feed = MutableStateFlow(
        FeedPrefs(
            countryCode = prefs.optional(KEY_COUNTRY),
            regionMix = prefs.getString(KEY_REGION_MIX, null).toMix(Mix.Bias),
            language = prefs.optional(KEY_LANGUAGE),
            languageMix = prefs.getString(KEY_LANGUAGE_MIX, null).toMix(Mix.Mixed),
            genre = prefs.optional(KEY_GENRE),
            mood = prefs.optional(KEY_MOOD),
        )
    )
    val feed: StateFlow<FeedPrefs> = _feed.asStateFlow()

    fun setFeed(value: FeedPrefs) {
        prefs.edit()
            .putString(KEY_COUNTRY, value.countryCode)
            .putString(KEY_REGION_MIX, value.regionMix.name)
            .putString(KEY_LANGUAGE, value.language)
            .putString(KEY_LANGUAGE_MIX, value.languageMix.name)
            .putString(KEY_GENRE, value.genre)
            .putString(KEY_MOOD, value.mood)
            .apply()
        _feed.value = value
    }

    /** Blank means "automatic" (also covers the empty values version 0.1 saved). */
    private fun android.content.SharedPreferences.optional(key: String): String? =
        getString(key, null)?.takeIf { it.isNotBlank() }

    private fun String?.toMix(default: Mix) = Mix.entries.firstOrNull { it.name == this } ?: default

    private companion object {
        const val KEY_COUNTRY = "country_code"
        const val KEY_REGION_MIX = "region_mix"
        const val KEY_LANGUAGE = "language"
        const val KEY_LANGUAGE_MIX = "language_mix"
        const val KEY_GENRE = "genre"
        const val KEY_MOOD = "mood"
    }
}
