package com.patgrady64.picroulette

import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

private enum class SearchLocation(val title: String) {
    FOLDERS("Library folders"), ALBUMS("Albums"), FAVORITES("Favorites")
}

private data class NamedPhoto(val uri: Uri, val filename: String, val group: String)
private data class CandidatePhoto(val uri: Uri, val group: String, val knownName: String? = null)

/** Search filename matches without blocking the UI. Results form a dedicated Roulette session. */
@Composable
fun PhotoFilenameSearchSheet(
    libraryPhotos: List<Uri>,
    albums: List<PhotoAlbum>,
    favorites: List<FavoriteFile>,
    onOpenResults: (List<Uri>, Int, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var location by remember { mutableStateOf(SearchLocation.FOLDERS) }
    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<NamedPhoto>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }
    var checkedCount by remember { mutableIntStateOf(0) }
    var totalCount by remember { mutableIntStateOf(0) }
    var searchRequest by remember { mutableIntStateOf(0) }
    var submittedQuery by remember { mutableStateOf("") }
    var submittedCaseSensitive by remember { mutableStateOf(false) }
    var confirmDismiss by remember { mutableStateOf(false) }

    fun requestDismiss() {
        if (busy || searched || results.isNotEmpty()) {
            confirmDismiss = true
        } else {
            onDismiss()
        }
    }

    // Search only on explicit request; changing scope or text clears stale results.
    LaunchedEffect(location, query, caseSensitive) {
        busy = false
        searched = false
        checkedCount = 0
        totalCount = 0
        results = emptyList()
        submittedQuery = ""
    }
    LaunchedEffect(searchRequest) {
        if (searchRequest == 0) return@LaunchedEffect
        results = emptyList()
        searched = false
        checkedCount = 0
        totalCount = 0
        if (submittedQuery.isBlank()) {
            busy = false
            return@LaunchedEffect
        }
        busy = true
        val searchText = submittedQuery.trim()
        val candidates = when (location) {
            SearchLocation.FOLDERS -> libraryPhotos.map { CandidatePhoto(it, "Library") }
            SearchLocation.FAVORITES -> favorites.map { CandidatePhoto(it.mediaUri, "Favorites", it.fileNameOnDisk) }
            SearchLocation.ALBUMS -> albums.flatMap { album ->
                album.photoUris.mapNotNull { stored ->
                    runCatching { CandidatePhoto(Uri.parse(stored), album.name) }.getOrNull()
                }
            }
        }.distinctBy { it.uri.toString() }
        totalCount = candidates.size
        // Give Compose an opportunity to display the initial 0 / total state.
        yield()
        val found = mutableListOf<NamedPhoto>()
        for (batch in candidates.chunked(32)) {
            // SAF metadata requests are potentially slow: do not run them on the main thread.
            val processed = withContext(Dispatchers.IO) {
                batch.map { candidate ->
                    val filename = candidate.knownName?.takeIf { it.isNotBlank() }
                        ?: runCatching {
                            context.contentResolver.query(
                                candidate.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                            )?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            }
                        }.getOrNull()?.takeIf { it.isNotBlank() }
                        ?: candidate.uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
                        ?: candidate.uri.toString()
                    NamedPhoto(candidate.uri, filename, candidate.group)
                }
            }
            found.addAll(processed.filter { it.filename.contains(searchText, ignoreCase = !submittedCaseSensitive) })
            checkedCount += processed.size
            results = found.toList()
            // Allow the loading text and bar to render between chunks.
            yield()
        }
        busy = false
        searched = true
    }

    if (confirmDismiss) {
        AlertDialog(
            onDismissRequest = { confirmDismiss = false },
            title = { Text("Discard search?") },
            text = { Text(if (busy) "Search is still running. Closing now will stop the search and discard any results." else "Closing Search Photos will discard your search results. Stay here to view them in Roulette.") },
            confirmButton = {
                TextButton(onClick = { confirmDismiss = false; onDismiss() }) { Text("Discard & Close") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDismiss = false }) { Text("Keep Search") }
            }
        )
    }

    AlertDialog(
        onDismissRequest = { requestDismiss() },
        title = { Text("Search Photos") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Filename contains") },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { caseSensitive = !caseSensitive },
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Case sensitive", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = caseSensitive, onCheckedChange = { caseSensitive = it })
                }
                Text("Search in", style = MaterialTheme.typography.labelMedium)
                SearchLocation.entries.forEach { option ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { location = option }.padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        RadioButton(selected = location == option, onClick = { location = option })
                        Text(option.title)
                    }
                }
                Button(
                    onClick = {
                        if (searched) {
                            if (results.isNotEmpty()) {
                                onOpenResults(results.map { it.uri }, 0, location == SearchLocation.FAVORITES)
                            }
                        } else {
                            submittedQuery = query.trim()
                            submittedCaseSensitive = caseSensitive
                            searchRequest++
                        }
                    },
                    enabled = !busy && if (searched) results.isNotEmpty() else query.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(when {
                        busy -> "Searching…"
                        searched -> "Show (${results.size})"
                        else -> "Search"
                    })
                }
                if (busy || searched) {
                    LinearProgressIndicator(
                        progress = { if (totalCount == 0) 0f else checkedCount.toFloat() / totalCount },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    when {
                        busy -> "${results.size} found, $checkedCount of $totalCount searched"
                        searched -> "${results.size} found, $checkedCount searched"
                        else -> "Enter part of a filename, then tap Search."
                    },
                    style = MaterialTheme.typography.bodySmall
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 290.dp)) {
                    itemsIndexed(results) { index, match ->
                        Column(
                            modifier = Modifier.fillMaxWidth().clickable(enabled = !busy) {
                                onOpenResults(results.map { it.uri }, index, location == SearchLocation.FAVORITES)
                            }.padding(vertical = 8.dp)
                        ) {
                            Text(match.filename, style = MaterialTheme.typography.bodyMedium)
                            Text(match.group, style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { requestDismiss() }) { Text("Close") } }
    )
}
