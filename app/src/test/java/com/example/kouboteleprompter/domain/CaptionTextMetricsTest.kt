package com.example.kouboteleprompter.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionTextMetricsTest {
    @Test
    fun `default setting maps to the export renderer default pixels`() {
        assertEquals(56, CaptionTextMetrics.exportTextSizePx(DEFAULT_CAPTION_FONT_SIZE_SP))
    }

    @Test
    fun `preview applies export overlay scale and video fit scale`() {
        val previewPixels = CaptionTextMetrics.previewTextSizePx(
            fontSizeSp = DEFAULT_CAPTION_FONT_SIZE_SP,
            videoWidthPx = 1080f,
            videoHeightPx = 1920f,
            viewportWidthPx = 360f,
            viewportHeightPx = 640f
        )

        assertEquals(56f * .88f / 3f, previewPixels, .001f)
    }

    @Test
    fun `preview uses height when a portrait video is letterboxed horizontally`() {
        val previewPixels = CaptionTextMetrics.previewTextSizePx(
            fontSizeSp = 18f,
            videoWidthPx = 1080f,
            videoHeightPx = 1920f,
            viewportWidthPx = 720f,
            viewportHeightPx = 800f
        )

        val expectedFitScale = 800f / 1920f
        assertEquals(
            CaptionTextMetrics.exportTextSizePx(18f) * .88f * expectedFitScale,
            previewPixels,
            .001f
        )
    }

    @Test
    fun `invalid dimensions do not produce a misleading preview size`() {
        assertEquals(
            0f,
            CaptionTextMetrics.previewTextSizePx(26f, 0f, 1920f, 360f, 640f),
            0f
        )
    }
}
