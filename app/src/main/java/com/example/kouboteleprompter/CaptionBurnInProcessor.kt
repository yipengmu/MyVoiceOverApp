package com.example.kouboteleprompter

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.CharacterStyle
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UpdateAppearance
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.example.kouboteleprompter.domain.CaptionCard
import com.example.kouboteleprompter.domain.CaptionProject
import com.example.kouboteleprompter.domain.CaptionStylePreset
import com.example.kouboteleprompter.domain.CaptionTextMetrics
import com.example.kouboteleprompter.domain.CaptionTimelineEditor
import com.example.kouboteleprompter.domain.DEFAULT_VIDEO_SPEED
import com.example.kouboteleprompter.domain.materializeVideoProcessing
import com.example.kouboteleprompter.domain.normalizedVideoSpeed
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
internal object CaptionBurnInProcessor {
    fun export(
        context: Context,
        inputFile: File,
        project: CaptionProject,
        sourceDurationMs: Long,
        progress: (Int) -> Unit = {},
        completed: (Result<File>) -> Unit
    ) {
        val outputFile = uniqueOutputFile(inputFile)
        val decision = CaptionTimelineEditor.decide(project, sourceDurationMs)
        val speed = normalizedVideoSpeed(project.videoSpeed)
        val outputProject = decision.outputProject.materializeVideoProcessing(outputFile.name)
        val overlay = TimedCaptionOverlay(outputProject)
        val audioProcessors = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
        val videoEffects = mutableListOf<Effect>()
        if (project.noiseReductionEnabled) {
            // Preview uses the same order: voice enhancement first, pitch-preserving speed second.
            audioProcessors += ToInt16PcmAudioProcessor()
            audioProcessors += VoiceEnhancementAudioProcessor()
        }
        if (speed != DEFAULT_VIDEO_SPEED) {
            val speedEffects = Effects.createExperimentalSpeedChangingEffect(
                ConstantSpeedProvider(speed)
            )
            audioProcessors += speedEffects.first
            videoEffects += speedEffects.second
        }
        // The speed effect comes first, so this overlay reads the materialized output timeline.
        videoEffects += OverlayEffect(listOf(overlay))
        val effects = Effects(
            /* audioProcessors = */ audioProcessors,
            /* videoEffects = */ videoEffects
        )
        val sourceUri = Uri.fromFile(inputFile)
        val editedMediaItems = decision.keptRanges.map { range ->
            val mediaItem = MediaItem.Builder()
                .setUri(sourceUri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(range.startMs)
                        .setEndPositionMs(range.endMs)
                        .build()
                )
                .build()
            EditedMediaItem.Builder(mediaItem).build()
        }
        val sequence = EditedMediaItemSequence.Builder(editedMediaItems).build()
        val composition = Composition.Builder(sequence)
            .setEffects(effects)
            .build()
        val handler = Handler(Looper.getMainLooper())
        var progressRunnable: Runnable? = null
        var finished = false
        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder().setBitrate(TARGET_VIDEO_BIT_RATE).build()
            )
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(context.applicationContext)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setEncoderFactory(encoderFactory)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                    progressRunnable?.let(handler::removeCallbacks)
                    progress(100)
                    val valid = outputFile.isFile && outputFile.length() > 0L
                    if (valid) {
                        CaptionProjectStore.save(outputFile, outputProject)
                        Log.i(
                            TAG,
                            "caption_export_complete durationMs=${exportResult.durationMs.takeUnless { it == C.TIME_UNSET }} " +
                                "bytes=${outputFile.length()}"
                        )
                        completed(Result.success(outputFile))
                    } else {
                        outputFile.delete()
                        completed(Result.failure(IllegalStateException("Caption export is empty")))
                    }
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished = true
                    progressRunnable?.let(handler::removeCallbacks)
                    outputFile.delete()
                    Log.e(TAG, "caption_export_failed input=${inputFile.absolutePath}", exportException)
                    completed(Result.failure(exportException))
                }
            })
            .build()

        runCatching {
            transformer.start(composition, outputFile.absolutePath)
            val holder = ProgressHolder()
            progressRunnable = object : Runnable {
                override fun run() {
                    if (finished) return
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        progress(holder.progress.coerceIn(0, 99))
                    }
                    handler.postDelayed(this, PROGRESS_INTERVAL_MS)
                }
            }.also {
                progress(0)
                handler.post(it)
            }
        }.onFailure { error ->
            finished = true
            progressRunnable?.let(handler::removeCallbacks)
            outputFile.delete()
            completed(Result.failure(error))
        }
    }

    private fun uniqueOutputFile(inputFile: File): File {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val base = inputFile.nameWithoutExtension
            .removeSuffix("-字幕")
            .take(80)
        return File(inputFile.parentFile, "$base-字幕-$timestamp.mp4")
    }

    private class ConstantSpeedProvider(
        private val speed: Float
    ) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed

        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    private class TimedCaptionOverlay(
        private val project: CaptionProject
    ) : TextOverlay() {
        private val settings = OverlaySettings.Builder()
            .setBackgroundFrameAnchor(
                project.captionPositionX,
                -project.captionPositionY
            )
            .setOverlayFrameAnchor(0f, -1f)
            .setScale(
                CaptionTextMetrics.EXPORT_OVERLAY_SCALE,
                CaptionTextMetrics.EXPORT_OVERLAY_SCALE
            )
            .build()

        override fun getText(presentationTimeUs: Long): SpannableString {
            val timeMs = presentationTimeUs / 1_000L
            val card = project.cards.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }
                ?: return invisiblePlaceholderText()
            return styledText(card, project.style, project.fontSizeSp)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings = settings
    }

    private fun styledText(
        card: CaptionCard,
        style: CaptionStylePreset,
        fontSizeSp: Float
    ): SpannableString {
        val text = StringBuilder()
        val emphasisRanges = mutableListOf<IntRange>()
        var previousLatin = false
        card.visibleWords.forEach { word ->
            val currentLatin = word.text.firstOrNull()?.let {
                it.code < 128 && it.isLetterOrDigit()
            } == true
            if (text.isNotEmpty() && previousLatin && currentLatin) text.append(' ')
            val start = text.length
            text.append(word.text)
            if (word.emphasized) emphasisRanges += start until text.length
            previousLatin = currentLatin
        }
        val spannable = SpannableString(text.toString())
        if (spannable.isEmpty()) return invisiblePlaceholderText()

        val emphasisColor = when (style) {
            CaptionStylePreset.BOLD_ORANGE -> Color.rgb(255, 183, 102)
            CaptionStylePreset.FRESH_GREEN -> Color.rgb(102, 242, 154)
            CaptionStylePreset.CLEAN_WHITE -> Color.rgb(255, 215, 94)
        }
        spannable.setSpan(
            ForegroundColorSpan(Color.WHITE),
            0,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            AbsoluteSizeSpan(
                CaptionTextMetrics.exportTextSizePx(fontSizeSp)
            ),
            0,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            StyleSpan(Typeface.BOLD),
            0,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannable.setSpan(
            CaptionShadowSpan,
            0,
            spannable.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        if (style == CaptionStylePreset.CLEAN_WHITE) {
            spannable.setSpan(
                BackgroundColorSpan(Color.argb(150, 0, 0, 0)),
                0,
                spannable.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        emphasisRanges.forEach { range ->
            spannable.setSpan(
                ForegroundColorSpan(emphasisColor),
                range.first,
                range.last + 1,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        return spannable
    }

    /**
     * Media3 1.5.1's TextOverlay attempts to create a bitmap directly from the measured text.
     * An empty Spannable has a width of zero and crashes export during caption gaps. Media3 first
     * measures text without applying spans, so use a measurable glyph and then make it transparent.
     * This keeps the backing bitmap valid without drawing anything into the video.
     */
    private fun invisiblePlaceholderText(): SpannableString =
        SpannableString(".").apply {
            setSpan(
                ForegroundColorSpan(Color.TRANSPARENT),
                0,
                length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

    private object CaptionShadowSpan : CharacterStyle(), UpdateAppearance {
        override fun updateDrawState(textPaint: TextPaint) {
            textPaint.setShadowLayer(7f, 0f, 2f, Color.BLACK)
        }
    }

    private const val TAG = "KouboCaptions"
    private const val TARGET_VIDEO_BIT_RATE = 6_000_000
    private const val PROGRESS_INTERVAL_MS = 400L
}
