package com.example.kouboteleprompter.domain

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The shared caption size model used by both export and editor preview.
 *
 * [fontSizeSp] is kept as the persisted setting name for project-file compatibility. It is a
 * resolution-independent caption setting, not a request to render that many Android screen sp.
 * Export renders it into video pixels; preview then scales those pixels by the same fit transform
 * used to display the source video.
 */
object CaptionTextMetrics {
    const val DEFAULT_EXPORT_TEXT_SIZE_PX = 56f
    const val EXPORT_OVERLAY_SCALE = .88f

    fun exportTextSizePx(fontSizeSp: Float): Int =
        (
            DEFAULT_EXPORT_TEXT_SIZE_PX *
                fontSizeSp.coerceIn(MIN_CAPTION_FONT_SIZE_SP, MAX_CAPTION_FONT_SIZE_SP) /
                DEFAULT_CAPTION_FONT_SIZE_SP
            )
            .roundToInt()
            .coerceAtLeast(1)

    fun previewTextSizePx(
        fontSizeSp: Float,
        videoWidthPx: Float,
        videoHeightPx: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): Float {
        if (
            videoWidthPx <= 0f || videoHeightPx <= 0f ||
            viewportWidthPx <= 0f || viewportHeightPx <= 0f
        ) {
            return 0f
        }
        val videoFitScale = min(
            viewportWidthPx / videoWidthPx,
            viewportHeightPx / videoHeightPx
        )
        return exportTextSizePx(fontSizeSp) * EXPORT_OVERLAY_SCALE * videoFitScale
    }
}
