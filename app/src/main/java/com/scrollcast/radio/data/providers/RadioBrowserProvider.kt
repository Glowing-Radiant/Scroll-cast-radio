package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Station
import com.scrollcast.radio.data.StationQuery
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlin.random.Random

/** The community-run Radio Browser directory: tens of thousands of stations worldwide. */
class RadioBrowserProvider(private val api: RadioBrowserClient) : StationProvider {
    override val id = Station.RADIO_BROWSER
    override val name = "Radio Browser"
    override val description = "Community directory of over 50,000 stations from every country."

    override suspend fun sample(query: StationQuery, limit: Int, popular: Boolean): List<Station> = coroutineScope {
        val top = async { if (popular) runCatching { popularSlice(query) }.getOrDefault(emptyList()) else emptyList() }
        api.search(query, order = "random", limit = limit) + top.await()
    }

    /** A random window of well-voted stations, so favourites surface without the feed becoming a chart. */
    private suspend fun popularSlice(query: StationQuery): List<Station> {
        val window = if (query == StationQuery()) 2000 else 60
        val slice = api.search(query, order = "votes", reverse = true, limit = POPULAR_SAMPLE, offset = Random.nextInt(window))
        return slice.ifEmpty { api.search(query, order = "votes", reverse = true, limit = POPULAR_SAMPLE) }
    }

    override suspend fun station(id: String): Station? = api.stationByUuid(id)

    override suspend fun reportPlay(stationId: String) = api.countClick(stationId)

    override suspend fun countries(): List<CatalogEntry> = api.countries()
        .filter { it.key.length == 2 && it.name.isNotBlank() && it.stationCount > 0 }
        .groupBy { it.key }
        .map { (_, same) -> same.maxBy { it.stationCount }.copy(stationCount = same.sumOf { it.stationCount }) }

    override suspend fun languages(): List<CatalogEntry> =
        api.languages().filter { it.name.isNotBlank() && it.stationCount >= MIN_STATIONS }

    override suspend fun genres(): List<CatalogEntry> =
        api.genres(limit = 300).filter { it.name.isNotBlank() && it.stationCount >= MIN_STATIONS }

    private companion object {
        const val POPULAR_SAMPLE = 12
        /** Skip one-off spellings that would give a feed of one or two stations. */
        const val MIN_STATIONS = 3
    }
}
