package com.example.kouboteleprompter.domain

import java.util.Locale
import kotlin.math.ceil

/** A word/token emitted by the recorder's offline recognizer. */
data class TimedTranscriptToken(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1f
)

data class TimedTranscriptSegment(
    val index: Int,
    val tokens: List<TimedTranscriptToken>
)

enum class FillerKind {
    NONE,
    /** Safe to hide automatically because it is a non-semantic hesitation sound. */
    SAFE,
    /** A verbal habit recognized from a multi-character phrase and hidden by default. */
    REVIEW
}

data class CaptionWord(
    val id: Int,
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float = 1f,
    val fillerKind: FillerKind = FillerKind.NONE,
    val removed: Boolean = false,
    val emphasized: Boolean = false
)

data class CaptionCard(
    val id: Int,
    val words: List<CaptionWord>
) {
    val startMs: Long get() = words.firstOrNull()?.startMs ?: 0L
    val endMs: Long get() = words.lastOrNull()?.endMs ?: startMs
    val visibleWords: List<CaptionWord> get() = words.filterNot(CaptionWord::removed)
    val displayText: String get() = joinCaptionTokens(visibleWords.map(CaptionWord::text))
}

enum class CaptionStylePreset {
    BOLD_ORANGE,
    FRESH_GREEN,
    CLEAN_WHITE
}

data class CaptionProject(
    val sourceFileName: String,
    val cards: List<CaptionCard>,
    val style: CaptionStylePreset = CaptionStylePreset.BOLD_ORANGE,
    val fontSizeSp: Float = DEFAULT_CAPTION_FONT_SIZE_SP,
    /** Normalized caption centre relative to the video centre; positive Y moves it down. */
    val captionPositionX: Float = DEFAULT_CAPTION_POSITION_X,
    val captionPositionY: Float = DEFAULT_CAPTION_POSITION_Y,
    val autoCleanupEnabled: Boolean = true,
    val autoEmphasisEnabled: Boolean = true,
    val noiseReductionEnabled: Boolean = false,
    val videoSpeed: Float = DEFAULT_VIDEO_SPEED,
    val version: Int = 3
) {
    val automaticallyRemovedCount: Int
        get() = cards.sumOf { card ->
            card.words.count { it.removed && it.fillerKind != FillerKind.NONE }
        }
}

const val MIN_CAPTION_FONT_SIZE_SP = 18f
const val MAX_CAPTION_FONT_SIZE_SP = 42f
const val DEFAULT_CAPTION_FONT_SIZE_SP = 26f
const val DEFAULT_CAPTION_POSITION_X = 0f
const val DEFAULT_CAPTION_POSITION_Y = .72f
const val MAX_CAPTION_POSITION = .86f
const val DEFAULT_VIDEO_SPEED = 1f
const val MIN_VIDEO_SPEED = 1.10f
const val MAX_VIDEO_SPEED = 1.50f

/** Keeps persisted and UI-provided speed values inside the product-supported range. */
fun normalizedVideoSpeed(speed: Float): Float = when {
    !speed.isFinite() || speed < MIN_VIDEO_SPEED -> DEFAULT_VIDEO_SPEED
    else -> speed.coerceAtMost(MAX_VIDEO_SPEED)
}

/**
 * Converts a source/cut timeline into the timeline of a speed-adjusted exported file.
 *
 * Export-only media options are reset because their result is already materialized in the new
 * file. This prevents a second export of that file from applying speed or denoising twice.
 */
fun CaptionProject.materializeVideoProcessing(outputFileName: String): CaptionProject {
    val speed = normalizedVideoSpeed(videoSpeed)
    fun scale(timeMs: Long): Long = (timeMs / speed).toLong().coerceAtLeast(0L)
    return copy(
        sourceFileName = outputFileName,
        cards = cards.map { card ->
            card.copy(
                words = card.words.map { word ->
                    word.copy(
                        startMs = scale(word.startMs),
                        endMs = scale(word.endMs).coerceAtLeast(scale(word.startMs))
                    )
                }
            )
        },
        noiseReductionEnabled = false,
        videoSpeed = DEFAULT_VIDEO_SPEED,
        version = maxOf(version, 3)
    )
}

