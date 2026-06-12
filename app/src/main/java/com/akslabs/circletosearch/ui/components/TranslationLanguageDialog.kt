/*
 *
 *  * Copyright (C) 2025 AKS-Labs (original author)
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package com.akslabs.circletosearch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.nl.translate.TranslateLanguage

/**
 * Pair: display name → ML Kit language code
 */
private data class LanguageOption(
    val displayName: String,
    val code: String,
    val nativeName: String
)

/**
 * Full list of languages supported by ML Kit Translation.
 * Sorted alphabetically by display name.
 */
private val SUPPORTED_LANGUAGES = listOf(
    LanguageOption("Afrikaans", "af", "Afrikaans"),
    LanguageOption("Arabic", "ar", "العربية"),
    LanguageOption("Bengali", "bn", "বাংলা"),
    LanguageOption("Bulgarian", "bg", "Български"),
    LanguageOption("Catalan", "ca", "Català"),
    LanguageOption("Chinese (Simplified)", "zh", "简体中文"),
    LanguageOption("Chinese (Traditional)", "zh-TW", "繁體中文"),
    LanguageOption("Croatian", "hr", "Hrvatski"),
    LanguageOption("Czech", "cs", "Čeština"),
    LanguageOption("Danish", "da", "Dansk"),
    LanguageOption("Dutch", "nl", "Nederlands"),
    LanguageOption("English", "en", "English"),
    LanguageOption("Estonian", "et", "Eesti"),
    LanguageOption("Filipino", "fil", "Filipino"),
    LanguageOption("Finnish", "fi", "Suomi"),
    LanguageOption("French", "fr", "Français"),
    LanguageOption("German", "de", "Deutsch"),
    LanguageOption("Greek", "el", "Ελληνικά"),
    LanguageOption("Gujarati", "gu", "ગુજરાતી"),
    LanguageOption("Hebrew", "he", "עברית"),
    LanguageOption("Hindi", "hi", "हिन्दी"),
    LanguageOption("Hungarian", "hu", "Magyar"),
    LanguageOption("Icelandic", "is", "Íslenska"),
    LanguageOption("Indonesian", "id", "Bahasa Indonesia"),
    LanguageOption("Irish", "ga", "Gaeilge"),
    LanguageOption("Italian", "it", "Italiano"),
    LanguageOption("Japanese", "ja", "日本語"),
    LanguageOption("Kannada", "kn", "ಕನ್ನಡ"),
    LanguageOption("Korean", "ko", "한국어"),
    LanguageOption("Latvian", "lv", "Latviešu"),
    LanguageOption("Lithuanian", "lt", "Lietuvių"),
    LanguageOption("Malay", "ms", "Bahasa Melayu"),
    LanguageOption("Malayalam", "ml", "മലയാളം"),
    LanguageOption("Marathi", "mr", "मराठी"),
    LanguageOption("Norwegian", "nb", "Norsk"),
    LanguageOption("Persian", "fa", "فارسی"),
    LanguageOption("Polish", "pl", "Polski"),
    LanguageOption("Portuguese", "pt", "Português"),
    LanguageOption("Punjabi", "pa", "ਪੰਜਾਬੀ"),
    LanguageOption("Romanian", "ro", "Română"),
    LanguageOption("Russian", "ru", "Русский"),
    LanguageOption("Serbian", "sr", "Српски"),
    LanguageOption("Slovak", "sk", "Slovenčina"),
    LanguageOption("Slovenian", "sl", "Slovenščina"),
    LanguageOption("Spanish", "es", "Español"),
    LanguageOption("Swahili", "sw", "Kiswahili"),
    LanguageOption("Swedish", "sv", "Svenska"),
    LanguageOption("Tamil", "ta", "தமிழ்"),
    LanguageOption("Telugu", "te", "తెలుగు"),
    LanguageOption("Thai", "th", "ไทย"),
    LanguageOption("Turkish", "tr", "Türkçe"),
    LanguageOption("Ukrainian", "uk", "Українська"),
    LanguageOption("Urdu", "ur", "اردو"),
    LanguageOption("Vietnamese", "vi", "Tiếng Việt")
).sortedBy { it.displayName }

/**
 * Dialog for selecting the target translation language.
 * Shows a searchable list of all ML Kit supported languages.
 */
@Composable
fun TranslationLanguageDialog(
    currentLanguageCode: String?,
    onLanguageSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedCode by remember { mutableStateOf(currentLanguageCode ?: "en") }

    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            SUPPORTED_LANGUAGES
        } else {
            val q = searchQuery.lowercase()
            SUPPORTED_LANGUAGES.filter {
                it.displayName.lowercase().contains(q) ||
                it.nativeName.lowercase().contains(q) ||
                it.code.lowercase().contains(q)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxSize().padding(20.dp),
        shape = RoundedCornerShape(24.dp),
        icon = {
            Icon(
                imageVector = Icons.Default.Language,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = "Translate to",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Search field
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search language...") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Language list
                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredLanguages, key = { it.code }) { lang ->
                        LanguageRow(
                            option = lang,
                            isSelected = selectedCode == lang.code,
                            onClick = { selectedCode = lang.code }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Auto-detect info
                Text(
                    text = "Auto-detect: the source language is identified automatically for each text block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onLanguageSelected(selectedCode) },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Translate")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun LanguageRow(
    option: LanguageOption,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = option.nativeName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isSelected) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}