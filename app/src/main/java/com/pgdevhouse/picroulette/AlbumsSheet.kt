package com.pgdevhouse.picroulette

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PhotoAlbum
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumsSheet(
    currentPhoto: Uri,
    linkedPhotoUris: Set<String> = setOf(currentPhoto.toString()),
    albums: List<PhotoAlbum>,
    onToggle: (String, Boolean) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Add to Albums", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text("Select an album to add or remove this photo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (albums.isEmpty()) Text("Create your first album for this photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            albums.forEach { album ->
                val checked = album.photoUris.any { it in linkedPhotoUris }
                fun toggleAndClose(included: Boolean) {
                    onToggle(album.id, included)
                    onDismiss()
                }
                Row(
                    Modifier.fillMaxWidth().clickable { toggleAndClose(!checked) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggleAndClose(it) })
                    Text(album.name, Modifier.weight(1f))
                    Text("${album.photoUris.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (creating) {
                OutlinedTextField(name, { name = it }, label = { Text("Album name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { creating = false; name = "" }) { Text("Cancel") }
                    TextButton(onClick = { if (name.isNotBlank()) { onCreate(name); name = ""; creating = false } }) { Text("Create & add") }
                }
            } else {
                TextButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("New Album") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsBrowserSheet(
    albums: List<PhotoAlbum>,
    isPro: Boolean,
    allPhotos: List<Uri>,
    onPlay: (List<Uri>) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<PhotoAlbum?>(null) }
    var editName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<PhotoAlbum?>(null) }
    var resolvingAlbumId by remember { mutableStateOf<String?>(null) }
    var albumQuery by remember { mutableStateOf("") }
    var albumSort by remember { mutableStateOf(AlbumSortMode.NAME) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Counts are provided by the existing Room-backed album membership store.
    // The list uses a stable snapshot for sorting; no membership records are modified.
    val visibleAlbums = remember(albums, albumQuery, albumSort) {
        val matching = albums.filter { it.name.contains(albumQuery.trim(), ignoreCase = true) }
        when (albumSort) {
            AlbumSortMode.NAME -> matching.sortedBy { it.name.lowercase() }
            AlbumSortMode.NEWEST -> matching.sortedByDescending { it.createdAt }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Albums", style = MaterialTheme.typography.headlineSmall)
                    Text("${albums.size} ${if (albums.size == 1) "album" else "albums"} · Organize your photos", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New")
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = albumQuery,
                onValueChange = { albumQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                label = { Text("Find an album") }
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Rounded.Sort, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                FilterChip(selected = albumSort == AlbumSortMode.NAME, onClick = { albumSort = AlbumSortMode.NAME }, label = { Text("Name") })
                FilterChip(selected = albumSort == AlbumSortMode.NEWEST, onClick = { albumSort = AlbumSortMode.NEWEST }, label = { Text("Recently added") })
            }
            Spacer(Modifier.height(4.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visibleAlbums, key = { it.id }) { album ->
                    // Album v2 membership remains authoritative, including entries whose SAF URI changes.
                    val memberCount = albumMembershipCount(context, album.id)
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                            AlbumBrowserRow(
                                name = album.name,
                                count = memberCount,
                                enabled = memberCount > 0 && resolvingAlbumId == null,
                                isLoading = resolvingAlbumId == album.id,
                                onPlay = {
                                    if (resolvingAlbumId == null) {
                                        resolvingAlbumId = album.id
                                        scope.launch {
                                            try {
                                                val photos = withContext(Dispatchers.IO) {
                                                    resolveAlbumPhotos(context = context, albumId = album.id, scannedPhotos = allPhotos)
                                                }
                                                onPlay(photos)
                                            } finally {
                                                resolvingAlbumId = null
                                            }
                                        }
                                    }
                                },
                                onEdit = { editing = album; editName = album.name },
                                onDelete = { deleting = album }
                            )
                        }
                    }
                }
                if (albums.isEmpty()) item {
                    Text("No albums yet. Create one, then add photos while using Roulette.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                } else if (visibleAlbums.isEmpty()) item {
                    Text("No albums match your search.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New Album") }, text = { OutlinedTextField(newName, { newName = it }, label = { Text("Album name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onCreate(newName); newName = ""; creating = false } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    editing?.let { album -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Rename Album") }, text = { OutlinedTextField(editName, { editName = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (editName.isNotBlank()) { onRename(album.id, editName); editing = null } }) { Text("Rename") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }) }
    deleting?.let { album -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${album.name}?") }, text = { Text("The album will be deleted. Your photos will not be deleted.") }, confirmButton = { TextButton(onClick = { onDelete(album.id); deleting = null }) { Text("Delete", color = Color.Red) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

private enum class AlbumSortMode { NAME, NEWEST }

private fun albumMonogramColor(name: String): Color {
    val initial = name.trim().firstOrNull()?.uppercaseChar() ?: '?'
    // Curated PicRoulette purple/yellow palette. The letter chooses the color,
    // so the same album initial is stable across launches rather than random.
    val palette = listOf(
        Color(0xFF4A148C), Color(0xFF6A1B9A), Color(0xFF7B1FA2),
        Color(0xFF8E24AA), Color(0xFFAB47BC), Color(0xFFF9A825),
        Color(0xFFFBC02D), Color(0xFFFDD835), Color(0xFFFFD54F)
    )
    return palette[(initial.code and 0x7fffffff) % palette.size]
}

private fun albumMonogramTextColor(background: Color): Color {
    // Relative luminance threshold keeps the letter readable on both purple and yellow tiles.
    return if (background.luminance() > 0.48f) Color(0xFF1A1A1A) else Color.White
}

@Composable
private fun AlbumBrowserRow(
    name: String,
    count: Int,
    enabled: Boolean,
    onPlay: (() -> Unit)?,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    isLoading: Boolean = false,
    hidePlayButtonWhileLoading: Boolean = false,
    isFavorites: Boolean = false
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val tileColor = if (isFavorites) Color(0xFFAA2758) else albumMonogramColor(name)
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = tileColor,
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isFavorites) {
                Icon(Icons.Rounded.Favorite, contentDescription = null, tint = Color.White, modifier = Modifier.size(27.dp))
            } else {
                Text(
                    text = initial,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = albumMonogramTextColor(tileColor)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleMedium); Text(if (count == 1) "1 photo" else "$count photos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Rename") }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
        if (onPlay != null) {
            if (isLoading && hidePlayButtonWhileLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp
                )
            } else {
                TextButton(onClick = onPlay, enabled = enabled) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Loading…")
                    } else {
                        Text("Roulette")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouletteCollectionSheet(
    favoriteCount: Int,
    isFavoritesLoading: Boolean,
    albums: List<PhotoAlbum>,
    isPro: Boolean,
    allPhotos: List<Uri>,
    onPlayFavorites: () -> Unit,
    onPlayAlbum: (List<Uri>) -> Unit,
    onRequestPro: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var resolvingAlbumId by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp)
        ) {
            Text("Pick a Roulette", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Choose Favorites or one of your albums.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )
            LazyColumn(Modifier.weight(1f)) {
                item {
                    Text("YOUR FAVORITES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp))
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Box(Modifier.padding(horizontal = 10.dp, vertical = 2.dp)) {
                            AlbumBrowserRow(
                        name = "Favorites",
                        count = favoriteCount,
                        enabled = favoriteCount > 0 && !isFavoritesLoading,
                        onPlay = onPlayFavorites,
                        onEdit = null,
                        onDelete = null,
                        isLoading = isFavoritesLoading,
                        hidePlayButtonWhileLoading = true,
                        isFavorites = true
                            )
                        }
                    }
                }
                if (isPro) {
                    if (albums.isNotEmpty()) item {
                        Text("YOUR ALBUMS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    }
                    items(albums, key = { "roulette_${it.id}" }) { album ->
                        // Use the Room-backed album membership directly. Filtering by exact URI
                        // made valid members disappear when Android exposed the same photo through
                        // a different SAF URI.
                        val memberCount = albumMembershipCount(context, album.id)
                        AlbumBrowserRow(
                            name = album.name,
                            count = memberCount,
                            enabled = memberCount > 0 && resolvingAlbumId == null,
                            isLoading = resolvingAlbumId == album.id,
                            onPlay = {
                                if (resolvingAlbumId == null) {
                                    resolvingAlbumId = album.id
                                    scope.launch {
                                        val photos = withContext(Dispatchers.IO) {
                                            resolveAlbumPhotos(
                                                context = context,
                                                albumId = album.id,
                                                scannedPhotos = allPhotos
                                            )
                                        }
                                        resolvingAlbumId = null
                                        onPlayAlbum(photos)
                                    }
                                }
                            },
                            onEdit = null,
                            onDelete = null
                        )
                    }
                } else {
                    item {
                        AlbumBrowserRow(
                            name = "Albums · Pro",
                            count = albums.size,
                            enabled = true,
                            onPlay = onRequestPro,
                            onEdit = null,
                            onDelete = null
                        )
                    }
                }
                if (favoriteCount == 0 && albums.isEmpty()) {
                    item {
                        Text(
                            "No Favorites or albums yet.",
                            modifier = Modifier.padding(vertical = 24.dp)
                        )
                    }
                }
            }
        }
    }
}
