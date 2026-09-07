package com.paddle.ocr.postprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CTCDecoderTest {
    @Test
    fun collapsesRunsAndKeepsBlankSeparatedDuplicates() {
        val result = decode(
            winners = intArrayOf(1, 1, 0, 1),
            characters = listOf("a"),
        )

        assertEquals("aa", result.text)
        assertEquals(2, result.spans.size)
        assertEquals(0, result.spans[0].startIndex)
        assertEquals(1, result.spans[0].endIndex)
        assertEquals(1, result.spans[1].startIndex)
        assertEquals(2, result.spans[1].endIndex)
    }

    @Test
    fun usesUtf16IndexesForSupplementaryDictionaryTokens() {
        val result = decode(
            winners = intArrayOf(1, 0, 2),
            characters = listOf("😀", "x"),
        )

        assertEquals("😀x", result.text)
        assertEquals(0, result.spans[0].startIndex)
        assertEquals(2, result.spans[0].endIndex)
        assertEquals(2, result.spans[1].startIndex)
        assertEquals(3, result.spans[1].endIndex)
    }

    @Test
    fun ignoresTimestepsBelongingOnlyToBatchPadding() {
        val result = decode(
            winners = intArrayOf(1, 0, 2, 0, 3, 3, 3, 3),
            characters = listOf("a", "b", "c"),
            validRatio = 0.5f,
        )

        assertEquals("ab", result.text)
        assertEquals(2, result.spans.size)
        assertEquals(0.5f, result.spans[1].startFraction, 0.0001f)
        assertEquals(0.75f, result.spans[1].endFraction, 0.0001f)
        assertTrue(result.spans.all { it.endFraction <= 1f })
    }

    private fun decode(
        winners: IntArray,
        characters: List<String>,
        validRatio: Float = 1f,
    ): CTCDecodedText {
        val classCount = characters.size + 1
        val output = FloatArray(winners.size * classCount) { -1f }
        winners.forEachIndexed { timestep, winner ->
            output[timestep * classCount + winner] = 0.9f
        }
        return CTCDecoder.decode(
            output = output,
            shape = longArrayOf(1, winners.size.toLong(), classCount.toLong()),
            characterList = characters,
            validRatios = floatArrayOf(validRatio),
        ).single()
    }
}
