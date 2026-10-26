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
    fun otherProvidersIdsRoundTrip() {
        val soma = Station(uuid = "somafm:groovesalad", name = "Groove Salad")
        val link = ShareLinks.appLink("https://me.github.io/scroll-cast-radio", soma)
        assertEquals("https://me.github.io/scroll-cast-radio/s/?id=somafm:groovesalad", link)
        assertEquals("somafm:groovesalad", ShareLinks.stationIdFrom(link))
        assertEquals("lautfm:de-alljazz", ShareLinks.stationIdFrom("https://x.y/s/?id=lautfm%3Ade-alljazz"))
        assertEquals("radioparadise:main-mix", ShareLinks.stationIdFrom("scrollcast://station/radioparadise:main-mix"))
    }

    @Test
    fun userSourceStationsCarryTheirStream() {
        val station = Station(uuid = "user-1a2b3c4d:0123456789ab", name = "Aaj Tak & Co", url = "https://cdn/x/index.m3u8?a=1&b=2")
        val link = ShareLinks.appLink("https://me.github.io/app", station)
        assertEquals(
            "https://me.github.io/app/s/?id=user-1a2b3c4d:0123456789ab&n=Aaj%20Tak%20%26%20Co&u=https%3A%2F%2Fcdn%2Fx%2Findex.m3u8%3Fa%3D1%26b%3D2",
            link,
        )
        val parsed = ShareLinks.parse(link) as ShareLinks.Target.StationLink
        assertEquals(station.copy(url = station.url), parsed.toStation())
        // The web page hands the same details to the app through the custom scheme.
        assertEquals(parsed, ShareLinks.parse("scrollcast://station/user-1a2b3c4d:0123456789ab?n=Aaj%20Tak%20%26%20Co&u=https%3A%2F%2Fcdn%2Fx%2Findex.m3u8%3Fa%3D1%26b%3D2"))
        // Without a playable stream there is nothing to open.
        assertNull(ShareLinks.parse("https://x.y/s/?id=user-1a2b3c4d:0123456789ab&n=X&u=javascript:alert(1)"))
    }

    @Test
    fun sourceLinksRoundTrip() {
        val list = "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u"
        val link = ShareLinks.sourceLink("https://me.github.io/app/", list, "iptv · India")
        assertEquals(ShareLinks.Target.SourceLink(list, "iptv · India"), ShareLinks.parse(link))
        assertEquals(
            ShareLinks.Target.SourceLink(list, "iptv"),
            ShareLinks.parse("scrollcast://source?url=${java.net.URLEncoder.encode(list, "UTF-8")}&n=iptv"),
        )
        assertNull(ShareLinks.parse("https://me.github.io/app/s/?source=file:///etc/passwd"))
    }

    @Test
    fun rejectsForeignOrMalformedLinks() {
        assertNull(ShareLinks.stationIdFrom("https://me.github.io/s/?id=somafm:<script>"))
        assertNull(ShareLinks.stationIdFrom("https://me.github.io/s/?id=:groovesalad"))
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
