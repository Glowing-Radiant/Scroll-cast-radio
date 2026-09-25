package com.scrollcast.radio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Talks to the Radio Browser API (https://api.radio-browser.info).
 *
 * The API is run as a set of community mirrors. The live mirror list is fetched once from
 * `all.api.radio-browser.info`; requests go to the last mirror that worked and fail over to
 * the others in turn.
 */
class RadioBrowserClient(
    private val http: OkHttpClient,
    private val json: Json,
) {
    @Serializable
    private data class Server(val name: String = "")

    @Volatile private var mirrors: List<String>? = null
    @Volatile private var preferred: String? = null

    suspend fun search(
        query: StationQuery,
        order: String,
        limit: Int,
        offset: Int = 0,
        reverse: Boolean = false,
    ): List<Station> = get("json/stations/search", ListSerializer(Station.serializer())) {
        addQueryParameter("order", order)
        addQueryParameter("reverse", reverse.toString())
        addQueryParameter("limit", limit.toString())
        addQueryParameter("offset", offset.toString())
        addQueryParameter("hidebroken", "true")
        query.countryCode?.let { addQueryParameter("countrycode", it) }
        query.language?.let {
            addQueryParameter("language", it)
            addQueryParameter("languageExact", "true")
        }
        query.tag?.let {
            addQueryParameter("tag", it)
            addQueryParameter("tagExact", query.tagExact.toString())
        }
        query.name?.let { addQueryParameter("name", it) }
    }

    suspend fun countries(): List<CatalogEntry> =
        get("json/countries", ListSerializer(CountryDto.serializer())) {
            addQueryParameter("hidebroken", "true")
        }.map { CatalogEntry(it.code.uppercase(), it.name, it.stationCount) }

    suspend fun languages(): List<CatalogEntry> =
        get("json/languages", ListSerializer(LanguageDto.serializer())) {
            addQueryParameter("hidebroken", "true")
        }.map { CatalogEntry(it.name, it.name, it.stationCount, isoCode = it.iso) }

    suspend fun genres(limit: Int): List<CatalogEntry> =
        get("json/tags", ListSerializer(TagDto.serializer())) {
            addQueryParameter("hidebroken", "true")
            addQueryParameter("order", "stationcount")
            addQueryParameter("reverse", "true")
            addQueryParameter("limit", limit.toString())
        }.map { CatalogEntry(it.name, it.name, it.stationCount) }

    @Serializable
    private data class CountryDto(
        val name: String = "",
        @SerialName("iso_3166_1") val code: String = "",
        @SerialName("stationcount") val stationCount: Int = 0,
    )

    @Serializable
    private data class LanguageDto(
        val name: String = "",
        @SerialName("iso_639") val iso: String? = null,
        @SerialName("stationcount") val stationCount: Int = 0,
    )

    @Serializable
    private data class TagDto(
        val name: String = "",
        @SerialName("stationcount") val stationCount: Int = 0,
    )

    suspend fun stationByUuid(uuid: String): Station? =
        get("json/stations/byuuid/$uuid", ListSerializer(Station.serializer())) {}.firstOrNull()

    /** Tells Radio Browser a station was listened to, which feeds its popularity ranking. */
    suspend fun countClick(uuid: String) {
        runCatching { getRaw("json/url/$uuid") {} }
    }

    private suspend fun <T> get(
        path: String,
        deserializer: kotlinx.serialization.DeserializationStrategy<T>,
        query: HttpUrl.Builder.() -> Unit,
    ): T = json.decodeFromString(deserializer, getRaw(path, query))

    private suspend fun getRaw(path: String, query: HttpUrl.Builder.() -> Unit): String =
        withContext(Dispatchers.IO) {
            var lastError: Exception? = null
            for (host in orderedMirrors()) {
                val url = "https://$host/$path".toHttpUrl().newBuilder().apply(query).build()
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} from $host")
                        preferred = host
                        return@withContext response.body.string()
                    }
                } catch (e: IOException) {
                    lastError = e
                    if (preferred == host) preferred = null
                }
            }
            throw lastError ?: IOException("No Radio Browser mirror reachable")
        }

    private fun orderedMirrors(): List<String> {
        val known = mirrors ?: discoverMirrors().also { mirrors = it }
        val first = preferred
        return if (first == null) known else listOf(first) + (known - first)
    }

    private fun discoverMirrors(): List<String> {
        val discovered = runCatching {
            val request = Request.Builder().url("https://$DISCOVERY_HOST/json/servers").build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                json.decodeFromString(ListSerializer(Server.serializer()), response.body.string())
                    .map { it.name }
                    .filter { it.endsWith(".radio-browser.info") }
            }
        }.getOrDefault(emptyList())
        return (discovered.distinct().shuffled() + FALLBACK_MIRRORS).distinct()
    }

    companion object {
        private const val DISCOVERY_HOST = "all.api.radio-browser.info"

        /** Known mirrors, used when discovery fails or its mirrors are down. */
        private val FALLBACK_MIRRORS = listOf(
            "de1.api.radio-browser.info",
            "de2.api.radio-browser.info",
            "fi1.api.radio-browser.info",
            DISCOVERY_HOST,
        )
    }
}
