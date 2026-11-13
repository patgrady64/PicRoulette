package com.pgdevhouse.picroulette

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryFoldersSheet(
    folders: List<FolderConfig>,
    isScanning: Boolean,
    photosFound: Int,
    currentFolderName: String,
    themeColor: Color,
    onDismiss: () -> Unit,
    onAddFolder: () -> Unit,
    onToggleSubfolders: (FolderConfig, Boolean) -> Unit,
    onRemoveFolder: (FolderConfig) -> Unit,
    onRescan: () -> Unit,
    onFixFavoritesMapping: () -> Unit,
    isFixingFavoritesMapping: Boolean,
    onConvertFavorites: () -> Unit,
    isConvertingFavorites: Boolean,
    conversionProgress: FavoriteFormatProgress?
) {
    var showAdvancedTools by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 30.dp
                )
        ) {
            Text(
                text = "Library folders",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Choose the folders PicRoulette searches when building your photo library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(18.dp))

            if (isScanning) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = themeColor.copy(alpha = 0.12f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp,
                            color = themeColor
                        )

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$photosFound photos found",
                                fontWeight = FontWeight.Bold
                            )

                            Text(
                                text = currentFolderName
                                    .takeIf { it.isNotBlank() }
                                    ?.let { "Scanning $it" }
                                    ?: "Scanning your selected folders",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
            }

            Text("PHOTO SOURCES", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text("${folders.size} ${if (folders.size == 1) "folder" else "folders"} selected",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(10.dp))
            if (folders.isEmpty()) {
                EmptyFolderState(themeColor = themeColor)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 390.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        items = folders,
                        key = { it.uri.toString() }
                    ) { config ->
                        FolderCard(
                            config = config,
                            themeColor = themeColor,
                            onToggleSubfolders = {
                                onToggleSubfolders(config, it)
                            },
                            onRemove = {
                                onRemoveFolder(config)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onAddFolder,
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderCopy,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(9.dp))
                Text("Add library folder")
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onRescan,
                enabled = !isScanning && folders.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
                Spacer(modifier = Modifier.width(9.dp))
                Text(if (isScanning) "Scanning…" else "Rescan photo library")
            }
            Text("Refresh the list of photos after adding, moving, or deleting files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))

            HorizontalDivider()
            TextButton(
                onClick = { showAdvancedTools = !showAdvancedTools },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (showAdvancedTools) "Hide advanced repair tools  ▲" else "Advanced repair tools  ▼")
            }
            if (showAdvancedTools || isFixingFavoritesMapping || isConvertingFavorites) {
                Text("Only use these tools if favorites are missing, linked to the wrong originals, or were created by an older version.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = onFixFavoritesMapping,
                    enabled = !isScanning && !isFixingFavoritesMapping && !isConvertingFavorites && folders.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isFixingFavoritesMapping) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(if (isFixingFavoritesMapping) "Repairing favorite links…" else "Fix favorites mapping")
                }
                Text("Reconnect saved favorites to their original photos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onConvertFavorites,
                    enabled = !isScanning && !isFixingFavoritesMapping && !isConvertingFavorites,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isConvertingFavorites) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
                    }
                    Spacer(modifier = Modifier.width(9.dp))
                    Text(if (isConvertingFavorites) "Converting favorites…" else "Convert legacy favorite names")
                }
                Text("One-time update for older favorite filenames. New favorites already use FAV_ names.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (isConvertingFavorites && conversionProgress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { conversionProgress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    val eta = conversionProgress.etaSeconds()
                    Text(
                        "${conversionProgress.completed} / ${conversionProgress.total} done" +
                            (eta?.let { " · ETA ${it / 60}m ${it % 60}s" } ?: " · Calculating ETA…"),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

        }
    }
}

@Composable
private fun EmptyFolderState(
    themeColor: Color
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        color = themeColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(18.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderCopy,
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(30.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "No folders selected",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Add at least one folder before starting a roulette session.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FolderCard(
    config: FolderConfig,
    themeColor: Color,
    onToggleSubfolders: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(
                            color = themeColor.copy(alpha = 0.13f),
                            shape = RoundedCornerShape(14.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderCopy,
                        contentDescription = null,
                        tint = themeColor
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = readableFolderNameForUi(config.uri),
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = config.uri.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Remove folder",
                        tint = Color(0xFFFF6B6B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Include subfolders",
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = if (config.includeSubfolders) {
                            "Photos inside nested folders are included."
                        } else {
                            "Only photos directly inside this folder are included."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Switch(
                    checked = config.includeSubfolders,
                    onCheckedChange = onToggleSubfolders
                )
            }
        }
    }
}

private fun readableFolderNameForUi(uri: Uri): String {
    val documentId = runCatching {
        DocumentsContract.getTreeDocumentId(uri)
    }.getOrNull()

    return Uri.decode(
        documentId
            ?.substringAfter(":")
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: "Library folder"
    )
}
