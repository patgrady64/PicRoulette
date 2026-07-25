package com.patgrady64.picroulette

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.util.Locale

private val HomeBackground = Color(0xFF09070D)
private val HomeSurface = Color(0xFF15111C)
private val HomeSurfaceRaised = Color(0xFF1B1624)
private val MutedText = Color(0xFFAAA3B3)

@Composable
fun PicRouletteHomeScreen(
    modifier: Modifier = Modifier,
    themeColor: Color,
    photoCount: Int,
    favoriteCount: Int,
    folderCount: Int,
    isScanning: Boolean,
    scanPhotosFound: Int,
    scanFoldersCompleted: Int,
    scanTotalFolders: Int,
    scanCurrentFolder: String,
    onStartRoulette: () -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenFolders: () -> Unit,
    onOpenOptions: () -> Unit,
    onOpenAboutSupport: () -> Unit,
    onRefresh: () -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(HomeBackground),
        contentPadding = PaddingValues(
            start = 20.dp,
            top = 18.dp,
            end = 20.dp,
            bottom = 32.dp
        ),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            HomeHeader(
                themeColor = themeColor,
                isScanning = isScanning,
                onRefresh = onRefresh
            )
        }

        item {
            StartRouletteCard(
                themeColor = themeColor,
                photoCount = photoCount,
                isScanning = isScanning,
                scanPhotosFound = scanPhotosFound,
                scanFoldersCompleted = scanFoldersCompleted,
                scanTotalFolders = scanTotalFolders,
                scanCurrentFolder = scanCurrentFolder,
                onClick = onStartRoulette
            )
        }

        item {
            LibrarySummaryRow(
                photoCount = if (isScanning) scanPhotosFound else photoCount,
                favoriteCount = favoriteCount,
                folderCount = folderCount,
                themeColor = themeColor
            )
        }

        item {
            Text(
                text = "Your library",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeActionTile(
                    modifier = Modifier.weight(1f),
                    title = "Favorites",
                    subtitle = if (favoriteCount == 1) {
                        "1 saved photo"
                    } else {
                        "${formatCount(favoriteCount)} saved photos"
                    },
                    icon = Icons.Rounded.Favorite,
                    accentColor = Color(0xFFFF5C8A),
                    enabled = favoriteCount > 0,
                    onClick = onOpenFavorites
                )

                HomeActionTile(
                    modifier = Modifier.weight(1f),
                    title = "Folders",
                    subtitle = if (folderCount == 1) {
                        "1 library folder"
                    } else {
                        "$folderCount library folders"
                    },
                    icon = Icons.Rounded.FolderCopy,
                    accentColor = Color(0xFFB79CFF),
                    enabled = true,
                    onClick = onOpenFolders
                )
            }
        }

        item {
            Text(
                text = "App",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            HomeMenuRow(
                title = "Options",
                subtitle = "Viewer, scanning, haptics, backup, and restore",
                icon = Icons.Rounded.Settings,
                accentColor = Color(0xFFB79CFF),
                onClick = onOpenOptions
            )
        }

        item {
            HomeMenuRow(
                title = "About & Support",
                subtitle = "Developer bio, links, app info, and support",
                icon = Icons.Rounded.Info,
                accentColor = Color(0xFF55D6C2),
                onClick = onOpenAboutSupport
            )
        }
    }
}

