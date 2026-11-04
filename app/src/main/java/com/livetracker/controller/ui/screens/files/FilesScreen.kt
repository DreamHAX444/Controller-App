package com.livetracker.controller.ui.screens.files

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.livetracker.controller.core.tracker.files.domain.RemoteFileItem
import com.livetracker.controller.core.tracker.files.domain.TransferProgress
import com.livetracker.controller.core.tracker.files.domain.TransferStatus
import com.livetracker.controller.ui.theme.StatusConnecting
import com.livetracker.controller.ui.theme.StatusError
import com.livetracker.controller.ui.theme.StatusLive
import com.livetracker.controller.ui.theme.StatusOffline
import com.livetracker.controller.ui.util.DeviceNameFormatter
import java.io.File
import java.util.Locale

object FilesTestTags {
    const val SCREEN = "files_screen"
    const val DEVICE_HEADER = "files_device_header"
    const val PATH_BREADCRUMB = "files_path_breadcrumb"
    const val UP_BUTTON = "files_up_button"
    const val REFRESH_BUTTON = "files_refresh_button"
    const val FILE_LIST = "files_file_list"
    const val FILE_ITEM_PREFIX = "file_item_"
    const val DOWNLOAD_BUTTON_PREFIX = "download_btn_"
    const val ERROR_BANNER = "files_error_banner"
    const val TRANSFER_CARD = "files_transfer_card"
    const val CONFLICT_DIALOG = "files_conflict_dialog"
    const val REPLACE_BUTTON = "files_replace_button"
    const val SKIP_BUTTON = "files_skip_button"
    const val CANCEL_CONFLICT_BUTTON = "files_cancel_conflict_button"
}

