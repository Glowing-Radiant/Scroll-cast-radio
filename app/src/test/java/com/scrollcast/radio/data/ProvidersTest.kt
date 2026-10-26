package com.scrollcast.radio.data

import com.scrollcast.radio.data.providers.Providers
import com.scrollcast.radio.data.providers.RadioParadiseProvider
import com.scrollcast.radio.data.providers.matches
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    private val station = Station(
        uuid = "somafm:groovesalad",
        name = "Groove Salad",
        tags = "ambient,Electronic",
        countryCode = "US",
        language = "english",
    )

    @Test
    fun stationIdsCarryTheirProvider() {
        assertEquals("somafm", station.providerId)
        assertEquals("groovesalad", Station.localIdOf(station.uuid))
        assertEquals(Station.RADIO_BROWSER, Station(uuid = "dd9f9e73-de08-11e9-a8ba-52543be04c81").providerId)
    }

    @Test
    fun queryMatchesLikeRadioBrowser() {
        assertTrue(StationQuery().matches(station))
        assertTrue(StationQuery(countryCode = "us", language = "English").matches(station))
        assertTrue(StationQuery(tag = "electronic").matches(station))
        assertFalse(StationQuery(tag = "electro").matches(station))
        assertTrue(StationQuery(tag = "electro", tagExact = false).matches(station))
        assertTrue(StationQuery(name = "salad").matches(station))
        assertFalse(StationQuery(countryCode = "IN").matches(station))
        assertFalse(StationQuery(language = "hindi").matches(station))
    }

    @Test
    fun catalogMergeAddsUpCounts() {
        val merged = Catalog.merge(
            listOf(
                listOf(CatalogEntry("jazz", "jazz", 900), CatalogEntry("US", "The United States Of America", 5000)),
                listOf(CatalogEntry("jazz", "Jazz", 300), CatalogEntry("celtic", "Celtic", 33)),
                listOf(CatalogEntry("US", "United States", 50)),
            ),
        )
        assertEquals(
            listOf(
                CatalogEntry("celtic", "Celtic", 33),
                CatalogEntry("jazz", "jazz", 1200),
                CatalogEntry("US", "The United States Of America", 5050),
            ),
            merged,
        )
    }

    @Test
    fun enabledNeverEmptyAndNamesRead() {
        val providers = Providers(listOf(RadioParadiseProvider()))
        assertEquals(listOf("radioparadise"), providers.enabled(emptySet()).map { it.id })
        assertEquals("Radio Paradise", providers.names(providers.all))
    }

    @Test
    fun listedProviderFiltersOnThePhone() = runTest {
        val paradise = RadioParadiseProvider()
        assertEquals(7, paradise.sample(StationQuery(), 50).size)
        assertEquals(listOf("radioparadise:serenity"), paradise.sample(StationQuery(tag = "ambient"), 50).map { it.uuid })
        assertTrue(paradise.sample(StationQuery(countryCode = "IN"), 50).isEmpty())
        assertEquals("Radio Paradise Rock Mix", paradise.station("radioparadise:rock")?.name)
    }
}
