package com.example.kouboteleprompter

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.example.kouboteleprompter.domain.TimedTranscriptToken
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

internal data class OfflineRecognitionResult(
    val text: String,
    val segment: Int,
    val isFinal: Boolean,
    val tokens: List<TimedTranscriptToken> = emptyList()
)

/**
 * App-owned, fully offline streaming ASR.
 *
 * Keeping microphone capture and decoding in this process avoids depending on an OEM
 * RecognitionService. This is the only microphone owner: the same PCM stream feeds recognition
 * and the recorded video's AAC track, so CameraX never competes for microphone access.
 */
internal class OfflineSpeechEngine(
    context: Context,
    private val onStatus: (String) -> Unit,
    private val onResult: (OfflineRecognitionResult) -> Unit,
    private val onAudioLevel: (Float) -> Unit,
    private val onSilenced: (Boolean) -> Unit
) : Closeable {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var preparationStarted = false

    @Volatile
    private var preparationFinished = false

    @Volatile
    private var running = false

    @Volatile
    private var closed = false

    private var recognizer: OnlineRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var captureThread: Thread? = null

    /** True once model preparation has either succeeded or safely degraded to audio-only mode. */
    val isPrepared: Boolean get() = preparationFinished

    @RequiresApi(Build.VERSION_CODES.Q)
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            val sessionId = audioRecord?.audioSessionId ?: return
            val ownConfig = configs.firstOrNull { it.clientAudioSessionId == sessionId } ?: return
            val silenced = ownConfig.isClientSilenced
            Log.i(
                TAG,
                "offline_audio_config session=$sessionId source=${ownConfig.clientAudioSource} silenced=$silenced"
            )
            dispatch { onSilenced(silenced) }
            if (silenced) dispatch { onStatus("离线识音音频被系统静音") }
        }
    }

    fun prepare() {
        synchronized(lock) {
            if (preparationStarted || closed) return
            preparationStarted = true
        }
        dispatch { onStatus("正在加载离线识音模型…") }
        thread(start = true, isDaemon = true, name = "KouboAsrPrepare") {
            val startedAt = System.currentTimeMillis()
            Log.i(TAG, "offline_model_prepare_started")
            val created = runCatching {
                val model = OfflineModelAssets.install(appContext)
                OnlineRecognizer(
                    assetManager = null,
                    config = recognizerConfig(model)
                )
            }.onFailure { Log.e(TAG, "offline_model_prepare_failed", it) }.getOrNull()

            synchronized(lock) {
                if (closed) {
                    created?.release()
                } else {
                    recognizer = created
                    preparationFinished = true
                }
            }
            if (created != null && !closed) {
                Log.i(TAG, "offline_model_ready elapsedMs=${System.currentTimeMillis() - startedAt}")
                dispatch { onStatus("离线识音已就绪") }
            } else if (!closed) {
                dispatch { onStatus("识音暂不可用，仍可正常录制") }
            }
        }
    }

    @MainThread
    @SuppressLint("MissingPermission")
    fun start(pcmOutputFile: File, noiseSuppressionEnabled: Boolean): Boolean {
        if (running) return true
        // A failed worker can finish before the UI has a chance to call stop(). Reclaim its
        // AudioRecord here so a later recording never competes with a stale microphone session.
        if (audioRecord != null && captureThread?.isAlive != true) stop()
        if (captureThread?.isAlive == true) {
            dispatch { onStatus("上一段录音仍在收尾，请稍后重试") }
            return false
        }
        val activeRecognizer = synchronized(lock) { recognizer }
        if (!preparationFinished) {
            dispatch { onStatus("离线识音模型仍在加载，请稍后重试") }
            return false
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferBytes <= 0) {
            Log.e(TAG, "offline_audio_invalid_min_buffer value=$minBufferBytes")
            dispatch { onStatus("无法创建离线识音音频通道") }
            return false
        }

        val record = runCatching {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufferBytes * 2, SAMPLES_PER_CHUNK * 4))
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        setPrivacySensitive(false)
                    }
                }
                .build()
        }.onFailure { Log.e(TAG, "offline_audio_create_failed", it) }.getOrNull() ?: return false

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "offline_audio_not_initialized state=${record.state}")
            record.release()
            dispatch { onStatus("离线识音麦克风初始化失败") }
            return false
        }

        val activeNoiseSuppressor = if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
            runCatching {
                NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
            }.onFailure { Log.w(TAG, "offline_noise_suppressor_create_failed", it) }.getOrNull()
        } else {
            null
        }
        noiseSuppressor = activeNoiseSuppressor
        Log.i(
            TAG,
            "offline_noise_suppression requested=$noiseSuppressionEnabled " +
                "available=${NoiseSuppressor.isAvailable()} active=${activeNoiseSuppressor?.enabled == true}"
        )

        // Native contextual hotwords currently have open Android crash reports. Application-side
        // script alignment already handles names and skipped phrases, so use the stable basic stream.
        val stream = activeRecognizer?.let { recognizer ->
            runCatching { recognizer.createStream("") }
                .onFailure { Log.e(TAG, "offline_stream_create_failed", it) }
                .getOrNull()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            record.registerAudioRecordingCallback(appContext.mainExecutor, recordingCallback)
        }
        val started = runCatching {
            pcmOutputFile.parentFile?.mkdirs()
            record.startRecording()
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        }.onFailure { Log.e(TAG, "offline_audio_start_failed", it) }.getOrDefault(false)
        if (!started) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { record.unregisterAudioRecordingCallback(recordingCallback) }
            }
            stream?.release()
            activeNoiseSuppressor?.release()
            noiseSuppressor = null
            record.release()
            dispatch { onStatus("离线识音麦克风启动失败") }
            return false
        }

        audioRecord = record
        running = true
        onSilenced(false)
        val pcmOutput = runCatching { FileOutputStream(pcmOutputFile, false) }
            .onFailure { Log.e(TAG, "offline_pcm_output_failed file=${pcmOutputFile.absolutePath}", it) }
            .getOrNull()
        if (pcmOutput == null) {
            running = false
            runCatching { record.stop() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { record.unregisterAudioRecordingCallback(recordingCallback) }
            }
            stream?.release()
            activeNoiseSuppressor?.release()
            noiseSuppressor = null
            record.release()
            audioRecord = null
            dispatch { onStatus("无法创建录音文件") }
            return false
        }
        val worker = thread(start = false, isDaemon = true, name = "KouboOfflineAsr") {
            processAudio(activeRecognizer, stream, record, pcmOutput)
        }
        captureThread = worker
        worker.start()
        Log.i(
            TAG,
            "offline_start session=${record.audioSessionId} sampleRate=$SAMPLE_RATE " +
                "pcm=${pcmOutputFile.absolutePath}"
        )
        dispatch {
            onStatus(if (stream != null) "请开始朗读" else "识音不可用，录音仍在进行")
        }
        return true
    }

    @MainThread
    fun stop() {
        val record = audioRecord ?: return
        val worker = captureThread
        running = false
        runCatching { record.stop() }
        runCatching { worker?.join(STOP_JOIN_TIMEOUT_MS) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { record.unregisterAudioRecordingCallback(recordingCallback) }
        }
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
        record.release()
        audioRecord = null
        if (worker?.isAlive != true && captureThread === worker) captureThread = null
        onSilenced(false)
        Log.i(TAG, "offline_stopped")
    }

    override fun close() {
        closed = true
        stop()
        val worker = captureThread
        if (worker?.isAlive == true) {
            Log.w(TAG, "offline_close_waiting_for_decoder")
            thread(start = true, isDaemon = true, name = "KouboAsrClose") {
                runCatching { worker.join() }
                releaseRecognizer()
                if (captureThread === worker) captureThread = null
            }
        } else {
            releaseRecognizer()
        }
    }

    private fun releaseRecognizer() {
        synchronized(lock) {
            recognizer?.release()
            recognizer = null
            preparationFinished = false
        }
    }

    private fun processAudio(
        activeRecognizer: OnlineRecognizer?,
        initialStream: OnlineStream?,
        record: AudioRecord,
        pcmOutput: FileOutputStream
    ) {
        val pcm = ShortArray(SAMPLES_PER_CHUNK)
        val samples = FloatArray(SAMPLES_PER_CHUNK)
        val pcmBytes = ByteBuffer.allocate(SAMPLES_PER_CHUNK * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
        var segment = 0
        var lastText = ""
        var lastLevelAt = 0L
        var stream = initialStream
        var processedSamples = 0L
        var streamStartedAtMs = 0L
        try {
            while (running && captureThread === Thread.currentThread()) {
                val count = record.read(pcm, 0, pcm.size, AudioRecord.READ_BLOCKING)
                if (count <= 0) {
                    if (running) Log.w(TAG, "offline_audio_read_failed code=$count")
                    continue
                }

                pcmBytes.clear()
                for (index in 0 until count) pcmBytes.putShort(pcm[index])
                pcmOutput.write(pcmBytes.array(), 0, count * Short.SIZE_BYTES)
                processedSamples += count

                val now = System.currentTimeMillis()
                if (now - lastLevelAt >= LEVEL_INTERVAL_MS) {
                    val level = pcmDb(pcm, count)
                    val silenced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        record.activeRecordingConfiguration?.isClientSilenced
                    } else {
                        null
                    }
                    Log.d(
                        TAG,
                        "offline_audio_level_db=${"%.1f".format(level)} silenced=$silenced samples=$count"
                    )
                    dispatch { onAudioLevel(level) }
                    if (silenced != null) dispatch { onSilenced(silenced) }
                    lastLevelAt = now
                }

                val currentStream = stream
                if (activeRecognizer != null && currentStream != null) {
                    runCatching {
                        for (index in 0 until count) samples[index] = pcm[index] / 32768f
                        currentStream.acceptWaveform(samples.copyOf(count), SAMPLE_RATE)
                        while (activeRecognizer.isReady(currentStream)) {
                            activeRecognizer.decode(currentStream)
                        }

                        val recognizerResult = activeRecognizer.getResult(currentStream)
                        val text = recognizerResult.text.trim()
                        val completedAtMs = processedSamples * 1_000L / SAMPLE_RATE
                        val timedTokens = recognizerResult.toTimedTokens(
                            streamStartedAtMs = streamStartedAtMs,
                            completedAtMs = completedAtMs
                        )
                        if (text.isNotBlank() && text != lastText) {
                            lastText = text
                            val currentSegment = segment
                            Log.d(TAG, "offline_partial segment=$currentSegment text=${text.takeLast(40)}")
                            dispatch {
                                onResult(
                                    OfflineRecognitionResult(
                                        text = text,
                                        segment = currentSegment,
                                        isFinal = false,
                                        tokens = timedTokens
                                    )
                                )
                            }
                        }

                        if (activeRecognizer.isEndpoint(currentStream)) {
                            val completedSegment = segment
                            Log.i(TAG, "offline_endpoint segment=$completedSegment text=${text.takeLast(40)}")
                            if (text.isNotBlank()) {
                                dispatch {
                                    onResult(
                                        OfflineRecognitionResult(
                                            text = text,
                                            segment = completedSegment,
                                            isFinal = true,
                                            tokens = timedTokens
                                        )
                                    )
                                }
                            }
                            // Start a fresh stream at the exact PCM boundary we own. Besides making
                            // the next segment's token timestamps unambiguous, this avoids carrying
                            // decoder context across endpoints (which can suppress a repeated first
                            // token when the next utterance begins with the previous utterance's
                            // final token).
                            val nextStream = runCatching { activeRecognizer.createStream("") }
                                .onFailure {
                                    Log.e(TAG, "offline_next_stream_create_failed", it)
                                }
                                .getOrNull()
                            runCatching { currentStream.release() }
                            stream = nextStream
                            segment++
                            lastText = ""
                            streamStartedAtMs = completedAtMs
                            if (nextStream == null) {
                                dispatch { onStatus("识音已停止，录音仍在继续") }
                            }
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "offline_processing_degraded_to_audio_only", error)
                        runCatching { currentStream.release() }
                        stream = null
                        dispatch {
                            onStatus("识音已停止，录音仍在继续")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (running && captureThread === Thread.currentThread()) {
                Log.e(TAG, "offline_processing_failed", error)
                dispatch { onStatus("离线识音运行失败，请导出日志") }
            }
        } finally {
            val finalStream = stream
            if (activeRecognizer != null && finalStream != null) runCatching {
                finalStream.inputFinished()
                while (activeRecognizer.isReady(finalStream)) activeRecognizer.decode(finalStream)
                val finalResult = activeRecognizer.getResult(finalStream)
                val finalText = finalResult.text.trim()
                if (finalText.isNotBlank()) {
                    val finalSegment = segment
                    val completedAtMs = processedSamples * 1_000L / SAMPLE_RATE
                    dispatch {
                        onResult(
                            OfflineRecognitionResult(
                                text = finalText,
                                segment = finalSegment,
                                isFinal = true,
                                tokens = finalResult.toTimedTokens(
                                    streamStartedAtMs = streamStartedAtMs,
                                    completedAtMs = completedAtMs
                                )
                            )
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "offline_flush_failed", it) }
            runCatching { pcmOutput.flush() }
            runCatching { pcmOutput.close() }
            runCatching { finalStream?.release() }
            if (captureThread === Thread.currentThread()) {
                running = false
                captureThread = null
            }
        }
    }

    private fun recognizerConfig(model: InstalledOnlineModel): OnlineRecognizerConfig =
        OnlineRecognizerConfig().apply {
            featConfig = FeatureConfig().apply {
                sampleRate = SAMPLE_RATE
                featureDim = 80
                dither = 0f
            }
            modelConfig = OnlineModelConfig().apply {
                transducer = OnlineTransducerModelConfig().apply {
                    encoder = model.encoder.absolutePath
                    decoder = model.decoder.absolutePath
                    joiner = model.joiner.absolutePath
                }
                tokens = model.tokens.absolutePath
                numThreads = 2
                debug = false
                provider = "cpu"
            }
            endpointConfig = EndpointConfig(
                EndpointRule(false, 2.4f, 0f),
                EndpointRule(true, 0.9f, 0f),
                EndpointRule(false, 0f, 20f)
            )
            enableEndpoint = true
            decodingMethod = "greedy_search"
        }

    private fun dispatch(action: () -> Unit) {
        ContextCompat.getMainExecutor(appContext).execute {
            if (!closed) action()
        }
    }

    private fun com.k2fsa.sherpa.onnx.OnlineRecognizerResult.toTimedTokens(
        streamStartedAtMs: Long,
        completedAtMs: Long
    ): List<TimedTranscriptToken> {
        val usableTokens = tokens.mapIndexedNotNull { index, raw ->
            val cleaned = raw
                .replace("▁", " ")
                .replace("<blk>", "")
                .replace("<blank>", "")
                .replace("<unk>", "")
                .trim()
            if (cleaned.isBlank()) null else index to cleaned
        }
        if (usableTokens.isNotEmpty() && timestamps.isNotEmpty()) {
            return usableTokens.mapIndexed { usableIndex, (sourceIndex, text) ->
                val relativeStartMs = (timestamps.getOrElse(sourceIndex) { 0f } * 1_000f)
                    .toLong()
                    .coerceAtLeast(0L)
                val nextSourceIndex = usableTokens.getOrNull(usableIndex + 1)?.first
                val relativeEndMs = nextSourceIndex
                    ?.let {
                        (timestamps.getOrElse(it) { relativeStartMs / 1_000f } * 1_000f).toLong()
                    }
                    ?: (completedAtMs - streamStartedAtMs).coerceAtMost(relativeStartMs + 650L)
                val startMs = streamStartedAtMs + relativeStartMs
                TimedTranscriptToken(
                    text = text,
                    startMs = startMs,
                    endMs = (streamStartedAtMs + relativeEndMs).coerceAtLeast(startMs + 80L),
                    confidence = ysProbs.getOrElse(sourceIndex) { 1f }.coerceIn(0f, 1f)
                )
            }
        }

        val fallbackTokens = text.trim().codePoints().toArray()
            .map { String(Character.toChars(it)) }
            .filterNot(String::isBlank)
        if (fallbackTokens.isEmpty()) return emptyList()
        val duration = (completedAtMs - streamStartedAtMs).coerceAtLeast(fallbackTokens.size * 80L)
        val step = duration.toDouble() / fallbackTokens.size
        return fallbackTokens.mapIndexed { index, value ->
            TimedTranscriptToken(
                text = value,
                startMs = streamStartedAtMs + (index * step).toLong(),
                endMs = streamStartedAtMs + ((index + 1) * step).toLong()
            )
        }
    }

    private fun pcmDb(buffer: ShortArray, count: Int): Float {
        var sumSquares = 0.0
        for (index in 0 until count) {
            val sample = buffer[index].toDouble()
            sumSquares += sample * sample
        }
        if (count == 0) return -120f
        val rms = sqrt(sumSquares / count)
        return if (rms < 1.0) -120f else (20.0 * log10(rms / Short.MAX_VALUE)).toFloat()
    }

    private companion object {
        const val TAG = "KouboSpeech"
        const val SAMPLE_RATE = 16_000
        const val SAMPLES_PER_CHUNK = 1_600
        const val LEVEL_INTERVAL_MS = 500L
        const val STOP_JOIN_TIMEOUT_MS = 1_200L
    }
}
