package com.akslabs.circletosearch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.akslabs.circletosearch.ocr.DownloadState
import com.akslabs.circletosearch.ocr.OcrLanguageCatalog
import com.akslabs.circletosearch.ocr.OcrLanguageManager
import com.akslabs.circletosearch.ocr.OcrLanguagePack
import com.akslabs.circletosearch.ocr.sanitizeDownloadErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var manager by remember { mutableStateOf<OcrLanguageManager?>(null) }
    var initError by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var retryTrigger by remember { mutableStateOf(0) }

    LaunchedEffect(retryTrigger) {
        isLoading = true
        initError = null
        try {
            val mgr = OcrLanguageManager.getInstance(context)
            mgr.init()
            manager = mgr
            isLoading = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            initError = e.localizedMessage ?: "Failed to initialize OCR language storage"
            isLoading = false
        }
    }

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Download languages", fontWeight = FontWeight.Bold)
                        Text(
                            "Add or remove languages for text recognition",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        val currentManager = manager
        when {
            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            initError != null && currentManager == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Initialization failed: $initError",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = { retryTrigger++ },
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                ) {
                                    Text("Retry initialization")
                                }
                            }
                        }
                    }
                }
            }
            currentManager != null -> {
                OcrSettingsLoadedContent(
                    manager = currentManager,
                    padding = padding,
                )
            }
        }
    }
}

