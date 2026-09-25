package com.scrollcast.radio.data

import org.junit.Assert.assertEquals
import org.junit.Test

class QueryPlanTest {
    private fun prefs(
        country: String? = "IN", regionMix: Mix = Mix.Bias,
        language: String? = null, languageMix: Mix = Mix.Mixed, genre: String? = null,
    ) = ResolvedPrefs(country, regionMix, language, languageMix, genre)

    @Test
    fun defaultIsMostlyRegionWithSomeWorldwide() {
        val plan = FeedRepository.planQueries(prefs(), 100)
        assertEquals(
            listOf(StationQuery(countryCode = "IN") to 70, StationQuery() to 30),
            plan,
        )
    }

    @Test
    fun onlyRegionNeverQueriesWorldwide() {
        val plan = FeedRepository.planQueries(prefs(regionMix = Mix.Only), 100)
        assertEquals(listOf(StationQuery(countryCode = "IN") to 100), plan)
    }

    @Test
    fun mixedIgnoresRegion() {
        val plan = FeedRepository.planQueries(prefs(regionMix = Mix.Mixed), 45)
        assertEquals(listOf(StationQuery() to 45), plan)
    }

    @Test
    fun undetectedRegionFallsBackToWorldwide() {
        val plan = FeedRepository.planQueries(prefs(country = null, regionMix = Mix.Only), 45)
        assertEquals(listOf(StationQuery() to 45), plan)
    }

    @Test
    fun regionAndLanguageBiasCombine() {
        val plan = FeedRepository.planQueries(prefs(language = "hindi", languageMix = Mix.Bias), 100)
        assertEquals(
            listOf(
                StationQuery("IN", "hindi") to 49,
                StationQuery("IN", null) to 21,
                StationQuery(null, "hindi") to 21,
                StationQuery(null, null) to 9,
            ),
            plan,
        )
    }

    @Test
    fun genreAppliesToEveryQuery() {
        val plan = FeedRepository.planQueries(prefs(genre = "jazz"), 45)
        assertEquals(listOf("jazz", "jazz"), plan.map { it.first.tag })
    }
}