data class CaptionTimeRange(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

data class CaptionTimelineDecision(
    val removedRanges: List<CaptionTimeRange>,
    val keptRanges: List<CaptionTimeRange>,
    val outputProject: CaptionProject
) {
    val removedDurationMs: Long get() = removedRanges.sumOf(CaptionTimeRange::durationMs)
}

/** Converts reviewed word removals into a non-destructive source-time edit decision list. */
object CaptionTimelineEditor {
    private const val MERGE_CUT_GAP_MS = 90L
    private const val MINIMUM_KEPT_VIDEO_MS = 300L

    fun decide(project: CaptionProject, sourceDurationMs: Long): CaptionTimelineDecision {
        val durationMs = sourceDurationMs.coerceAtLeast(
            project.cards.maxOfOrNull(CaptionCard::endMs) ?: 0L
        )
        val rawCuts = project.cards
            .flatMap(CaptionCard::words)
            .filter(CaptionWord::removed)
            .map { word ->
                CaptionTimeRange(
                    startMs = word.startMs.coerceIn(0L, durationMs),
                    endMs = word.endMs.coerceIn(0L, durationMs)
                )
            }
            .filter { it.durationMs > 0L }
            .sortedBy(CaptionTimeRange::startMs)
        val mergedCuts = mergeRanges(rawCuts)
        val keptRanges = complement(mergedCuts, durationMs)
        if (keptRanges.sumOf(CaptionTimeRange::durationMs) < MINIMUM_KEPT_VIDEO_MS) {
            return CaptionTimelineDecision(
                removedRanges = emptyList(),
                keptRanges = listOf(CaptionTimeRange(0L, durationMs)),
                outputProject = project
            )
        }

        val remappedCards = project.cards.mapNotNull { card ->
            val visible = card.words.filterNot(CaptionWord::removed)
            if (visible.isEmpty()) return@mapNotNull null
            card.copy(
                words = visible.map { word ->
                    word.copy(
                        startMs = remapTime(word.startMs, mergedCuts),
                        endMs = remapTime(word.endMs, mergedCuts),
                        removed = false
                    )
                }
            )
        }.mapIndexed { index, card -> card.copy(id = index) }

        return CaptionTimelineDecision(
            removedRanges = mergedCuts,
            keptRanges = keptRanges,
            outputProject = project.copy(cards = remappedCards)
        )
    }

    private fun mergeRanges(ranges: List<CaptionTimeRange>): List<CaptionTimeRange> {
        if (ranges.isEmpty()) return emptyList()
        val result = mutableListOf<CaptionTimeRange>()
        for (range in ranges) {
            val previous = result.lastOrNull()
            if (previous != null && range.startMs - previous.endMs <= MERGE_CUT_GAP_MS) {
                result[result.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, range.endMs))
            } else {
                result += range
            }
        }
        return result
    }

    private fun complement(
        removed: List<CaptionTimeRange>,
        durationMs: Long
    ): List<CaptionTimeRange> {
        if (removed.isEmpty()) return listOf(CaptionTimeRange(0L, durationMs))
        val kept = mutableListOf<CaptionTimeRange>()
        var cursor = 0L
        removed.forEach { cut ->
            if (cut.startMs > cursor) kept += CaptionTimeRange(cursor, cut.startMs)
            cursor = maxOf(cursor, cut.endMs)
        }
        if (cursor < durationMs) kept += CaptionTimeRange(cursor, durationMs)
        return kept.filter { it.durationMs > 0L }
    }

    private fun remapTime(sourceTimeMs: Long, removed: List<CaptionTimeRange>): Long {
        var removedBefore = 0L
        removed.forEach { cut ->
            when {
                sourceTimeMs >= cut.endMs -> removedBefore += cut.durationMs
                sourceTimeMs > cut.startMs -> removedBefore += sourceTimeMs - cut.startMs
            }
        }
        return (sourceTimeMs - removedBefore).coerceAtLeast(0L)
    }
}

/**
 * Turns timestamped ASR output into readable caption cards and conservative editing suggestions.
 *
 * The recognizer is intentionally not trusted to infer punctuation or semantics it did not hear.
 * Cards are split on pauses, duration and readable character count. Every recognized filler is
 * automatically marked deleted; the original word stays in the project so a user can restore it.
 */
