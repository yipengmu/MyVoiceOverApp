package com.example.kouboteleprompter

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** A deliberately subtle, export-time skin smoothing and tone correction effect. */
@UnstableApi
internal object NaturalBeautyEffect : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        check(!useHdr) { "Natural beauty currently supports SDR video only" }
        return NaturalBeautyShaderProgram()
    }
}

@UnstableApi
private class NaturalBeautyShaderProgram : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents = */ false,
    /* texturePoolCapacity = */ 1
) {
    private val program = try {
        GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
    } catch (error: GlUtil.GlException) {
        throw VideoFrameProcessingException(error)
    }.apply {
        setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE
        )
        val identity = GlUtil.create4x4IdentityMatrix()
        setFloatsUniform("uTransformationMatrix", identity)
        setFloatsUniform("uTexTransformationMatrix", identity)
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        program.setFloatUniform("uTexelWidth", 1f / inputWidth.coerceAtLeast(1))
        program.setFloatUniform("uTexelHeight", 1f / inputHeight.coerceAtLeast(1))
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
            #version 100
            attribute vec4 aFramePosition;
            uniform mat4 uTransformationMatrix;
            uniform mat4 uTexTransformationMatrix;
            varying vec2 vTexSamplingCoord;

            void main() {
              gl_Position = uTransformationMatrix * aFramePosition;
              vec4 texturePosition = vec4(
                  aFramePosition.x * 0.5 + 0.5,
                  aFramePosition.y * 0.5 + 0.5,
                  0.0,
                  1.0);
              vTexSamplingCoord = (uTexTransformationMatrix * texturePosition).xy;
            }
        """

        // Nine taps keep the cost predictable at FHD. The RGB skin mask limits smoothing so
        // hair, eyes, text, and most of the background retain their original detail.
        const val FRAGMENT_SHADER = """
            #version 100
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform float uTexelWidth;
            uniform float uTexelHeight;
            varying vec2 vTexSamplingCoord;

            void main() {
              // A one-pixel kernel was effectively invisible at 1080p. A 2.6-pixel radius is
              // still restrained, but visibly softens small skin texture on phone screens.
              vec2 px = vec2(uTexelWidth, uTexelHeight) * 2.6;
              vec3 center = texture2D(uTexSampler, vTexSamplingCoord).rgb;
              vec3 blur = center * 4.0;
              blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(px.x, 0.0)).rgb * 2.0;
              blur += texture2D(uTexSampler, vTexSamplingCoord - vec2(px.x, 0.0)).rgb * 2.0;
              blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, px.y)).rgb * 2.0;
              blur += texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, px.y)).rgb * 2.0;
              blur += texture2D(uTexSampler, vTexSamplingCoord + px).rgb;
              blur += texture2D(uTexSampler, vTexSamplingCoord - px).rgb;
              blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(px.x, -px.y)).rgb;
              blur += texture2D(uTexSampler, vTexSamplingCoord + vec2(-px.x, px.y)).rgb;
              blur /= 16.0;

              float luminance = dot(center, vec3(0.2126, 0.7152, 0.0722));
              float warm = smoothstep(-0.025, 0.11, center.r - center.g)
                  * smoothstep(-0.055, 0.09, center.g - center.b);
              float skin = warm * smoothstep(0.07, 0.25, luminance)
                  * (1.0 - smoothstep(0.88, 1.0, luminance));
              float detail = length(center - blur);
              float edgePreservation = 1.0 - smoothstep(0.035, 0.18, detail);
              vec3 smoothed = mix(center, blur, 0.42 * skin * edgePreservation);

              vec3 lifted = pow(clamp(smoothed, 0.0, 1.0), vec3(0.955));
              lifted *= vec3(1.024, 1.010, 0.994);
              float toneStrength = 0.30 + 0.38 * skin;
              vec3 result = mix(smoothed, lifted, toneStrength);
              gl_FragColor = vec4(clamp(result, 0.0, 1.0), 1.0);
            }
        """
    }
}
