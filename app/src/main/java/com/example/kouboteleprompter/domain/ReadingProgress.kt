package com.example.kouboteleprompter.domain

import java.text.Normalizer

/**
 * Aligns an incremental ASR hypothesis with the unread part of a script.
 *
 * Recognition services regularly omit punctuation, revise partial results, spell latin letters,
 * or return spoken Chinese numerals for digits. The matcher therefore compares canonical readable
 * characters and uses a local LCS score inside a bounded look-ahead window. Scoring a local window
 * (instead of the whole unread prefix) is important: recognition may entirely omit a plate number
 * or revise a short phrase, and those omissions must not permanently pin the teleprompter.
 */
object ReadingProgress {
    data class Match(
        val cursor: Int,
        val matchedCharacters: Int,
        val spokenCharacters: Int,
        val score: Float,
        val accepted: Boolean
    )

    fun consumedCharacters(script: String, recognition: String, from: Int = 0): Int =
        match(script, recognition, from).cursor

    fun match(script: String, recognition: String, from: Int = 0): Match {
        val start = nextReadableCursor(script, from.coerceIn(0, script.length))
        val target = script.mapIndexedNotNull { index, char ->
            canonicalCharacter(char)?.let { index to it }
        }.filter { it.first >= start }
        val spoken = normalize(recognition)
        if (spoken.isEmpty() || target.isEmpty()) {
            return Match(start, 0, spoken.length, 0f, accepted = false)
        }

        // One or two uncertain characters must never seek forward. A stable phrase may look further
        // ahead so a missed plate number or a whole short clause does not pin the teleprompter.
        val lookAhead = if (spoken.length <= 2) spoken.length else spoken.length * 3 + 24
        val candidate = target.take(lookAhead)
        val latestWindowStart = if (spoken.length <= 2) 0 else candidate.lastIndex
        var bestStart = 0
        var bestEnd = 0
        var bestMatchCount = 0
        var bestScore = 0f
        var currentEnd = 0
        var currentMatchCount = 0
        var currentScore = 0f
        for (windowStart in 0..latestWindowStart) {
            // A candidate phrase only needs modest insertion/deletion headroom. Keeping it bounded
            // stops a few common characters spread across a long sentence from looking convincing.
            val maxWindowLength = spoken.length + maxOf(4, spoken.length / 2)
            val windowSize = minOf(maxWindowLength, candidate.size - windowStart)
            if (windowSize <= 0) continue
            val lcs = Array(spoken.length + 1) { IntArray(windowSize + 1) }
            for (spokenIndex in 1..spoken.length) {
                for (scriptIndex in 1..windowSize) {
                    lcs[spokenIndex][scriptIndex] = if (
                        spoken[spokenIndex - 1] == candidate[windowStart + scriptIndex - 1].second
                    ) {
                        lcs[spokenIndex - 1][scriptIndex - 1] + 1
                    } else {
                        maxOf(lcs[spokenIndex - 1][scriptIndex], lcs[spokenIndex][scriptIndex - 1])
                    }
                }
            }
            if (windowStart == 0) {
                // Compare the hypothesis with an equally long prefix when deciding whether the
                // speaker is following from the current cursor. Letting this anchored window shrink
                // would stop immediately before a substituted final character instead of catching
                // the highlight up to the complete spoken phrase.
                currentEnd = minOf(spoken.length, windowSize)
                currentMatchCount = lcs[spoken.length][currentEnd]
                currentScore = (2f * currentMatchCount) / (spoken.length + currentEnd)
            }
            for (windowLength in 1..windowSize) {
                val matchCount = lcs[spoken.length][windowLength]
                val score = (2f * matchCount) / (spoken.length + windowLength)
                val absoluteEnd = windowStart + windowLength
                val isBetter = score > bestScore ||
                    (score == bestScore && matchCount > bestMatchCount) ||
                    (score == bestScore && matchCount == bestMatchCount && windowStart < bestStart) ||
                    (score == bestScore && matchCount == bestMatchCount &&
                        windowStart == bestStart && absoluteEnd > bestEnd)
                if (isBetter) {
                    bestScore = score
                    bestStart = windowStart
                    bestEnd = absoluteEnd
                    bestMatchCount = matchCount
                }
            }
        }

        // A hypothesis aligned at the current cursor is much safer than one that seeks forward.
        // Accept the former more eagerly so a couple of homophone substitutions do not leave the
        // highlight behind the speaker. Forward recovery keeps the stricter threshold because a
        // false positive there would permanently skip unread text.
        val currentCursorEvidence = when (spoken.length) {
            1, 2 -> currentMatchCount == spoken.length && currentScore >= SHORT_EXACT_SCORE
            3, 4 -> currentMatchCount >= 2 && currentScore >= CURRENT_CURSOR_SCORE
            else -> {
                val comparedLength = minOf(spoken.length, currentEnd)
                currentMatchCount >= maxOf(
                    3,
                    (comparedLength * CURRENT_CURSOR_MATCH_RATIO).toInt()
                ) && currentScore >= CURRENT_CURSOR_SCORE
            }
        }
        val forwardEvidence = when (spoken.length) {
            1, 2 -> false
            3, 4 -> bestMatchCount >= 3 && bestScore >= FORWARD_SHORT_SCORE
            else -> {
                val comparedLength = minOf(spoken.length, bestEnd - bestStart)
                bestMatchCount >= maxOf(3, (comparedLength * FORWARD_MATCH_RATIO).toInt()) &&
                    bestScore >= FORWARD_SCORE
            }
        }
        val useCurrentCursor = currentCursorEvidence
        val enoughEvidence = useCurrentCursor || forwardEvidence
        val acceptedEnd = if (useCurrentCursor) currentEnd else bestEnd
        val acceptedMatchCount = if (useCurrentCursor) currentMatchCount else bestMatchCount
        val acceptedScore = if (useCurrentCursor) currentScore else bestScore
        val matchedCursor = if (enoughEvidence) candidate[acceptedEnd - 1].first + 1 else start
        return Match(
            cursor = nextReadableCursor(script, matchedCursor),
            matchedCharacters = acceptedMatchCount,
            spokenCharacters = spoken.length,
            score = acceptedScore,
            accepted = enoughEvidence
        )
    }

