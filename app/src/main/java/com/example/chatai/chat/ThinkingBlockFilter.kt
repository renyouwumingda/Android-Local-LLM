package com.example.chatai.chat

/**
 * Strips the reasoning block that Qwen3 emits before the visible answer.
 *
 * The stream arrives token by token, so a partial tag can straddle chunk
 * boundaries. The filter therefore withholds anything that could still turn
 * out to be part of a tag, and drops everything between the opening and
 * closing tag once the opening one is confirmed.
 */
class ThinkingBlockFilter(private val enabled: Boolean) {

    companion object {
        // Qwen3 templates differ: some emit "<think>", others " thinking".
        private val OPEN_TAGS = listOf("<think>", " thinking")
        private val CLOSE_TAGS = listOf("</think>", "<｜end▁of▁thinking｜>")
    }

    private val pending = StringBuilder()
    private var insideThinking = false
    private var openTagResolved = false
    private var activeCloseTag = ""
    private var emittedAnything = false

    /** Returns the text that is safe to show for this chunk. */
    fun feed(chunk: String): String {
        if (!enabled) return chunk
        return trimLeadingBlankLines(collect(chunk))
    }

    private fun collect(chunk: String): String {
        pending.append(chunk)
        val output = StringBuilder()

        while (pending.isNotEmpty()) {
            if (insideThinking) {
                val close = CLOSE_TAGS
                    .map { pending.indexOf(it) to it }
                    .filter { it.first >= 0 }
                    .minByOrNull { it.first }

                if (close == null) {
                    // Keep only a tail that could still be the start of a close tag.
                    val keep = CLOSE_TAGS.maxOf { longestSuffixThatPrefixes(it) }
                    pending.delete(0, pending.length - keep)
                    return output.toString()
                }

                pending.delete(0, close.first + close.second.length)
                insideThinking = false
                // The model usually emits a newline right after the block.
                while (pending.isNotEmpty() && (pending[0] == '\n' || pending[0] == '\r')) {
                    pending.deleteCharAt(0)
                }
                continue
            }

            if (!openTagResolved) {
                val open = OPEN_TAGS
                    .map { pending.indexOf(it) to it }
                    .filter { it.first >= 0 }
                    .minByOrNull { it.first }

                if (open == null) {
                    val keep = OPEN_TAGS.maxOf { longestSuffixThatPrefixes(it) }
                    val flushable = pending.length - keep
                    if (flushable > 0) {
                        output.append(pending, 0, flushable)
                        pending.delete(0, flushable)
                    }
                    return output.toString()
                }

                output.append(pending, 0, open.first)
                pending.delete(0, open.first + open.second.length)
                insideThinking = true
                openTagResolved = true
                activeCloseTag = CLOSE_TAGS.first()
                continue
            }

            output.append(pending)
            pending.clear()
        }

        return output.toString()
    }

    /** Flushes whatever is still held back once the stream ends. */
    fun finish(): String {
        if (!enabled) return ""
        val rest = if (insideThinking) "" else pending.toString()
        pending.clear()
        return trimLeadingBlankLines(rest)
    }

    /** The answer starts right after the block, so drop the blank lines it left behind. */
    private fun trimLeadingBlankLines(text: String): String {
        if (emittedAnything) return text
        val trimmed = text.trimStart('\n', '\r', ' ', '\t')
        if (trimmed.isNotEmpty()) emittedAnything = true
        return trimmed
    }

    private fun longestSuffixThatPrefixes(tag: String): Int {
        val max = minOf(tag.length - 1, pending.length)
        for (length in max downTo 1) {
            if (pending.regionMatches(pending.length - length, tag, 0, length)) {
                return length
            }
        }
        return 0
    }
}
