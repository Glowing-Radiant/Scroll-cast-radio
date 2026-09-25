package com.scrollcast.radio.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Builds the endless random feed.
 *
 * The region and language settings decide what share of each batch comes from which query
 * (e.g. "mostly India" = 70% Indian stations, 30% worldwide). Each query samples stations at
 * random, a slice of popular stations is added, and the whole lot is ordered by a weighted
 * shuffle so well-voted stations surface a bit more often without the feed becoming a chart.
 */
class FeedRepository(
    private val api: RadioBrowserClient,
    private val catalog: Catalog,
    private val settings: SettingsStore,
    private val region: RegionDetector,
) {
    private val stations = ConcurrentHashMap<String, Station>()
    private val served = ConcurrentHashMap.newKeySet<String>()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _moreRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Emitted when the UI asks for stations again (e.g. "Try again" after a network error). */
    val moreRequests: SharedFlow<Unit> = _moreRequests.asSharedFlow()

    fun requestMore() {
        _error.value = null
        _moreRequests.tryEmit(Unit)
    }

    fun cached(uuid: String): Station? = stations[uuid]

    fun remember(station: Station) {
        stations[station.uuid] = station
    }

    fun resetServed() = served.clear()

    suspend fun stationById(uuid: String): Station? =
        stations[uuid] ?: runCatching { api.stationByUuid(uuid) }.getOrNull()?.also(::remember)

    suspend fun countClick(uuid: String) = api.countClick(uuid)

    /** Turns "automatic" settings into the detected country and the device language. */
    suspend fun resolve(prefs: FeedPrefs): ResolvedPrefs = ResolvedPrefs(
        countryCode = prefs.countryCode ?: region.countryCode(),
        regionMix = prefs.regionMix,
        language = prefs.language
            ?: if (prefs.languageMix == Mix.Mixed) null else catalog.languageForIso(region.languageCode()),
        languageMix = prefs.languageMix,
        genre = prefs.genre,
    )

    /** Returns the next stations for the feed, or an empty list (with [error] set) on failure. */
    suspend fun nextBatch(): List<Station> {
        _loading.value = true
        try {
            val prefs = resolve(settings.feed.value)
            var batch = fetchBatch(prefs)
            if (batch.isEmpty() && served.isNotEmpty()) {
                // A narrow feed (say, one small country only) has been played through: start over.
                served.clear()
                batch = fetchBatch(prefs)
            }
            batch.forEach { remember(it); served += it.uuid }
            _error.value = if (batch.isNotEmpty()) null else "No stations match your settings. Try a wider mix in Settings."
            return batch
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _error.value = "Couldn't reach Radio Browser. Check your connection and try again."
            return emptyList()
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetchBatch(prefs: ResolvedPrefs): List<Station> {
        val plan = planQueries(prefs, RANDOM_SAMPLE)
        val candidates = coroutineScope {
            val random = plan.map { (query, count) ->
                async { runCatching { api.search(query, order = "random", limit = count) }.getOrNull() }
            }
            val popular = async { runCatching { popularSlice(plan.first().first) }.getOrDefault(emptyList()) }
            val results = random.awaitAll()
            // Only a total failure is an error; an empty narrow query just leaves room for the others.
            if (results.all { it == null }) throw java.io.IOException("All station queries failed")
            results.filterNotNull().flatten() + popular.await()
        }
        return pickBatch(candidates, served, BATCH_SIZE)
    }

    private suspend fun popularSlice(query: StationQuery): List<Station> {
        val window = if (query == StationQuery()) 2000 else 60
        val slice = api.search(query, order = "votes", reverse = true, limit = POPULAR_SAMPLE, offset = Random.nextInt(window))
        return slice.ifEmpty { api.search(query, order = "votes", reverse = true, limit = POPULAR_SAMPLE) }
    }

    companion object {
        const val BATCH_SIZE = 15
        private const val RANDOM_SAMPLE = 45
        private const val POPULAR_SAMPLE = 12

        /**
         * Splits [total] random picks across queries so the feed matches the region and language
         * mix. Region and language are independent, so "mostly India" + "mostly Hindi" gives
         * four queries: India+Hindi 49%, India+any 21%, anywhere+Hindi 21%, anywhere+any 9%.
         * The largest share comes first.
         */
        fun planQueries(prefs: ResolvedPrefs, total: Int): List<Pair<StationQuery, Int>> {
            val regionShare = if (prefs.countryCode == null) 0.0 else prefs.regionMix.share
            val languageShare = if (prefs.language == null) 0.0 else prefs.languageMix.share
            val plan = mutableListOf<Pair<StationQuery, Double>>()
            for (useRegion in listOf(true, false)) for (useLanguage in listOf(true, false)) {
                val p = (if (useRegion) regionShare else 1 - regionShare) *
                    (if (useLanguage) languageShare else 1 - languageShare)
                if (p <= 0.0) continue
                val query = StationQuery(
                    countryCode = prefs.countryCode.takeIf { useRegion },
                    language = prefs.language.takeIf { useLanguage },
                    tag = prefs.genre,
                )
                plan += query to p
            }
            return plan
                .sortedByDescending { it.second }
                .map { (query, p) -> query to (p * total).roundToInt().coerceAtLeast(MIN_PER_QUERY) }
        }

        private const val MIN_PER_QUERY = 4

        /**
         * Drops unplayable, duplicate and already-served stations, then orders the rest with a
         * weighted random shuffle (Efraimidis–Spirakis) and returns the first [size].
         */
        fun pickBatch(
            candidates: List<Station>,
            alreadyServed: Set<String>,
            size: Int,
            random: Random = Random.Default,
        ): List<Station> = candidates
            .asSequence()
            .filter { it.streamUrl.isNotBlank() && it.lastCheckOk == 1 && it.uuid !in alreadyServed }
            .distinctBy { it.uuid }
            .distinctBy { it.streamUrl.trimEnd('/').lowercase() }
            .map { it to random.nextDouble().pow(1.0 / weight(it)) }
            .sortedByDescending { it.second }
            .take(size)
            .map { it.first }
            .toList()

        fun weight(station: Station): Double =
            1.0 + ln(1.0 + station.votes.coerceAtLeast(0)) * 0.6 +
                ln(1.0 + station.clickCount.coerceAtLeast(0)) * 0.3
    }
}
