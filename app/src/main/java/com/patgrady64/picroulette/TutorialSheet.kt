package com.patgrady64.picroulette

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private data class TutorialPage(val title: String, val body: String, val icon: ImageVector)

@Composable
fun PicRouletteTutorialSheet(themeColor: Color, onFinished: () -> Unit) {
    val pages = remember {
        listOf(
            TutorialPage("Welcome to PicRoulette", "Rediscover photos you forgot you had. PicRoulette randomly brings your library back to life instead of making you scroll for it.", Icons.Rounded.PlayArrow),
            TutorialPage("Choose your Library Folders", "Library Folders are where PicRoulette gets its photos. Choose your folders, then Start Roulette to shuffle through them.", Icons.Rounded.FolderCopy),
            TutorialPage("Choose what to Roulette", "Start Roulette uses your Library Folders. Pick a Roulette lets you shuffle only your Favorites or one specific Album.", Icons.Rounded.Shuffle),
            TutorialPage("Organize while you browse", "The heart controls Favorites. Albums can hold a photo in one or several collections. A highlighted Albums icon means the photo is already in an album.", Icons.Rounded.PhotoAlbum),
            TutorialPage("You're ready", "Tap a photo to show its controls, then favorite it, add it to albums, or view its details. Replay this tutorial anytime from Options.", Icons.Rounded.CheckCircle)
        )
    }
    var page by remember { mutableIntStateOf(0) }
    val current = pages[page]

    Dialog(
        onDismissRequest = onFinished,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = themeColor.copy(alpha = .16f)
                ) {
                    Icon(
                        current.icon,
                        contentDescription = null,
                        tint = themeColor,
                        modifier = Modifier.padding(14.dp).size(38.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    current.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    current.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(18.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    pages.indices.forEach { index ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = if (index == page) themeColor else Color.Gray.copy(alpha = .3f),
                            modifier = Modifier.size(if (index == page) 22.dp else 8.dp, 8.dp)
                        ) {}
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (page > 0) {
                        OutlinedButton(
                            onClick = { page-- },
                            modifier = Modifier.weight(1f)
                        ) { Text("Back") }
                    }

                    Button(
                        onClick = { if (page < pages.lastIndex) page++ else onFinished() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (page == pages.lastIndex) "Start Exploring" else "Next")
                    }
                }
            }
        }
    }
}
