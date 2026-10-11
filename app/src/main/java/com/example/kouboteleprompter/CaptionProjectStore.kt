package com.example.kouboteleprompter

import com.example.kouboteleprompter.domain.CaptionCard
import com.example.kouboteleprompter.domain.CaptionProject
import com.example.kouboteleprompter.domain.CaptionProjectBuilder
import com.example.kouboteleprompter.domain.CaptionStylePreset
import com.example.kouboteleprompter.domain.CaptionWord
import com.example.kouboteleprompter.domain.DEFAULT_CAPTION_FONT_SIZE_SP
import com.example.kouboteleprompter.domain.DEFAULT_CAPTION_POSITION_X
import com.example.kouboteleprompter.domain.DEFAULT_CAPTION_POSITION_Y
import com.example.kouboteleprompter.domain.DEFAULT_VIDEO_SPEED
import com.example.kouboteleprompter.domain.FillerKind
import com.example.kouboteleprompter.domain.MAX_CAPTION_FONT_SIZE_SP
import com.example.kouboteleprompter.domain.MIN_CAPTION_FONT_SIZE_SP
import com.example.kouboteleprompter.domain.MAX_CAPTION_POSITION
import com.example.kouboteleprompter.domain.TimedTranscriptSegment
import com.example.kouboteleprompter.domain.normalizedVideoSpeed
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object CaptionProjectStore {
    fun sidecarFor(videoFile: File): File = File(
        videoFile.parentFile,
        "${videoFile.nameWithoutExtension}.captions.json"
    )

    fun createAndSave(
        videoFile: File,
        transcript: List<TimedTranscriptSegment>
    ): Result<CaptionProject> = runCatching {
        val project = CaptionProjectBuilder.build(videoFile.name, transcript)
        save(videoFile, project).getOrThrow()
        project
    }

    fun save(videoFile: File, project: CaptionProject): Result<Unit> = runCatching {
        val target = sidecarFor(videoFile)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(project.toJson().toString(), Charsets.UTF_8)
        check(temporary.renameTo(target) || run {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
            true
        }) { "Unable to save caption project" }
    }

    fun load(videoFile: File): Result<CaptionProject> = runCatching {
        val file = sidecarFor(videoFile)
        require(file.isFile) { "No caption project for ${videoFile.name}" }
        JSONObject(file.readText(Charsets.UTF_8)).toCaptionProject()
    }

    private fun CaptionProject.toJson(): JSONObject = JSONObject().apply {
        put("version", version)
        put("sourceFileName", sourceFileName)
        put("style", style.name)
        put("fontSizeSp", fontSizeSp.toDouble())
        put("captionPositionX", captionPositionX.toDouble())
        put("captionPositionY", captionPositionY.toDouble())
        put("autoCleanupEnabled", autoCleanupEnabled)
        put("autoEmphasisEnabled", autoEmphasisEnabled)
        put("noiseReductionEnabled", noiseReductionEnabled)
        put("videoSpeed", videoSpeed.toDouble())
        put("cards", JSONArray().apply {
            cards.forEach { card ->
                put(JSONObject().apply {
                    put("id", card.id)
                    put("words", JSONArray().apply {
                        card.words.forEach { word ->
                            put(JSONObject().apply {
                                put("id", word.id)
                                put("text", word.text)
                                put("startMs", word.startMs)
                                put("endMs", word.endMs)
                                put("confidence", word.confidence.toDouble())
                                put("fillerKind", word.fillerKind.name)
                                put("removed", word.removed)
                                put("emphasized", word.emphasized)
                            })
                        }
                    })
                })
            }
        })
    }

    private fun JSONObject.toCaptionProject(): CaptionProject {
        val cardsJson = getJSONArray("cards")
        val cards = buildList {
            for (cardIndex in 0 until cardsJson.length()) {
                val cardJson = cardsJson.getJSONObject(cardIndex)
                val wordsJson = cardJson.getJSONArray("words")
                val words = buildList {
                    for (wordIndex in 0 until wordsJson.length()) {
                        val word = wordsJson.getJSONObject(wordIndex)
                        add(
                            CaptionWord(
                                id = word.getInt("id"),
                                text = word.getString("text"),
                                startMs = word.getLong("startMs"),
                                endMs = word.getLong("endMs"),
                                confidence = word.optDouble("confidence", 1.0).toFloat(),
                                fillerKind = enumValueOrDefault(
                                    word.optString("fillerKind"),
                                    FillerKind.NONE
                                ),
                                removed = word.optBoolean("removed", false),
                                emphasized = word.optBoolean("emphasized", false)
                            )
                        )
                    }
                }
                add(CaptionCard(cardJson.optInt("id", cardIndex), words))
            }
        }
        return CaptionProject(
            sourceFileName = optString("sourceFileName"),
            cards = cards,
            style = enumValueOrDefault(optString("style"), CaptionStylePreset.BOLD_ORANGE),
            fontSizeSp = optDouble(
                "fontSizeSp",
                DEFAULT_CAPTION_FONT_SIZE_SP.toDouble()
            ).toFloat().coerceIn(MIN_CAPTION_FONT_SIZE_SP, MAX_CAPTION_FONT_SIZE_SP),
            captionPositionX = optDouble(
                "captionPositionX",
                DEFAULT_CAPTION_POSITION_X.toDouble()
            ).toFloat().coerceIn(-MAX_CAPTION_POSITION, MAX_CAPTION_POSITION),
            captionPositionY = optDouble(
                "captionPositionY",
                DEFAULT_CAPTION_POSITION_Y.toDouble()
            ).toFloat().coerceIn(-MAX_CAPTION_POSITION, MAX_CAPTION_POSITION),
            autoCleanupEnabled = optBoolean("autoCleanupEnabled", true),
            autoEmphasisEnabled = optBoolean("autoEmphasisEnabled", true),
            noiseReductionEnabled = optBoolean("noiseReductionEnabled", false),
            videoSpeed = normalizedVideoSpeed(
                optDouble("videoSpeed", DEFAULT_VIDEO_SPEED.toDouble()).toFloat()
            ),
            version = maxOf(optInt("version", 1), 3)
        )
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default
}
