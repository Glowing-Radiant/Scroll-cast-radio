package com.scrollcast.radio.data.providers

import com.scrollcast.radio.data.Station

/**
 * Radio Paradise (https://radioparadise.com): a handful of listener-supported mixes from
 * California. Their API lists channels but not stream addresses, so the channels are listed here.
 */
class RadioParadiseProvider : ListedProvider() {
    override val id = ID
    override val name = "Radio Paradise"
    override val description = "Seven ad-free mixes blending rock, world, electronic and more."

    private data class Channel(val slug: String, val title: String, val stream: String, val tags: String)

    override suspend fun load(): List<Station> = CHANNELS.map { c ->
        Station(
            uuid = Station.id(ID, c.slug),
            name = c.title,
            url = "https://stream.radioparadise.com/${c.stream}",
            homepage = "https://radioparadise.com/",
            tags = c.tags,
            country = "United States",
            countryCode = "US",
            language = "english",
            codec = "AAC",
            bitrate = 128,
        )
    }

    private companion object {
        const val ID = "radioparadise"
        val CHANNELS = listOf(
            Channel("main-mix", "Radio Paradise Main Mix", "aac-128", "eclectic,rock,world,electronic"),
            Channel("mellow", "Radio Paradise Mellow Mix", "mellow-128", "mellow,eclectic,acoustic"),
            Channel("rock", "Radio Paradise Rock Mix", "rock-128", "rock,classic rock,alternative"),
            Channel("global", "Radio Paradise Global Mix", "global-128", "world,global"),
            Channel("beyond", "Radio Paradise Beyond", "beyond-128", "eclectic,electronic,experimental"),
            Channel("serenity", "Radio Paradise Serenity", "serenity", "ambient,chillout"),
            Channel("kfat", "KFAT on Radio Paradise", "kfat-128", "americana,country,folk"),
        )
    }
}