@Composable
fun FilesScreen(
    viewModel: FilesViewModel,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val downloadDir = remember { context.getExternalFilesDir(null) ?: context.filesDir }
    val friendlyTarget = DeviceNameFormatter.formatFriendlyName(uiState.displayName, uiState.deviceId)
    var trackerDropdownExpanded by remember { mutableStateOf(false) }

    var pendingSafItem by remember { mutableStateOf<RemoteFileItem?>(null) }
    val createDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val item = pendingSafItem
        pendingSafItem = null
        if (uri != null && item != null) {
            viewModel.downloadFileToUri(item, uri, context)
        }
        viewModel.dismissDownloadPrompt()
    }

    var pendingBatchItems by remember { mutableStateOf<List<RemoteFileItem>?>(null) }
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val items = pendingBatchItems
        pendingBatchItems = null
        viewModel.dismissDownloadPrompt()
        if (uri != null && !items.isNullOrEmpty()) {
            val resolvedDir = resolveTreeUriToDirectory(context, uri) ?: viewModel.getEffectiveDownloadDestination()
            if (items.size == 1 && !items.first().isDirectory) {
                viewModel.requestDownloadFile(items.first(), resolvedDir)
            } else {
                viewModel.confirmDownloadBatchCustom(items, resolvedDir)
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().testTag(FilesTestTags.SCREEN)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Target Tracker Bar
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderShared,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "TARGET TRACKER",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box {
                        OutlinedButton(
                            onClick = { trackerDropdownExpanded = true },
                            shape = RoundedCornerShape(20.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.sizeIn(minHeight = 40.dp)
                        ) {
                            val isOnline = uiState.availableTrackers.find { it.deviceId.value == uiState.deviceId }?.isOnline ?: (uiState.deviceId.isNotEmpty())
                            val dotColor = if (isOnline) StatusLive else StatusOffline
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(dotColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.deviceId.isNotEmpty()) friendlyTarget else "Select device",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(FilesTestTags.DEVICE_HEADER)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Default.ArrowDropDown,
                                contentDescription = "Select tracker",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = trackerDropdownExpanded,
                            onDismissRequest = { trackerDropdownExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            if (uiState.availableTrackers.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("No trackers available", style = MaterialTheme.typography.bodyMedium) },
                                    onClick = { trackerDropdownExpanded = false },
                                    enabled = false
                                )
                            } else {
                                uiState.availableTrackers.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                val itemDotColor = if (option.isOnline) StatusLive else StatusOffline
                                                Box(
                                                    modifier = Modifier
                                                        .size(8.dp)
                                                        .background(itemDotColor, CircleShape)
                                                )
                                                Spacer(modifier = Modifier.width(8.dp))
                                                Text(
                                                    text = option.displayName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(
                                                        fontWeight = if (option.deviceId.value == uiState.deviceId) FontWeight.Bold else FontWeight.Normal
                                                    )
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                if (option.isFilesSupported) {
                                                    Surface(
                                                        shape = RoundedCornerShape(6.dp),
                                                        color = MaterialTheme.colorScheme.primaryContainer,
                                                        modifier = Modifier.padding(start = 8.dp)
                                                    ) {
                                                        Text(
                                                            text = "FILES",
                                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 9.sp),
                                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            trackerDropdownExpanded = false
                                            viewModel.selectTracker(option.deviceId)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Path Navigation Breadcrumb Bar + Multi-select Trigger
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.navigateUp() },
                        enabled = uiState.currentPath.isNotEmpty() && uiState.currentPath != "/",
                        modifier = Modifier
                            .size(40.dp)
                            .testTag(FilesTestTags.UP_BUTTON)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowUpward,
                            contentDescription = "Up",
                            tint = if (uiState.currentPath.isNotEmpty() && uiState.currentPath != "/") {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            }
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Text(
                        text = if (uiState.currentPath.isEmpty()) "/" else uiState.currentPath,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(FilesTestTags.PATH_BREADCRUMB)
                    )

                    IconButton(
                        onClick = { viewModel.toggleSelectionMode() },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = if (uiState.isSelectionMode) Icons.Default.CheckCircle else Icons.Default.Checklist,
                            contentDescription = "Toggle Multi-select",
                            tint = if (uiState.isSelectionMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = { viewModel.refreshFiles() },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag(FilesTestTags.REFRESH_BUTTON)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    var showLocationMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showLocationMenu = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderSpecial,
                                contentDescription = "Download Location: ${uiState.downloadLocation.label}",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        DropdownMenu(
                            expanded = showLocationMenu,
                            onDismissRequest = { showLocationMenu = false }
                        ) {
                            DownloadLocationType.entries.forEach { loc ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = loc.label,
                                                fontWeight = if (loc == uiState.downloadLocation) FontWeight.Bold else FontWeight.Normal
                                            )
                                            Text(
                                                text = loc.description,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = {
                                        viewModel.setDownloadLocation(loc)
                                        showLocationMenu = false
                                    },
                                    leadingIcon = {
                                        if (loc == uiState.downloadLocation) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Contextual Multi-Selection Action Bar
            AnimatedVisibility(
                visible = uiState.isSelectionMode,
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Exit selection", modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${uiState.selectedFilePaths.size} selected",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.selectAll() },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(Icons.Default.SelectAll, contentDescription = "Select All", modifier = Modifier.size(18.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.requestDownloadSelectedFiles() },
                            enabled = uiState.selectedFilePaths.isNotEmpty(),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Download (${uiState.selectedFilePaths.size})", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }

            // Error Banner
            AnimatedVisibility(
                visible = uiState.errorMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusError.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(FilesTestTags.ERROR_BANNER)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = StatusError,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = StatusError
                            )
                        }
                    }
                }
            }

            // Success Banner
            AnimatedVisibility(
                visible = uiState.successMessage != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = StatusLive.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, StatusLive.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = StatusLive,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.successMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusLive,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { viewModel.clearMessages() }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = StatusLive
                            )
                        }
                    }
                }
            }

            // Active Transfers Card
            if (uiState.activeTransfers.isNotEmpty()) {
                ActiveTransfersPanel(
                    transfers = uiState.activeTransfers,
                    onCancel = { viewModel.cancelTransfer(it) }
                )
            }

            // File List
            if (uiState.isLoading && uiState.files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (uiState.files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Empty Directory",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No files or directories found at this path.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag(FilesTestTags.FILE_LIST),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.files, key = { it.path }) { item ->
                        val isSelected = uiState.selectedFilePaths.contains(item.path)
                        FileItemRow(
                            item = item,
                            isSelectionMode = uiState.isSelectionMode,
                            isSelected = isSelected,
                            onToggleSelect = { viewModel.toggleFileSelection(item.path) },
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleFileSelection(item.path)
                                } else if (item.isDirectory) {
                                    viewModel.openDirectory(item.path)
                                } else {
                                    viewModel.promptDownload(item)
                                }
                            },
                            onDownload = { viewModel.promptDownload(item) }
                        )
                    }
                }
            }
        }

        // Floating Mini Batch Download Banner
        AnimatedVisibility(
            visible = uiState.batchDownloadState.isActive && !uiState.showBatchProgressDialog,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp)
        ) {
            val batch = uiState.batchDownloadState
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shadowElevation = 8.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.50f)),
                onClick = { viewModel.setShowBatchProgressDialog(true) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Batch Downloading (${batch.completedCount}/${batch.totalCount} completed)",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${(batch.overallProgressFraction * 100).toInt()}% • Tap to view details",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { viewModel.setShowBatchProgressDialog(true) }) {
                        Text("View", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    // Batch Download Live Progress Dialog
    if (uiState.showBatchProgressDialog) {
        BatchDownloadProgressDialog(
            batchState = uiState.batchDownloadState,
            onDismiss = { viewModel.setShowBatchProgressDialog(false) },
            onCancel = { viewModel.cancelBatchDownload() }
        )
    }

    // File Conflict / Overwrite Confirmation Dialog
    if (uiState.fileConflictPrompt != null) {
        FileConflictDialog(
            conflict = uiState.fileConflictPrompt!!,
            onReplaceSingle = { item, dir -> viewModel.confirmReplaceSingleFile(item, dir) },
            onReplaceAllBatch = { allFiles, dir -> viewModel.confirmReplaceAllBatch(allFiles, dir) },
            onSkipExistingBatch = { nonConflicting, dir -> viewModel.confirmSkipExistingBatch(nonConflicting, dir) },
            onDismiss = { viewModel.dismissFileConflictPrompt() }
        )
    }

    // "Always Ask" Download Location Dialog (Single file, folder, or batch)
    if (uiState.downloadLocationPrompt != null) {
        when (val prompt = uiState.downloadLocationPrompt!!) {
            is DownloadLocationPrompt.Single -> {
                val fileToPrompt = prompt.item
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDownloadPrompt() },
                    icon = {
                        Icon(
                            imageVector = if (fileToPrompt.isDirectory) Icons.Default.FolderZip else Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    title = {
                        Text(
                            text = if (fileToPrompt.isDirectory) "Download Folder" else "Download File",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = fileToPrompt.name,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (!fileToPrompt.isDirectory) {
                                Text(
                                    text = "Size: ${fileToPrompt.formattedSize}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Where would you like to save this ${if (fileToPrompt.isDirectory) "folder" else "file"}?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDownloadPrompt() }) {
                            Text("Cancel")
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.dismissDownloadPrompt()
                                    viewModel.requestDownloadFile(fileToPrompt)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Default Folder")
                            }
                            Button(
                                onClick = {
                                    viewModel.dismissDownloadPrompt()
                                    if (fileToPrompt.isDirectory) {
                                        pendingBatchItems = listOf(fileToPrompt)
                                        openDocumentTreeLauncher.launch(null)
                                    } else {
                                        pendingSafItem = fileToPrompt
                                        createDocumentLauncher.launch(fileToPrompt.name)
                                    }
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Choose Custom Location")
                            }
                        }
                    }
                )
            }
            is DownloadLocationPrompt.Batch -> {
                val totalBytes = prompt.items.sumOf { it.sizeBytes }
                val formattedTotalSize = formatSize(totalBytes)
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDownloadPrompt() },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.FolderZip,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "Download Selected Items",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${prompt.items.size} files and folders selected",
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                            )
                            if (totalBytes > 0) {
                                Text(
                                    text = "Total size: $formattedTotalSize",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = "Where would you like to save these files?",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDownloadPrompt() }) {
                            Text("Cancel")
                        }
                    },
                    confirmButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.confirmDownloadBatchDefault(prompt.items)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Default Folder")
                            }
                            Button(
                                onClick = {
                                    pendingBatchItems = prompt.items
                                    openDocumentTreeLauncher.launch(null)
                                },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Choose Custom Location")
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun FileItemRow(
    item: RemoteFileItem,
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onClick: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.30f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("${FilesTestTags.FILE_ITEM_PREFIX}${item.name}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isSelectionMode) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggleSelect() },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            Icon(
                imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (item.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (item.isDirectory) "Folder" else item.formattedSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isSelectionMode) {
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.testTag("${FilesTestTags.DOWNLOAD_BUTTON_PREFIX}${item.name}")
                ) {
                    Icon(
                        imageVector = if (item.isDirectory) Icons.Default.FolderZip else Icons.Default.Download,
                        contentDescription = if (item.isDirectory) "Download Folder" else "Download File",
                        tint = StatusLive
                    )
                }
            }
            if (item.isDirectory && !isSelectionMode) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ActiveTransfersPanel(
    transfers: List<TransferProgress>,
    onCancel: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(FilesTestTags.TRANSFER_CARD)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
        ) {
            Text(
                text = "Active Transfers",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            transfers.forEach { transfer ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = transfer.fileName,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { transfer.progressFraction },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            color = StatusLive,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val pct = (transfer.progressFraction * 100).toInt()
                            Text(
                                text = if (transfer.totalBytes > 0) {
                                    "${formatSize(transfer.bytesTransferred)} / ${formatSize(transfer.totalBytes)} ($pct%)"
                                } else if (transfer.bytesTransferred > 0) {
                                    "${formatSize(transfer.bytesTransferred)} transferred"
                                } else {
                                    "Starting transfer..."
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = transfer.status.name,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = when (transfer.status) {
                                    TransferStatus.COMPLETED -> StatusLive
                                    TransferStatus.ERROR, TransferStatus.CANCELLED -> StatusError
                                    TransferStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    if (transfer.status == TransferStatus.IN_PROGRESS) {
                        IconButton(
                            onClick = { onCancel(transfer.transferId) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = StatusError
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun BatchDownloadProgressDialog(
    batchState: BatchDownloadState,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = if (batchState.isCompleted) Icons.Default.CheckCircle else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (batchState.isCompleted) StatusLive else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = if (batchState.isCompleted) "Download Completed" else "Downloading ${batchState.totalCount} Files",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "${batchState.completedCount} of ${batchState.totalCount} files completed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Overall Progress Bar
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Overall Progress",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "${(batchState.overallProgressFraction * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { batchState.overallProgressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (batchState.hasErrors) StatusConnecting else StatusLive,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

                // Scrollable per-file list
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(batchState.items, key = { it.file.path }) { item ->
                        BatchFileProgressRow(item = item)
                    }
                }
            }
        },
        confirmButton = {
            if (batchState.isCompleted) {
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            } else {
                OutlinedButton(onClick = onDismiss) {
                    Text("Run in Background")
                }
            }
        },
        dismissButton = {
            if (!batchState.isCompleted) {
                TextButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.textButtonColors(contentColor = StatusError)
                ) {
                    Text("Cancel All")
                }
            }
        }
    )
}

@Composable
private fun BatchFileProgressRow(item: BatchDownloadItemProgress) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.40f),
        border = BorderStroke(
            1.dp,
            when (item.status) {
                TransferStatus.COMPLETED -> StatusLive.copy(alpha = 0.40f)
                TransferStatus.ERROR, TransferStatus.CANCELLED -> StatusError.copy(alpha = 0.40f)
                TransferStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary.copy(alpha = 0.40f)
                else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
            }
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (item.file.isDirectory) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.file.name,
                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                when (item.status) {
                    TransferStatus.QUEUED -> {
                        Text(
                            text = "Queued",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                    TransferStatus.IN_PROGRESS -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TransferStatus.COMPLETED -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = StatusLive,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    TransferStatus.ERROR, TransferStatus.CANCELLED -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = StatusError,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (item.status == TransferStatus.IN_PROGRESS) {
                LinearProgressIndicator(
                    progress = { item.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val pct = (item.progressFraction * 100).toInt()
                    Text(
                        text = "$pct%",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${formatSize(item.bytesTransferred)} / ${formatSize(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (item.status == TransferStatus.ERROR) {
                Text(
                    text = item.errorMessage ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = StatusError
                )
            } else if (item.status == TransferStatus.COMPLETED) {
                Text(
                    text = "Saved • ${formatSize(item.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = StatusLive
                )
            }
        }
    }
}

@Composable
private fun FileConflictDialog(
    conflict: FileConflictPrompt,
    onReplaceSingle: (RemoteFileItem, File) -> Unit,
    onReplaceAllBatch: (List<RemoteFileItem>, File) -> Unit,
    onSkipExistingBatch: (List<RemoteFileItem>, File) -> Unit,
    onDismiss: () -> Unit
) {
    when (conflict) {
        is FileConflictPrompt.Single -> {
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "Replace Existing File?",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "A file named \"${conflict.item.name}\" already exists in this folder.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Existing file size:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = formatSize(conflict.existingSizeBytes),
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "New file size:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = conflict.item.formattedSize,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    )
                                }
                            }
                        }
                        Text(
                            text = "Do you want to replace the existing file with the downloaded one?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag(FilesTestTags.CANCEL_CONFLICT_BUTTON)
                    ) {
                        Text("Don't Replace")
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onReplaceSingle(conflict.item, conflict.destinationDir) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag(FilesTestTags.REPLACE_BUTTON)
                    ) {
                        Text("Replace")
                    }
                },
                modifier = Modifier.testTag(FilesTestTags.CONFLICT_DIALOG)
            )
        }

        is FileConflictPrompt.Batch -> {
            val nonConflicting = conflict.allSelectedFiles.filter { sel ->
                conflict.conflictingFiles.none { it.path == sel.path }
            }
            AlertDialog(
                onDismissRequest = onDismiss,
                icon = {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                title = {
                    Text(
                        text = "${conflict.conflictingFiles.size} File(s) Already Exist",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "${conflict.conflictingFiles.size} of the ${conflict.allSelectedFiles.size} selected files already exist in destination:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 140.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(conflict.conflictingFiles, key = { it.path }) { file ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.InsertDriveFile,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = file.name,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = file.formattedSize,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        Text(
                            text = if (nonConflicting.isNotEmpty()) {
                                "You can replace all existing files, or skip them and download only the ${nonConflicting.size} new file(s)."
                            } else {
                                "All selected files already exist in destination. Do you want to replace them?"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag(FilesTestTags.CANCEL_CONFLICT_BUTTON)
                        ) {
                            Text("Cancel")
                        }
                        if (nonConflicting.isNotEmpty()) {
                            OutlinedButton(
                                onClick = { onSkipExistingBatch(nonConflicting, conflict.destinationDir) },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.testTag(FilesTestTags.SKIP_BUTTON)
                            ) {
                                Text("Skip Existing (${nonConflicting.size})")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { onReplaceAllBatch(conflict.allSelectedFiles, conflict.destinationDir) },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.testTag(FilesTestTags.REPLACE_BUTTON)
                    ) {
                        Text(if (nonConflicting.isNotEmpty()) "Replace All" else "Replace")
                    }
                },
                modifier = Modifier.testTag(FilesTestTags.CONFLICT_DIALOG)
            )
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun resolveTreeUriToDirectory(context: android.content.Context, treeUri: android.net.Uri): File? {
    try {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        if (docId.startsWith("primary:")) {
            val relativePath = docId.substringAfter("primary:")
            val target = if (relativePath.isEmpty()) {
                android.os.Environment.getExternalStorageDirectory()
            } else {
                File(android.os.Environment.getExternalStorageDirectory(), relativePath)
            }
            if (target.exists() || target.mkdirs()) return target
        } else if (docId.equals("primary", ignoreCase = true)) {
            val target = android.os.Environment.getExternalStorageDirectory()
            if (target.exists() || target.mkdirs()) return target
        } else if (docId.startsWith("raw:")) {
            val target = File(docId.substringAfter("raw:"))
            if (target.exists() || target.mkdirs()) return target
        } else {
            val parts = docId.split(":")
            if (parts.size >= 2) {
                val volumeId = parts[0]
                val subPath = parts[1]
                val target = File("/storage/$volumeId", subPath)
                if (target.exists() || target.mkdirs()) return target
            }
        }
    } catch (_: Exception) {}
    return null
}
