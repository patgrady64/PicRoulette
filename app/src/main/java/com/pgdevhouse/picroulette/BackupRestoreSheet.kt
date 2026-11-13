package com.pgdevhouse.picroulette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreSheet(
    favoriteCount: Int,
    albumCount: Int,
    linkedFavoriteCount: Int,
    sourcePhotoCount: Int,
    isExporting: Boolean,
    isImporting: Boolean,
    isRepairing: Boolean,
    repairProgress: Float,
    repairStatus: String,
    isLibraryScanning: Boolean,
    themeColor: Color,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onRepair: () -> Unit
) {
    var showRepairTools by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 20.dp,
                    end = 20.dp,
                    bottom = 32.dp
                )
        ) {
            Text(
                text = "Backup & restore",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Save your favorites, their zoomed crops, albums, and photo links. Restore them even if your pictures have moved to a new phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(18.dp))

            BackupSummaryCard(
                favoriteCount = favoriteCount,
                linkedFavoriteCount = linkedFavoriteCount,
                themeColor = themeColor
            )

            Spacer(modifier = Modifier.height(14.dp))

            BackupActionCard(
                title = "1. Back up your library",
                description = "Includes the actual saved favorite images, folder structure, original-photo links, and album memberships. Original full-size photos are not copied.",
                accentColor = themeColor
            ) {
                Button(
                    onClick = onExport,
                    enabled = (favoriteCount > 0 || albumCount > 0) &&
                            !isExporting &&
                            !isImporting &&
                            !isRepairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text("Creating backup…")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text("Create backup")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            BackupActionCard(
                title = "2. Restore on this phone or a new one",
                description = "Choose a backup, select where your original pictures are stored, and review missing folders before restoring.",
                accentColor = Color(0xFF57D5C7)
            ) {
                OutlinedButton(
                    onClick = onImport,
                    enabled = !isImporting &&
                            !isExporting &&
                            !isRepairing,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text("Importing backup…")
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text("Choose backup to restore")
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("While a transfer is running", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "You can leave this screen or turn off the display. The Android notification shows progress, and you can return to PicRoulette to see the current stage.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = { showRepairTools = !showRepairTools }) {
                Text(if (showRepairTools) "Hide advanced repair tools ▲" else "Advanced repair tools ▼")
            }
            if (showRepairTools) {
            BackupActionCard(
                title = "Repair original-photo links",
                description = when {
                    favoriteCount == 0 ->
                        "Save at least one favorite before repairing links."

                    sourcePhotoCount == 0 ->
                        "Add and scan your original photo folders before repairing links."

                    else ->
                        "Use this for an older backup or when a normal-library photo is not showing its filled heart."
                },
                accentColor = Color(0xFFFFB55C)
            ) {
                if (isRepairing) {
                    val progress = repairProgress.coerceIn(0f, 1f)
                    val percent = (progress * 100f).toInt()

                    Text(
                        text = repairStatus.ifBlank {
                            "Repairing favorite links"
                        },
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(9.dp))

                    if (progress > 0f) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "$percent% complete",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }

                OutlinedButton(
                    onClick = onRepair,
                    enabled = !isRepairing &&
                            !isImporting &&
                            !isExporting &&
                            !isLibraryScanning &&
                            favoriteCount > 0 &&
                            sourcePhotoCount > 0,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRepairing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        Text("Repairing…")
                    } else {
                        Text("Repair favorite links")
                    }
                }
            }

            }
            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Backups do not delete or modify the favorites currently stored on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
    }
}

@Composable
private fun BackupSummaryCard(
    favoriteCount: Int,
    linkedFavoriteCount: Int,
    themeColor: Color
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = themeColor.copy(alpha = 0.11f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = themeColor.copy(alpha = 0.17f),
                        shape = RoundedCornerShape(15.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = themeColor
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$favoriteCount favorites ready",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Text(
                    text = "$linkedFavoriteCount original-photo links saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
private fun BackupActionCard(
    title: String,
    description: String,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(17.dp)
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = accentColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(14.dp))

            content()
        }
    }
}
