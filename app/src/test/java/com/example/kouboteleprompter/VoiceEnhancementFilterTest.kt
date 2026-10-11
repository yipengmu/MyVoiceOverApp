package com.example.kouboteleprompter

import kotlin.math.sin
import kotlin.math.PI
import kotlin.math.sqrt
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEnhancementFilterTest {
    @Test
    fun `disabled processor passes through 16 bit PCM and consumes its input`() {
        val processor = VoiceEnhancementAudioProcessor(enabled = false)
        processor.configure(
            androidx.media3.common.audio.AudioProcessor.AudioFormat(
                16_000,
                1,
                androidx.media3.common.C.ENCODING_PCM_16BIT
            )
        )
        processor.flush()
        val input = ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(12)
            putShort(-34)
            putShort(56)
            putShort(-78)
            flip()
        }

        processor.queueInput(input)

        val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
        val actual = ShortArray(output.remaining() / Short.SIZE_BYTES) { output.short }
        assertArrayEquals(shortArrayOf(12, -34, 56, -78), actual)
        assertTrue(!input.hasRemaining())
    }

    @Test
    fun `steady low level background is reduced while speech remains prominent`() {
        val sampleRate = 48_000
        val filter = VoiceEnhancementFilter().apply { configure(sampleRate, 1) }
        val quietInput = tone(sampleRate, durationMs = 800, amplitude = 900)
        val quietOutput = quietInput.mapIndexed { _, sample -> filter.process(sample, 0) }
        val speechInput = tone(sampleRate, durationMs = 500, amplitude = 10_000)
        val speechOutput = speechInput.mapIndexed { _, sample -> filter.process(sample, 0) }

        val settledQuietInputRms = rms(quietInput.drop(sampleRate / 4))
        val settledQuietOutputRms = rms(quietOutput.drop(sampleRate / 4))
        val speechOutputRms = rms(speechOutput.drop(sampleRate / 20))

        assertTrue(
            "quiet input=$settledQuietInputRms output=$settledQuietOutputRms",
            settledQuietOutputRms < settledQuietInputRms * 0.55
        )
        assertTrue(
            "quiet output=$settledQuietOutputRms speech output=$speechOutputRms",
            speechOutputRms > settledQuietOutputRms * 7.0
        )
    }

    private fun tone(sampleRate: Int, durationMs: Int, amplitude: Int): List<Short> =
        List(sampleRate * durationMs / 1_000) { index ->
            (sin(2.0 * PI * 1_000.0 * index / sampleRate) * amplitude).toInt().toShort()
        }

    private fun rms(samples: List<Short>): Double = sqrt(
        samples.sumOf { sample -> sample.toDouble() * sample } / samples.size
    )
}
