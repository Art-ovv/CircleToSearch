package com.akslabs.circletosearch

import java.util.Locale

internal const val MIN_RELIABLE_AGGREGATE_CONFIDENCE = 0.50f
internal const val SHORT_TRANSLATION_BLOCK_MAX_SIGNAL_LENGTH = 24
private const val MAX_AGGREGATE_LANGUAGE_SAMPLE_LENGTH = 4_000
private const val MAX_BLOCK_LANGUAGE_SAMPLE_LENGTH = 512

internal data class AggregateLanguage(
    val languageTag: String,
    val confidence: Float,
)

internal data class TranslationLanguagePair(
    val sourceLanguage: String,
    val targetLanguage: String,
)

/**
 * Produces a bounded sample for whole-screen language identification.
 *
 * A bounded sample keeps language identification latency independent of the size of an
 * AssistStructure-backed text dump while still retaining evidence from multiple screen regions.
 */
internal fun buildAggregateLanguageSample(
    blockTexts: List<String>,
    maximumLength: Int = MAX_AGGREGATE_LANGUAGE_SAMPLE_LENGTH,
): String {
    if (maximumLength <= 0) return ""

    val result = StringBuilder(minOf(maximumLength, 512))
    for (text in blockTexts) {
        val normalized = text.trim()
        if (normalized.isEmpty()) continue

        if (result.isNotEmpty()) {
            if (result.length == maximumLength) break
            result.append('\n')
        }
        val remaining = maximumLength - result.length
        if (remaining <= 0) break
        result.append(normalized, 0, minOf(normalized.length, remaining))
        if (result.length == maximumLength) break
    }
    return result.toString()
}

internal fun isShortTranslationBlock(text: String): Boolean {
    val signalLength = text.count(Char::isLetterOrDigit)
    return signalLength in 1..SHORT_TRANSLATION_BLOCK_MAX_SIGNAL_LENGTH
}

internal fun buildBlockLanguageSample(
    text: String,
    maximumLength: Int = MAX_BLOCK_LANGUAGE_SAMPLE_LENGTH,
): String {
    if (maximumLength <= 0) return ""
    val normalized = text.trim()
    return normalized.take(maximumLength)
}

internal fun normalizeTranslationLanguageTag(languageTag: String?): String? {
    val normalized = languageTag
        ?.trim()
        ?.lowercase(Locale.ROOT)
        ?.substringBefore('-')
        ?.substringBefore('_')
        .orEmpty()
    return normalized.takeIf { it.isNotEmpty() && it != "und" }
}

private fun AggregateLanguage?.reliableLanguageTag(): String? {
    val aggregate = this ?: return null
    if (aggregate.confidence < MIN_RELIABLE_AGGREGATE_CONFIDENCE) return null
    return normalizeTranslationLanguageTag(aggregate.languageTag)
}

/**
 * Resolves one source language without guessing long, unidentified text.
 *
 * Short labels frequently identify as `und` in isolation. They may inherit a reliable language
 * detected from the aggregate screen sample; a long unidentified block remains untranslated
 * because assigning the dominant screen language to it could corrupt genuinely mixed-language UI.
 */
internal fun resolveBlockSourceLanguage(
    text: String,
    detectedLanguageTag: String?,
    aggregateLanguage: AggregateLanguage?,
): String? {
    normalizeTranslationLanguageTag(detectedLanguageTag)?.let { return it }
    if (!isShortTranslationBlock(text)) return null
    return aggregateLanguage.reliableLanguageTag()
}

/**
 * Groups block indexes by model pair. One group corresponds to exactly one model download request.
 */
internal fun buildTranslationLanguageGroups(
    blockTexts: List<String>,
    detectedLanguageTags: List<String?>,
    aggregateLanguage: AggregateLanguage?,
    targetLanguageTag: String,
): Map<TranslationLanguagePair, List<Int>> {
    require(blockTexts.size == detectedLanguageTags.size)
    val targetLanguage = normalizeTranslationLanguageTag(targetLanguageTag) ?: return emptyMap()
    val groups = linkedMapOf<TranslationLanguagePair, MutableList<Int>>()

    blockTexts.indices.forEach { index ->
        val sourceLanguage = resolveBlockSourceLanguage(
            text = blockTexts[index],
            detectedLanguageTag = detectedLanguageTags[index],
            aggregateLanguage = aggregateLanguage,
        ) ?: return@forEach
        if (sourceLanguage == targetLanguage) return@forEach

        val pair = TranslationLanguagePair(sourceLanguage, targetLanguage)
        groups.getOrPut(pair) { mutableListOf() } += index
    }

    return groups
}
