package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.Station
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.time.LocalDate
import java.time.Month

/** SomaFM (https://somafm.com): listener-supported, ad-free channels picked by their own DJs. */
class SomaFmProvider(private val http: OkHttpClient, private val json: Json) : ListedProvider() {
    override val id = ID
    override val name = "SomaFM"
    override val description = "About 50 ad-free channels from San Francisco, hand-picked by DJs."

    @Serializable
    private data class Channels(val channels: List<Channel> = emptyList())

    @Serializable
    private data class Channel(
        val id: String = "",
        val title: String = "",
        val genre: String = "",
        val image: String = "",
        @SerialName("xlimage") val largeImage: String = "",
        val listeners: String = "",
    )

    override suspend fun load(): List<Station> =
        json.decodeFromString(Channels.serializer(), http.fetchText(CHANNELS_URL.toHttpUrl(), name))
            .channels
            .filter { it.id.isNotBlank() }
            .map { c ->
                Station(
                    uuid = Station.id(ID, c.id),
                    name = c.title,
                    // Every channel has a 128k MP3 stream named after it. The API only lists .pls
                    // playlists, which point at the same streams but which the player can't open.
                    url = "https://ice1.somafm.com/${c.id}-128-mp3",
                    homepage = "https://somafm.com/${c.id}/",
                    favicon = c.largeImage.ifBlank { c.image },
                    tags = c.genre.split('|').joinToString(","),
                    country = "United States",
                    countryCode = "US",
                    language = "english",
                    codec = "MP3",
                    bitrate = 128,
                    clickCount = c.listeners.toIntOrNull() ?: 0,
                )
            }

    /** The Christmas channels run all year; keep them for November and December. */
    override fun inRotation(station: Station): Boolean =
        "holiday" !in station.tagList || LocalDate.now().month in setOf(Month.NOVEMBER, Month.DECEMBER)

    private companion object {
        const val ID = "somafm"
        const val CHANNELS_URL = "https://api.somafm.com/channels.json"
    }
}
