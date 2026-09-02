package com.akslabs.circletosearch.utils

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import kotlinx.coroutines.ensureActive
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed class QrResult {
    data class Url(val url: String, val displayUrl: String) : QrResult()
    data class WiFi(val ssid: String, val password: String?, val security: String) : QrResult()
    data class Phone(val number: String) : QrResult()
    data class Product(val barcode: String) : QrResult()
    data class VCard(val name: String?, val phone: String?, val email: String?, val raw: String) : QrResult()
    data class GeoPoint(val lat: Double, val lng: Double) : QrResult()
    data class PlainText(val text: String) : QrResult()
}

/** Wraps a parsed QR result with its bounding box in bitmap-pixel coordinates */
data class QrResultWithBounds(
    val result: QrResult,
    val rawText: String,
    /** Bounds in bitmap pixel coords (may be null if position unavailable) */
    val bounds: RectF?,
    /** The actual symbology reported by ZXing. */
    val format: BarcodeFormat? = null,
)

object QrScanner {

    private val LINEAR_BARCODE_FORMATS = setOf(
        BarcodeFormat.CODABAR,
        BarcodeFormat.CODE_39,
        BarcodeFormat.CODE_93,
        BarcodeFormat.CODE_128,
        BarcodeFormat.EAN_8,
        BarcodeFormat.EAN_13,
        BarcodeFormat.ITF,
        BarcodeFormat.RSS_14,
        BarcodeFormat.RSS_EXPANDED,
        BarcodeFormat.UPC_A,
        BarcodeFormat.UPC_E,
        BarcodeFormat.UPC_EAN_EXTENSION,
    )

    private val SUPPORTED_FORMATS = LINEAR_BARCODE_FORMATS + setOf(
        BarcodeFormat.QR_CODE,
        BarcodeFormat.DATA_MATRIX,
        BarcodeFormat.AZTEC,
        BarcodeFormat.PDF_417,
        BarcodeFormat.MAXICODE,
    )

