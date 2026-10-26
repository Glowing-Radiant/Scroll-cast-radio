package com.scrollcast.radio.data.providers

import android.content.Context
import com.scrollcast.radio.data.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** A station list the user added by link: a playlist file, a GitHub file, or a single stream. */
@Serializable
data class UserSource(val id: String, val name: String, val url: String) {
    /** Where it comes from, for Settings, e.g. "github.com/iptv-org/iptv · streams/in.m3u". */
    val label: String
        get() {
            val raw = Regex("^https://raw\\.githubusercontent\\.com/([^/]+)/([^/]+)/[^/]+/(.+)$").find(url)
            if (raw != null) {
                val (owner, repo, path) = raw.destructured
                return "github.com/$owner/$repo · $path"
            }
            return url.substringAfter("://").substringBefore('?')
        }
}

/**
 * The user's own sources, saved on the phone. Each becomes a provider like the built-in ones,
 * so it can be switched on, mixed into the feed and filtered by region, language and genre.
 * The last download of each list is kept, so a source still works when its host is down.
 */
class UserSources(context: Context, private val http: OkHttpClient, private val json: Json) {
    private val prefs = context.getSharedPreferences("user_sources", Context.MODE_PRIVATE)
    private val cacheDir = File(context.filesDir, "user_sources")
    private val serializer = ListSerializer(UserSource.serializer())
    private val instances = ConcurrentHashMap<String, PlaylistProvider>()

    private val _sources = MutableStateFlow(
        runCatching { json.decodeFromString(serializer, prefs.getString(KEY, null) ?: "[]") }.getOrDefault(emptyList())
    )
    val sources: StateFlow<List<UserSource>> = _sources.asStateFlow()

    /** One provider per source, kept for the process so each list is only read once. */
    fun providers(): List<StationProvider> =
        _sources.value.map { source -> instances.getOrPut(source.id) { PlaylistProvider(source) } }

    sealed interface AddResult {
        data class Added(val source: UserSource, val stations: Int) : AddResult
        data class AlreadyAdded(val source: UserSource) : AddResult
    }

    /** Downloads the list at [url] and adds it if it holds at least one station. */
    suspend fun add(url: String, name: String): AddResult {
        val id = idFor(url)
        _sources.value.firstOrNull { it.id == id }?.let { return AddResult.AlreadyAdded(it) }
        val count = Playlists.parse(download(url, id), url).size
        if (count == 0) throw IOException("No stations found at that link.")
        val source = UserSource(id, name.trim().ifBlank { Playlists.nameFromUrl(url) }, url)
        _sources.update { it + source }
        save()
        return AddResult.Added(source, count)
    }

    /** Lists a GitHub folder's sub-folders and playlist files, for picking a source. */
    suspend fun browse(folder: GitHubFolder): List<GitHubEntry> = GitHubLinks.list(http, json, folder)

    fun remove(id: String) {
        _sources.update { list -> list.filterNot { it.id == id } }
        save()
        instances.remove(id)
        cacheFile(id).delete()
    }

    private fun save() {
        prefs.edit().putString(KEY, json.encodeToString(serializer, _sources.value)).apply()
    }

    private fun cacheFile(id: String) = File(cacheDir, "$id.txt")

    /** Fetches the list, falling back to the last copy when the host can't be reached. */
    private suspend fun download(url: String, id: String): String = withContext(Dispatchers.IO) {
        val cached = cacheFile(id)
        try {
            val text = http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("The link answered with an error (${response.code}).")
                val length = response.body.contentLength()
                if (length > MAX_BYTES) throw IOException("That file is too big to be a station list.")
                response.body.string()
            }
            cacheDir.mkdirs()
            cached.writeText(text)
            text
        } catch (e: IOException) {
            if (cached.exists()) cached.readText() else throw e
        } catch (e: IllegalArgumentException) {
            throw IOException("That isn't a web link.", e)
        }
    }

    /** A user source as a provider; the whole list is held, like the small built-in ones. */
    private inner class PlaylistProvider(private val source: UserSource) : ListedProvider() {
        override val id = source.id
        override val name = source.name
        override val description = source.label

        override suspend fun load(): List<Station> =
            Playlists.parse(download(source.url, source.id), source.url).map { e ->
                Station(
                    uuid = Station.id(id, hash(e.url, 12)),
                    name = e.name,
                    url = e.url,
                    favicon = e.logo,
                    tags = e.tags.joinToString(","),
                    country = e.countryCode.takeIf { it.isNotEmpty() }?.let(Playlists::countryName).orEmpty(),
                    countryCode = e.countryCode,
                    language = e.languages.joinToString(","),
                    hls = if (e.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) 1 else 0,
                )
            }
    }

    companion object {
        private const val KEY = "sources"
        private const val MAX_BYTES = 30L * 1024 * 1024

        /** "user-" plus a hash of the link. The dash keeps these out of app links, which only
         * another phone with the same source could open. */
        fun idFor(url: String): String = "user-" + hash(url.trim(), 8)

        fun isUserSource(providerId: String): Boolean = providerId.startsWith("user-")

        private fun hash(text: String, length: Int): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(length)
    }
}
