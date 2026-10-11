package com.example.kouboteleprompter.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionProjectTest {
    @Test
    fun `all recognized fillers are marked deleted by default and remain recoverable`() {
        val project = projectFrom("呃然后我们看第二点")
        val words = project.cards.flatMap(CaptionCard::words)

        assertTrue(words.first { it.text == "呃" }.removed)
        assertEquals(FillerKind.SAFE, words.first { it.text == "呃" }.fillerKind)
        assertEquals(
            FillerKind.REVIEW,
            words.first { it.text == "然" }.fillerKind
        )
        assertTrue(words.first { it.text == "然" }.removed)
        assertTrue(words.first { it.text == "后" }.removed)
        assertEquals("我们看第二点", project.cards.joinToString("") { it.displayText })
    }

    @Test
    fun `common fillers this then and so are all deleted by default`() {
        val project = projectFrom("这个然后那么开始")
        val words = project.cards.flatMap(CaptionCard::words)

        listOf("这", "然", "那").forEach { character ->
            assertEquals(FillerKind.REVIEW, words.first { it.text == character }.fillerKind)
            assertTrue(words.first { it.text == character }.removed)
        }
        assertEquals("开始", project.cards.joinToString("") { it.displayText })
    }

    @Test
    fun `contrast conclusion is emphasized as one compact phrase`() {
        val project = projectFrom("根本不是报补习班而是父母的眼界")
        val emphasized = project.cards
            .flatMap(CaptionCard::words)
            .filter(CaptionWord::emphasized)
            .joinToString("") { it.text }

        assertEquals("父母的眼界", emphasized)
        assertEquals(1, project.cards.size)
    }

    @Test
    fun `long pause starts a new caption card`() {
        val project = CaptionProjectBuilder.build(
            "video.mp4",
            listOf(
                TimedTranscriptSegment(
                    0,
                    listOf(
                        TimedTranscriptToken("第一句", 0, 600),
                        TimedTranscriptToken("第二句", 1_400, 2_000)
                    )
                )
            )
        )

        assertEquals(listOf("第一句", "第二句"), project.cards.map(CaptionCard::displayText))
    }

    @Test
    fun `chinese discourse starter begins the next caption instead of leaking its prefix`() {
        val project = projectFrom("今天先介绍三个重点然后我们看第一点")

        assertEquals(
            listOf("今天先介绍三个重点", "我们看第一点"),
            project.cards.map(CaptionCard::displayText)
        )
    }

    @Test
    fun `recognizer endpoint is respected as a chinese sentence boundary`() {
        val project = CaptionProjectBuilder.build(
            "video.mp4",
            listOf(
                TimedTranscriptSegment(0, listOf(TimedTranscriptToken("这是第一句", 0, 800))),
                TimedTranscriptSegment(1, listOf(TimedTranscriptToken("这是第二句", 820, 1_600)))
            )
        )

        assertEquals(listOf("这是第一句", "这是第二句"), project.cards.map(CaptionCard::displayText))
    }

    @Test
    fun `cleanup switch restores and reapplies all recognized fillers`() {
        val project = projectFrom("额这个方案可以")
        val restored = CaptionProjectBuilder.withAutoCleanup(project, false)
        val cleanedAgain = CaptionProjectBuilder.withAutoCleanup(restored, true)

        assertEquals("额这个方案可以", restored.cards.single().displayText)
        assertEquals("方案可以", cleanedAgain.cards.single().displayText)
        assertTrue(cleanedAgain.cards.single().words.first { it.text == "这" }.removed)
    }

    @Test
    fun `manual text replacement preserves card timing`() {
        val original = projectFrom("原来的字幕").cards.single()
        val edited = CaptionProjectBuilder.replaceCardText(original, "Hello 早上好")

        assertEquals("Hello早上好", edited.displayText)
        assertEquals(original.startMs, edited.startMs)
        assertEquals(original.endMs, edited.endMs)
    }

    @Test
    fun `removed filler creates video cut and remaps remaining captions`() {
        val project = projectFrom("呃核心内容")
        val decision = CaptionTimelineEditor.decide(project, 3_000)

        assertEquals(1, decision.removedRanges.size)
        assertTrue(decision.removedDurationMs > 0L)
        assertEquals(decision.removedRanges.single().endMs, decision.keptRanges.first().startMs)
        assertEquals("核心内容", decision.outputProject.cards.single().displayText)
        assertEquals(0L, decision.outputProject.cards.single().startMs)
        assertFalse(decision.outputProject.cards.single().words.any(CaptionWord::removed))
    }

    @Test
    fun `split card preserves word states and timing`() {
        val project = CaptionProject(
            sourceFileName = "video.mp4",
            cards = listOf(
                CaptionCard(
                    id = 8,
                    words = listOf(
                        CaptionWord(0, "早", 0, 300, emphasized = true),
                        CaptionWord(1, "呃", 300, 500, fillerKind = FillerKind.SAFE, removed = true),
                        CaptionWord(2, "上", 500, 800),
                        CaptionWord(3, "好", 800, 1_100)
                    )
                )
            )
        )

        val split = CaptionProjectBuilder.splitCard(project, cardIndex = 0, displayOffset = 2)

        assertEquals(listOf("早上", "好"), split.cards.map(CaptionCard::displayText))
        assertEquals(800L, split.cards[0].endMs)
        assertEquals(800L, split.cards[1].startMs)
        assertTrue(split.cards[0].words.first().emphasized)
        assertTrue(split.cards[0].words.first { it.text == "呃" }.removed)
    }

    @Test
    fun `split inside latin token divides its timing proportionally`() {
        val project = CaptionProject(
            sourceFileName = "video.mp4",
            cards = listOf(
                CaptionCard(0, listOf(CaptionWord(0, "Hello", 100, 1_100)))
            )
        )

        val split = CaptionProjectBuilder.splitCard(project, cardIndex = 0, displayOffset = 2)

        assertEquals(listOf("He", "llo"), split.cards.map(CaptionCard::displayText))
        assertEquals(500L, split.cards[0].endMs)
        assertEquals(500L, split.cards[1].startMs)
    }

    @Test
    fun `quick split uses selected word left edge and keeps selected word on the right`() {
        val project = CaptionProject(
            sourceFileName = "video.mp4",
            cards = listOf(
                CaptionCard(
                    id = 3,
                    words = listOf(
                        CaptionWord(0, "现", 0, 200),
                        CaptionWord(1, "在", 200, 400),
                        CaptionWord(2, "呃", 400, 500, removed = true),
                        CaptionWord(3, "缩", 500, 700, emphasized = true),
                        CaptionWord(4, "编", 700, 900, emphasized = true)
                    )
                )
            )
        )

        assertTrue(CaptionProjectBuilder.canSplitBeforeWord(project.cards.single(), 3))
        val split = CaptionProjectBuilder.splitCardBeforeWord(project, cardIndex = 0, wordIndex = 3)

        assertEquals(listOf("现在", "缩编"), split.cards.map(CaptionCard::displayText))
        assertEquals("缩", split.cards[1].words.first().text)
        assertTrue(split.cards[0].words.last().removed)
    }

    @Test
    fun `merge with next retains the complete interval`() {
        val project = CaptionProject(
            sourceFileName = "video.mp4",
            cards = listOf(
                CaptionCard(4, listOf(CaptionWord(0, "Hello", 0, 600))),
                CaptionCard(7, listOf(CaptionWord(0, "world", 900, 1_500)))
            )
        )

        val merged = CaptionProjectBuilder.mergeCardWithNext(project, cardIndex = 0)

        assertEquals(1, merged.cards.size)
        assertEquals("Hello world", merged.cards.single().displayText)
        assertEquals(0L, merged.cards.single().startMs)
        assertEquals(1_500L, merged.cards.single().endMs)
        assertEquals(4, merged.cards.single().id)
    }

    @Test
    fun `automatic split favors a chinese semantic boundary near the middle`() {
        val card = projectFrom("先介绍功能然后我们开始演示").cards
            .reduce { first, second ->
                first.copy(words = first.words + second.words)
            }

        assertEquals(5, CaptionProjectBuilder.suggestedSplitOffset(card))
    }

    @Test
    fun `merge with previous keeps the previous card identity and full interval`() {
        val project = CaptionProject(
            sourceFileName = "video.mp4",
            cards = listOf(
                CaptionCard(4, listOf(CaptionWord(0, "上一句", 0, 600))),
                CaptionCard(7, listOf(CaptionWord(0, "当前句", 900, 1_500)))
            )
        )

        val merged = CaptionProjectBuilder.mergeCardWithPrevious(project, cardIndex = 1)

        assertEquals(1, merged.cards.size)
        assertEquals(4, merged.cards.single().id)
        assertEquals(0L, merged.cards.single().startMs)
        assertEquals(1_500L, merged.cards.single().endMs)
        assertEquals("上一句当前句", merged.cards.single().displayText)
    }

    @Test
    fun `video speed stays at original outside supported accelerated range`() {
        assertEquals(DEFAULT_VIDEO_SPEED, normalizedVideoSpeed(Float.NaN))
        assertEquals(DEFAULT_VIDEO_SPEED, normalizedVideoSpeed(1.05f))
        assertEquals(MIN_VIDEO_SPEED, normalizedVideoSpeed(MIN_VIDEO_SPEED))
        assertEquals(MAX_VIDEO_SPEED, normalizedVideoSpeed(1.8f))
    }

    @Test
    fun `materialized processing scales caption time and prevents repeated processing`() {
        val project = CaptionProject(
            sourceFileName = "source.mp4",
            cards = listOf(
                CaptionCard(
                    0,
                    listOf(
                        CaptionWord(0, "重", 130, 650),
                        CaptionWord(1, "点", 650, 1_300)
                    )
                )
            ),
            noiseReductionEnabled = true,
            videoSpeed = 1.30f
        )

        val output = project.materializeVideoProcessing("output.mp4")

        assertEquals("output.mp4", output.sourceFileName)
        assertEquals(100L, output.cards.single().startMs)
        assertEquals(1_000L, output.cards.single().endMs)
        assertFalse(output.noiseReductionEnabled)
        assertEquals(DEFAULT_VIDEO_SPEED, output.videoSpeed)
        assertEquals(3, output.version)
    }

    private fun projectFrom(text: String): CaptionProject = CaptionProjectBuilder.build(
        sourceFileName = "video.mp4",
        segments = listOf(
            TimedTranscriptSegment(
                index = 0,
                tokens = listOf(TimedTranscriptToken(text, 0, 2_400))
            )
        )
    )
}
