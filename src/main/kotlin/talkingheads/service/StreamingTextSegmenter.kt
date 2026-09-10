package talkingheads.service

/**
 * Converts token-sized LLM deltas into speech-sized text segments.
 * It prefers sentence punctuation, but forces a split before a segment becomes too long.
 */
class StreamingTextSegmenter(
    private val minChars: Int = 24,
    private val maxChars: Int = 140,
) {
    private val buffer = StringBuilder()

    fun append(delta: String): List<String> {
        if (delta.isEmpty()) return emptyList()
        buffer.append(delta)
        val out = mutableListOf<String>()

        while (true) {
            val boundary = findBoundary() ?: break
            val segment = buffer.substring(0, boundary).trim()
            buffer.delete(0, boundary)
            if (segment.isNotEmpty()) out += segment
        }
        return out
    }

    fun flush(): String? {
        val tail = buffer.toString().trim()
        buffer.clear()
        return tail.takeIf { it.isNotEmpty() }
    }

    private fun findBoundary(): Int? {
        if (buffer.length < minChars) return null

        for (i in minChars - 1 until buffer.length) {
            val c = buffer[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                return i + 1
            }
        }

        if (buffer.length < maxChars) return null
        val searchStart = minChars.coerceAtMost(maxChars - 1)
        for (i in maxChars.coerceAtMost(buffer.length - 1) downTo searchStart) {
            if (buffer[i].isWhitespace()) return i + 1
        }
        return maxChars.coerceAtMost(buffer.length)
    }
}
