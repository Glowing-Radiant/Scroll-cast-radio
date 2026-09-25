package com.scrollcast.radio.data

import java.net.URI

/**
 * Share links in two forms:
 *  - app link: `<base>/s/?id=<uuid>`, a web page that opens the station in the app (or plays it
 *    in the browser when the app isn't installed), plus `scrollcast://station/<uuid>`;
 *  - direct link: the station's own stream URL, playable in any player.
 */
object ShareLinks {
    private val UUID_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun appLink(baseUrl: String, station: Station): String = "${baseUrl.trimEnd('/')}/s/?id=${station.uuid}"

    fun appShareText(baseUrl: String, station: Station): String =
        "Listen to ${station.displayName} on Scroll Cast Radio: ${appLink(baseUrl, station)}"

    fun directShareText(station: Station): String = "${station.displayName}: ${station.streamUrl}"

    /** Extracts a station uuid from an incoming app link, or null if the link isn't one of ours. */
    fun stationIdFrom(link: String?): String? {
        if (link.isNullOrBlank()) return null
        val uri = runCatching { URI(link) }.getOrNull() ?: return null
        val candidate = when (uri.scheme?.lowercase()) {
            "scrollcast" -> if (uri.host == "station") uri.path?.trim('/') else null
            "https", "http" -> uri.rawQuery
                ?.split('&')
                ?.map { it.split('=', limit = 2) }
                ?.firstOrNull { it.size == 2 && it[0] == "id" }
                ?.get(1)
            else -> null
        }
        return candidate?.takeIf { UUID_PATTERN.matches(it) }?.lowercase()
    }
}
