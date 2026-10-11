package com.example.kouboteleprompter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadingProgressTest {
    @Test
    fun exactChinesePhraseAdvancesCursor() {
        assertEquals(6, ReadingProgress.consumedCharacters("好的家庭教育", "好的家庭教育"))
    }

    @Test
    fun punctuationAndSpacesDoNotBlockAlignment() {
        assertEquals(6, ReadingProgress.consumedCharacters("你好， 世界", "你好世界"))
    }

    @Test
    fun oneCharacterSubstitutionIsTolerated() {
        assertEquals(6, ReadingProgress.consumedCharacters("好的家庭教育", "号的家庭教育"))
    }

    @Test
    fun shortPhraseWithOneHomophoneSubstitutionAdvancesImmediately() {
        assertEquals(3, ReadingProgress.consumedCharacters("立即读", "立即毒"))
    }

    @Test
    fun currentPhraseToleratesTwoLikelyHomophoneSubstitutions() {
        assertEquals(4, ReadingProgress.consumedCharacters("知识分享", "芝士分享"))
    }

    @Test
    fun oneCommonCharacterCannotJumpAhead() {
        assertEquals(0, ReadingProgress.consumedCharacters("今天的天气很好", "好"))
    }

    @Test
    fun nextRecognitionStartsAtExistingProgress() {
        assertEquals(7, ReadingProgress.consumedCharacters("第一段，第二段", "第二段", from = 4))
    }

    @Test
    fun cursorSkipsPunctuationAndPointsAtNextSpokenCharacter() {
        assertEquals(3, ReadingProgress.consumedCharacters("你好，世界", "你好"))
    }

    @Test
    fun spokenChineseDigitsMatchPlateDigits() {
        assertEquals(9, ReadingProgress.consumedCharacters("浙AED6319，车牌报备下哈", "浙 A E D 六三幺九"))
    }

    @Test
    fun aMissedPlateDoesNotPinTheFollowingPhrase() {
        assertEquals(16, ReadingProgress.consumedCharacters("浙AED6319，车牌报备下哈，一会朋友开车来", "车牌报备下哈"))
    }

    @Test
    fun aWholeMissedClauseDoesNotDiluteTheFollowingStablePhrase() {
        assertEquals(
            27,
            ReadingProgress.consumedCharacters(
                "浙AED6319，\n\n车牌报备下哈，\n\n一会朋友开车来",
                "一会儿朋友开车过来当"
            )
        )
    }

    @Test
    fun shortCommonPhraseCannotJumpAcrossAClause() {
        assertEquals(0, ReadingProgress.consumedCharacters("请先介绍产品，然后说一会朋友开车来", "一会"))
    }

    @Test
    fun fuzzyShortPhraseCannotJumpForward() {
        assertEquals(0, ReadingProgress.consumedCharacters("先介绍产品，然后知识分享", "芝士分享"))
    }

    @Test
    fun symbolsAndFullWidthCharactersAreNormalized() {
        assertEquals("aed6319", ReadingProgress.normalize("ＡＥＤ-６３１９"))
        assertEquals(7, ReadingProgress.consumedCharacters("ＡＥＤ６３１９", "AED6319"))
    }

    @Test
    fun completionUsesReadableCharactersOnly() {
        assertEquals(50, ReadingProgress.completionPercent("你，好", cursor = 1))
        assertEquals(100, ReadingProgress.completionPercent("你，好", cursor = 3))
    }

    @Test
    fun scriptHotwordsUseRecognizerTokenFormatAndSpokenDigits() {
        assertEquals(
            "浙 A E D 六 三 一 九/车 牌 报 备 下 哈/一 会 朋 友 开 车 来",
            ReadingProgress.hotwords("浙AED6319，车牌报备下哈\n一会朋友开车来")
        )
    }
}
