package com.akslabs.circletosearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class IndexedTranslation(val index: Int, val text: String)
internal data class TranslationGroupOutput(
    val translations: List<IndexedTranslation>,
    val issues: Set<ScreenTranslationUnchangedReason>,
)

/** Preserve successful blocks while keeping model/inference failures distinguishable from no change. */
internal suspend fun translateTextGroup(
    texts: List<String>,
    ensureModel: suspend () -> Unit,
    translate: suspend (String) -> String,
    requestSemaphore: Semaphore,
): TranslationGroupOutput {
    try {
        ensureModel()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        return TranslationGroupOutput(emptyList(), setOf(ScreenTranslationUnchangedReason.MODEL_UNAVAILABLE))
    }
    val results = coroutineScope {
        texts.mapIndexed { index, text ->
            async {
                requestSemaphore.withPermit {
                    try {
                        val translated = translate(text)
                        check(translated.isNotBlank()) { "Empty translation" }
                        Result.success(IndexedTranslation(index, translated).takeIf { translated != text })
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }
        }.awaitAll()
    }
    return TranslationGroupOutput(
        translations = results.mapNotNull { it.getOrNull() },
        issues = if (results.any { it.isFailure }) {
            setOf(ScreenTranslationUnchangedReason.TRANSLATION_FAILED)
        } else {
            emptySet()
        },
    )
}

internal fun translationUnchangedReason(
    allAlreadyTarget: Boolean,
    issues: Set<ScreenTranslationUnchangedReason>,
): ScreenTranslationUnchangedReason = when {
    ScreenTranslationUnchangedReason.MODEL_UNAVAILABLE in issues -> ScreenTranslationUnchangedReason.MODEL_UNAVAILABLE
    ScreenTranslationUnchangedReason.TRANSLATION_FAILED in issues -> ScreenTranslationUnchangedReason.TRANSLATION_FAILED
    issues.isNotEmpty() -> issues.first()
    allAlreadyTarget -> ScreenTranslationUnchangedReason.ALREADY_TARGET_LANGUAGE
    else -> ScreenTranslationUnchangedReason.NO_TRANSLATABLE_TEXT
}

internal fun ScreenTranslationUnchangedReason.userMessage(): String = when (this) {
    ScreenTranslationUnchangedReason.NO_RECOGNIZED_TEXT -> "No text found to translate"
    ScreenTranslationUnchangedReason.ALREADY_TARGET_LANGUAGE -> "The visible text is already in the target language"
    ScreenTranslationUnchangedReason.NO_TRANSLATABLE_TEXT -> "Translation returned no changes"
    ScreenTranslationUnchangedReason.UNKNOWN_OR_UNSUPPORTED_LANGUAGE -> "Some text has an unknown or unsupported language"
    ScreenTranslationUnchangedReason.LANGUAGE_IDENTIFICATION_FAILED -> "Could not identify the language of some text. Try again"
    ScreenTranslationUnchangedReason.MODEL_UNAVAILABLE -> "Translation model unavailable. Check your connection and try again"
    ScreenTranslationUnchangedReason.TRANSLATION_FAILED -> "Some text could not be translated. Try again"
    ScreenTranslationUnchangedReason.TEXT_DOES_NOT_FIT -> "Some translations do not fit. Original text was kept"
}

internal fun ScreenTranslationOutcome.userMessage(): String? = when (this) {
    is ScreenTranslationOutcome.Unchanged -> reason.userMessage()
    is ScreenTranslationOutcome.Translated -> untranslatedReasons.takeIf { it.isNotEmpty() }
        ?.joinToString(separator = ". ", prefix = "Screen partially translated. ") { it.userMessage() }
}
