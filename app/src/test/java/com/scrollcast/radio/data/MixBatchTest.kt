package com.scrollcast.radio.data

import com.scrollcast.radio.data.FeedRepository.QueryPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MixBatchTest {
    private fun stations(provider: String, count: Int, country: String = "") = (1..count).map {
        val id = if (provider == Station.RADIO_BROWSER) "rb-$country-$it" else Station.id(provider, "$country$it")
        Station(uuid = id, name = id, urlResolved = "http://s/$id", countryCode = country)
    }

    private fun List<Station>.countBy(provider: String) = count { it.providerId == provider }

    @Test
    fun splitsEvenlyBetweenProviders() {
        val part = QueryPart(45, listOf(stations(Station.RADIO_BROWSER, 45), stations("somafm", 45), stations("lautfm", 45)))
        val batch = FeedRepository.mixBatch(listOf(part), emptySet(), 15, Random(1))
        assertEquals(15, batch.size)
        assertEquals(listOf(5, 5, 5), listOf(Station.RADIO_BROWSER, "somafm", "lautfm").map { batch.countBy(it) })
    }

    @Test
    fun providerThatRunsShortLeavesItsPlacesToTheOthers() {
        val part = QueryPart(45, listOf(stations(Station.RADIO_BROWSER, 45), stations("radioparadise", 2)))
        val batch = FeedRepository.mixBatch(listOf(part), emptySet(), 15, Random(2))
        assertEquals(15, batch.size)
        assertEquals(2, batch.countBy("radioparadise"))
    }

    @Test
    fun keepsTheRegionShareAcrossProviders() {
        // "Mostly India": only Radio Browser has Indian stations; SomaFM joins the worldwide part.
        val india = QueryPart(70, listOf(stations(Station.RADIO_BROWSER, 40, "IN"), emptyList()))
        val world = QueryPart(30, listOf(stations(Station.RADIO_BROWSER, 40, "DE"), stations("somafm", 40, "US")))
        val batch = FeedRepository.mixBatch(listOf(india, world), emptySet(), 20, Random(3))
        assertEquals(14, batch.count { it.countryCode == "IN" })
        assertEquals(3, batch.countBy("somafm"))
    }

    @Test
    fun sameStreamFromTwoProvidersIsTakenOnce() {
        val shared = "http://ice1.somafm.com/groovesalad-128-mp3"
        val part = QueryPart(
            10,
            listOf(
                listOf(Station(uuid = "rb-gs", urlResolved = "$shared/")),
                listOf(Station(uuid = "somafm:groovesalad", url = shared)),
            ),
        )
        val batch = FeedRepository.mixBatch(listOf(part), emptySet(), 5, Random(4))
        assertEquals(1, batch.size)
    }

    @Test
    fun skipsServedStationsAndTopsUpFromWhatIsLeft() {
        val rb = stations(Station.RADIO_BROWSER, 10)
        val served = rb.take(8).map { it.uuid }.toSet()
        val parts = listOf(QueryPart(50, listOf(rb)), QueryPart(50, listOf(stations("lautfm", 20))))
        val batch = FeedRepository.mixBatch(parts, served, 10, Random(5))
        assertEquals(10, batch.size)
        assertTrue(batch.none { it.uuid in served })
        assertEquals(2, batch.countBy(Station.RADIO_BROWSER))
    }
}
