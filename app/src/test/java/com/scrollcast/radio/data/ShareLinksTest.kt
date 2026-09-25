package com.scrollcast.radio.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLinksTest {
    private val id = "dd9f9e73-de08-11e9-a8ba-52543be04c81"
    private val station = Station(uuid = id, name = "RPR1 Oldies", urlResolved = "http://streams.rpr1.de/oldies")

    @Test
    fun appLinkRoundTrips() {
        val link = ShareLinks.appLink("https://me.github.io/scroll-cast-radio/", station)
        assertEquals("https://me.github.io/scroll-cast-radio/s/?id=$id", link)
        assertEquals(id, ShareLinks.stationIdFrom(link))
    }

    @Test
    fun customSchemeIsParsed() {
        assertEquals(id, ShareLinks.stationIdFrom("scrollcast://station/$id"))
        assertEquals(id, ShareLinks.stationIdFrom("scrollcast://station/${id.uppercase()}/"))
    }

    @Test
    fun rejectsForeignOrMalformedLinks() {
        assertNull(ShareLinks.stationIdFrom(null))
        assertNull(ShareLinks.stationIdFrom("https://me.github.io/s/?id=not-a-uuid"))
        assertNull(ShareLinks.stationIdFrom("scrollcast://other/$id"))
        assertNull(ShareLinks.stationIdFrom("ftp://x/?id=$id"))
    }

    @Test
    fun directShareUsesStreamUrl() {
        assertEquals("RPR1 Oldies: http://streams.rpr1.de/oldies", ShareLinks.directShareText(station))
    }
}