    private val HINTS = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.ALSO_INVERTED to true,
        DecodeHintType.POSSIBLE_FORMATS to SUPPORTED_FORMATS,
    )

    /** Scan for all barcodes / QR codes in the given bitmap. Returns a Flow of accumulating results. */
    fun scanBitmapAll(bitmap: Bitmap): kotlinx.coroutines.flow.Flow<List<QrResultWithBounds>> = kotlinx.coroutines.flow.flow {
        val allResults = mutableListOf<QrResultWithBounds>()

        fun processResults(rawResults: List<com.google.zxing.Result>, xOffset: Int, yOffset: Int) {
            rawResults.forEach { raw ->
                val globalBounds = computeBounds(raw.resultPoints)?.let { b ->
                    RectF(
                        b.left + xOffset,
                        b.top + yOffset,
                        b.right + xOffset,
                        b.bottom + yOffset,
                    )
                }
                val isDuplicate = allResults.any { existing ->
                    isSameDetection(
                        existing = existing,
                        rawText = raw.text,
                        format = raw.barcodeFormat,
                        bounds = globalBounds,
                    )
                }
                if (!isDuplicate) {
                    allResults.add(
                        QrResultWithBounds(
                            result = parseResult(raw.text, raw.barcodeFormat),
                            rawText = raw.text,
                            bounds = globalBounds,
                            format = raw.barcodeFormat,
                        )
                    )
                }
            }
        }

        try {
            val w = bitmap.width
            val h = bitmap.height

            // Convert once. Keeping the temporary ARGB array inside the helper
            // lets it become unreachable immediately after ZXing has produced
            // its compact luminance buffer instead of retaining both arrays for
            // every tile pass.
            val baseSource = createLuminanceSource(bitmap)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()

            // Define all tiles for 3 levels of zoom
            val tileRegions = mutableListOf<android.graphics.Rect>()
            
            // Level 1: Full-screen (1 tile)
            tileRegions.add(android.graphics.Rect(0, 0, w, h))

            // Level 2: 2x2 grid (4 tiles, ~65% size for overlap)
            val l2W = (w * 0.65f).toInt()
            val l2H = (h * 0.65f).toInt()
            tileRegions.add(android.graphics.Rect(0, 0, l2W, l2H))
            tileRegions.add(android.graphics.Rect(w - l2W, 0, w, l2H))
            tileRegions.add(android.graphics.Rect(0, h - l2H, l2W, h))
            tileRegions.add(android.graphics.Rect(w - l2W, h - l2H, w, h))



            // Execute every tile pass (1 full-screen + 4 overlapping corners)
            for (index in tileRegions.indices) {
                kotlinx.coroutines.yield() // Check for cancellation and yield thread
                val rect = tileRegions[index]
                try {
                    val subSource = baseSource.crop(rect.left, rect.top, rect.width(), rect.height())
                    val results = scanLuminanceSource(subSource)
                    val before = allResults.size
                    processResults(results, rect.left, rect.top)
                    
                    if (allResults.size > before) {
                        android.util.Log.d("CircleToSearch", "QrScanner: Pass $index (Rect: $rect) found ${allResults.size - before} NEW codes")
                        emit(allResults.toList()) // Emit immediately!
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e // Propagate cancellation
                } catch (e: Exception) {
                    android.util.Log.e("CircleToSearch", "QrScanner: Pass $index failed", e)
                }
            }

            android.util.Log.d("CircleToSearch", "QrScanner: Multi-res scan COMPLETE. Total codes: ${allResults.size}")
            // Optional: emit again at the end if you want to ensure the flow doesn't complete empty when nothing is found
            if (allResults.isEmpty()) {
                emit(emptyList())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("CircleToSearch", "QrScanner: Fatal error in scanBitmapAll", e)
            emit(emptyList())
        }
    }

    private fun createLuminanceSource(bitmap: Bitmap): RGBLuminanceSource {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return RGBLuminanceSource(width, height, pixels)
    }

    /** Core scanner: tries Hybrid, Global, and Inverted versions of a source. */
    private fun scanLuminanceSource(source: com.google.zxing.LuminanceSource): List<com.google.zxing.Result> {
        val results = mutableListOf<com.google.zxing.Result>()
        val multiReader = GenericMultipleBarcodeReader(MultiFormatReader())

        fun run(binarizer: com.google.zxing.Binarizer) {
            try {
                val bitmap = BinaryBitmap(binarizer)
                results.addAll(multiReader.decodeMultiple(bitmap, HINTS))
            } catch (e: NotFoundException) {
                // Ignore
            } catch (e: Exception) {
                // Log minor errors if needed
            }
        }

        // For clean screenshots, HybridBinarizer is usually sufficient and fast.
        run(HybridBinarizer(source))

        return results
    }

    private fun isSameDetection(
        existing: QrResultWithBounds,
        rawText: String,
        format: BarcodeFormat,
        bounds: RectF?,
    ): Boolean {
        if (existing.format != format || existing.rawText != rawText) return false

        val existingBounds = existing.bounds
        if (existingBounds == null || bounds == null) {
            // Some formats (notably MaxiCode) may not expose result points. In
            // that case position cannot distinguish repeated tile detections,
            // so the matching payload and symbology are the safest identity.
            return true
        }

        val intersection = RectF(existingBounds)
        val intersects = intersection.intersect(bounds)
        if (intersects) {
            val intersectionArea = intersection.width() * intersection.height()
            val smallerArea = min(
                existingBounds.width() * existingBounds.height(),
                bounds.width() * bounds.height(),
            )
            if (smallerArea > 0f && intersectionArea / smallerArea >= 0.35f) {
                return true
            }
        }

        // Result points can shift slightly between the full-frame and cropped
        // tile passes. Keep a small pixel tolerance without merging two nearby
        // physical codes carrying the same payload.
        val centerTolerance = max(
            24f,
            min(
                max(existingBounds.width(), existingBounds.height()),
                max(bounds.width(), bounds.height()),
            ) * 0.12f,
        )
        return abs(existingBounds.centerX() - bounds.centerX()) <= centerTolerance &&
            abs(existingBounds.centerY() - bounds.centerY()) <= centerTolerance
    }

    private fun computeBounds(points: Array<ResultPoint>?): RectF? {
        if (points.isNullOrEmpty()) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
        for (p in points) {
            if (p.x < minX) minX = p.x; if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y; if (p.y > maxY) maxY = p.y
        }
        return if (minX == Float.MAX_VALUE) null else RectF(minX - 20f, minY - 20f, maxX + 20f, maxY + 20f)
    }

    fun parseResult(
        text: String,
        format: BarcodeFormat? = null,
    ): QrResult {
        return when {
            isLinearBarcode(format) -> QrResult.Product(text)
            format != null && format != BarcodeFormat.QR_CODE -> QrResult.PlainText(text)
            text.startsWith("http://", ignoreCase = true) || text.startsWith("https://", ignoreCase = true) -> {
                val display = text.removePrefix("http://").removePrefix("https://").trimEnd('/')
                QrResult.Url(text, display)
            }
            text.startsWith("WIFI:", ignoreCase = true) -> parseWifi(text)
            text.startsWith("tel:", ignoreCase = true) -> QrResult.Phone(text.removePrefix("tel:").trim())
            text.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseVCard(text)
            text.startsWith("geo:", ignoreCase = true) -> parseGeo(text)
            format == null && text.matches(Regex("\\d{8,14}")) -> QrResult.Product(text)
            else -> QrResult.PlainText(text)
        }
    }

    fun isLinearBarcode(format: BarcodeFormat?): Boolean =
        format != null && format in LINEAR_BARCODE_FORMATS

    fun isQrCode(format: BarcodeFormat?): Boolean =
        format == BarcodeFormat.QR_CODE

    fun formatDisplayName(format: BarcodeFormat?): String = when (format) {
        BarcodeFormat.QR_CODE -> "QR code"
        BarcodeFormat.DATA_MATRIX -> "Data Matrix"
        BarcodeFormat.AZTEC -> "Aztec code"
        BarcodeFormat.PDF_417 -> "PDF417"
        BarcodeFormat.MAXICODE -> "MaxiCode"
        BarcodeFormat.CODABAR -> "Codabar"
        BarcodeFormat.CODE_39 -> "Code 39"
        BarcodeFormat.CODE_93 -> "Code 93"
        BarcodeFormat.CODE_128 -> "Code 128"
        BarcodeFormat.EAN_8 -> "EAN-8"
        BarcodeFormat.EAN_13 -> "EAN-13"
        BarcodeFormat.ITF -> "ITF"
        BarcodeFormat.RSS_14 -> "RSS-14"
        BarcodeFormat.RSS_EXPANDED -> "RSS Expanded"
        BarcodeFormat.UPC_A -> "UPC-A"
        BarcodeFormat.UPC_E -> "UPC-E"
        BarcodeFormat.UPC_EAN_EXTENSION -> "UPC/EAN extension"
        null -> "Code"
        else -> format.name.replace('_', ' ')
    }

    private fun parseWifi(text: String): QrResult {
        val ssid = Regex("S:([^;]*)").find(text)?.groupValues?.get(1) ?: ""
        val pass = Regex("P:([^;]*)").find(text)?.groupValues?.get(1)
        val sec  = Regex("T:([^;]*)").find(text)?.groupValues?.get(1) ?: "WPA"
        return QrResult.WiFi(ssid, pass, sec)
    }

    private fun parseVCard(text: String): QrResult {
        val name  = Regex("FN:([^\r\n]+)").find(text)?.groupValues?.get(1)
        val phone = Regex("TEL[^:]*:([^\r\n]+)").find(text)?.groupValues?.get(1)
        val email = Regex("EMAIL[^:]*:([^\r\n]+)").find(text)?.groupValues?.get(1)
        return QrResult.VCard(name, phone, email, text)
    }

    private fun parseGeo(text: String): QrResult {
        return try {
            val coords = text.removePrefix("geo:").split(",")
            QrResult.GeoPoint(coords[0].toDouble(), coords[1].split("?")[0].toDouble())
        } catch (e: Exception) { QrResult.PlainText(text) }
    }
}
