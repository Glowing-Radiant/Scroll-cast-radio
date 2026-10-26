package com.scrollcast.radio.data.providers

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

/** One station read from a playlist file. Blank fields are unknown. */
data class PlaylistEntry(
    val name: String,
    val url: String,
    val logo: String = "",
    val tags: List<String> = emptyList(),
    val countryCode: String = "",
    val languages: List<String> = emptyList(),
)

/**
 * Reads the station lists people publish: M3U / M3U8 (including IPTV-style `#EXTINF`
 * attributes), PLS, and JSON lists of stations. A link that is itself a stream (an HLS media
 * playlist) comes back as a single entry.
 */
object Playlists {
    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
    /** "News (1080p)", "Movies [Not 24/7]": IPTV lists put stream details after the name. */
    private val NAME_SUFFIX = Regex("""(\s*(\(\d{3,4}[pi]\)|\[[^\]]*\]))+\s*$""")
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, link: String): List<PlaylistEntry> {
        val body = text.removePrefix("﻿").trim()
        return when {
            body.startsWith("#EXTM3U") && "#EXT-X-" in body -> listOf(PlaylistEntry(nameFromUrl(link), link))
            body.startsWith("[playlist]", ignoreCase = true) -> parsePls(body)
            body.startsWith("[") || body.startsWith("{") -> parseJson(body)
            else -> parseM3u(body)
        }.filter { isStreamUrl(it.url) }.distinctBy { it.url }
    }

    private fun parseM3u(body: String): List<PlaylistEntry> {
        val entries = mutableListOf<PlaylistEntry>()
        var info: PlaylistEntry? = null
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> info = parseExtInf(line)
                line.isEmpty() || line.startsWith("#") -> Unit
                else -> {
                    entries += (info ?: PlaylistEntry(nameFromUrl(line), line)).copy(url = line)
                    info = null
                }
            }
        }
        return entries
    }

    /** `#EXTINF:-1 tvg-id="AajTak.in@SD" tvg-logo="…" group-title="News",Aaj Tak (720p)` */
    private fun parseExtInf(line: String): PlaylistEntry {
        // The title follows the first comma outside the quoted attributes.
        var inQuotes = false
        var comma = -1
        for ((i, c) in line.withIndex()) {
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) { comma = i; break }
        }
        val head = if (comma >= 0) line.substring(0, comma) else line
        val attrs = ATTRIBUTE.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        val title = if (comma >= 0) line.substring(comma + 1).trim() else ""
        val country = attrs["tvg-country"]?.let(::firstCountry)
            // iptv-org ids end in the country: "AajTak.in@SD".
            ?: attrs["tvg-id"]?.substringBefore('@')?.substringAfterLast('.', "")?.let(::firstCountry)
            ?: ""
        return PlaylistEntry(
            name = title.replace(NAME_SUFFIX, "").ifBlank { attrs["tvg-name"].orEmpty() },
            url = "",
            logo = attrs["tvg-logo"].orEmpty(),
            tags = attrs["group-title"].orEmpty().split(';', ',').map { it.trim() }.filter { it.isNotEmpty() && it != "Undefined" },
            countryCode = country,
            languages = attrs["tvg-language"].orEmpty().split(';', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() },
        )
    }

    private fun parsePls(body: String): List<PlaylistEntry> {
        val values = body.lineSequence()
            .map { it.trim() }
            .filter { '=' in it }
            .associate { it.substringBefore('=').trim().lowercase() to it.substringAfter('=').trim() }
        return values.keys
            .filter { it.startsWith("file") }
            .sortedBy { it.removePrefix("file").toIntOrNull() ?: Int.MAX_VALUE }
            .map { key ->
                val url = values.getValue(key)
                PlaylistEntry(values["title" + key.removePrefix("file")]?.ifBlank { null } ?: nameFromUrl(url), url)
            }
    }

    /** A JSON array of stations, or an object holding one under "stations", "channels" or "items". */
    private fun parseJson(body: String): List<PlaylistEntry> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("stations", "channels", "items", "data").firstNotNullOfOrNull { root[it] as? JsonArray }
            else -> null
        } ?: return emptyList()
        return list.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val url = o.text("url_resolved", "url", "stream_url", "streamUrl", "stream") ?: return@mapNotNull null
            PlaylistEntry(
                name = o.text("name", "title") ?: nameFromUrl(url),
                url = url,
                logo = o.text("favicon", "logo", "image").orEmpty(),
                tags = o.list("tags", "genres", "genre"),
                countryCode = o.text("countrycode", "country_code", "countryCode")?.let(::firstCountry).orEmpty(),
                languages = o.list("language", "languages").map { it.lowercase() },
            )
        }
    }

    private fun JsonObject.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { (this[it] as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }

    private fun JsonObject.list(vararg keys: String): List<String> {
        val value: JsonElement = keys.firstNotNullOfOrNull { this[it] } ?: return emptyList()
        val parts = when (value) {
            is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> value.contentOrNull.orEmpty().split(',', ';', '|')
            else -> emptyList()
        }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun firstCountry(value: String): String? =
        value.split(';', ',').map { it.trim().uppercase() }.firstOrNull { it.length == 2 && it.all(Char::isLetter) }

    fun isStreamUrl(url: String): Boolean = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** "https://host/path/name.m3u8" → "name", or the host when there's no useful file name. */
    fun nameFromUrl(url: String): String {
        val path = url.substringAfter("://").substringBefore('?').substringBefore('#')
        val host = path.substringBefore('/')
        val file = path.substringAfterLast('/', "").substringBeforeLast('.')
        return file.takeIf { it.isNotBlank() && it.lowercase() !in GENERIC_NAMES } ?: host
    }

    fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifBlank { code }

    private val GENERIC_NAMES = setOf("index", "playlist", "master", "stream", "live", "listen", "radio", "chunklist")
}
