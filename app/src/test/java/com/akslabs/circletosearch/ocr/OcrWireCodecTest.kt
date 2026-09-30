package com.akslabs.circletosearch.ocr

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class OcrWireCodecTest {
    private val box = listOf(2f, 3f, 120f, 8f, 118f, 26f, 0f, 21f)
    private val line = OcrWireLine(" Я 😀 123 ", .875f, box, box.map { it + .25f },
        listOf(OcrWireSpan(1, 2, .05f, .15f), OcrWireSpan(3, 5, .2f, .35f)))

    @Test fun textCoordinatesAndUtf16SpansSurviveRoundTripExactly() {
        val original = OcrWireResult(123L, listOf(line, line.copy(text = "中文" , spans = emptyList())))
        assertEquals(original, OcrWireCodec.decode(OcrWireCodec.encode(original)))
    }

    @Test fun emptyResultIsSuccessfulAndDistinctFromTransportFailure() {
        val empty = OcrWireResult(18L, emptyList())
        assertEquals(empty, OcrWireCodec.decode(OcrWireCodec.encode(empty)))
    }

    @Test fun truncatedResponseIsRejected() {
        val bytes = OcrWireCodec.encode(OcrWireResult(1, listOf(line)))
        assertThrows(Exception::class.java) { OcrWireCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }

    @Test fun unknownVersionAndTrailingBytesAreRejected() {
        val bytes = OcrWireCodec.encode(OcrWireResult(1, emptyList()))
        assertThrows(IllegalArgumentException::class.java) { OcrWireCodec.decode(bytes + byteArrayOf(0)) }
        ByteBuffer.wrap(bytes).putInt(2)
        assertThrows(IllegalArgumentException::class.java) { OcrWireCodec.decode(bytes) }
    }

    @Test fun hostileCountsAreRejectedBeforeAllocating() {
        val bytes = OcrWireCodec.encode(OcrWireResult(1, listOf(line)))
        ByteBuffer.wrap(bytes).putInt(12, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { OcrWireCodec.decode(bytes) }
        ByteBuffer.wrap(bytes).putInt(12, 1).putInt(16, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { OcrWireCodec.decode(bytes) }
    }

    @Test fun invalidSpanCannotChangeSelectionCoordinates() {
        val bytes = OcrWireCodec.encode(OcrWireResult(1, listOf(line.copy(
            spans = listOf(OcrWireSpan(0, 1000, 0f, 1f)),
        ))))
        assertThrows(IllegalArgumentException::class.java) { OcrWireCodec.decode(bytes) }
    }

    @Test fun nonFiniteGeometryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrWireCodec.encode(OcrWireResult(0, listOf(line.copy(box = List(8) { Float.NaN }))))
        }
    }

    @Test fun pixelMetadataChecksOverflowAndExactBufferSize() {
        OcrSharedMemory.validatePixels(2400, 1080, 9600, 10_368_000)
        listOf(
            listOf(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE, 4),
            listOf(100, 100, 399, 39900),
            listOf(100, 100, 400, 40001),
            listOf(0, 100, 400, 40000),
        ).forEach { (w, h, stride, size) ->
            assertThrows(IllegalArgumentException::class.java) { OcrSharedMemory.validatePixels(w, h, stride, size) }
        }
    }
}
