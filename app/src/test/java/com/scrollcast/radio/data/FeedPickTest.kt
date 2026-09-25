package com.scrollcast.radio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FeedPickTest {
    private fun station(n: Int, votes: Int = 0, url: String = "http://s/$n", ok: Int = 1) =
        Station(uuid = "id-$n", name = "S$n", urlResolved = url, votes = votes, lastCheckOk = ok)

    @Test
    fun dropsBrokenDuplicateAndServedStations() {
        val candidates = listOf(
            station(1),
            station(1),                       // same uuid
            station(2, url = "http://s/1/"),  // same stream
            station(3, ok = 0),               // failed last check
            station(4, url = ""),             // no stream
            station(5),                       // already served
            station(6),
        )
        val picked = FeedRepository.pickBatch(candidates, setOf("id-5"), 10, Random(1))
        assertEquals(setOf("id-1", "id-6"), picked.map { it.uuid }.toSet())
    }

    @Test
    fun respectsBatchSize() {
        val picked = FeedRepository.pickBatch((1..50).map { station(it) }, emptySet(), 15, Random(2))
        assertEquals(15, picked.size)
    }

    @Test
    fun popularStationsSurfaceMoreOftenButNotAlways() {
        val popular = station(0, votes = 5000)
        val others = (1..20).map { station(it) }
        val firsts = (0 until 2000).count { seed ->
            FeedRepository.pickBatch(others + popular, emptySet(), 1, Random(seed)).first() == popular
        }
        // Uniform would be ~95 of 2000; weighting should lift it well above that without always winning.
        assertTrue("popular picked $firsts times", firsts in 200..1500)
    }
}