object CaptionProjectBuilder {
    private const val MAX_CARD_CHARACTERS = 16
    private const val MAX_CARD_DURATION_MS = 3_800L
    private const val CARD_SPLIT_GAP_MS = 620L
    private const val MIN_SEMANTIC_CARD_CHARACTERS = 4
    private const val MIN_AUTO_SPLIT_SIDE_CHARACTERS = 2

    private val safeFillers = setOf("呃", "额", "uh", "um", "er")
    private val reviewFillers = listOf("然后", "这个", "那么", "就是", "那个", "嗯", "啊", "呜", "唔")
    private val punctuation = setOf("，", "。", "！", "？", ",", ".", "!", "?", ";", "；", ":", "：")
    private val strongPunctuation = setOf("。", "！", "？", ".", "!", "?", ";", "；")
    private val sentenceStarters = listOf(
        "换句话说", "接下来", "与此同时", "另一方面", "然后", "但是", "所以", "另外", "最后",
        "不过", "而且", "同时", "首先", "其次", "总之", "其实", "比如", "这时", "现在"
    )
    private val sentenceEndings = listOf(
        "就这样", "已经完成了", "完成了", "结束了", "可以了", "明白了", "知道了",
        "对不对", "是不是", "没问题", "怎么办", "为什么", "是什么", "可以吗", "行不行", "对吧", "好吧"
    )
    private val sentenceContinuationPrefixes = listOf("以后", "之后", "的话", "才能", "再", "就", "时", "时候")

    private data class SegmentedCaptionWord(
        val word: CaptionWord,
        val segmentIndex: Int
    )

    fun build(
        sourceFileName: String,
        segments: List<TimedTranscriptSegment>
    ): CaptionProject {
        val atomicWords = segments
            .sortedBy(TimedTranscriptSegment::index)
            .flatMap { segment ->
                segment.tokens.flatMap(::toAtomicWords).map { word ->
                    SegmentedCaptionWord(word, segment.index)
                }
            }
            .filter { it.word.text.isNotBlank() }
            .sortedWith(
                compareBy<SegmentedCaptionWord> { it.word.startMs }
                    .thenBy { it.word.id }
            )

        if (atomicWords.isEmpty()) return CaptionProject(sourceFileName, emptyList())

        val cards = mutableListOf<List<CaptionWord>>()
        var current = mutableListOf<CaptionWord>()
        for ((wordIndex, segmentedWord) in atomicWords.withIndex()) {
            val word = segmentedWord.word
            val previous = current.lastOrNull()
            val currentCharacters = current.sumOf { it.text.codePointCount(0, it.text.length) }
            val projectedCharacters = current.sumOf { it.text.codePointCount(0, it.text.length) } +
                word.text.codePointCount(0, word.text.length)
            val segmentChanged = wordIndex > 0 &&
                atomicWords[wordIndex - 1].segmentIndex != segmentedWord.segmentIndex
            val nextText = atomicWords.drop(wordIndex).take(6).joinToString("") { it.word.text }
            val shouldSplit = previous != null && (
                word.startMs - previous.endMs >= CARD_SPLIT_GAP_MS ||
                    previous.text in punctuation ||
                    projectedCharacters > MAX_CARD_CHARACTERS ||
                    word.endMs - current.first().startMs > MAX_CARD_DURATION_MS ||
                    currentCharacters >= MIN_SEMANTIC_CARD_CHARACTERS && segmentChanged ||
                    currentCharacters >= MIN_SEMANTIC_CARD_CHARACTERS && startsWithPhrase(
                        nextText,
                        sentenceStarters
                    ) ||
                    currentCharacters >= MIN_SEMANTIC_CARD_CHARACTERS &&
                    endsWithPhrase(current.joinToString("") { it.text }, sentenceEndings) &&
                    !startsWithPhrase(nextText, sentenceContinuationPrefixes)
                )
            if (shouldSplit) {
                cards += current
                current = mutableListOf()
            }
            current += word
        }
        if (current.isNotEmpty()) cards += current

        return CaptionProject(
            sourceFileName = sourceFileName,
            cards = cards.mapIndexed { cardIndex, words ->
                val withFillers = markFillers(words)
                CaptionCard(
                    id = cardIndex,
                    words = applySuggestedEmphasis(withFillers)
                )
            }
        )
    }

