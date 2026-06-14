package com.akslabs.circletosearch.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.QrCode
import com.akslabs.circletosearch.utils.QrResult

// --- Phase 44: Smart Entity Extractor Models ---
sealed class SmartEntity(
    val text: String,
    val bounds: android.graphics.RectF,
    val typeName: String,
    val icon: ImageVector,
    val sourceColor: Color
) {
    class Url(text: String, bounds: android.graphics.RectF) : SmartEntity(text, bounds, "Link", Icons.Default.Link, Color(0xFF1A73E8))
    class Email(text: String, bounds: android.graphics.RectF) : SmartEntity(text, bounds, "Email", Icons.Default.Email, Color(0xFF1A73E8))
    class Phone(text: String, bounds: android.graphics.RectF) : SmartEntity(text, bounds, "Phone", Icons.Default.Phone, Color(0xFF43A047))
    class Upi(text: String, bounds: android.graphics.RectF) : SmartEntity(text, bounds, "UPI", Icons.Default.Person, Color(0xFF8E24AA))
    class QrCode(val qrResult: QrResult, val rawText: String, bounds: android.graphics.RectF) : SmartEntity("QR Code", bounds, "QR", Icons.Default.QrCode, Color(0xFF1A73E8))
}