package com.llsl.viper4android.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llsl.viper4android.R
import com.llsl.viper4android.library.FileLibrary
import com.llsl.viper4android.library.LibraryEntry
import com.llsl.viper4android.library.LibraryKind

// A field showing the selected kernel or VDC; tapping it opens a searchable
// list. Unlike a dropdown, the list only builds the rows on screen, so it stays
// fast with thousands of files.
@Composable
fun LibraryPickerField(
    label: String,
    kind: LibraryKind,
    selectedKey: String,
    library: FileLibrary,
    onSelect: (String) -> Unit,
    onDeleteImported: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val entries by library.entries.collectAsStateWithLifecycle()
    val noneLabel = stringResource(R.string.label_none)
    val shown =
        if (selectedKey.isEmpty()) {
            noneLabel
        } else {
            entries.firstOrNull { it.kind == kind && it.key == selectedKey }?.label ?: library.label(kind, selectedKey)
        }

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = UiDimens.XSmall),
    ) {
        OutlinedTextField(
            value = shown,
            onValueChange = {},
            readOnly = true,
            label = { Text(label, style = MaterialTheme.typography.bodySmall) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        // A read-only text field swallows taps; catch them above it.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .clickable { open = true },
        )
    }

    if (open) {
        LibraryPickerDialog(
            title = label,
            kind = kind,
            selectedKey = selectedKey,
            library = library,
            onSelect = {
                onSelect(it)
                open = false
            },
            onDeleteImported = onDeleteImported,
            onDismiss = { open = false },
        )
    }
}

@Composable
private fun LibraryPickerDialog(
    title: String,
    kind: LibraryKind,
    selectedKey: String,
    library: FileLibrary,
    onSelect: (String) -> Unit,
    onDeleteImported: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries by library.entries.collectAsStateWithLifecycle()
    val folders by library.linkedFolders.collectAsStateWithLifecycle()
    val scanning by library.isScanning.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<LibraryEntry?>(null) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let(library::addFolder)
        }

    val filtered =
        remember(entries, kind, query) {
            entries.filter {
                it.kind == kind &&
                    (query.isBlank() || it.label.contains(query, ignoreCase = true) || it.folder.contains(query, ignoreCase = true))
            }
        }

    // Open at the current selection, with a couple of rows above it showing.
    // Row 0 is "None".
    val listState =
        rememberLazyListState(
            initialFirstVisibleItemIndex =
                (filtered.indexOfFirst { it.key == selectedKey } + 1 - 2).coerceAtLeast(0),
        )

    AlertDialog(
        modifier = Modifier.fillMaxWidth(0.9f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (scanning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(onClick = library::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.library_rescan))
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = UiDimens.Medium),
                    placeholder = { Text(stringResource(R.string.library_search)) },
                    singleLine = true,
                )
                DialogCard {
                    DialogList(state = listState) {
                        if (query.isBlank()) {
                            item(key = "none") {
                                EntryRow(
                                    label = stringResource(R.string.label_none),
                                    folder = "",
                                    selected = selectedKey.isEmpty(),
                                    onClick = { onSelect("") },
                                    onDelete = null,
                                )
                            }
                        }
                        items(filtered, key = { "entry:${it.key}" }) { entry ->
                            EntryRow(
                                label = entry.label,
                                folder = entry.folder,
                                selected = entry.key == selectedKey,
                                onClick = { onSelect(entry.key) },
                                onDelete = if (entry.isLinked) null else ({ deleteTarget = entry }),
                            )
                        }
                    }
                    if (filtered.isEmpty()) {
                        DialogEmptyState(text = stringResource(R.string.library_empty))
                    }
                }
                DialogSectionLabel(stringResource(R.string.library_folders))
                DialogCard {
                    for (folder in folders) {
                        DialogListItemRow {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text(
                                text = FileLibrary.folderLabel(folder),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { library.removeFolder(folder) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.library_remove_folder))
                            }
                        }
                        RowDivider()
                    }
                    ActionRow(
                        label = stringResource(R.string.library_add_folder),
                        subtitle = stringResource(R.string.library_add_folder_desc),
                        onClick = { folderPicker.launch(null) },
                        icon = Icons.Default.CreateNewFolder,
                    )
                }
            }
        },
        confirmButton = {},
    )

    deleteTarget?.let { entry ->
        ConfirmDialog(
            title = stringResource(R.string.delete_file_title),
            body = stringResource(R.string.delete_file_message, entry.label),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                onDeleteImported(entry.key)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun EntryRow(
    label: String,
    folder: String,
    selected: Boolean,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    DialogListItemRow(onClick = onClick) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (folder.isNotEmpty()) {
                Text(
                    text = folder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}
