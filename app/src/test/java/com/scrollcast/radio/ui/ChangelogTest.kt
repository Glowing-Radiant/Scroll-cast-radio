package com.scrollcast.radio.ui

import com.scrollcast.radio.ui.Changelog.Line
import org.junit.Assert.assertEquals
import org.junit.Test

class ChangelogTest {
    @Test
    fun readsHeadingsBulletsAndText() {
        val md = """
            ## New
            - Favorites is a **feed** now.
              * Nested [link](https://x.y) text
            Plain `note`.

        """.trimIndent()
        assertEquals(
            listOf(
                Line.Heading("New"),
                Line.Bullet("Favorites is a feed now."),
                Line.Bullet("Nested link text"),
                Line.Paragraph("Plain note."),
            ),
            Changelog.lines(md),
        )
    }
}