@Composable
private fun HomeHeader(
    themeColor: Color,
    isScanning: Boolean,
    onRefresh: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            Color(0xFF7B55FF),
                            themeColor
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "PicRoulette",
                color = Color.White,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )

            Text(
                text = if (isScanning) {
                    "Refreshing your photo library"
                } else {
                    "Rediscover the photos you forgot"
                },
                color = MutedText,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Surface(
            shape = CircleShape,
            color = HomeSurfaceRaised
        ) {
            IconButton(
                onClick = onRefresh,
                enabled = !isScanning
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(21.dp),
                        strokeWidth = 2.dp,
                        color = themeColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Refresh photo library",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun StartRouletteCard(
    themeColor: Color,
    photoCount: Int,
    isScanning: Boolean,
    scanPhotosFound: Int,
    scanFoldersCompleted: Int,
    scanTotalFolders: Int,
    scanCurrentFolder: String,
    onClick: () -> Unit
) {
    val enabled = photoCount > 0 && !isScanning

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(32.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(236.dp)
                .background(
                    Brush.linearGradient(
                        colors = if (enabled || isScanning) {
                            listOf(
                                Color(0xFF4C2AA8),
                                themeColor,
                                Color(0xFF126A6A)
                            )
                        } else {
                            listOf(
                                Color(0xFF27212F),
                                Color(0xFF1A1720)
                            )
                        }
                    )
                )
                .padding(26.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = Color.White.copy(alpha = 0.16f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(78.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
            ) {
                Text(
                    text = when {
                        isScanning -> "SCANNING LIBRARY"
                        photoCount > 0 -> "${formatCount(photoCount)} PHOTOS READY"
                        else -> "ADD A LIBRARY FOLDER"
                    },
                    color = Color.White.copy(alpha = 0.82f),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(7.dp))

                Text(
                    text = if (photoCount > 0 || isScanning) {
                        "Start Roulette"
                    } else {
                        "Build Your Library"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Black
                )

                Spacer(modifier = Modifier.height(5.dp))

                Text(
                    text = when {
                        isScanning -> {
                            buildString {
                                append("${formatCount(scanPhotosFound)} photos found")

                                if (scanTotalFolders > 0) {
                                    append(" • Folder ")
                                    append((scanFoldersCompleted + 1).coerceAtMost(scanTotalFolders))
                                    append(" of ")
                                    append(scanTotalFolders)
                                }

                                if (scanCurrentFolder.isNotBlank()) {
                                    append(" • ")
                                    append(scanCurrentFolder)
                                }
                            }
                        }

                        photoCount > 0 -> "Tap to shuffle your library and begin"
                        else -> "Choose at least one folder to get started"
                    },
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodyMedium
                )

                if (isScanning) {
                    Spacer(modifier = Modifier.height(18.dp))

                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.18f)
                    )
                }
            }
        }
    }
}

@Composable
private fun LibrarySummaryRow(
    photoCount: Int,
    favoriteCount: Int,
    folderCount: Int,
    themeColor: Color
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = HomeSurface,
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp, horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            LibraryStat(
                modifier = Modifier.weight(1f),
                value = formatCount(photoCount),
                label = "Photos",
                color = themeColor
            )

            LibraryStat(
                modifier = Modifier.weight(1f),
                value = formatCount(favoriteCount),
                label = "Favorites",
                color = Color(0xFFFF5C8A)
            )

            LibraryStat(
                modifier = Modifier.weight(1f),
                value = folderCount.toString(),
                label = "Folders",
                color = Color(0xFFB79CFF)
            )
        }
    }
}

@Composable
private fun LibraryStat(
    modifier: Modifier,
    value: String,
    label: String,
    color: Color
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            color = color,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black
        )

        Text(
            text = label,
            color = MutedText,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
private fun HomeActionTile(
    modifier: Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(148.dp),
        color = if (enabled) HomeSurfaceRaised else HomeSurface,
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor.copy(alpha = if (enabled) 0.15f else 0.07f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = if (enabled) 1f else 0.38f),
                    modifier = Modifier.size(25.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    color = Color.White.copy(alpha = if (enabled) 1f else 0.45f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = subtitle,
                    color = MutedText.copy(alpha = if (enabled) 1f else 0.5f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun HomeMenuRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = HomeSurface,
        shape = RoundedCornerShape(22.dp)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(accentColor.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall
                )

                Text(
                    text = subtitle,
                    color = MutedText,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private fun formatCount(value: Int): String {
    return NumberFormat
        .getIntegerInstance(Locale.US)
        .format(value)
}
