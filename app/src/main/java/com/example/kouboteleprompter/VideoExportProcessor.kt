package com.example.kouboteleprompter

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File

/** Applies export-only visual improvements after audio and video have been muxed. */
@UnstableApi
internal object VideoExportProcessor {
    fun applyBeauty(
        context: Context,
        inputFile: File,
        outputFile: File,
        progress: (Int) -> Unit = {},
        completed: (Result<Long>) -> Unit
    ) {
        outputFile.delete()
        val effects = Effects(
            /* audioProcessors = */ emptyList(),
            /* videoEffects = */ listOf<Effect>(NaturalBeautyEffect)
        )
        val editedMediaItem = EditedMediaItem.Builder(
            MediaItem.fromUri(Uri.fromFile(inputFile))
        ).setEffects(effects).build()

        val handler = Handler(Looper.getMainLooper())
        var progressRunnable: Runnable? = null
        var finished = false
        val encoderFactory = DefaultEncoderFactory.Builder(context.applicationContext)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(TARGET_VIDEO_BIT_RATE)
                    .build()
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
                    inputFile.delete()
                    val durationMs = exportResult.durationMs.takeUnless { it == C.TIME_UNSET } ?: 0L
                    Log.i(TAG, "beauty_export_complete durationMs=$durationMs bytes=${outputFile.length()}")
                    completed(Result.success(durationMs))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    finished = true
                    progressRunnable?.let(handler::removeCallbacks)
                    outputFile.delete()
                    Log.e(TAG, "beauty_export_failed input=${inputFile.absolutePath}", exportException)
                    completed(Result.failure(exportException))
                }
            })
            .build()

        runCatching {
            transformer.start(editedMediaItem, outputFile.absolutePath)
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
            Log.e(TAG, "beauty_export_start_failed input=${inputFile.absolutePath}", error)
            completed(Result.failure(error))
        }
    }

    private const val TAG = "KouboExport"
    private const val TARGET_VIDEO_BIT_RATE = 6_000_000
    private const val PROGRESS_INTERVAL_MS = 400L
}
