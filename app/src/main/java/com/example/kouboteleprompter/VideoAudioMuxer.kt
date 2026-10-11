package com.example.kouboteleprompter

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Encodes the app-owned mono PCM capture and attaches it to CameraX's video-only MP4. */
internal object VideoAudioMuxer {
    private const val TAG = "KouboSpeech"
    private const val SAMPLE_RATE = 16_000
    private const val CHANNEL_COUNT = 1
    private const val PCM_BYTES_PER_SAMPLE = 2
    private const val AAC_BIT_RATE = 64_000
    private const val CODEC_TIMEOUT_US = 10_000L

    fun mux(
        videoOnlyFile: File,
        pcmFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit = {}
    ): Result<Long> = runCatching {
        require(videoOnlyFile.isFile && videoOnlyFile.length() > 0) { "Video track is empty" }
        require(pcmFile.isFile && pcmFile.length() > 0) { "PCM audio is empty" }

        val encodedAudio = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.audio.m4a")
        onProgress(0)
        encodePcmToAac(pcmFile, encodedAudio) { percent -> onProgress(percent * 20 / 100) }
        val durationMs = combineTracks(videoOnlyFile, encodedAudio, outputFile) { percent ->
            onProgress(20 + percent * 80 / 100)
        }
        check(outputFile.isFile && outputFile.length() > 0) { "Muxed video is empty" }
        encodedAudio.delete()
        onProgress(100)
        Log.i(
            TAG,
            "mux_complete durationMs=$durationMs videoBytes=${videoOnlyFile.length()} " +
                "pcmBytes=${pcmFile.length()} outputBytes=${outputFile.length()}"
        )
        durationMs
    }.onFailure { error ->
        outputFile.delete()
        File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.audio.m4a").delete()
        Log.e(
            TAG,
            "mux_failed video=${videoOnlyFile.absolutePath} pcm=${pcmFile.absolutePath}",
            error
        )
    }

    private fun encodePcmToAac(
        pcmFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        outputFile.delete()
        val format = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            SAMPLE_RATE,
            CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AAC_BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            FileInputStream(pcmFile).use { input ->
                val info = MediaCodec.BufferInfo()
                var queuedPcmBytes = 0L
                var inputDone = false
                var outputDone = false
                var audioTrack = -1
                var lastProgress = -1
                val pcmBytes = pcmFile.length().coerceAtLeast(1L)
                val readBuffer = ByteArray(16 * 1024)

                while (!outputDone) {
                    if (!inputDone) {
                        val inputIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US)
                        if (inputIndex >= 0) {
                            val inputBuffer = checkNotNull(codec.getInputBuffer(inputIndex)).apply { clear() }
                            val count = input.read(
                                readBuffer,
                                0,
                                minOf(readBuffer.size, inputBuffer.remaining())
                            )
                            val ptsUs = queuedPcmBytes * 1_000_000L /
                                (SAMPLE_RATE * CHANNEL_COUNT * PCM_BYTES_PER_SAMPLE)
                            if (count < 0) {
                                codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    ptsUs,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                inputBuffer.put(readBuffer, 0, count)
                                codec.queueInputBuffer(inputIndex, 0, count, ptsUs, 0)
                                queuedPcmBytes += count
                                val percent = (queuedPcmBytes * 100L / pcmBytes)
                                    .toInt()
                                    .coerceIn(0, 100)
                                if (percent != lastProgress) {
                                    lastProgress = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }

                    when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted) { "AAC output format changed twice" }
                            muxer = MediaMuxer(
                                outputFile.absolutePath,
                                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                            )
                            audioTrack = checkNotNull(muxer).addTrack(codec.outputFormat)
                            checkNotNull(muxer).start()
                            muxerStarted = true
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = checkNotNull(codec.getOutputBuffer(outputIndex))
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                            if (info.size > 0) {
                                check(muxerStarted) { "AAC data arrived before output format" }
                                outputBuffer.position(info.offset)
                                outputBuffer.limit(info.offset + info.size)
                                checkNotNull(muxer).writeSampleData(audioTrack, outputBuffer, info)
                            }
                            outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            codec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
            onProgress(100)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    private fun combineTracks(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit
    ): Long {
        outputFile.delete()
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            val videoInputTrack = videoExtractor.findTrack("video/")
            val audioInputTrack = audioExtractor.findTrack("audio/")
            check(videoInputTrack >= 0) { "No video track" }
            check(audioInputTrack >= 0) { "No audio track" }

            val videoFormat = videoExtractor.getTrackFormat(videoInputTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioInputTrack)
            val durationUs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                videoFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                Long.MAX_VALUE
            }

            val muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )
            try {
                if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                    muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
                }
                val videoOutputTrack = muxer.addTrack(videoFormat)
                val audioOutputTrack = muxer.addTrack(audioFormat)
                muxer.start()
                val totalBytes = (videoFile.length() + audioFile.length()).coerceAtLeast(1L)
                val videoBytes = copyTrack(
                    videoExtractor,
                    videoInputTrack,
                    muxer,
                    videoOutputTrack,
                    durationUs,
                    copiedBeforeTrack = 0L,
                    totalBytes = totalBytes,
                    onProgress = onProgress
                )
                copyTrack(
                    audioExtractor,
                    audioInputTrack,
                    muxer,
                    audioOutputTrack,
                    durationUs,
                    copiedBeforeTrack = videoBytes,
                    totalBytes = totalBytes,
                    onProgress = onProgress
                )
                muxer.stop()
            } finally {
                muxer.release()
            }
            if (durationUs == Long.MAX_VALUE) 0L else durationUs / 1_000L
        } finally {
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun MediaExtractor.findTrack(mimePrefix: String): Int {
        for (index in 0 until trackCount) {
            val mime = getTrackFormat(index).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return index
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        maxPresentationTimeUs: Long,
        copiedBeforeTrack: Long,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ): Long {
        extractor.selectTrack(inputTrack)
        val buffer = ByteBuffer.allocateDirect(4 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        var copiedTrackBytes = 0L
        var lastProgress = -1
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val sampleTimeUs = extractor.sampleTime
            if (sampleTimeUs < 0 || sampleTimeUs > maxPresentationTimeUs) break
            val extractorFlags = extractor.sampleFlags
            var codecFlags = 0
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
            }
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
            info.set(0, size, sampleTimeUs, codecFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            copiedTrackBytes += size
            val percent = ((copiedBeforeTrack + copiedTrackBytes) * 100L / totalBytes)
                .toInt()
                .coerceIn(0, 100)
            if (percent != lastProgress) {
                lastProgress = percent
                onProgress(percent)
            }
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
        return copiedTrackBytes
    }
}
