package com.example.kouboteleprompter.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentTitleTest {
    @Test
    fun `generates a concise title from a conversational opening`() {
        assertEquals(
            "如何在三分钟内做好口播视频",
            generateContentTitle("大家好，今天想和大家聊聊如何在三分钟内做好口播视频。下面分享三个方法。")
        )
    }

    @Test
    fun `uses an explicit heading before body text`() {
        assertEquals(
            "新手选麦克风避坑指南",
            generateContentTitle("# 标题：新手选麦克风避坑指南\n买麦克风时不要只看价格。")
        )
        assertEquals("AI", generateContentTitle("标题：AI\n今天聊聊大模型的实际应用。"))
    }

    @Test
    fun `limits long generated titles`() {
        val title = generateContentTitle("这是一个非常非常长而且包含很多细节的口播内容标题用来验证长度限制。")
        assertTrue(title.length <= 24)
        assertFalse(title.endsWith("，"))
    }

    @Test
    fun `manual title wins but legacy default is replaced`() {
        assertEquals("我的自定义标题", resolveScriptTitle(" 我的自定义标题 ", "正文主题"))
        assertEquals("正文主题", resolveScriptTitle("2026-08-23 14:30 台本", "正文主题。更多内容"))
        assertTrue(isAutomaticScriptTitle("正文主题", "正文主题。更多内容"))
        assertFalse(isAutomaticScriptTitle("我的自定义标题", "正文主题。更多内容"))
    }

    @Test
    fun `video name starts with a safe script title`() {
        assertEquals("选麦克风_入门_指南_1234567890123", videoBaseName("选麦克风/入门:指南", 1234567890123))
        assertEquals("选麦克风 入门 指南", videoDisplayTitle("选麦克风_入门_指南_1234567890123.mp4"))
    }

    @Test
    fun `video date is read from its timestamp`() {
        assertEquals(
            "2023-11-15 06:13",
            videoRecordedAt("口播技巧_1700000000000.mp4", 0, ZoneId.of("Asia/Shanghai"))
        )
    }
}