    /** Returns a readable automatic split point in the card's rendered text. */
    fun suggestedSplitOffset(card: CaptionCard): Int? {
        val text = card.displayText
        if (text.length < MIN_AUTO_SPLIT_SIDE_CHARACTERS * 2) return null
        val midpoint = text.length / 2
        val candidates = mutableListOf<Pair<Int, Int>>()

        text.forEachIndexed { index, character ->
            val offset = index + 1
            if (character.toString() in punctuation) {
                candidates += offset to if (character.toString() in strongPunctuation) 5 else 4
            }
        }
        sentenceStarters.forEach { phrase ->
            var fromIndex = 1
            while (fromIndex < text.length) {
                val index = text.indexOf(phrase, fromIndex)
                if (index < 0) break
                candidates += index to 5
                fromIndex = index + phrase.length
            }
        }
        sentenceEndings.forEach { phrase ->
            var fromIndex = 0
            while (fromIndex < text.length) {
                val index = text.indexOf(phrase, fromIndex)
                if (index < 0) break
                candidates += index + phrase.length to 4
                fromIndex = index + phrase.length
            }
        }

        val validCandidates = candidates.filter { (offset, _) ->
            offset >= MIN_AUTO_SPLIT_SIDE_CHARACTERS &&
                text.length - offset >= MIN_AUTO_SPLIT_SIDE_CHARACTERS
        }
        val semanticCandidate = validCandidates.maxWithOrNull(
            compareBy<Pair<Int, Int>> { it.second }
                .thenBy { -kotlin.math.abs(it.first - midpoint) }
        )?.first
        return semanticCandidate ?: nearestCodePointBoundary(text, midpoint)
            .takeIf { it in 1 until text.length }
    }

    /** Retimes edited text across the card's existing interval and refreshes suggestions. */
    fun replaceCardText(card: CaptionCard, newText: String): CaptionCard {
        val tokens = tokenizeForEditing(newText)
        if (tokens.isEmpty()) return card.copy(words = emptyList())
        val duration = (card.endMs - card.startMs).coerceAtLeast(tokens.size * 80L)
        val step = duration.toDouble() / tokens.size
        val retimed = tokens.mapIndexed { index, token ->
            CaptionWord(
                id = index,
                text = token,
                startMs = card.startMs + (index * step).toLong(),
                endMs = card.startMs + ((index + 1) * step).toLong(),
                confidence = 1f
            )
        }
        return card.copy(words = applySuggestedEmphasis(markFillers(retimed)))
    }

    /**
     * Splits a card at an offset in its rendered, visible text.
     *
     * Existing word timing and review state are preserved. If the cursor is inside a token (for
     * example, "Hello"), only that token is divided and its interval is split proportionally.
     */
    fun splitCard(
        project: CaptionProject,
        cardIndex: Int,
        displayOffset: Int
    ): CaptionProject {
        val card = project.cards.getOrNull(cardIndex) ?: return project
        val ranges = renderedWordRanges(card)
        val displayLength = ranges.maxOfOrNull(RenderedWordRange::endOffset) ?: return project
        if (displayOffset !in 1 until displayLength) return project

        val updatedWords = card.words.toMutableList()
        val containingRange = ranges.firstOrNull { range ->
            displayOffset > range.startOffset && displayOffset < range.endOffset
        }
        val splitWordIndex = if (containingRange != null) {
            val word = updatedWords[containingRange.wordIndex]
            val rawOffset = displayOffset - containingRange.startOffset
            val wordOffset = nearestCodePointBoundary(word.text, rawOffset)
            if (wordOffset <= 0 || wordOffset >= word.text.length) return project

            val leftText = word.text.substring(0, wordOffset)
            val rightText = word.text.substring(wordOffset)
            if (leftText.isBlank() || rightText.isBlank()) return project
            val totalCodePoints = word.text.codePointCount(0, word.text.length).coerceAtLeast(1)
            val leftCodePoints = leftText.codePointCount(0, leftText.length)
            val splitTimeMs = (
                word.startMs +
                    (word.endMs - word.startMs) * leftCodePoints / totalCodePoints
                ).coerceIn(word.startMs, word.endMs)
            updatedWords[containingRange.wordIndex] = word.copy(
                text = leftText,
                endMs = splitTimeMs
            )
            updatedWords.add(
                containingRange.wordIndex + 1,
                word.copy(text = rightText, startMs = splitTimeMs)
            )
            containingRange.wordIndex + 1
        } else {
            ranges.firstOrNull { it.startOffset >= displayOffset }?.wordIndex
                ?: (ranges.last().wordIndex + 1)
        }

        return splitCardAtWordBoundary(project, cardIndex, updatedWords, splitWordIndex)
    }

