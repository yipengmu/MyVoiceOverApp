package com.example.kouboteleprompter.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val MAX_TITLE_LENGTH = 24
private const val MAX_FILE_TITLE_LENGTH = 40

private val LEGACY_SCRIPT_TITLE = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2} 台本")
private val SENTENCE_SEPARATOR = Regex("[。！？!?；;\\n\\r]+")
private val LIST_PREFIX = Regex("^\\s*(?:#{1,6}\\s*|[-*•·]\\s*|\\d+[.、）)]\\s*)")
private val EXPLICIT_TITLE_PREFIX = Regex("^(?:标题|主题|题目)\\s*[:：]\\s*")
private val GREETING_PREFIX = Regex("^(?:(?:嗨|哈喽|hello)[，,！! ]*)?(?:大家|朋友们|各位)?好[，,！! ]*", RegexOption.IGNORE_CASE)
private val TOPIC_INTRO = Regex(
    "^(?:今天|这期|本期|这次)?(?:我(?:们)?)?(?:想|要|来|会|准备)?(?:和大家|跟大家|给大家)?" +
        "(?:聊聊|聊一聊|分享一下|分享|讲讲|讲一讲|说说|说一说|介绍一下|介绍|谈谈)\\s*[:：，, ]*"
)
private val TRAILING_FILLER = Regex("[，,。.!！?？:：;；、\\s]+$")
private val INVALID_FILE_NAME_CHARACTERS = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
private val DUPLICATE_FILE_SEPARATORS = Regex("[\\s_-]+")
private val VIDEO_TIMESTAMP_SUFFIX = Regex("_\\d{13}$")

/** Produces a short, deterministic title without requiring network or model availability. */
internal fun generateContentTitle(content: String): String {
    val normalized = content
        .replace('\u3000', ' ')
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString("\n")

    if (normalized.isBlank()) return "未命名台本"

    val firstLine = normalized.lineSequence().first()
    val firstLineWithoutListPrefix = firstLine.replace(LIST_PREFIX, "")
    val explicitTitle = if (
        firstLine.trimStart().startsWith("#") ||
        EXPLICIT_TITLE_PREFIX.containsMatchIn(firstLineWithoutListPrefix)
    ) {
        cleanTitleCandidate(firstLine).takeIf { it.isNotBlank() }
    } else null

    val candidates = SENTENCE_SEPARATOR.split(normalized)
        .asSequence()
        .map(::cleanTitleCandidate)
        .filter { it.isNotBlank() }
        .toList()

    val candidate = explicitTitle
        ?: candidates.firstOrNull { it.length >= 4 }
        ?: candidates.firstOrNull()
        ?: "未命名台本"
    return candidate.take(MAX_TITLE_LENGTH).trimEnd().trimEnd('，', ',', '、', '：', ':', '-', '—')
        .ifBlank { "未命名台本" }
}

internal fun resolveScriptTitle(enteredTitle: String, content: String): String {
    val trimmed = enteredTitle.trim()
    return if (trimmed.isBlank() || isLegacyDefaultScriptTitle(trimmed)) {
        generateContentTitle(content)
    } else {
        trimmed
    }
}

internal fun isLegacyDefaultScriptTitle(title: String): Boolean = LEGACY_SCRIPT_TITLE.matches(title.trim())

internal fun isAutomaticScriptTitle(title: String, content: String): Boolean =
    isLegacyDefaultScriptTitle(title) || title.trim() == generateContentTitle(content)

internal fun videoBaseName(scriptTitle: String, timestampMillis: Long = System.currentTimeMillis()): String {
    val safeTitle = scriptTitle
        .replace(INVALID_FILE_NAME_CHARACTERS, " ")
        .replace(DUPLICATE_FILE_SEPARATORS, "_")
        .trim('_', '.', ' ')
        .take(MAX_FILE_TITLE_LENGTH)
        .trimEnd('_', '.', ' ')
        .ifBlank { "口播视频" }
    return "${safeTitle}_$timestampMillis"
}

internal fun videoDisplayTitle(fileName: String): String {
    val baseName = fileName.substringBeforeLast('.')
    if (baseName.matches(Regex("koubo_\\d+"))) return "口播视频"
    return baseName.replace(VIDEO_TIMESTAMP_SUFFIX, "").replace('_', ' ').trim().ifBlank { "口播视频" }
}

internal fun videoRecordedAt(
    fileName: String,
    fallbackMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val timestamp = Regex("_(\\d{13})(?:\\.mp4)?$").find(fileName)?.groupValues?.get(1)?.toLongOrNull()
        ?: Regex("koubo_(\\d{13})\\.mp4").matchEntire(fileName)?.groupValues?.get(1)?.toLongOrNull()
        ?: fallbackMillis
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.CHINA)
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(timestamp))
}

private fun cleanTitleCandidate(raw: String): String {
    var candidate = raw
        .replace(LIST_PREFIX, "")
        .replace(EXPLICIT_TITLE_PREFIX, "")
        .trim(' ', '\t', '“', '”', '"', '\'', '《', '》', '【', '】')
    candidate = candidate.replace(GREETING_PREFIX, "")
    candidate = candidate.replace(TOPIC_INTRO, "")
    return candidate.replace(TRAILING_FILLER, "").trim()
}
