package com.scrollcast.radio.data

import com.scrollcast.radio.data.providers.GitHubEntry
import com.scrollcast.radio.data.providers.GitHubFolder
import com.scrollcast.radio.data.providers.GitHubLinks
import com.scrollcast.radio.data.providers.PlaylistEntry
import com.scrollcast.radio.data.providers.Playlists
import com.scrollcast.radio.data.providers.UserSource
import com.scrollcast.radio.data.providers.UserSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistsTest {
    private val link = "https://example.org/list.m3u"

    @Test
    fun readsIptvStyleM3u() {
        val m3u = """
            #EXTM3U x-tvg-url="https://epg"
            #EXTINF:-1 tvg-id="AajTak.in@SD" tvg-logo="https://logo/aaj.png" group-title="News;Hindi, Too",Aaj Tak (720p) [Geo-blocked]
            #EXTVLCOPT:http-user-agent=Mozilla
            https://cdn/aajtak/index.m3u8
            #EXTINF:-1 tvg-country="US;CA" tvg-language="English;French" group-title="Undefined",Plain, with comma
            http://plain/stream
        """.trimIndent()
        assertEquals(
            listOf(
                PlaylistEntry("Aaj Tak", "https://cdn/aajtak/index.m3u8", "https://logo/aaj.png", listOf("News", "Hindi", "Too"), "IN"),
                PlaylistEntry("Plain, with comma", "http://plain/stream", countryCode = "US", languages = listOf("english", "french")),
            ),
            Playlists.parse(m3u, link),
        )
    }

    @Test
    fun plainUrlListsAndRadioM3u() {
        val m3u = "#EXTM3U\n#EXTINF:-1,Jazz FM\nhttps://jazz.example/live\n\nhttps://other.example/radio/rock.mp3\nrtmp://skipped\n"
        assertEquals(
            listOf(PlaylistEntry("Jazz FM", "https://jazz.example/live"), PlaylistEntry("rock", "https://other.example/radio/rock.mp3")),
            Playlists.parse(m3u, link),
        )
    }

    @Test
    fun readsPls() {
        val pls = "[playlist]\nNumberOfEntries=2\nFile2=http://b/stream\nTitle2=Second\nFile1=http://a/stream\nTitle1=First\n"
        assertEquals(listOf("First", "Second"), Playlists.parse(pls, link).map { it.name })
    }

    @Test
    fun readsJsonLists() {
        val json = """{"stations":[{"name":"One","url":"https://one/s","tags":"pop,rock","countrycode":"de","language":["German"]},
            |{"title":"No url"},{"title":"Two","stream_url":"https://two/s","genres":["Jazz"]}]}""".trimMargin()
        assertEquals(
            listOf(
                PlaylistEntry("One", "https://one/s", tags = listOf("pop", "rock"), countryCode = "DE", languages = listOf("german")),
                PlaylistEntry("Two", "https://two/s", tags = listOf("Jazz")),
            ),
            Playlists.parse(json, link),
        )
    }

    @Test
    fun hlsStreamLinkIsOneStation() {
        val hls = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n"
        assertEquals(listOf(PlaylistEntry("news", "https://tv.example/news.m3u8")), Playlists.parse(hls, "https://tv.example/news.m3u8"))
    }

    @Test
    fun namesFromUrls() {
        assertEquals("tv.example", Playlists.nameFromUrl("https://tv.example/live/index.m3u8?token=1"))
        assertEquals("groove", Playlists.nameFromUrl("http://host/groove.mp3"))
    }

    @Test
    fun gitHubLinks() {
        assertEquals(
            GitHubLinks.Target.Folder(GitHubFolder("iptv-org", "iptv", null, "")),
            GitHubLinks.parse("https://github.com/iptv-org/iptv"),
        )
        assertEquals(
            GitHubLinks.Target.Folder(GitHubFolder("iptv-org", "iptv", "master", "streams")),
            GitHubLinks.parse("https://github.com/iptv-org/iptv/tree/master/streams"),
        )
        assertEquals(
            GitHubLinks.Target.File("https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u", "iptv · in"),
            GitHubLinks.parse("https://github.com/iptv-org/iptv/blob/master/streams/in.m3u"),
        )
        assertNull(GitHubLinks.parse("https://iptv-org.github.io/iptv/countries/in.m3u"))
        assertEquals("iptv-org/iptv", GitHubFolder("iptv-org", "iptv", null, "streams").parent()?.title)
    }

    @Test
    fun countryCodedFoldersGetCountryNames() {
        fun file(name: String) = GitHubEntry(name, "streams/$name", isFolder = false, downloadUrl = "https://raw/$name")
        val iptv = listOf("in.m3u", "at_pluto.m3u", "de.m3u", "fr.m3u", "us.m3u", "README.md").map(::file)
        assertEquals(
            listOf("India", "Austria (pluto)", "Germany", "France", "United States", "README.md"),
            GitHubLinks.withCountryTitles(iptv).map { it.title },
        )
        // A genre folder with the odd two-letter name keeps its file names.
        val genres = listOf("dj.m3u", "jazz.m3u", "rock.m3u", "pop.m3u", "house.m3u").map(::file)
        assertEquals(genres, GitHubLinks.withCountryTitles(genres))
    }

    @Test
    fun userSourceIdsAndLabels() {
        val id = UserSources.idFor("https://example.org/list.m3u")
        assertTrue(id.matches(Regex("user-[0-9a-f]{8}")))
        assertEquals(id, UserSources.idFor(" https://example.org/list.m3u "))
        val raw = UserSource(id, "iptv · in", "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/in.m3u")
        assertEquals("github.com/iptv-org/iptv · streams/in.m3u", raw.label)
        // A user station never parses as an app link: other phones don't have the source.
        assertNull(ShareLinks.stationIdFrom("https://x.y/s/?id=$id:abc123"))
    }
}
