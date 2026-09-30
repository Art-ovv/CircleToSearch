package com.akslabs.circletosearch.ocr

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** Coordinates and CTC spans are lossless; only the transport representation changes. */
internal data class OcrWireLine(
    val text: String,
    val confidence: Float,
    val box: List<Float>,
    val recognitionBox: List<Float>,
    val spans: List<OcrWireSpan>,
)

internal data class OcrWireSpan(val start: Int, val end: Int, val left: Float, val right: Float)
internal data class OcrWireResult(val elapsedMs: Long, val lines: List<OcrWireLine>)

internal object OcrWireCodec {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val VERSION = 1
    private const val MAX_LINES = 512
    private const val MAX_TEXT_BYTES = 1024 * 1024

    fun encode(result: OcrWireResult): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            require(result.lines.size <= MAX_LINES)
            out.writeInt(VERSION)
            out.writeLong(result.elapsedMs)
            out.writeInt(result.lines.size)
            for (line in result.lines) {
                val text = line.text.toByteArray(Charsets.UTF_8)
                require(text.size <= MAX_TEXT_BYTES)
                out.writeInt(text.size)
                out.write(text)
                out.writeFloat(line.confidence)
                for (box in listOf(line.box, line.recognitionBox)) {
                    require(box.size == 8 && box.all { it.isFinite() })
                    box.forEach(out::writeFloat)
                }
                require(line.spans.size <= line.text.length)
                out.writeInt(line.spans.size)
                for (span in line.spans) {
                    out.writeInt(span.start)
                    out.writeInt(span.end)
                    out.writeFloat(span.left)
                    out.writeFloat(span.right)
                }
                require(bytes.size() <= MAX_BYTES)
            }
        }
        return bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): OcrWireResult {
        require(bytes.size in 16..MAX_BYTES)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION)
            val elapsed = input.readLong()
            val count = input.readInt()
            require(count in 0..MAX_LINES)
            val lines = List(count) {
                val length = input.readInt()
                require(length in 0..MAX_TEXT_BYTES && length <= input.available())
                val text = ByteArray(length).also(input::readFully).toString(Charsets.UTF_8)
                val confidence = input.readFloat()
                require(confidence.isFinite())
                val box = List(8) { input.readFloat().also { require(it.isFinite()) } }
                val recognitionBox = List(8) { input.readFloat().also { require(it.isFinite()) } }
                val spansCount = input.readInt()
                require(spansCount in 0..text.length && spansCount <= input.available() / 16)
                val spans = List(spansCount) {
                    val start = input.readInt()
                    val end = input.readInt()
                    val left = input.readFloat()
                    val right = input.readFloat()
                    require(start >= 0 && end in start..text.length)
                    require(left.isFinite() && right.isFinite())
                    OcrWireSpan(start, end, left, right)
                }
                OcrWireLine(text, confidence, box, recognitionBox, spans)
            }
            require(input.available() == 0)
            return OcrWireResult(elapsed, lines)
        }
    }
}
