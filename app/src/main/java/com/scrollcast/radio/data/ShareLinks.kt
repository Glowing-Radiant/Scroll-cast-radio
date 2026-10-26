package com.scrollcast.radio.data

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Share links:
 *  - station app link: `<base>/s/?id=<station id>`, a web page that opens the station in the app
 *    (or plays it in the browser when the app isn't installed), plus `scrollcast://station/<id>`.
 *    Stations from a user's own source can't be looked up on another phone, so their links also
 *    carry the name and stream (`&n=…&u=…`) and play from those;
 *  - source link: `<base>/s/?source=<list url>&n=<name>` (or `scrollcast://source?url=…`), which
 *    offers to add one of the user's own sources on another phone;
 *  - direct link: the station's own stream URL, playable in any player.
 */
object ShareLinks {
    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Ids of stations from providers other than Radio Browser, e.g. `somafm:groovesalad`. */
    private val PROVIDER_ID_PATTERN = Regex("^[a-z]+:[A-Za-z0-9._-]{1,100}$")

    /** Ids of stations from a user's own source, e.g. `user-1a2b3c4d:0123456789ab`. */
    private val USER_ID_PATTERN = Regex("^user-[0-9a-f]{8}:[0-9a-f]{12}$")

    /** What an incoming link points at. */
    sealed interface Target {
        /** [name] and [streamUrl] come with stations from a user's source, null otherwise. */
        data class StationLink(val id: String, val name: String? = null, val streamUrl: String? = null) : Target {
            /** The station as the link describes it, for phones that can't look it up. */
            fun toStation(): Station? = streamUrl?.let { Station(uuid = id, name = name.orEmpty(), url = it) }
        }

        data class SourceLink(val url: String, val name: String?) : Target
    }

    fun appLink(baseUrl: String, station: Station): String {
        val base = "${baseUrl.trimEnd('/')}/s/?id=${station.uuid}"
        return if (USER_ID_PATTERN.matches(station.uuid)) {
            "$base&n=${encode(station.displayName)}&u=${encode(station.streamUrl)}"
        } else {
            base
        }
    }

    fun appShareText(baseUrl: String, station: Station): String =
        "Listen to ${station.displayName} on Scroll Cast Radio: ${appLink(baseUrl, station)}"

    fun directShareText(station: Station): String = "${station.displayName}: ${station.streamUrl}"

    fun sourceLink(baseUrl: String, url: String, name: String): String =
        "${baseUrl.trimEnd('/')}/s/?source=${encode(url)}&n=${encode(name)}"

    fun sourceShareText(baseUrl: String, url: String, name: String): String =
        "Add the station list $name to Scroll Cast Radio: ${sourceLink(baseUrl, url, name)}"

    /** Extracts a station id from an incoming app link, or null if the link isn't a station of ours. */
    fun stationIdFrom(link: String?): String? = (parse(link) as? Target.StationLink)?.id

    /** Reads an incoming app link, or null if it isn't one of ours. */
    fun parse(link: String?): Target? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val query = params(uri.rawQuery)
        return when (uri.scheme?.lowercase()) {
            "scrollcast" -> when (uri.host) {
                "station" -> station(uri.path?.trim('/'), query)
                "source" -> source(query["url"], query["n"])
                else -> null
            }
            "https", "http" -> query["source"]?.let { source(it, query["n"]) } ?: station(query["id"], query)
            else -> null
        }
    }

    private fun station(id: String?, query: Map<String, String>): Target.StationLink? = when {
        id == null -> null
        UUID_PATTERN.matches(id) -> Target.StationLink(id.lowercase())
        USER_ID_PATTERN.matches(id) -> {
            // Without its stream, a station from someone else's source can't be played.
            val stream = query["u"]?.takeIf(::isWebLink) ?: return null
            Target.StationLink(id, query["n"]?.take(200), stream)
        }
        PROVIDER_ID_PATTERN.matches(id) -> Target.StationLink(id)
        else -> null
    }

    private fun source(url: String?, name: String?): Target.SourceLink? =
        url?.takeIf(::isWebLink)?.let { Target.SourceLink(it, name?.take(200)) }

    private fun isWebLink(url: String) = url.startsWith("https://") || url.startsWith("http://")

    private fun params(rawQuery: String?): Map<String, String> = rawQuery.orEmpty()
        .split('&')
        .map { it.split('=', limit = 2) }
        .filter { it.size == 2 }
        .mapNotNull { (k, v) -> runCatching { k to URLDecoder.decode(v, "UTF-8") }.getOrNull() }
        .toMap()

    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}
