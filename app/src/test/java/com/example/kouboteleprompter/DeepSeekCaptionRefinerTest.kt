package com.example.kouboteleprompter

import com.example.kouboteleprompter.domain.TimedTranscriptToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeepSeekCaptionRefinerTest {
    private val source = listOf(
        TimedTranscriptToken("深度", 100, 400, .8f),
        TimedTranscriptToken("求索", 400, 800, .7f),
        TimedTranscriptToken("接口", 900, 1_200, .9f)
    )

    @Test
    fun `correction preserves token timing and applies semantic punctuation`() {
        val result = CaptionCorrectionMapper.apply(
            source,
            """{"tokens":[
                {"id":0,"text":"深度","breakAfter":"none"},
                {"id":1,"text":"求索","breakAfter":"comma"},
                {"id":2,"text":"接口","breakAfter":"sentence"}
            ]}""".trimIndent()
        ).single().tokens

        assertEquals(listOf("深度", "求索，", "接口。"), result.map { it.text })
        assertEquals(source.map { it.startMs to it.endMs }, result.map { it.startMs to it.endMs })
        assertEquals(source.map { it.confidence }, result.map { it.confidence })
    }

    @Test
    fun `changed token count is rejected for safe fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptionCorrectionMapper.apply(
                source,
                """{"tokens":[{"id":0,"text":"DeepSeek","breakAfter":"sentence"}]}"""
            )
        }
    }

    @Test
    fun `large rewrite is rejected for safe fallback`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptionCorrectionMapper.apply(
                source,
                """{"tokens":[
                    {"id":0,"text":"完全","breakAfter":"none"},
                    {"id":1,"text":"不同","breakAfter":"none"},
                    {"id":2,"text":"内容","breakAfter":"sentence"}
                ]}""".trimIndent()
            )
        }
    }

    @Test
    fun `punctuation inside corrected text is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            CaptionCorrectionMapper.apply(
                source,
                """{"tokens":[
                    {"id":0,"text":"深度，","breakAfter":"none"},
                    {"id":1,"text":"求索","breakAfter":"none"},
                    {"id":2,"text":"接口","breakAfter":"sentence"}
                ]}""".trimIndent()
            )
        }
    }
}
