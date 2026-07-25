package com.patgrady64.picroulette

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PicRouletteOptionsSheet(
    hapticFeedbackEnabled: Boolean,
    keepScreenAwakeEnabled: Boolean,
    showPhotoCounterByDefault: Boolean,
    scanLibraryOnStart: Boolean,
    defaultPhotoDisplayMode: PhotoDisplayMode,
    favoriteCount: Int,
    themeColor: Color,
    onHapticFeedbackChanged: (Boolean) -> Unit,
    onKeepScreenAwakeChanged: (Boolean) -> Unit,
    onPhotoCounterDefaultChanged: (Boolean) -> Unit,
    onScanLibraryOnStartChanged: (Boolean) -> Unit,
    onDefaultPhotoDisplayModeChanged: (PhotoDisplayMode) -> Unit,
    onOpenBackupRestore: () -> Unit,
    onDismiss: () -> Unit
) {
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
                    bottom = 30.dp
                )
        ) {
            Text(
                text = "Options",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Customize PicRoulette and manage your app data.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Gray
            )

            OptionSectionTitle("Experience")

            OptionSwitchRow(
                title = "Haptic feedback",
                description = "Vibrate for taps, favorites, and viewer actions",
                checked = hapticFeedbackEnabled,
                themeColor = themeColor,
                onCheckedChange = onHapticFeedbackChanged
            )

            Spacer(modifier = Modifier.height(10.dp))

            OptionSwitchRow(
                title = "Keep screen awake",
                description = "Prevent the screen from sleeping while viewing photos",
                checked = keepScreenAwakeEnabled,
                themeColor = themeColor,
                onCheckedChange = onKeepScreenAwakeChanged
            )

            OptionSectionTitle("Viewer")

            OptionSwitchRow(
                title = "Show photo counter by default",
                description = "Start each viewer session with the current photo number visible",
                checked = showPhotoCounterByDefault,
                themeColor = themeColor,
                onCheckedChange = onPhotoCounterDefaultChanged
            )

            Spacer(modifier = Modifier.height(10.dp))

            PhotoDisplayModeCard(
                selectedMode = defaultPhotoDisplayMode,
                themeColor = themeColor,
                onModeSelected = onDefaultPhotoDisplayModeChanged
            )

            OptionSectionTitle("Library")

            OptionSwitchRow(
                title = "Scan library on app start",
                description = if (scanLibraryOnStart) {
                    "Refresh selected folders whenever PicRoulette opens"
                } else {
                    "Open quickly using the last completed scan"
                },
                checked = scanLibraryOnStart,
                themeColor = themeColor,
                onCheckedChange = onScanLibraryOnStartChanged
            )

            OptionSectionTitle("Data")

            Surface(
                onClick = onOpenBackupRestore,
                shape = RoundedCornerShape(22.dp),
                color = Color.White.copy(alpha = 0.055f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(17.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OptionIcon(
                        iconColor = Color(0xFFB79CFF)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = null,
                            tint = Color(0xFFB79CFF),
                            modifier = Modifier.size(25.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Backup & restore",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = if (favoriteCount == 1) {
                                "Protect 1 favorite and its original-photo link"
                            } else {
                                "Protect $favoriteCount favorites and their original-photo links"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = Color.White.copy(alpha = 0.045f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = Color(0xFFFF5C8A),
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(10.dp))

                    Text(
                        text = "PicRoulette scans your selected folders locally. Your photos are never uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionSectionTitle(title: String) {
    Spacer(modifier = Modifier.height(22.dp))

    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White
    )

    Spacer(modifier = Modifier.height(10.dp))
}

@Composable
private fun OptionSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    themeColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.055f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(17.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OptionIcon(iconColor = themeColor) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    tint = themeColor,
                    modifier = Modifier.size(25.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun PhotoDisplayModeCard(
    selectedMode: PhotoDisplayMode,
    themeColor: Color,
    onModeSelected: (PhotoDisplayMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Color.White.copy(alpha = 0.055f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(17.dp)) {
            Text(
                text = "Default photo display",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = "Fit shows the whole photo. Fill uses the entire screen and may crop the edges.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                DisplayModeChoice(
                    modifier = Modifier.weight(1f),
                    title = "Fit",
                    selected = selectedMode == PhotoDisplayMode.FIT,
                    themeColor = themeColor,
                    onClick = {
                        onModeSelected(PhotoDisplayMode.FIT)
                    }
                )

                DisplayModeChoice(
                    modifier = Modifier.weight(1f),
                    title = "Fill",
                    selected = selectedMode == PhotoDisplayMode.FILL,
                    themeColor = themeColor,
                    onClick = {
                        onModeSelected(PhotoDisplayMode.FILL)
                    }
                )
            }
        }
    }
}

@Composable
private fun DisplayModeChoice(
    modifier: Modifier,
    title: String,
    selected: Boolean,
    themeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) {
            themeColor.copy(alpha = 0.22f)
        } else {
            Color.Black.copy(alpha = 0.20f)
        },
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = themeColor.copy(alpha = 0.75f)
            )
        } else {
            null
        }
    ) {
        Box(
            modifier = Modifier.padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = if (selected) themeColor else Color.White
            )
        }
    }
}

@Composable
private fun OptionIcon(
    iconColor: Color,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                color = iconColor.copy(alpha = 0.14f),
                shape = RoundedCornerShape(15.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        icon()
    }
}
