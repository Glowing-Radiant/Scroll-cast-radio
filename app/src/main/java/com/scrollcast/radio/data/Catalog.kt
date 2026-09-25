package com.scrollcast.radio.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Search terms for one Radio Browser query; null means "any". */
data class StationQuery(
    val countryCode: String? = null,
    val language: String? = null,
    val tag: String? = null,
    /** False matches any tag containing [tag], e.g. "hip hop" also finds "hip hop classics". */
    val tagExact: Boolean = true,
    /** Matches station names containing this text. */
    val name: String? = null,
)

/** What a typed mood means, e.g. "romantic hindi" → language hindi + tag romantic. */
data class Mood(
    val language: String? = null,
    val countryCode: String? = null,
    val tag: String? = null,
) {
    companion object {
        /**
         * Reads a mood against the real languages and countries: a word (or the whole phrase)
         * naming one becomes that filter, and whatever is left is treated as a genre.
         */
        fun parse(text: String, languages: List<CatalogEntry>, countries: List<CatalogEntry>): Mood {
            val phrase = text.trim().lowercase().replace(Regex("\\s+"), " ")
            if (phrase.isEmpty()) return Mood()
            fun language(term: String) = languages.firstOrNull { it.name.equals(term, ignoreCase = true) }?.key
            fun country(term: String) = countries.firstOrNull { it.name.equals(term, ignoreCase = true) }?.key

            language(phrase)?.let { return Mood(language = it) }
            country(phrase)?.let { return Mood(countryCode = it) }

            var lang: String? = null
            var place: String? = null
            val rest = mutableListOf<String>()
            for (word in phrase.split(' ')) {
                when {
                    lang == null && language(word) != null -> lang = language(word)
                    place == null && country(word) != null -> place = country(word)
                    else -> rest += word
                }
            }
            return Mood(language = lang, countryCode = place, tag = rest.joinToString(" ").ifEmpty { null })
        }
    }
}

/** One choosable option (a country, language or genre) and how many stations it has. */
data class CatalogEntry(
    /** Value sent to the API: ISO country code, or the language / tag name. */
    val key: String,
    val name: String,
    val stationCount: Int,
    val isoCode: String? = null,
) {
    /** Radio Browser stores languages and tags in lower case; show them capitalised. */
    val displayName: String get() = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

/**
 * The countries, languages and genres that actually have working stations, fetched once
 * per process, so settings only ever offer real choices.
 */
class Catalog(private val api: RadioBrowserClient) {
    private val lock = Mutex()
    private var countries: List<CatalogEntry>? = null
    private var languages: List<CatalogEntry>? = null
    private var genres: List<CatalogEntry>? = null

    suspend fun countries(): List<CatalogEntry> = lock.withLock {
        countries ?: api.countries()
            .filter { it.key.length == 2 && it.name.isNotBlank() && it.stationCount > 0 }
            .groupBy { it.key }
            .map { (_, same) -> same.maxBy { it.stationCount }.copy(stationCount = same.sumOf { it.stationCount }) }
            .sortedBy { it.name.lowercase() }
            .also { countries = it }
    }

    suspend fun languages(): List<CatalogEntry> = lock.withLock {
        languages ?: api.languages()
            .filter { it.name.isNotBlank() && it.stationCount >= MIN_STATIONS }
            .sortedBy { it.name.lowercase() }
            .also { languages = it }
    }

    suspend fun genres(): List<CatalogEntry> = lock.withLock {
        genres ?: api.genres(limit = 300)
            .filter { it.name.isNotBlank() && it.stationCount >= MIN_STATIONS }
            .sortedBy { it.name.lowercase() }
            .also { genres = it }
    }

    /** Works out what a typed mood refers to; unknown words become a loose genre match. */
    suspend fun parseMood(text: String): Mood = Mood.parse(
        text,
        languages = runCatching { languages() }.getOrDefault(emptyList()),
        countries = runCatching { countries() }.getOrDefault(emptyList()),
    )

    /** Radio Browser's name for an ISO 639 language code, e.g. "hi" → "hindi". */
    suspend fun languageForIso(iso: String): String? =
        runCatching { languages() }.getOrDefault(emptyList())
            .filter { it.isoCode.equals(iso, ignoreCase = true) }
            .maxByOrNull { it.stationCount }
            ?.key

    private companion object {
        /** Skip one-off spellings that would give a feed of one or two stations. */
        const val MIN_STATIONS = 3
    }
}
