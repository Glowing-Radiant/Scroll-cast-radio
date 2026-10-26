package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Station
import com.scrollcast.radio.data.StationQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import kotlin.random.Random

/**
 * laut.fm (https://laut.fm): thousands of stations run by music fans, mostly in Germany, via
 * its public API (https://api.laut.fm). Stations only record a town, not a country or language,
 * so laut.fm only answers queries that don't ask for one.
 */
class LautFmProvider(private val http: OkHttpClient, private val json: Json) : StationProvider {
    override val id = ID
    override val name = "laut.fm"
    override val description = "Thousands of stations run by music fans, mostly from Germany."

    @Serializable
    private data class LautStation(
        val name: String = "",
        @SerialName("display_name") val displayName: String = "",
        @SerialName("page_url") val pageUrl: String = "",
        @SerialName("stream_url") val streamUrl: String = "",
        val genres: List<String> = emptyList(),
        val active: Boolean = true,
        val images: Images? = null,
    )

    @Serializable
    private data class Images(@SerialName("station_640x640") val large: String? = null)

    /** Lists come back as pages like this whenever an offset is given. */
    @Serializable
    private data class Page(val total: Int = 0, val items: List<LautStation> = emptyList())

    @Serializable
    private data class Genre(val name: String = "", val score: Double = 0.0)

    @Serializable
    private data class SearchResults(val results: List<SearchGroup> = emptyList())

    @Serializable
    private data class SearchGroup(val items: List<SearchItem> = emptyList())

    @Serializable
    private data class SearchItem(val station: LautStation? = null)

    private val lock = Mutex()
    @Volatile private var stationCount: Int? = null
    @Volatile private var genreCache: List<Genre>? = null

    override suspend fun sample(query: StationQuery, limit: Int, popular: Boolean): List<Station> {
        if (query.countryCode != null || query.language != null) return emptyList()
        val found = when {
            query.name != null -> search(query.name, limit)
            query.tag != null && query.tagExact -> byGenre(query.tag, limit)
            // A loose tag is a typed mood: laut.fm's search covers genres, names and descriptions.
            query.tag != null -> search(query.tag, limit)
            else -> anywhere(limit)
        }
        return found.filter { it.active && it.streamUrl.isNotBlank() }.map { it.toStation() }.shuffled().take(limit)
    }

    /** Random slices of the full list; one request per slice keeps each one small. */
    private suspend fun anywhere(limit: Int): List<LautStation> = coroutineScope {
        val total = count()
        val slice = (limit + SLICES - 1) / SLICES
        (1..SLICES).map {
            async { page(url("stations") { pageOf(total, slice) }).items }
        }.awaitAll().flatten()
    }

    private suspend fun byGenre(tag: String, limit: Int): List<LautStation> {
        val genre = genreList().firstOrNull { it.name.equals(tag, ignoreCase = true) } ?: return emptyList()
        // Genre names are case sensitive in the path ("Jazz", not "jazz").
        return page(url("stations", "genre", genre.name) { pageOf(genre.score.toInt(), limit * 2) }).items
    }

    private suspend fun search(text: String, limit: Int): List<LautStation> {
        val body = http.fetchText(
            url("search", "stations") {
                addQueryParameter("query", text)
                addQueryParameter("limit", (limit * 2).toString())
            },
            name,
        )
        return json.decodeFromString(SearchResults.serializer(), body).results
            .flatMap { group -> group.items.mapNotNull { it.station } }
            .distinctBy { it.name }
    }

    override suspend fun station(id: String): Station? {
        val body = http.fetchText(url("station", Station.localIdOf(id)) {}, name)
        return json.decodeFromString(LautStation.serializer(), body).toStation()
    }

    override suspend fun genres(): List<CatalogEntry> = genreList()
        .filter { it.name.isNotBlank() && it.score >= MIN_STATIONS }
        .map { CatalogEntry(it.name.lowercase(), it.name, it.score.toInt()) }

    private suspend fun genreList(): List<Genre> = genreCache ?: lock.withLock {
        genreCache ?: json.decodeFromString(ListSerializer(Genre.serializer()), http.fetchText(url("genres") {}, name))
            .also { genreCache = it }
    }

    /** How many stations laut.fm has, read from a one-station page. */
    private suspend fun count(): Int = stationCount ?: lock.withLock {
        stationCount ?: page(
            url("stations") {
                addQueryParameter("limit", "1")
                addQueryParameter("offset", "0")
            },
        ).total.also { stationCount = it }
    }

    private suspend fun page(url: HttpUrl): Page = json.decodeFromString(Page.serializer(), http.fetchText(url, name))

    private fun url(vararg segments: String, query: HttpUrl.Builder.() -> Unit): HttpUrl =
        API.toHttpUrl().newBuilder().apply {
            segments.forEach(::addPathSegment)
            query()
        }.build()

    /** A random page of [size] out of [total] items. */
    private fun HttpUrl.Builder.pageOf(total: Int, size: Int) {
        addQueryParameter("limit", size.toString())
        addQueryParameter("offset", Random.nextInt((total - size).coerceAtLeast(1)).toString())
    }

    private fun LautStation.toStation() = Station(
        uuid = Station.id(ID, name),
        name = displayName.ifBlank { name },
        url = streamUrl,
        homepage = pageUrl,
        favicon = images?.large.orEmpty(),
        tags = genres.joinToString(","),
        codec = "MP3",
        lastCheckOk = if (active) 1 else 0,
    )

    private companion object {
        const val ID = "lautfm"
        const val API = "https://api.laut.fm"
        const val SLICES = 3
        const val MIN_STATIONS = 3
    }
}
