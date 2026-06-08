package com.trustableai.koru.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TrackModeCueTest {
    @Test
    fun `truncateCueWords caps at seven words with ellipsis`() {
        val text = "one two three four five six seven eight nine"
        assertEquals("one two three four five six seven…", truncateCueWords(text))
    }

    @Test
    fun `truncateCueWords leaves short cues unchanged`() {
        assertEquals("Brake now", truncateCueWords("Brake now"))
    }
}
