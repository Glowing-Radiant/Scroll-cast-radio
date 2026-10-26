package com.scrollcast.radio.data

import com.scrollcast.radio.data.providers.Providers
import com.scrollcast.radio.data.providers.StationProvider
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
 * Builds the endless random feed from the providers chosen in Settings.
 *
 * The region and language settings decide what share of each batch comes from which query
 * (e.g. "mostly India" = 70% Indian stations, 30% worldwide). Every chosen provider is asked
 * each query, and each query's share is split evenly between the providers that have matching
 * stations. Stations are picked by a weighted shuffle, so well-voted stations surface a bit more
 * often without the feed becoming a chart.
 */
class FeedRepository(
    private val providers: Providers,
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

    suspend fun stationById(uuid: String): Station? = stations[uuid]
        ?: runCatching { providers.forStation(uuid)?.station(uuid) }.getOrNull()?.also(::remember)

    suspend fun countClick(uuid: String) {
        runCatching { providers.forStation(uuid)?.reportPlay(uuid) }
    }

    /**
     * Turns "automatic" settings into the detected country and the device language, then lets
     * a typed mood override whatever it names: "hindi" means only Hindi, "brazil" only
     * Brazil, and other words replace the genre with a loose tag match.
     */
    suspend fun resolve(prefs: FeedPrefs): ResolvedPrefs {
        val base = ResolvedPrefs(
            countryCode = prefs.countryCode ?: region.countryCode(),
            regionMix = prefs.regionMix,
            language = prefs.language
                ?: if (prefs.languageMix == Mix.Mixed) null else catalog.languageForIso(region.languageCode(), prefs.sources),
            languageMix = prefs.languageMix,
            genre = prefs.genre,
        )
        val keyword = prefs.mood?.trim()?.takeIf { it.isNotEmpty() } ?: return base
        val mood = catalog.parseMood(keyword, prefs.sources)
        return base.copy(
            countryCode = mood.countryCode ?: base.countryCode,
            regionMix = if (mood.countryCode != null) Mix.Only else base.regionMix,
            language = mood.language ?: base.language,
            languageMix = if (mood.language != null) Mix.Only else base.languageMix,
            genre = mood.tag,
            genreExact = false,
            moodKeyword = keyword,
        )
    }

    /** Returns the next stations for the feed, or an empty list (with [error] set) on failure. */
    suspend fun nextBatch(): List<Station> {
        _loading.value = true
        val feedPrefs = settings.feed.value
        val sources = providers.enabled(feedPrefs.sources)
        try {
            val prefs = resolve(feedPrefs)
            var batch = fetchBatch(prefs, sources)
            if (batch.isEmpty() && prefs.moodKeyword != null) batch = moodFallback(prefs.moodKeyword, sources)
            if (batch.isEmpty() && served.isNotEmpty()) {
                // A narrow feed (say, one small country only) has been played through: start over.
                served.clear()
                batch = fetchBatch(prefs, sources)
                if (batch.isEmpty() && prefs.moodKeyword != null) batch = moodFallback(prefs.moodKeyword, sources)
            }
            batch.forEach { remember(it); served += it.uuid }
            _error.value = when {
                batch.isNotEmpty() -> null
                prefs.moodKeyword != null -> "No stations found for “${prefs.moodKeyword}”. Try another mood."
                else -> "No stations match your settings. Try a wider mix in Settings."
            }
            return batch
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            _error.value = "Couldn't reach ${providers.names(sources)}. Check your connection and try again."
            return emptyList()
        } finally {
            _loading.value = false
        }
    }

    private suspend fun fetchBatch(prefs: ResolvedPrefs, sources: List<StationProvider>): List<Station> {
        val plan = planQueries(prefs, RANDOM_SAMPLE)
        // The popular slice rides on the biggest query, as the feed's main flavour.
        return mixBatch(sample(plan, sources, popularFirst = true), served, BATCH_SIZE)
    }

    /** Last resort for a mood: the keyword as a tag or in station names, anywhere in the world. */
    private suspend fun moodFallback(keyword: String, sources: List<StationProvider>): List<Station> {
        val plan = listOf(
            StationQuery(tag = keyword, tagExact = false) to RANDOM_SAMPLE,
            StationQuery(name = keyword) to RANDOM_SAMPLE,
        )
        return runCatching { mixBatch(sample(plan, sources, popularFirst = false), served, BATCH_SIZE) }
            .getOrDefault(emptyList())
    }

    /**
     * Asks every provider every query at once. Only a total failure is an error; a provider that
     * can't be reached, or has nothing for a narrow query, just leaves room for the others.
     */
    private suspend fun sample(
        plan: List<Pair<StationQuery, Int>>,
        sources: List<StationProvider>,
        popularFirst: Boolean,
    ): List<QueryPart> = coroutineScope {
        val pending = plan.mapIndexed { i, (query, count) ->
            count to sources.map { p ->
                async { runCatching { p.sample(query, count, popular = popularFirst && i == 0) }.getOrNull() }
            }
        }
        val parts = pending.map { (count, results) -> count to results.awaitAll() }
        if (parts.all { (_, results) -> results.all { it == null } }) {
            throw java.io.IOException("All station queries failed")
        }
        parts.map { (count, results) -> QueryPart(count, results.map { it.orEmpty() }) }
    }

    /** One query's candidates: [share] is its weight in the batch, one list per provider. */
    data class QueryPart(val share: Int, val byProvider: List<List<Station>>)

    companion object {
        const val BATCH_SIZE = 15
        private const val RANDOM_SAMPLE = 45

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
                    tagExact = prefs.genreExact,
                )
                plan += query to p
            }
            return plan
                .sortedByDescending { it.second }
                .map { (query, p) -> query to (p * total).roundToInt().coerceAtLeast(MIN_PER_QUERY) }
        }

        private const val MIN_PER_QUERY = 4

        /**
         * Fills a batch of [size] from the query parts. Each part gets its share of the batch,
         * split evenly between the providers that have stations for it; a provider that runs
         * short leaves its places to the others, and any places still empty are filled from
         * whatever is left. The same stream listed by two providers is only taken once.
         */
        fun mixBatch(
            parts: List<QueryPart>,
            alreadyServed: Set<String>,
            size: Int,
            random: Random = Random.Default,
        ): List<Station> {
            val chosen = mutableListOf<Station>()
            val takenIds = alreadyServed.toHashSet()
            val takenStreams = HashSet<String>()

            fun take(candidates: List<Station>, count: Int): Int {
                val fresh = candidates.filter { it.uuid !in takenIds && streamKey(it) !in takenStreams }
                val picked = pickBatch(fresh, emptySet(), count, random)
                picked.forEach { takenIds += it.uuid; takenStreams += streamKey(it) }
                chosen += picked
                return picked.size
            }

            val totalShare = parts.sumOf { it.share }.coerceAtLeast(1)
            for (part in parts) {
                var places = (size.toDouble() * part.share / totalShare).roundToInt()
                    .coerceAtMost(size - chosen.size)
                val pools = part.byProvider.filter { it.isNotEmpty() }.toMutableList()
                while (places > 0 && pools.isNotEmpty()) {
                    val each = (places + pools.size - 1) / pools.size
                    for (pool in pools.shuffled(random)) {
                        if (places == 0) break
                        val want = minOf(each, places)
                        val got = take(pool, want)
                        places -= got
                        if (got < want) pools -= pool
                    }
                }
            }
            if (chosen.size < size) take(parts.flatMap { it.byProvider.flatten() }, size - chosen.size)
            // Shuffle the batch so the providers and queries are interleaved.
            return pickBatch(chosen, emptySet(), chosen.size, random)
        }

        private fun streamKey(station: Station) = station.streamUrl.trimEnd('/').lowercase()

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
            .distinctBy { streamKey(it) }
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
