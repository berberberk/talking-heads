package talkingheads.service

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingTtsSupportTest {
    @Test
    fun `word safe buffer does not split a word`() {
        val buffer = WordSafeTextBuffer(10)

        assertEquals(emptyList(), buffer.append("Добрый ден"))
        assertEquals(listOf("Добрый день "), buffer.append("ь коллеги"))
        assertEquals("коллеги", buffer.finish())
    }

    @Test
    fun `word safe buffer flushes completed sentence early`() {
        val buffer = WordSafeTextBuffer(50)

        assertEquals(listOf("Да."), buffer.append("Да."))
        assertEquals("", buffer.finish())
    }

    @Test
    fun `subtitle builder ignores inconsistent alignment`() {
        val cues = SubtitleCueBuilder.build(TtsAlignment(listOf("А"), emptyList(), emptyList()))

        assertEquals(emptyList(), cues)
    }

    @Test
    fun `subtitle builder keeps short phrase intact`() {
        val text = "Добрый день"
        val cues = SubtitleCueBuilder.build(
            TtsAlignment(
                chars = text.map(Char::toString),
                charStartTimesMs = text.indices.map { it.toLong() * 50 },
                charDurationsMs = List(text.length) { 50 },
            ),
        )

        assertEquals(listOf(SubtitleCue("Добрый день", 0, 550)), cues)
    }
}
