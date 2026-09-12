package com.patgrady64.picroulette

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
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Smart Roulette · Pro", style = MaterialTheme.typography.headlineSmall)
            Text("Filter your main library before PicRoulette builds the deck.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Date", style = MaterialTheme.typography.titleMedium)
            ProDateFilter.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { date = option; onFiltersChanged(ProRouletteFilters(option, favorites)) }.padding(vertical = 4.dp)) {
                    RadioButton(selected = date == option, onClick = { date = option; onFiltersChanged(ProRouletteFilters(option, favorites)) })
                    Text(option.label, Modifier.padding(top = 12.dp))
                }
            }
            Text("Favorites", style = MaterialTheme.typography.titleMedium)
            ProFavoriteFilter.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { favorites = option; onFiltersChanged(ProRouletteFilters(date, option)) }.padding(vertical = 4.dp)) {
                    RadioButton(selected = favorites == option, onClick = { favorites = option; onFiltersChanged(ProRouletteFilters(date, option)) })
                    Text(option.label, Modifier.padding(top = 12.dp))
                }
            }
            Button(onClick = { onApply(ProRouletteFilters(date, favorites)) }, modifier = Modifier.fillMaxWidth()) { Text("Start Roulette") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouletteHistorySheet(
    history: List<RouletteHistoryEntry>,
    onOpen: (Uri) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.75f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Roulette History · Pro", style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = onClear, enabled = history.isNotEmpty()) { Text("Clear") }
            }
            Text("Recently shown photos. Tap one to reopen it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            if (history.isEmpty()) Text("No Roulette history yet.", modifier = Modifier.padding(vertical = 24.dp))
            else LazyColumn(Modifier.weight(1f)) {
                items(history, key = { "${it.uri}_${it.viewedAt}" }) { entry ->
                    val date = remember(entry.viewedAt) { SimpleDateFormat("MMM d, yyyy · h:mm a", Locale.getDefault()).format(Date(entry.viewedAt)) }
                    Column(Modifier.fillMaxWidth().clickable { onOpen(entry.uri) }.padding(vertical = 12.dp)) {
                        Text(entry.uri.lastPathSegment ?: "Photo", maxLines = 1)
                        Text(date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
