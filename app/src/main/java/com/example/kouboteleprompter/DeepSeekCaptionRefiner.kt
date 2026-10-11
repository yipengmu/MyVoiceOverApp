package com.example.kouboteleprompter

import com.example.kouboteleprompter.domain.TimedTranscriptSegment
import com.example.kouboteleprompter.domain.TimedTranscriptToken
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil

/**
 * Conservatively corrects offline ASR tokens before the caption project is built.
 *
 * The API must preserve a one-to-one token mapping. That lets the app retain the recognizer's
 * timing while accepting contextual spelling fixes and better sentence boundaries.
 */
internal object DeepSeekCaptionRefiner {
    private const val ENDPOINT = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-v4-flash"
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_CONTEXT_CHARACTERS = 30_000

    fun refine(
        transcript: List<TimedTranscriptSegment>,
        scriptContext: String,
        apiKey: String = BuildConfig.DEEPSEEK_API_KEY
    ): Result<List<TimedTranscriptSegment>> = runCatching {
        require(apiKey.isNotBlank()) { "DeepSeek API key is not configured" }
        val sourceTokens = transcript
            .sortedBy(TimedTranscriptSegment::index)
            .flatMap(TimedTranscriptSegment::tokens)
            .filter { it.text.isNotBlank() }
        require(sourceTokens.isNotEmpty()) { "Transcript is empty" }

        val request = JSONObject().apply {
            put("model", MODEL)
            put("stream", false)
            put("thinking", JSONObject().put("type", "disabled"))
            put("temperature", 0.1)
            put("max_tokens", maxOf(1_024, sourceTokens.size * 18).coerceAtMost(12_000))
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", SYSTEM_PROMPT)
                    )
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt(sourceTokens, scriptContext))
                    )
            )
        }

        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(request.toString())
            }
            val status = connection.responseCode
            check(status in 200..299) { "DeepSeek request failed with HTTP $status" }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            CaptionCorrectionMapper.apply(sourceTokens, content)
        } finally {
            connection.disconnect()
        }
    }

    private fun userPrompt(tokens: List<TimedTranscriptToken>, scriptContext: String): String {
        val tokenJson = JSONArray().apply {
            tokens.forEachIndexed { id, token ->
                put(JSONObject().put("id", id).put("text", token.text))
            }
        }
        val boundedContext = scriptContext.take(MAX_CONTEXT_CHARACTERS)
        return buildString {
            appendLine("下面是口播台本上下文。它只用于判断同音错字，不代表所有文字都实际说出：")
            appendLine(boundedContext.ifBlank { "（没有台本）" })
            appendLine()
            appendLine("下面是按时间顺序排列的 ASR token JSON：")
            append(tokenJson)
        }
    }

    private val SYSTEM_PROMPT = """
        你是中文口播字幕校对器。请输出 JSON，不要输出解释或 Markdown。

        任务：结合完整台本上下文，修正 ASR token 中明显的同音字、专有名词、数字和错别字，并给出自然断句。

        严格规则：
        1. 输出 tokens 数组，元素数量、id、顺序必须与输入完全一致，不得新增、删除、合并或重排。
        2. 每个元素只能有 id、text、breakAfter 三个字段。
        3. text 不得为空，不得包含标点；只修正听错的文字，不润色、不扩写、不删减口播内容。
        4. 台本仅作纠错上下文。若实际 ASR 与台本不同，保留实际口播，不得把未说出的台本文字补进来。
        5. breakAfter 只能是 none、comma、sentence：分别表示不断句、逗号停顿、句末停顿。
        6. 避免过密断句；一个字幕语义单元通常为 8～16 个汉字，明显长停顿或完整句意结束时用 sentence。

        输出 JSON 示例：
        {"tokens":[{"id":0,"text":"大家","breakAfter":"none"},{"id":1,"text":"好","breakAfter":"sentence"}]}
    """.trimIndent()
}

/** Pure validation and timestamp-preserving mapping, kept separate for deterministic tests. */
internal object CaptionCorrectionMapper {
    private const val MAX_EDIT_RATIO = 0.40
    private val punctuation = Regex("[，。！？、；：,.!?;:]")

    fun apply(
        sourceTokens: List<TimedTranscriptToken>,
        correctionJson: String
    ): List<TimedTranscriptSegment> {
        require(sourceTokens.isNotEmpty()) { "Transcript is empty" }
        val corrections = JSONObject(correctionJson).getJSONArray("tokens")
        require(corrections.length() == sourceTokens.size) { "Token count changed" }

        val correctedTokens = sourceTokens.mapIndexed { index, source ->
            val correction = corrections.getJSONObject(index)
            require(correction.length() == 3) { "Unexpected correction fields" }
            require(correction.getInt("id") == index) { "Token order changed" }
            val correctedText = correction.getString("text").trim()
            require(correctedText.isNotEmpty()) { "Corrected token is empty" }
            require(!punctuation.containsMatchIn(correctedText)) {
                "Punctuation must be expressed with breakAfter"
            }
            val sourceLength = source.text.codePointLength()
            val correctedLength = correctedText.codePointLength()
            require(correctedLength <= maxOf(8, sourceLength * 3)) { "Corrected token is too long" }
            val suffix = when (correction.getString("breakAfter")) {
                "none" -> ""
                "comma" -> "，"
                "sentence" -> "。"
                else -> error("Unsupported breakAfter value")
            }
            source.copy(text = correctedText + suffix)
        }

        val original = sourceTokens.joinToString("") { it.text }.withoutPunctuation()
        val corrected = correctedTokens.joinToString("") { it.text }.withoutPunctuation()
        val allowedEdits = maxOf(4, ceil(original.codePointLength() * MAX_EDIT_RATIO).toInt())
        require(levenshtein(original, corrected) <= allowedEdits) {
            "Correction changed too much source text"
        }

        // Model punctuation now owns semantic boundaries. Original ASR endpoints are removed;
        // real acoustic pauses remain available through the unchanged token timestamps.
        return listOf(TimedTranscriptSegment(index = 0, tokens = correctedTokens))
    }

    private fun String.withoutPunctuation(): String =
        replace(punctuation, "").filterNot(Char::isWhitespace)

    private fun String.codePointLength(): Int = codePointCount(0, length)

    private fun levenshtein(left: String, right: String): Int {
        val a = left.codePoints().toArray()
        val b = right.codePoints().toArray()
        var previous = IntArray(b.size + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.size + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val substitution = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(
                    current[j] + 1,
                    previous[j + 1] + 1,
                    previous[j] + substitution
                )
            }
            previous = current
        }
        return previous[b.size]
    }
}