    /** Returns whether both sides of the selected word's left edge contain visible caption text. */
    fun canSplitBeforeWord(card: CaptionCard, wordIndex: Int): Boolean =
        wordIndex in 1..card.words.lastIndex &&
            card.words.take(wordIndex).hasVisibleCaptionText() &&
            card.words.drop(wordIndex).hasVisibleCaptionText()

    /** Splits immediately before the selected word, keeping that word in the new following card. */
    fun splitCardBeforeWord(
        project: CaptionProject,
        cardIndex: Int,
        wordIndex: Int
    ): CaptionProject {
        val card = project.cards.getOrNull(cardIndex) ?: return project
        if (!canSplitBeforeWord(card, wordIndex)) return project
        return splitCardAtWordBoundary(project, cardIndex, card.words, wordIndex)
    }

    private fun splitCardAtWordBoundary(
        project: CaptionProject,
        cardIndex: Int,
        words: List<CaptionWord>,
        splitWordIndex: Int
    ): CaptionProject {
        val card = project.cards.getOrNull(cardIndex) ?: return project

        val leftWords = words.take(splitWordIndex).reindexWords()
        val rightWords = words.drop(splitWordIndex).reindexWords()
        if (!leftWords.hasVisibleCaptionText() || !rightWords.hasVisibleCaptionText()) {
            return project
        }

        val nextCardId = (project.cards.maxOfOrNull(CaptionCard::id) ?: -1) + 1
        val updatedCards = project.cards.toMutableList().apply {
            this[cardIndex] = card.copy(words = leftWords)
            add(cardIndex + 1, CaptionCard(id = nextCardId, words = rightWords))
        }
        return project.copy(cards = updatedCards)
    }

    /** Merges the selected card with the following card and keeps the full source-time interval. */
    fun mergeCardWithNext(project: CaptionProject, cardIndex: Int): CaptionProject {
        val card = project.cards.getOrNull(cardIndex) ?: return project
        val nextCard = project.cards.getOrNull(cardIndex + 1) ?: return project
        val updatedCards = project.cards.toMutableList().apply {
            this[cardIndex] = card.copy(words = (card.words + nextCard.words).reindexWords())
            removeAt(cardIndex + 1)
        }
        return project.copy(cards = updatedCards)
    }

    /** Merges the selected card into the preceding card. */
    fun mergeCardWithPrevious(project: CaptionProject, cardIndex: Int): CaptionProject =
        if (cardIndex > 0) mergeCardWithNext(project, cardIndex - 1) else project

    private fun startsWithPhrase(text: String, phrases: List<String>): Boolean =
        phrases.any(text::startsWith)

    private fun endsWithPhrase(text: String, phrases: List<String>): Boolean =
        phrases.any(text::endsWith)

    private data class RenderedWordRange(
        val wordIndex: Int,
        val startOffset: Int,
        val endOffset: Int
    )

    private fun renderedWordRanges(card: CaptionCard): List<RenderedWordRange> {
        val result = mutableListOf<RenderedWordRange>()
        var offset = 0
        var previousLatin = false
        card.words.forEachIndexed { wordIndex, word ->
            if (word.removed) return@forEachIndexed
            val currentLatin = word.text.firstOrNull()?.let {
                it.code < 128 && it.isLetterOrDigit()
            } == true
            if (result.isNotEmpty() && previousLatin && currentLatin) offset += 1
            val startOffset = offset
            offset += word.text.length
            result += RenderedWordRange(wordIndex, startOffset, offset)
            previousLatin = currentLatin
        }
        return result
    }

    private fun nearestCodePointBoundary(text: String, requestedOffset: Int): Int {
        val offset = requestedOffset.coerceIn(0, text.length)
        if (offset == 0 || offset == text.length) return offset
        return if (Character.isLowSurrogate(text[offset]) && Character.isHighSurrogate(text[offset - 1])) {
            offset - 1
        } else {
            offset
        }
    }

    private fun List<CaptionWord>.reindexWords(): List<CaptionWord> = mapIndexed { index, word ->
        word.copy(id = index)
    }

    private fun List<CaptionWord>.hasVisibleCaptionText(): Boolean =
        any { !it.removed && it.text.isNotBlank() }

