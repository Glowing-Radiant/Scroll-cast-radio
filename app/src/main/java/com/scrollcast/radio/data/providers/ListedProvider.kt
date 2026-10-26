package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.CatalogEntry
import com.scrollcast.radio.data.Station
import com.scrollcast.radio.data.StationQuery
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A provider with few enough stations to hold them all: the list is loaded once per process and
 * searched on the phone.
 */
abstract class ListedProvider : StationProvider {
    private val lock = Mutex()
    @Volatile private var cache: List<Station>? = null

    /** The provider's full station list. */
    protected abstract suspend fun load(): List<Station>

    /** False for stations that exist but shouldn't come up in the feed right now. */
    protected open fun inRotation(station: Station): Boolean = true

    private suspend fun stations(): List<Station> =
        cache ?: lock.withLock { cache ?: load().also { cache = it } }

    private suspend fun playable(): List<Station> = stations().filter(::inRotation)

    override suspend fun sample(query: StationQuery, limit: Int, popular: Boolean): List<Station> =
        playable().filter(query::matches).shuffled().take(limit)

    override suspend fun station(id: String): Station? = stations().firstOrNull { it.uuid == id }

    override suspend fun countries(): List<CatalogEntry> = playable()
        .filter { it.countryCode.length == 2 }
        .groupBy { it.countryCode.uppercase() }
        .map { (code, same) -> CatalogEntry(code, same.first().country.ifBlank { code }, same.size) }

    override suspend fun languages(): List<CatalogEntry> = count(playable().flatMap { it.languageList })

    override suspend fun genres(): List<CatalogEntry> =
        count(playable().flatMap { s -> s.tagList.map { it.lowercase() } })

    private fun count(keys: List<String>) =
        keys.groupingBy { it }.eachCount().map { (key, n) -> CatalogEntry(key, key, n) }
}
