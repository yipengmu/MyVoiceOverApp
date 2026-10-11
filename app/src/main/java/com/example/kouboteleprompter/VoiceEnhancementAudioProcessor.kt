package com.example.kouboteleprompter

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * Lightweight, fully offline voice enhancement for post-recording preview and export.
 *
 * The filter removes low-frequency handling/room rumble and high-frequency hiss, then applies an
 * adaptive downward expander. It deliberately keeps a non-zero noise bed so speech tails do not
 * sound abruptly gated. This is intended for spoken video, not music mastering.
 */
internal class VoiceEnhancementFilter {
    private var sampleRate = 48_000
    private var channelCount = 1
    private var highPassAlpha = 0f
    private var lowPassAlpha = 0f
    private var envelopeAttack = 0f
    private var envelopeRelease = 0f
    private var gainAttack = 0f
    private var gainRelease = 0f
    private var previousInput = FloatArray(1)
    private var previousHighPass = FloatArray(1)
    private var previousLowPass = FloatArray(1)
    private var envelope = 0f
    private var noiseFloor = INITIAL_NOISE_FLOOR
    private var suppressionGain = MINIMUM_GAIN

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(8_000)
        this.channelCount = channelCount.coerceAtLeast(1)
        previousInput = FloatArray(this.channelCount)
        previousHighPass = FloatArray(this.channelCount)
        previousLowPass = FloatArray(this.channelCount)
        val secondsPerSample = 1f / this.sampleRate
        val highPassRc = 1f / (2f * PI.toFloat() * HIGH_PASS_HZ)
        highPassAlpha = highPassRc / (highPassRc + secondsPerSample)
        val lowPassHz = minOf(LOW_PASS_HZ, this.sampleRate * 0.42f)
        val lowPassRc = 1f / (2f * PI.toFloat() * lowPassHz)
        lowPassAlpha = secondsPerSample / (lowPassRc + secondsPerSample)
        envelopeAttack = timeCoefficient(ENVELOPE_ATTACK_MS)
        envelopeRelease = timeCoefficient(ENVELOPE_RELEASE_MS)
        gainAttack = timeCoefficient(GAIN_ATTACK_MS)
        gainRelease = timeCoefficient(GAIN_RELEASE_MS)
        envelope = 0f
        noiseFloor = INITIAL_NOISE_FLOOR
        suppressionGain = MINIMUM_GAIN
    }

    fun process(sample: Short, channel: Int): Short {
        val channelIndex = channel.coerceIn(0, channelCount - 1)
        val input = sample / 32768f
        val highPassed = highPassAlpha * (
            previousHighPass[channelIndex] + input - previousInput[channelIndex]
            )
        previousInput[channelIndex] = input
        previousHighPass[channelIndex] = highPassed
        val bandLimited = previousLowPass[channelIndex] +
            lowPassAlpha * (highPassed - previousLowPass[channelIndex])
        previousLowPass[channelIndex] = bandLimited

        val magnitude = abs(bandLimited)
        val envelopeCoefficient = if (magnitude > envelope) envelopeAttack else envelopeRelease
        envelope = envelopeCoefficient * envelope + (1f - envelopeCoefficient) * magnitude
        updateNoiseFloor()

        val targetGain = targetGain(envelope, noiseFloor)
        val gainCoefficient = if (targetGain > suppressionGain) gainAttack else gainRelease
        suppressionGain = gainCoefficient * suppressionGain +
            (1f - gainCoefficient) * targetGain
        val enhanced = (bandLimited * suppressionGain * VOICE_GAIN)
            .coerceIn(-OUTPUT_LIMIT, OUTPUT_LIMIT)
        return (enhanced * Short.MAX_VALUE).toInt().toShort()
    }

    private fun updateNoiseFloor() {
        val canTrackNoise = envelope <= noiseFloor * NOISE_TRACKING_CEILING_RATIO ||
            envelope < ABSOLUTE_NOISE_TRACKING_CEILING
        if (!canTrackNoise) return
        val coefficient = if (envelope < noiseFloor) NOISE_FALL_COEFFICIENT else NOISE_RISE_COEFFICIENT
        noiseFloor = (coefficient * noiseFloor + (1f - coefficient) * envelope)
            .coerceIn(MINIMUM_NOISE_FLOOR, MAXIMUM_NOISE_FLOOR)
    }

    private fun targetGain(envelope: Float, noiseFloor: Float): Float {
        val ratio = envelope / noiseFloor.coerceAtLeast(MINIMUM_NOISE_FLOOR)
        val voiceAmount = (
            (ratio - CLOSED_GATE_RATIO) / (OPEN_GATE_RATIO - CLOSED_GATE_RATIO)
            ).coerceIn(0f, 1f)
        val easedVoiceAmount = voiceAmount * voiceAmount * (3f - 2f * voiceAmount)
        return MINIMUM_GAIN + (1f - MINIMUM_GAIN) * easedVoiceAmount
    }

    private fun timeCoefficient(durationMs: Float): Float =
        exp(-1f / (sampleRate * durationMs / 1_000f))

    private companion object {
        const val HIGH_PASS_HZ = 85f
        const val LOW_PASS_HZ = 10_500f
        const val ENVELOPE_ATTACK_MS = 7f
        const val ENVELOPE_RELEASE_MS = 110f
        const val GAIN_ATTACK_MS = 12f
        const val GAIN_RELEASE_MS = 70f
        const val INITIAL_NOISE_FLOOR = 0.007f
        const val MINIMUM_NOISE_FLOOR = 0.0015f
        const val MAXIMUM_NOISE_FLOOR = 0.045f
        const val NOISE_TRACKING_CEILING_RATIO = 2.4f
        // A steady signal below roughly -28 dBFS is treated as a candidate noise bed. Normal
        // close-mic speech is well above this; the ratio guard protects quiet speech tails.
        const val ABSOLUTE_NOISE_TRACKING_CEILING = 0.040f
        const val NOISE_RISE_COEFFICIENT = 0.99992f
        const val NOISE_FALL_COEFFICIENT = 0.9995f
        const val CLOSED_GATE_RATIO = 1.35f
        const val OPEN_GATE_RATIO = 3.8f
        const val MINIMUM_GAIN = 0.20f
        const val VOICE_GAIN = 1.08f
        const val OUTPUT_LIMIT = 0.98f
    }
}

