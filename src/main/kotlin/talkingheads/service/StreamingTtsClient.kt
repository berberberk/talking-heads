package talkingheads.service

import kotlinx.coroutines.flow.Flow

interface StreamingTtsClient {
    fun synthesize(textDeltas: Flow<String>): Flow<TtsAudioFrame>
}

data class TtsAudioFrame(
    val pcm: ByteArray,
    val alignment: TtsAlignment? = null,
    val isFinal: Boolean = false,
)

data class TtsAlignment(
    val chars: List<String>,
    val charStartTimesMs: List<Long>,
    val charDurationsMs: List<Long>,
)

data class SubtitleCue(val text: String, val startMs: Long, val endMs: Long)

object SubtitleCueBuilder {
    private const val MAX_VISIBLE_CHARACTERS = 42

    fun build(alignment: TtsAlignment?): List<SubtitleCue> {
        if (alignment == null || !alignment.isValid()) return emptyList()
        val cues = mutableListOf<SubtitleCue>()
        val text = StringBuilder()
        var startMs = 0L
        var endMs = 0L
        alignment.chars.forEachIndexed { index, character ->
            if (text.isEmpty()) startMs = alignment.charStartTimesMs[index]
            text.append(character)
            endMs = alignment.charStartTimesMs[index] + alignment.charDurationsMs[index]
            if (isCueBoundary(character) && text.count { !it.isWhitespace() } >= MAX_VISIBLE_CHARACTERS) {
                addCue(cues, text, startMs, endMs)
            }
        }
        addCue(cues, text, startMs, endMs)
        return cues
    }

    private fun TtsAlignment.isValid(): Boolean = chars.isNotEmpty() &&
        chars.size == charStartTimesMs.size &&
        chars.size == charDurationsMs.size &&
        charStartTimesMs.zip(charDurationsMs).all { (startMs, durationMs) -> startMs >= 0 && durationMs >= 0 }

    private fun isCueBoundary(character: String): Boolean = character.any { it.isWhitespace() || it in ".,!?:;…" }

    private fun addCue(cues: MutableList<SubtitleCue>, text: StringBuilder, startMs: Long, endMs: Long) {
        val value = text.toString().trim()
        text.clear()
        if (value.isNotEmpty()) cues += SubtitleCue(value, startMs, endMs.coerceAtLeast(startMs))
    }
}

class WordSafeTextBuffer(private val minimumCharacters: Int) {
    private val buffer = StringBuilder()

    init {
        require(minimumCharacters > 0) { "minimumCharacters must be positive" }
    }

    fun append(delta: String): List<String> {
        buffer.append(delta)
        val sentenceBoundary = buffer.indexOfLast { it == '.' || it == '!' || it == '?' || it == '…' }
        val wordBoundary = buffer.indexOfLast { it.isWhitespace() }
        val boundary = when {
            sentenceBoundary >= 0 -> sentenceBoundary + 1
            wordBoundary >= 0 && wordBoundary + 1 >= minimumCharacters -> wordBoundary + 1
            else -> -1
        }
        return if (boundary > 0) listOf(take(boundary)) else emptyList()
    }

    fun finish(): String = take(buffer.length)

    private fun take(length: Int): String = buffer.substring(0, length).also { buffer.delete(0, length) }
}
