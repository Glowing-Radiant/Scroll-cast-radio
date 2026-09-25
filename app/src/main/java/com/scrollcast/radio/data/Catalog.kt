package com.scrollcast.radio.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exact search terms for one Radio Browser query; null means "any". */
data class StationQuery(
    val countryCode: String? = null,
    val language: String? = null,
    val tag: String? = null,
)

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