@Composable
private fun OcrSettingsLoadedContent(
    manager: OcrLanguageManager,
    padding: PaddingValues,
) {
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()

    val activePackId by manager.activePackId.collectAsState()
    val installedPackIds by manager.installedPackIds.collectAsState()
    val downloadStates by manager.downloadStates.collectAsState()
    val isInitialized by manager.isInitialized.collectAsState()
    val initError by manager.initError.collectAsState()

    var packPendingRemoval by remember { mutableStateOf<OcrLanguagePack?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    var isSelecting by remember { mutableStateOf(false) }
    var actionErrorMessage by remember { mutableStateOf<String?>(null) }
    var removalBannerText by remember { mutableStateOf<String?>(null) }

    val isBusy = !isInitialized || isSelecting || isDeleting
    val downloadBusy = downloadStates.values.any {
        it is DownloadState.Downloading || it is DownloadState.Cancelling
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
            // 1. Info Card
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("On-device OCR", fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "This OCR runs on device. Language downloads do not upload screenshots or text. " +
                                    "Only one language pack can be active at a time. Downloading a language does not switch your active pack.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // Initialization error banner
            initError?.let { err ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Initialization failed: $err",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Spacer(Modifier.height(4.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            try {
                                                manager.init()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                ) {
                                    Text("Retry initialization")
                                }
                            }
                        }
                    }
                }
            }

            // Action error banner
            actionErrorMessage?.let { errText ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = errText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { actionErrorMessage = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }

            // Notice banner if an active pack was removed
            removalBannerText?.let { bannerText ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = bannerText,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { removalBannerText = null }) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss")
                            }
                        }
                    }
                }
            }

            // 2. Bundled Language Section
            item {
                Text(
                    "Included with app",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            item {
                val bundledPack = OcrLanguageCatalog.bundledPack
                val isSelected = (activePackId == bundledPack.id)
                LanguagePackCard(
                    pack = bundledPack,
                    isActive = isSelected,
                    isBundled = true,
                    isInstalled = true,
                    enabled = !isBusy,
                    downloadState = DownloadState.Idle,
                    onSelect = {
                        scope.launch {
                            try {
                                isSelecting = true
                                actionErrorMessage = null
                                manager.selectActivePack(bundledPack.id)
                                removalBannerText = null
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                actionErrorMessage = e.localizedMessage ?: "Failed to select language pack"
                            } finally {
                                isSelecting = false
                            }
                        }
                    },
                    onDownload = {},
                    onCancelDownload = {},
                    onDelete = {},
                )
            }

            // 3. Downloaded Languages Section
            val downloadedPacks = OcrLanguageCatalog.downloadablePacks.filter { pack ->
                installedPackIds.contains(pack.id)
            }
            if (downloadedPacks.isNotEmpty()) {
                item {
                    Text(
                        "Downloaded languages",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                items(downloadedPacks, key = { it.id }) { pack ->
                    val isSelected = (activePackId == pack.id)
                    LanguagePackCard(
                        pack = pack,
                        isActive = isSelected,
                        isBundled = false,
                        isInstalled = true,
                        enabled = !isBusy,
                        downloadState = DownloadState.Idle,
                        onSelect = {
                            scope.launch {
                                try {
                                    isSelecting = true
                                    actionErrorMessage = null
                                    manager.selectActivePack(pack.id)
                                    removalBannerText = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    actionErrorMessage = e.localizedMessage ?: "Failed to select language pack"
                                } finally {
                                    isSelecting = false
                                }
                            }
                        },
                        onDownload = {},
                        onCancelDownload = {},
                        onDelete = {
                            deleteError = null
                            packPendingRemoval = pack
                        },
                    )
                }
            }

            // 4. Available for Download Section
            val availablePacks = OcrLanguageCatalog.downloadablePacks.filter { pack ->
                !installedPackIds.contains(pack.id)
            }
            if (availablePacks.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        Text(
                            "Available for download",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Official PaddlePaddle models downloaded from Hugging Face via HTTPS",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                items(availablePacks, key = { it.id }) { pack ->
                    val downloadState = downloadStates[pack.id] ?: DownloadState.Idle
                    LanguagePackCard(
                        pack = pack,
                        isActive = false,
                        isBundled = false,
                        isInstalled = false,
                        enabled = !isBusy && (!downloadBusy || downloadState is DownloadState.Downloading),
                        downloadState = downloadState,
                        onSelect = {},
                        onDownload = {
                            scope.launch {
                                try {
                                    actionErrorMessage = null
                                    manager.startDownload(pack.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    actionErrorMessage = sanitizeDownloadErrorMessage(e)
                                }
                            }
                        },
                        onCancelDownload = {
                            scope.launch {
                                try {
                                    manager.cancelDownload(pack.id)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    actionErrorMessage = sanitizeDownloadErrorMessage(e)
                                }
                            }
                        },
                        onDelete = {},
                    )
                }
            }

            // 5. Documentation & Apache 2.0 attribution footer
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    ),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "PaddleOCR models are published by PaddlePaddle under the Apache License 2.0. " +
                                "All recognition models are downloaded via HTTPS and run 100% on-device. " +
                                "The bundled PP-OCRv6 detector is shared across all languages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        TextButton(
                            onClick = { uriHandler.openUri("https://github.com/PaddlePaddle/PaddleOCR") },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text("PaddleOCR source and models")
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }

    // Removal Confirmation Dialog with in-dialog progress and actionable error handling
    packPendingRemoval?.let { pack ->
        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    packPendingRemoval = null
                    deleteError = null
                }
            },
            icon = {
                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            },
            title = {
                Text("Remove ${pack.displayName}?")
            },
            text = {
                Column {
                    val isCurrentlyActive = (activePackId == pack.id)
                    val activeWarning = if (isCurrentlyActive) {
                        "\n\nThis pack is currently active. Recognition will switch back to the bundled East Slavic model."
                    } else {
                        ""
                    }
                    Text("Are you sure you want to remove this language pack (${pack.formattedSize})? You can download it again at any time.$activeWarning")

                    deleteError?.let { err ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Error: $err",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDeleting,
                    onClick = {
                        val removedPack = pack
                        scope.launch {
                            try {
                                isDeleting = true
                                deleteError = null
                                val wasActive = manager.removePack(removedPack.id)
                                packPendingRemoval = null
                                if (wasActive) {
                                    removalBannerText = "Active pack was removed. Switched back to bundled East Slavic (Russian, English, Ukrainian, Belarusian)."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                deleteError = e.localizedMessage ?: "Failed to remove language pack"
                            } finally {
                                isDeleting = false
                            }
                        }
                    },
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Remove")
                }
            },
            dismissButton = {
                OutlinedButton(
                    enabled = !isDeleting,
                    onClick = {
                        packPendingRemoval = null
                        deleteError = null
                    },
                ) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun LanguagePackCard(
    pack: OcrLanguagePack,
    isActive: Boolean,
    isBundled: Boolean,
    isInstalled: Boolean,
    enabled: Boolean,
    downloadState: DownloadState,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            },
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Details section: icon and text
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    Icons.Default.Translate,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .padding(top = 2.dp),
                    tint = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = pack.displayName,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (isActive) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = "Active",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pack.coverageDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = pack.formattedSize,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            // Action row placed below language details to ensure readability on large fonts
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isInstalled) {
                    if (isActive) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp),
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Active pack",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Active pack",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    } else {
                        FilledTonalButton(
                            onClick = onSelect,
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Use")
                        }
                    }

                    if (!isBundled) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = onDelete,
                            enabled = enabled,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "Remove",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                } else {
                    // Not installed
                    when (downloadState) {
                        is DownloadState.Idle -> {
                            Button(
                                onClick = onDownload,
                                enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Filled.VerticalAlignBottom,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Download")
                            }
                        }
                        is DownloadState.Downloading -> {
                            OutlinedButton(
                                onClick = onCancelDownload,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Cancel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        is DownloadState.Cancelling -> {
                            Text(
                                text = "Cancelling...",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        is DownloadState.Failed -> {
                            Button(
                                onClick = onDownload,
                                enabled = enabled,
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Retry")
                            }
                        }
                    }
                }
            }

            // Progress bar and status for Downloading or Failed
            when (downloadState) {
                is DownloadState.Downloading -> {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "${(downloadState.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "${formatBytes(downloadState.downloadedBytes)} / ${formatBytes(downloadState.totalBytes)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                is DownloadState.Failed -> {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Download failed: ${downloadState.errorMessage}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {}
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "%.1f MB".format(Locale.US, mb)
}
