package com.pgdevhouse.picroulette

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartRouletteSheet(
    initial: ProRouletteFilters,
    onFiltersChanged: (ProRouletteFilters) -> Unit,
    onApply: (ProRouletteFilters) -> Unit,
    onDismiss: () -> Unit
) {
    var date by remember { mutableStateOf(initial.dateFilter) }
    var favorites by remember { mutableStateOf(initial.favoriteFilter) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.88f)
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Smart Roulette · Pro", style = MaterialTheme.typography.headlineSmall)
            Text("Choose which photos can appear in your next roulette.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text("CURRENT SELECTION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Date: ${date.label}  •  Favorites: ${favorites.label}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text("1  •  Date range", style = MaterialTheme.typography.titleMedium)
            ProDateFilter.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { date = option; onFiltersChanged(ProRouletteFilters(option, favorites)) }.padding(vertical = 4.dp)) {
                    RadioButton(selected = date == option, onClick = { date = option; onFiltersChanged(ProRouletteFilters(option, favorites)) })
                    Text(option.label, Modifier.padding(top = 12.dp))
                }
            }
            Text("2  •  Favorites", style = MaterialTheme.typography.titleMedium)
            ProFavoriteFilter.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { favorites = option; onFiltersChanged(ProRouletteFilters(date, option)) }.padding(vertical = 4.dp)) {
                    RadioButton(selected = favorites == option, onClick = { favorites = option; onFiltersChanged(ProRouletteFilters(date, option)) })
                    Text(option.label, Modifier.padding(top = 12.dp))
                }
            }
            Button(onClick = { onApply(ProRouletteFilters(date, favorites)) }, modifier = Modifier.fillMaxWidth()) {
                Text("Start Roulette with These Filters")
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouletteHistorySheet(
    history: List<RouletteHistoryEntry>,
    recentFolders: List<RecentFolderEntry>,
    onOpen: (Uri) -> Unit,
    onOpenFolder: (RecentFolderEntry) -> Unit,
    onClearPhotos: () -> Unit,
    onClearFolders: () -> Unit,
    onDismiss: () -> Unit
) {
    var foldersTab by remember { mutableStateOf(false) }
    // Remember separate search terms so switching tabs does not erase the user's place.
    var photosQuery by remember { mutableStateOf("") }
    var foldersQuery by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }
    val activeQuery = if (foldersTab) foldersQuery else photosQuery
    val filteredPhotos = remember(history, photosQuery) {
        history.filter { it.uri.lastPathSegment.orEmpty().contains(photosQuery.trim(), ignoreCase = true) }
    }
    val filteredFolders = remember(recentFolders, foldersQuery) {
        recentFolders.filter { it.label.contains(foldersQuery.trim(), ignoreCase = true) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(if (foldersTab) "Clear recent folders?" else "Clear recent photos?") },
            text = { Text("This removes ${if (foldersTab) recentFolders.size else history.size} entries from this history list. Your photos, albums, and favorites will not be deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    if (foldersTab) onClearFolders() else onClearPhotos()
                }) { Text("Clear history") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("History · Pro", style = MaterialTheme.typography.headlineSmall)
                TextButton(
                    onClick = { confirmClear = true },
                    enabled = if (foldersTab) recentFolders.isNotEmpty() else history.isNotEmpty()
                ) { Text("Clear") }
            }
            TabRow(selectedTabIndex = if (foldersTab) 1 else 0) {
                Tab(selected = !foldersTab, onClick = { foldersTab = false }, text = { Text("Recent Photos") })
                Tab(selected = foldersTab, onClick = { foldersTab = true }, text = { Text("Recent Folders") })
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = activeQuery,
                onValueChange = { if (foldersTab) foldersQuery = it else photosQuery = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(if (foldersTab) "Find a recent folder" else "Find a recent photo") },
                singleLine = true,
                trailingIcon = {
                    if (activeQuery.isNotEmpty()) {
                        TextButton(onClick = { if (foldersTab) foldersQuery = "" else photosQuery = "" }) {
                            Text("Clear")
                        }
                    }
                }
            )
            Text(
                "${if (foldersTab) filteredFolders.size else filteredPhotos.size} of ${if (foldersTab) recentFolders.size else history.size} entries",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(Modifier.height(12.dp))
            if (!foldersTab) {
                Text("Recently shown photos. Tap one to reopen it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filteredPhotos.isEmpty()) Text(if (history.isEmpty()) "No photo history yet." else "No recent photos match your search.", modifier = Modifier.padding(vertical = 24.dp))
                else LazyColumn(Modifier.weight(1f)) {
                    items(filteredPhotos, key = { "${it.uri}_${it.viewedAt}" }) { entry ->
                        val date = remember(entry.viewedAt) { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(entry.viewedAt)) }
                        Column(Modifier.fillMaxWidth().clickable { onOpen(entry.uri) }.padding(vertical = 12.dp)) {
                            Text(entry.uri.lastPathSegment ?: "Photo", maxLines = 1)
                            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            } else {
                Text("Your 10 most recently viewed folders. Tap one to reopen its photos.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (filteredFolders.isEmpty()) Text(if (recentFolders.isEmpty()) "No folder history yet." else "No recent folders match your search.", modifier = Modifier.padding(vertical = 24.dp))
                else LazyColumn(Modifier.weight(1f)) {
                    items(filteredFolders, key = { it.key }) { entry ->
                        val date = remember(entry.viewedAt) { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(entry.viewedAt)) }
                        Column(Modifier.fillMaxWidth().clickable { onOpenFolder(entry) }.padding(vertical = 12.dp)) {
                            Text(entry.label, maxLines = 1)
                            Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close history") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

