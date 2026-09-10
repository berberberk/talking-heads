package talkingheads.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingTextSegmenterTest {
    @Test
    fun `emits complete speech segment before whole answer is finished`() {
        val segmenter = StreamingTextSegmenter(minChars = 10, maxChars = 80)
        val first = segmenter.append("Здравствуйте. Это начало")

        assertEquals(listOf("Здравствуйте."), first)
        assertEquals("Это начало", segmenter.flush())
    }

    @Test
    fun `forces split for long text without punctuation`() {
        val segmenter = StreamingTextSegmenter(minChars = 5, maxChars = 20)
        val chunks = segmenter.append("один два три четыре пять шесть семь")
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.first().length <= 20)
    }
}