@UnstableApi
internal class VoiceEnhancementAudioProcessor(
    enabled: Boolean = true
) : BaseAudioProcessor() {
    private val filter = VoiceEnhancementFilter()

    @Volatile
    private var enabled = enabled

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // BaseAudioProcessor recycles its output buffer. Keep a separate view of the source
        // before requesting the next output buffer: during a pipeline flush Media3 may feed this
        // processor a buffer it previously returned. A direct output.put(inputBuffer) then becomes
        // a self-copy and ByteBuffer throws IllegalArgumentException, stopping ExoPlayer.
        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        if (!enabled) {
            output.put(input)
            inputBuffer.position(inputBuffer.limit())
            output.flip()
            return
        }
        var channel = 0
        while (input.remaining() >= Short.SIZE_BYTES) {
            output.putShort(filter.process(input.short, channel))
            channel = (channel + 1) % inputAudioFormat.channelCount
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onFlush() {
        if (inputAudioFormat != AudioProcessor.AudioFormat.NOT_SET) {
            filter.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
    }
}

/** Installs the same denoising processor in ExoPlayer that export uses. */
@UnstableApi
internal class CaptionPreviewRenderersFactory(
    context: Context,
    private val voiceEnhancementProcessor: VoiceEnhancementAudioProcessor
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(
            arrayOf(
                ToInt16PcmAudioProcessor(),
                voiceEnhancementProcessor
            )
        )
        .setEnableFloatOutput(false)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        .build()
}