    fun withAutoCleanup(project: CaptionProject, enabled: Boolean): CaptionProject = project.copy(
        autoCleanupEnabled = enabled,
        cards = project.cards.map { card ->
            card.copy(
                words = card.words.map { word ->
                    if (word.fillerKind != FillerKind.NONE) word.copy(removed = enabled) else word
                }
            )
        }
    )

    fun withAutoEmphasis(project: CaptionProject, enabled: Boolean): CaptionProject = project.copy(
        autoEmphasisEnabled = enabled,
        cards = project.cards.map { card ->
            card.copy(
                words = if (enabled) {
                    applySuggestedEmphasis(card.words.map { it.copy(emphasized = false) })
                } else {
                    card.words.map { it.copy(emphasized = false) }
                }
            )
        }
    )

    private fun toAtomicWords(token: TimedTranscriptToken): List<CaptionWord> {
        val cleaned = normalizeRecognizerToken(token.text)
        if (cleaned.isBlank()) return emptyList()
        val parts = tokenizeForEditing(cleaned)
        if (parts.isEmpty()) return emptyList()
        val duration = (token.endMs - token.startMs).coerceAtLeast(parts.size * 60L)
        val step = duration.toDouble() / parts.size
        return parts.mapIndexed { index, part ->
            CaptionWord(
                id = stableWordId(token.startMs, index, part),
                text = part,
                startMs = token.startMs + (index * step).toLong(),
                endMs = token.startMs + ((index + 1) * step).toLong(),
                confidence = token.confidence
            )
        }
    }

    private fun markFillers(words: List<CaptionWord>): List<CaptionWord> {
        if (words.isEmpty()) return words
        val result = words.map { word ->
            val normalized = word.text.lowercase(Locale.ROOT)
            if (normalized in safeFillers) {
                word.copy(fillerKind = FillerKind.SAFE, removed = true)
            } else {
                word.copy(fillerKind = FillerKind.NONE, removed = false)
            }
        }.toMutableList()

        val text = result.joinToString("") { it.text }
        for (phrase in reviewFillers) {
            var fromIndex = 0
            while (true) {
                val match = text.indexOf(phrase, fromIndex)
                if (match < 0) break
                val indices = characterRangeToWordIndices(result, match, match + phrase.length)
                for (index in indices) {
                    if (result[index].fillerKind == FillerKind.NONE) {
                        result[index] = result[index].copy(
                            fillerKind = FillerKind.REVIEW,
                            removed = true
                        )
                    }
                }
                fromIndex = match + phrase.length
            }
        }
        return result
    }

    private fun applySuggestedEmphasis(words: List<CaptionWord>): List<CaptionWord> {
        val visibleIndices = words.indices.filterNot { words[it].removed || words[it].text in punctuation }
        if (visibleIndices.size < 2) return words
        val visibleText = visibleIndices.joinToString("") { words[it].text }
        val maxEmphasisCharacters = ceil(visibleText.length * .40).toInt().coerceIn(2, 6)

        val candidateRange = when {
            visibleText.contains("而是") -> {
                val start = visibleText.lastIndexOf("而是") + 2
                trimCandidate(visibleText, start, visibleText.length, maxEmphasisCharacters)
            }
            visibleText.contains("关键") -> keywordCandidate(visibleText, "关键", maxEmphasisCharacters)
            visibleText.contains("核心") -> keywordCandidate(visibleText, "核心", maxEmphasisCharacters)
            visibleText.contains("重点") -> keywordCandidate(visibleText, "重点", maxEmphasisCharacters)
            visibleText.contains("必须") -> keywordCandidate(visibleText, "必须", maxEmphasisCharacters)
            visibleText.contains("不要") -> keywordCandidate(visibleText, "不要", maxEmphasisCharacters)
            visibleText.any(Char::isDigit) -> numericCandidate(visibleText, maxEmphasisCharacters)
            visibleText.any { it.isLetter() && it.code < 128 } -> latinCandidate(visibleText, maxEmphasisCharacters)
            else -> genericTailCandidate(visibleText, maxEmphasisCharacters)
        }
        if (candidateRange.isEmpty()) return words

        val emphasizedVisible = characterRangeToVisibleWordIndices(
            words = words,
            visibleWordIndices = visibleIndices,
            start = candidateRange.first,
            endExclusive = candidateRange.last + 1
        ).toSet()
        return words.mapIndexed { index, word -> word.copy(emphasized = index in emphasizedVisible) }
    }

