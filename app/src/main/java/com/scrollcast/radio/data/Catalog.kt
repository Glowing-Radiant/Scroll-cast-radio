package com.scrollcast.radio.data

import com.scrollcast.radio.data.providers.Providers
import com.scrollcast.radio.data.providers.StationProvider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Search terms for one station query; null means "any". */
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
    /** ISO country code, or the language / tag name in lower case. */
    val key: String,
    val name: String,
    val stationCount: Int,
    val isoCode: String? = null,
) {
    /** Languages and tags are stored in lower case; show them capitalised. */
    val displayName: String get() = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
}

/**
 * The countries, languages and genres that actually have working stations in the chosen
 * providers, so settings only ever offer real choices. Each provider's lists are fetched once
 * per process; choosing several providers merges their lists and adds up the station counts.
 */
class Catalog(private val providers: Providers) {
    private enum class Kind { Countries, Languages, Genres }

    private val locks = ConcurrentHashMap<Pair<String, Kind>, Mutex>()
    private val cache = ConcurrentHashMap<Pair<String, Kind>, List<CatalogEntry>>()

    suspend fun countries(sources: Set<String>): List<CatalogEntry> = merged(sources, Kind.Countries)

    suspend fun languages(sources: Set<String>): List<CatalogEntry> = merged(sources, Kind.Languages)

    suspend fun genres(sources: Set<String>): List<CatalogEntry> = merged(sources, Kind.Genres)

    /** Works out what a typed mood refers to; unknown words become a loose genre match. */
    suspend fun parseMood(text: String, sources: Set<String>): Mood = Mood.parse(
        text,
        languages = runCatching { languages(sources) }.getOrDefault(emptyList()),
        countries = runCatching { countries(sources) }.getOrDefault(emptyList()),
    )

    /** The language name for an ISO 639 language code, e.g. "hi" → "hindi". */
    suspend fun languageForIso(iso: String, sources: Set<String>): String? =
        runCatching { languages(sources) }.getOrDefault(emptyList())
            .filter { it.isoCode.equals(iso, ignoreCase = true) }
            .maxByOrNull { it.stationCount }
            ?.key

    /** Fails only when every provider fails; a provider that can't be reached is left out. */
    private suspend fun merged(sources: Set<String>, kind: Kind): List<CatalogEntry> = coroutineScope {
        val results = providers.enabled(sources).map { p -> async { runCatching { entries(p, kind) } } }.awaitAll()
        if (results.none { it.isSuccess }) throw results.first().exceptionOrNull()!!
        merge(results.mapNotNull { it.getOrNull() })
    }

    private suspend fun entries(provider: StationProvider, kind: Kind): List<CatalogEntry> {
        val key = provider.id to kind
        cache[key]?.let { return it }
        return locks.getOrPut(key) { Mutex() }.withLock {
            cache[key] ?: when (kind) {
                Kind.Countries -> provider.countries()
                Kind.Languages -> provider.languages()
                Kind.Genres -> provider.genres()
            }.also { cache[key] = it }
        }
    }

    companion object {
        /** One entry per key, named as the provider with the most stations names it. */
        fun merge(lists: List<List<CatalogEntry>>): List<CatalogEntry> = lists.flatten()
            .filter { it.key.isNotBlank() && it.name.isNotBlank() && it.stationCount > 0 }
            .groupBy { it.key.lowercase() }
            .map { (_, same) ->
                same.maxBy { it.stationCount }.copy(
                    stationCount = same.sumOf { it.stationCount },
                    isoCode = same.firstNotNullOfOrNull { it.isoCode },
                )
            }
            .sortedBy { it.name.lowercase() }
    }
}
