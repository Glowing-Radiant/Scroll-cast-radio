package com.scrollcast.radio.data

import com.scrollcast.radio.update.Versions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoodTest {
    private val languages = listOf("hindi", "english", "punjabi").map { CatalogEntry(it, it, 100) }
    private val countries = listOf("IN" to "India", "BR" to "Brazil", "US" to "The United States Of America")
        .map { (code, name) -> CatalogEntry(code, name, 100) }

    private fun parse(text: String) = Mood.parse(text, languages, countries)

    @Test
    fun languageWord() = assertEquals(Mood(language = "hindi"), parse("Hindi"))

    @Test
    fun countryWord() = assertEquals(Mood(countryCode = "BR"), parse(" brazil "))

    @Test
    fun multiWordCountry() = assertEquals(Mood(countryCode = "US"), parse("the united states of america"))

    @Test
    fun genrePhraseStaysTogether() = assertEquals(Mood(tag = "hip hop"), parse("hip   hop"))

    @Test
    fun mixesLanguageAndGenre() = assertEquals(Mood(language = "hindi", tag = "romantic"), parse("romantic hindi"))

    @Test
    fun mixesAllThree() =
        assertEquals(Mood(language = "punjabi", countryCode = "IN", tag = "bhangra"), parse("punjabi bhangra india"))

    @Test
    fun blankIsEmpty() = assertEquals(Mood(), parse("   "))

    @Test
    fun comparesVersions() {
        assertTrue(Versions.isNewer("0.3.0", "0.2.9"))
        assertTrue(Versions.isNewer("v1.0", "0.9.9"))
        assertTrue(Versions.isNewer("0.2.1", "0.2.0-dev"))
        assertFalse(Versions.isNewer("0.2.0", "0.2.0"))
        assertFalse(Versions.isNewer("0.1.9", "0.2.0"))
    }
}
