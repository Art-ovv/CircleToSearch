package com.akslabs.circletosearch

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import org.junit.Assert.*
import org.junit.Test

class ScreenTranslationResultsTest {
    @Test
    fun modelFailureDoesNotCallInferenceOrClaimTargetLanguage() = runBlocking {
        val result = translateTextGroup(listOf("Hello"),
            ensureModel = { throw IllegalStateException("offline") },
            translate = { error("Must not translate") }, requestSemaphore = Semaphore(1))
        assertTrue(result.translations.isEmpty())
        assertEquals(ScreenTranslationUnchangedReason.MODEL_UNAVAILABLE,
            translationUnchangedReason(false, result.issues))
    }

    @Test
    fun allInferenceFailuresRemainFailures() = runBlocking {
        val result = translateTextGroup(listOf("Hello", "World"), {},
            translate = { throw IllegalStateException("inference") }, requestSemaphore = Semaphore(2))
        assertTrue(result.translations.isEmpty())
        assertEquals(setOf(ScreenTranslationUnchangedReason.TRANSLATION_FAILED), result.issues)
    }

    @Test
    fun partialFailurePreservesSuccessfulTextAndOriginalIndex() = runBlocking {
        val result = translateTextGroup(listOf("first", "second", "third"), {},
            translate = { if (it == "second") error("failed") else "translated $it" },
            requestSemaphore = Semaphore(2))
        assertEquals(listOf(0, 2), result.translations.map { it.index })
        assertEquals(listOf("translated first", "translated third"), result.translations.map { it.text })
        assertEquals(setOf(ScreenTranslationUnchangedReason.TRANSLATION_FAILED), result.issues)
    }

    @Test
    fun emptyTranslationIsFailureButUnchangedOutputIsNot() = runBlocking {
        val result = translateTextGroup(listOf("same", "empty"), {},
            translate = { if (it == "same") it else "   " }, requestSemaphore = Semaphore(1))
        assertTrue(result.translations.isEmpty())
        assertEquals(setOf(ScreenTranslationUnchangedReason.TRANSLATION_FAILED), result.issues)
        val unchanged = translateTextGroup(listOf("same"), {}, { it }, Semaphore(1))
        assertEquals(ScreenTranslationUnchangedReason.NO_TRANSLATABLE_TEXT,
            translationUnchangedReason(false, unchanged.issues))
    }

    @Test
    fun unsupportedLanguageAndIdentificationFailureCannotClaimAlreadyTarget() {
        for (issue in listOf(ScreenTranslationUnchangedReason.UNKNOWN_OR_UNSUPPORTED_LANGUAGE,
            ScreenTranslationUnchangedReason.LANGUAGE_IDENTIFICATION_FAILED)) {
            assertEquals(issue, translationUnchangedReason(true, setOf(issue)))
        }
        assertEquals(ScreenTranslationUnchangedReason.ALREADY_TARGET_LANGUAGE,
            translationUnchangedReason(true, emptySet()))
    }

    @Test
    fun modelCancellationPropagates() = runBlocking {
        try {
            translateTextGroup(listOf("hello"), { throw CancellationException("cancel") }, { it }, Semaphore(1))
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            // Expected.
        }
    }

    @Test
    fun inferenceCancellationPropagates() = runBlocking {
        try {
            translateTextGroup(listOf("hello"), {}, { throw CancellationException("cancel") }, Semaphore(1))
            fail("Cancellation was swallowed")
        } catch (_: CancellationException) {
            // Expected.
        }
    }
}