    fun completionPercent(script: String, cursor: Int): Int {
        val total = script.count(::isReadable).coerceAtLeast(1)
        val consumed = script.take(cursor.coerceIn(0, script.length)).count(::isReadable)
        return ((consumed.toFloat() / total) * 100).toInt().coerceIn(0, 100)
    }

    /** Returns the next character that can actually be spoken/highlighted, or script.length. */
    fun nextReadableCursor(script: String, from: Int): Int {
        var cursor = from.coerceIn(0, script.length)
        while (cursor < script.length && !isReadable(script[cursor])) cursor++
        return cursor
    }

    /** Text suitable for recognizer biasing. Unlike [normalize], natural numerals are preserved. */
    fun biasingText(value: String): String = nfkc(value)
        .filter(::isReadable)
        .lowercase()

    /**
     * Builds per-stream sherpa-onnx hotwords from the script.
     *
     * CJK-character models expect spaces between tokens and slashes between phrases. Digits are
     * written as spoken Chinese numerals because this model does not expose digit tokens.
     */
    fun hotwords(value: String): String {
        val phrases = mutableListOf<List<Char>>()
        val current = mutableListOf<Char>()

        fun flush() {
            if (current.size >= MIN_HOTWORD_TOKENS) {
                var start = 0
                while (start < current.size) {
                    val end = minOf(start + MAX_HOTWORD_TOKENS, current.size)
                    val chunk = current.subList(start, end).toList()
                    if (chunk.size >= MIN_HOTWORD_TOKENS) phrases += chunk
                    if (end == current.size) break
                    start += HOTWORD_STRIDE
                }
            }
            current.clear()
        }

        nfkc(value).forEach { char ->
            val token = hotwordCharacter(char)
            if (token == null) flush() else current += token
        }
        flush()
        return phrases.joinToString("/") { phrase -> phrase.joinToString(" ") }
    }

    fun normalize(value: String): String = buildString {
        nfkc(value).forEach { char -> canonicalCharacter(char)?.let(::append) }
    }

    private fun canonicalCharacter(char: Char): Char? {
        val normalized = nfkc(char.toString()).firstOrNull(::isReadable) ?: return null
        return when (normalized.lowercaseChar()) {
            '零', '〇' -> '0'
            '一', '幺' -> '1'
            '二', '两' -> '2'
            '三' -> '3'
            '四' -> '4'
            '五' -> '5'
            '六' -> '6'
            '七' -> '7'
            '八' -> '8'
            '九' -> '9'
            else -> normalized.lowercaseChar()
        }
    }

    private fun hotwordCharacter(char: Char): Char? {
        val normalized = nfkc(char.toString()).firstOrNull(::isReadable) ?: return null
        return when (normalized) {
            '0' -> '零'
            '1' -> '一'
            '2' -> '二'
            '3' -> '三'
            '4' -> '四'
            '5' -> '五'
            '6' -> '六'
            '7' -> '七'
            '8' -> '八'
            '9' -> '九'
            else -> normalized
        }
    }

    private fun nfkc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)

    private fun isReadable(char: Char): Boolean = char.isLetterOrDigit()

    private const val MIN_HOTWORD_TOKENS = 2
    private const val MAX_HOTWORD_TOKENS = 12
    private const val HOTWORD_STRIDE = 10
    private const val SHORT_EXACT_SCORE = 0.72f
    private const val CURRENT_CURSOR_SCORE = 0.50f
    private const val CURRENT_CURSOR_MATCH_RATIO = 0.45f
    private const val FORWARD_SHORT_SCORE = 0.72f
    private const val FORWARD_SCORE = 0.62f
    private const val FORWARD_MATCH_RATIO = 0.55f
}
