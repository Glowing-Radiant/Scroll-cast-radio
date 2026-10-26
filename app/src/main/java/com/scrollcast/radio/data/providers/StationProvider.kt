package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Station
import com.scrollcast.radio.data.StationQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * A directory of stations the feed can draw from. The user picks one or more in Settings;
 * several are mixed into one feed.
 */
interface StationProvider {
    /** Stable id, saved in settings and used as the prefix of this provider's station ids. */
    val id: String
    val name: String
    /** One line for Settings describing what the provider offers. */
    val description: String

    /**
     * Up to [limit] random stations matching [query]. A query on something the provider doesn't
     * record (most only know their own country, if any) matches nothing. With [popular], a few
     * well-liked stations may be added on top. Throws when the provider can't be reached.
     */
    suspend fun sample(query: StationQuery, limit: Int, popular: Boolean = false): List<Station>

    /** Looks up one of this provider's stations by its full station id. */
    suspend fun station(id: String): Station?

    /** Tells the provider a station was listened to, for providers that rank by it. */
    suspend fun reportPlay(stationId: String) {}

    /** What can be chosen in Settings; keys follow [CatalogEntry]. Empty when not applicable. */
    suspend fun countries(): List<CatalogEntry> = emptyList()
    suspend fun languages(): List<CatalogEntry> = emptyList()
    suspend fun genres(): List<CatalogEntry> = emptyList()
}

/**
 * Every provider the app knows: the built-in directories, in the order Settings lists them,
 * then the user's own sources.
 */
class Providers(
    val builtIn: List<StationProvider>,
    private val user: () -> List<StationProvider> = { emptyList() },
) {
    init {
        require(builtIn.isNotEmpty())
    }

    val all: List<StationProvider> get() = builtIn + user()

    fun byId(id: String): StationProvider? = all.firstOrNull { it.id == id }

    /** The provider a station id belongs to. */
    fun forStation(stationId: String): StationProvider? = byId(Station.providerOf(stationId))

    /** The chosen providers; never empty, so the feed always has a source. */
    fun enabled(ids: Set<String>): List<StationProvider> = all.filter { it.id in ids }.ifEmpty { listOf(builtIn.first()) }

    /** "Radio Browser", "Radio Browser and SomaFM", "A, B and C". */
    fun names(providers: List<StationProvider>): String {
        val names = providers.map { it.name }
        return if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()
    }
}

/**
 * Whether [station] satisfies this query; for providers that search a downloaded list
 * themselves. Follows Radio Browser's rules: exact country and language, exact or partial tag,
 * name containing the text.
 */
fun StationQuery.matches(station: Station): Boolean {
    if (countryCode != null && !station.countryCode.equals(countryCode, ignoreCase = true)) return false
    if (language != null && language.lowercase() !in station.languageList) return false
    if (tag != null) {
        val wanted = tag.lowercase()
        val tags = station.tagList.map { it.lowercase() }
        if (if (tagExact) wanted !in tags else tags.none { wanted in it }) return false
    }
    if (name != null && !station.name.contains(name, ignoreCase = true)) return false
    return true
}

/** Fetches [url] as text, throwing when [source] can't be reached or answers with an error. */
internal suspend fun OkHttpClient.fetchText(url: HttpUrl, source: String): String = withContext(Dispatchers.IO) {
    newCall(Request.Builder().url(url).build()).execute().use { response ->
        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $source")
        response.body.string()
    }
}