    private fun keywordCandidate(text: String, keyword: String, max: Int): IntRange {
        val keywordStart = text.indexOf(keyword)
        return trimCandidate(text, keywordStart, minOf(text.length, keywordStart + max), max)
    }

    private fun numericCandidate(text: String, max: Int): IntRange {
        val firstDigit = text.indexOfFirst(Char::isDigit)
        var end = firstDigit
        while (end < text.length && (text[end].isDigit() || text[end] in ".%年月日万亿")) end++
        return trimCandidate(text, firstDigit, end, max)
    }

    private fun latinCandidate(text: String, max: Int): IntRange {
        val start = text.indexOfFirst { it.isLetter() && it.code < 128 }
        var end = start
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "-_")) end++
        return trimCandidate(text, start, end, max)
    }

    private fun genericTailCandidate(text: String, max: Int): IntRange {
        val particles = listOf("就是", "的是", "一个", "我们", "你们", "他们", "因为", "所以")
        var end = text.length
        while (end > 0 && text[end - 1].toString() in punctuation) end--
        var start = (end - max).coerceAtLeast(0)
        particles.firstOrNull { text.substring(start, end).startsWith(it) }?.let { start += it.length }
        return trimCandidate(text, start, end, max)
    }

    private fun trimCandidate(text: String, requestedStart: Int, requestedEnd: Int, max: Int): IntRange {
        if (text.isEmpty() || requestedStart !in text.indices) return IntRange.EMPTY
        var start = requestedStart.coerceAtLeast(0)
        var end = requestedEnd.coerceIn(start + 1, text.length)
        if (end - start > max) start = end - max
        while (start < end && text[start].toString() in punctuation) start++
        while (end > start && text[end - 1].toString() in punctuation) end--
        return if (start < end) start until end else IntRange.EMPTY
    }

    private fun characterRangeToWordIndices(
        words: List<CaptionWord>,
        start: Int,
        endExclusive: Int
    ): List<Int> {
        var cursor = 0
        return buildList {
            words.forEachIndexed { index, word ->
                val next = cursor + word.text.length
                if (cursor < endExclusive && next > start) add(index)
                cursor = next
            }
        }
    }

    private fun characterRangeToVisibleWordIndices(
        words: List<CaptionWord>,
        visibleWordIndices: List<Int>,
        start: Int,
        endExclusive: Int
    ): List<Int> {
        var cursor = 0
        return buildList {
            visibleWordIndices.forEach { index ->
                val next = cursor + words[index].text.length
                if (cursor < endExclusive && next > start) add(index)
                cursor = next
            }
        }
    }

    private fun tokenizeForEditing(text: String): List<String> {
        val result = mutableListOf<String>()
        val latin = StringBuilder()
        fun flushLatin() {
            if (latin.isNotEmpty()) {
                result += latin.toString()
                latin.clear()
            }
        }
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val value = String(Character.toChars(codePoint))
            when {
                value.isBlank() -> flushLatin()
                codePoint < 128 && (Character.isLetterOrDigit(codePoint) || value in setOf("-", "_", "%")) -> {
                    latin.append(value)
                }
                else -> {
                    flushLatin()
                    result += value
                }
            }
            index += Character.charCount(codePoint)
        }
        flushLatin()
        return result
    }

    private fun normalizeRecognizerToken(value: String): String = value
        .replace("▁", " ")
        .replace("<blk>", "")
        .replace("<blank>", "")
        .replace("<unk>", "")
        .trim()

    private fun stableWordId(startMs: Long, offset: Int, text: String): Int {
        var result = (startMs xor (startMs ushr 32)).toInt()
        result = 31 * result + offset
        result = 31 * result + text.hashCode()
        return result
    }
}

fun joinCaptionTokens(tokens: List<String>): String {
    val builder = StringBuilder()
    var previousLatin = false
    tokens.forEach { token ->
        val currentLatin = token.firstOrNull()?.let { it.code < 128 && it.isLetterOrDigit() } == true
        if (builder.isNotEmpty() && previousLatin && currentLatin) builder.append(' ')
        builder.append(token)
        previousLatin = currentLatin
    }
    return builder.toString()
}
