package com.scrollcast.radio.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A station from any provider (only the fields the app uses). The field names follow the Radio
 * Browser API, which also makes them the favorites file format.
 */
@Serializable
data class Station(
    /** Radio Browser's station uuid, or `<provider>:<id>` for stations from other providers. */
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

    /** Id of the provider this station came from. */
    val providerId: String get() = providerOf(uuid)

    val displayName: String get() = name.trim().ifBlank { "Unnamed station" }

    val tagList: List<String>
        get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** The station's languages, lower case, e.g. "english,hindi" → [english, hindi]. */
    val languageList: List<String>
        get() = language.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }

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

    companion object {
        /** Radio Browser stations keep their plain uuid as id, so older favorites and links still work. */
        const val RADIO_BROWSER = "radiobrowser"

        fun id(provider: String, localId: String): String = "$provider:$localId"

        fun providerOf(stationId: String): String =
            if (':' in stationId) stationId.substringBefore(':') else RADIO_BROWSER

        /** The provider's own id for a station, e.g. "somafm:groovesalad" → "groovesalad". */
        fun localIdOf(stationId: String): String = stationId.substringAfter(':')
    }
}
