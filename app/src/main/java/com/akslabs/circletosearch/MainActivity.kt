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


package com.akslabs.circletosearch

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akslabs.circletosearch.ui.components.PrivacyDialog
import com.akslabs.circletosearch.ui.theme.CircleToSearchTheme
import com.akslabs.circletosearch.utils.PrivacyPreferences
import com.akslabs.circletosearch.utils.StorageUtils

import com.akslabs.circletosearch.ui.components.AccessibilityDisclosureDialog
import com.akslabs.circletosearch.ui.components.UnifiedSearchMethodSelector
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                StorageUtils.pruneTransientImageCache(this@MainActivity)
            } catch (error: Exception) {
                android.util.Log.w(
                    "CircleToSearch",
                    "Unable to prune transient image cache",
                    error,
                )
            }
        }
        Toast.makeText(this, "Double tap status bar or use floating bubble to start.", Toast.LENGTH_LONG).show()
        setContent {
            CircleToSearchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    var currentScreen by remember { mutableStateOf("home") }
                    androidx.compose.animation.Crossfade(targetState = currentScreen) { screen ->
                        when (screen) {
                            "settings" -> com.akslabs.circletosearch.ui.OverlaySettingsScreen(onBack = { currentScreen = "home" })
                            "ocr_settings" -> com.akslabs.circletosearch.ui.OcrSettingsScreen(onBack = { currentScreen = "home" })
                            else -> SetupScreen(
                                onSettingsClick = { currentScreen = "settings" },
                                onOcrSettingsClick = { currentScreen = "ocr_settings" }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onSettingsClick: () -> Unit, onOcrSettingsClick: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    // Privacy Dialog State
    val privacyPreferences = remember { PrivacyPreferences(context) }
    var showPrivacyDialog by remember { mutableStateOf(!privacyPreferences.hasAcceptedPrivacyPolicy()) }
    
    // Permission States
    var isAccessibilityEnabled by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }
    var assistantState by remember { mutableStateOf(getAssistantActivationState(context)) }
    var showAccessibilityDisclosure by remember { mutableStateOf(false) }
    
    // Check permissions on Resume
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
                assistantState = getAssistantActivationState(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    
    // Show Privacy Dialog First - User must accept before seeing main UI
    if (showPrivacyDialog) {
        PrivacyDialog(
            onAccept = {
                privacyPreferences.setPrivacyAccepted()
                showPrivacyDialog = false
            }
        )
        return // Don't show main UI until accepted
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            
            // 1. Header
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Circle to Search",
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
                IconButton(
                    onClick = onSettingsClick,
                    modifier = Modifier.align(Alignment.CenterEnd)
                ) {
                     Icon(Icons.Default.Settings, contentDescription = "Overlay Settings", tint = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Search anything on your screen instantly.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
            
            Spacer(modifier = Modifier.height(32.dp))

            // 2. REQUIRED: Accessibility Service
            Text(
                text = "REQUIRED:",
                style = MaterialTheme.typography.labelSmall,
                color = if (isAccessibilityEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )
            
            if (isAccessibilityEnabled) {
                // Granted State
                Card(
                     colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Granted",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Accessibility Service Active",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "Double tap status bar to launch CircleToSearch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            } else {
                 // Action Needed State
                Card(
                    onClick = {
                        showAccessibilityDisclosure = true
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Accessibility,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Enable Accessibility",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                             Text(
                                text = "To do its job properly, the app needs this permission.\n" +
                                        "Tap allow and we’re good to go! \uD83D\uDC4D",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // 3. OPTIONAL: Default Assistant
            Text(
                text = "OPTIONAL: For Home Button Trigger",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.Start).padding(bottom = 8.dp)
            )

            if (assistantState == AssistantActivationState.ACTIVE) {
                 // Granted State
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                         Text(
                            text = "Default Assistant Set",
                            style = MaterialTheme.typography.titleMedium,
                             color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            } else {
                val needsReconnect = assistantState == AssistantActivationState.DISCONNECTED
                val cardContainerColor = if (needsReconnect) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
                val cardContentColor = if (needsReconnect) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }
                 // Action State
                Card(
                    onClick = {
                        val intent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
                        context.startActivity(intent)
                    },
                    colors = CardDefaults.cardColors(
                        containerColor = cardContainerColor
                    ),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (needsReconnect) {
                                Icons.Default.Warning
                            } else {
                                Icons.Default.TouchApp
                            },
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = cardContentColor
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (needsReconnect) {
                                    "Reconnect Default Assistant"
                                } else {
                                    "Set as Default Assistant"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = cardContentColor
                            )
                            Text(
                                text = if (needsReconnect) {
                                    "Android still lists CircleToSearch as the assistant, but its voice service is disconnected. Tap here, choose None, then select CircleToSearch again. Until then, use the status-bar trigger or Quick Settings tile."
                                } else {
                                    "Hold the Home button or edge-swipe up to summon CircleToSearch — like calling your Pokémon."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = cardContentColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 4. Settings (Bubble)
            Text(
                text = "CUSTOMIZATION",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.align(Alignment.Start)
            )
            BubbleSwitch(context)
            
            val uiPreferences = remember { com.akslabs.circletosearch.utils.UIPreferences(context) }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(16.dp)
            ) {
                UnifiedSearchMethodSelector(
                    uiPreferences = uiPreferences
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Lens needs Google App Installed. But Degoogled friends can stick with the versatile Multi-Search Engine mode.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            ListItem(
                headlineContent = { Text("OCR Language Settings") },
                supportingContent = { Text("View bundled offline PaddleOCR models") },
                trailingContent = { Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.clickable(onClick = onOcrSettingsClick),
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            Spacer(modifier = Modifier.height(25.dp))

            // Privacy Note
            Text(
                text = "That’s it. No more permissions.\n We’re not trying to adopt your phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))


            // 5. Footer
            SocialLinksRow(
                context = context
            )
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aks-labs"))
                        context.startActivity(intent)
                    }
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "From AKS-Labs With ❤️",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }

    if (showAccessibilityDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showAccessibilityDisclosure = false
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, "Could not open Accessibility Settings", android.widget.Toast.LENGTH_LONG).show()
                }
            },
            onDismiss = {
                showAccessibilityDisclosure = false
            }
        )
    }
}

// Helper Functions
fun isAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expectedComponentName = android.content.ComponentName(context, CircleToSearchAccessibilityService::class.java)
    val enabledServicesSetting = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    
    val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    
    while (colonSplitter.hasNext()) {
        val componentNameString = colonSplitter.next()
        val enabledComponent = android.content.ComponentName.unflattenFromString(componentNameString)
        if (enabledComponent != null && enabledComponent == expectedComponentName)
            return true
    }
    return false
}

enum class AssistantActivationState {
    ACTIVE,
    DISCONNECTED,
    NOT_SELECTED,
}

internal fun classifyAssistantActivation(
    activeService: Boolean,
    roleHeld: Boolean,
): AssistantActivationState = when {
    activeService -> AssistantActivationState.ACTIVE
    roleHeld -> AssistantActivationState.DISCONNECTED
    else -> AssistantActivationState.NOT_SELECTED
}

fun getAssistantActivationState(
    context: android.content.Context,
): AssistantActivationState {
    val component = android.content.ComponentName(context, CircleToSearchVoiceService::class.java)
    val activeService = VoiceInteractionService.isActiveService(context, component)

    val roleManager = context.getSystemService(RoleManager::class.java)
    val roleHeld = roleManager?.let {
        it.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            it.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    } == true

    return classifyAssistantActivation(activeService, roleHeld)
}

fun isDefaultAssistant(context: android.content.Context): Boolean {
    return getAssistantActivationState(context) == AssistantActivationState.ACTIVE
}

// ... SocialLinksRow and BubbleSwitch same as before ...
@Composable
fun SocialLinksRow(
    context: android.content.Context
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/aks-labs"))
                context.startActivity(intent)
            }, 
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                painter = painterResource(id = com.akslabs.circletosearch.R.drawable.github),
                contentDescription = "Github",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(8.dp)
            )
        }
        
        IconButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/akslabs"))
                context.startActivity(intent)
            }, 
            colors = IconButtonDefaults.filledTonalIconButtonColors(),
            modifier = Modifier.padding(horizontal = 8.dp)
        ) {
            Icon(
                painter = painterResource(id = com.akslabs.circletosearch.R.drawable.telegram),
                contentDescription = "Telegram",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(8.dp).size(23.dp)
            )
        }
    }
}

@Composable
fun BubbleSwitch(context: android.content.Context) {
    val prefs = context.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
    val isBubbleEnabled = remember { mutableStateOf(prefs.getBoolean("bubble_enabled", false)) }

    ListItem(
        headlineContent = { Text("Floating Bubble") },
        supportingContent = { Text("Show a floating button triggers search") },
        trailingContent = {
            Switch(
                checked = isBubbleEnabled.value,
                onCheckedChange = { enabled ->
                    isBubbleEnabled.value = enabled
                    prefs.edit().putBoolean("bubble_enabled", enabled).apply()
                }
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = Color.Transparent
        )
    )
}
