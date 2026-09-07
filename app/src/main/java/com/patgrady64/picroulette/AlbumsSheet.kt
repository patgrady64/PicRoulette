package com.patgrady64.picroulette

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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToAlbumsSheet(
    currentPhoto: Uri,
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
            Spacer(Modifier.height(8.dp))
            if (albums.isEmpty()) Text("Create your first album for this photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            albums.forEach { album ->
                val checked = currentPhoto.toString() in album.photoUris
                Row(
                    Modifier.fillMaxWidth().clickable { onToggle(album.id, !checked) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked, onCheckedChange = { onToggle(album.id, it) })
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
    val albumed = albums.flatMap { it.photoUris }.toSet()
    val unfiled = allPhotos.filter { it.toString() !in albumed }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Albums", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, null); Text(" New") }
            }
            LazyColumn(Modifier.weight(1f)) {
                if (unfiled.isNotEmpty()) item {
                    AlbumBrowserRow("Not in an Album", unfiled.size, unfiled.firstOrNull(), true, { onPlay(unfiled) }, null, null)
                }
                items(albums, key = { it.id }) { album ->
                    val photos = album.photoUris.map(Uri::parse).filter { uri -> allPhotos.any { it.toString() == uri.toString() } }
                    AlbumBrowserRow(album.name, photos.size, photos.firstOrNull(), photos.isNotEmpty(), { onPlay(photos) }, { editing = album; editName = album.name }, { deleting = album })
                }
                if (albums.isEmpty()) item { Text("No albums yet. Create one, then add photos while using Roulette.", modifier = Modifier.padding(vertical = 24.dp)) }
            }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { creating = false }, title = { Text("New Album") }, text = { OutlinedTextField(newName, { newName = it }, label = { Text("Album name") }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (newName.isNotBlank()) { onCreate(newName); newName = ""; creating = false } }) { Text("Create") } }, dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } })
    editing?.let { album -> AlertDialog(onDismissRequest = { editing = null }, title = { Text("Rename Album") }, text = { OutlinedTextField(editName, { editName = it }, singleLine = true) }, confirmButton = { TextButton(onClick = { if (editName.isNotBlank()) { onRename(album.id, editName); editing = null } }) { Text("Rename") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } }) }
    deleting?.let { album -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete ${album.name}?") }, text = { Text("The album will be deleted. Your photos will not be deleted.") }, confirmButton = { TextButton(onClick = { onDelete(album.id); deleting = null }) { Text("Delete", color = Color.Red) } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } }) }
}

@Composable
private fun AlbumBrowserRow(name: String, count: Int, cover: Uri?, enabled: Boolean, onPlay: () -> Unit, onEdit: (() -> Unit)?, onDelete: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
            )
        } else {
            Icon(Icons.Rounded.PhotoAlbum, null, Modifier.size(48.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(name, style = MaterialTheme.typography.titleMedium); Text(if (count == 1) "1 photo" else "$count photos", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (onEdit != null) IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, "Rename") }
        if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Rounded.DeleteOutline, "Delete") }
        TextButton(onClick = onPlay, enabled = enabled) { Text("Roulette") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouletteCollectionSheet(
    favoriteCount: Int,
    albums: List<PhotoAlbum>,
    allPhotos: List<Uri>,
    onPlayFavorites: () -> Unit,
    onPlayAlbum: (List<Uri>) -> Unit,
    onDismiss: () -> Unit
) {
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
                    AlbumBrowserRow(
                        name = "Favorites",
                        count = favoriteCount,
                        cover = null,
                        enabled = favoriteCount > 0,
                        onPlay = onPlayFavorites,
                        onEdit = null,
                        onDelete = null
                    )
                }
                items(albums, key = { "roulette_${it.id}" }) { album ->
                    val photos = album.photoUris
                        .map(Uri::parse)
                        .filter { uri -> allPhotos.any { it.toString() == uri.toString() } }
                    AlbumBrowserRow(
                        name = album.name,
                        count = photos.size,
                        cover = photos.firstOrNull(),
                        enabled = photos.isNotEmpty(),
                        onPlay = { onPlayAlbum(photos) },
                        onEdit = null,
                        onDelete = null
                    )
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
