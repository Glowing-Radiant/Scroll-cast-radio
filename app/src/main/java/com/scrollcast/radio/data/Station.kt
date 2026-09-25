package com.scrollcast.radio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A station as returned by the Radio Browser API (only the fields the app uses). */
@Serializable
data class Station(
    @SerialName("stationuuid") val uuid: String,
    val name: String = "",
    val url: String = "",
    @SerialName("url_resolved") val urlResolved: String = "",
    val homepage: String = "",
    val favicon: String = "",
    val tags: String = "",
    val country: String = "",
    @SerialName("countrycode") val countryCode: String = "",
    val language: String = "",
    val codec: String = "",
    val bitrate: Int = 0,
    val hls: Int = 0,
    @SerialName("lastcheckok") val lastCheckOk: Int = 1,
    val votes: Int = 0,
    @SerialName("clickcount") val clickCount: Int = 0,
) {
    val streamUrl: String get() = urlResolved.ifBlank { url }

    val displayName: String get() = name.trim().ifBlank { "Unnamed station" }

    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** "Germany · German" style line; empty when unknown. */
    val placeLine: String
        get() = listOf(country.trim(), language.split(',').first().trim().replaceFirstChar { it.uppercase() })
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

    val genreLine: String get() = tagList.take(4).joinToString(" · ")

    /** What a screen reader says when this station comes on. */
    val spokenSummary: String
        get() = buildString {
            append(displayName)
            if (country.isNotBlank()) append(". ").append(country.trim())
            val genres = tagList.take(3)
            if (genres.isNotEmpty()) append(". ").append(genres.joinToString(", "))
            append('.')
        }
}
