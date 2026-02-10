package com.patgrady64.picroulette // <-- CHANGE THIS to your package name

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- 1. MULTI-FOLDER PERSISTENCE ---

fun saveFolders(context: Context, uris: Set<Uri>) {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("folder_uris", uris.map { it.toString() }.toSet()).apply()
}

fun getSavedFolders(context: Context): Set<Uri> {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    return prefs.getStringSet("folder_uris", emptySet())?.map { Uri.parse(it) }?.toSet() ?: emptySet()
}

// --- 2. FAVORITES & SCANNER HELPERS ---

fun saveToGlobalFavorites(context: Context, sourceUri: Uri): Boolean {
    return try {
        val resolver = context.contentResolver
        val inputStream = resolver.openInputStream(sourceUri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "Fav_${System.currentTimeMillis()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PicRoulette_Favorites")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        uri?.let {
            resolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
            true
        } ?: false
    } catch (e: Exception) { false }
}

fun queryGlobalFavorites(context: Context): List<Uri> {
    val favorites = mutableListOf<Uri>()
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%Pictures/PicRoulette_Favorites%")
    try {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                favorites.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString()))
            }
        }
    } catch (e: Exception) {}
    return favorites
}

fun queryAllImagesInTree(context: Context, treeUri: Uri): List<Uri> {
    val images = mutableListOf<Uri>()
    try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        context.contentResolver.query(childrenUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1)?.startsWith("image/") == true) {
                    images.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0)))
                }
            }
        }
    } catch (e: Exception) {}
    return images
}

// --- 3. MAIN ACTIVITY ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) { PicRouletteApp() }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp() {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (context as? ComponentActivity)?.window
    val scope = rememberCoroutineScope()

    // State
    val folderList = remember { mutableStateOf(getSavedFolders(context)) }
    val pickedFolderImages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val activeSessionList = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val currentIndex = remember { mutableStateOf(0) }
    val isPlaying = remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    val uiVisible = remember { mutableStateOf(true) }
    val showDeleteConfirm = remember { mutableStateOf(false) }

    // Zoom State
    val scale = remember { mutableStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { z, o, _ -> scale.value *= z; offset.value += o }

    // Function to re-scan all folders
    val scanAllFolders: suspend () -> Unit = {
        isScanning.value = true
        val allImages = mutableListOf<Uri>()
        folderList.value.forEach { uri ->
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                allImages.addAll(queryAllImagesInTree(context, uri))
            } catch (e: Exception) {}
        }
        pickedFolderImages.value = allImages
        isScanning.value = false
    }

    LaunchedEffect(Unit) { scanAllFolders() }

    SideEffect {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (isPlaying.value) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(currentIndex.value) { scale.value = 1f; offset.value = Offset.Zero }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            val updated = folderList.value + it
            folderList.value = updated
            saveFolders(context, updated)
            scope.launch(Dispatchers.IO) { scanAllFolders() }
        }
    }

    if (!isPlaying.value) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
            Text("🎰 PicRoulette", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(16.dp))

            // Folder Management List
            Text("Folders to scan:", style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                items(folderList.value.toList()) { uri ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(uri.path ?: "Unknown Folder", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                val updated = folderList.value - uri
                                folderList.value = updated
                                saveFolders(context, updated)
                                scope.launch(Dispatchers.IO) { scanAllFolders() }
                            }) { Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.Red) }
                        }
                    }
                }
            }

            if (isScanning.value) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                Button(onClick = { launcher.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text("Add Folder") }
                Spacer(modifier = Modifier.height(8.dp))
                if (pickedFolderImages.value.isNotEmpty()) {
                    Button(onClick = {
                        activeSessionList.value = pickedFolderImages.value.shuffled()
                        currentIndex.value = 0
                        isPlaying.value = true
                    }, modifier = Modifier.fillMaxWidth()) { Text("START ROULETTE (${pickedFolderImages.value.size})") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    isScanning.value = true
                    scope.launch(Dispatchers.IO) {
                        val favs = queryGlobalFavorites(context)
                        withContext(Dispatchers.Main) {
                            if (favs.isNotEmpty()) {
                                activeSessionList.value = favs.shuffled()
                                currentIndex.value = 0
                                isPlaying.value = true
                                Toast.makeText(context, "Showing ${favs.size} favorites", Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, "No favorites found!", Toast.LENGTH_SHORT).show()
                            isScanning.value = false
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("VIEW FAVORITES") }
            }
        }
    } else {
        // --- VIEWER SCREEN (Same logic as before) ---
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).transformable(state = transformState)
            .combinedClickable(
                onClick = { if (activeSessionList.value.isNotEmpty()) currentIndex.value = (currentIndex.value + 1) % activeSessionList.value.size },
                onLongClick = { uiVisible.value = !uiVisible.value }
            )
        ) {
            if (activeSessionList.value.isNotEmpty()) {
                AsyncImage(
                    model = activeSessionList.value[currentIndex.value],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer(
                        scaleX = scale.value.coerceIn(1f, 5f), scaleY = scale.value.coerceIn(1f, 5f),
                        translationX = offset.value.x, translationY = offset.value.y
                    ),
                    contentScale = ContentScale.Fit
                )
            }
            if (uiVisible.value) {
                Row(modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { isPlaying.value = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(0.7f))) { Text("Stop") }
                    IconButton(onClick = {
                        val uri = activeSessionList.value[currentIndex.value]
                        scope.launch(Dispatchers.IO) {
                            if (saveToGlobalFavorites(context, uri)) withContext(Dispatchers.Main) { Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show() }
                        }
                    }, modifier = Modifier.background(Color.DarkGray.copy(0.5f), MaterialTheme.shapes.medium)) { Text("⭐") }
                    Button(onClick = { currentIndex.value = if (currentIndex.value > 0) currentIndex.value - 1 else activeSessionList.value.size - 1 },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(0.7f))) { Text("Back") }
                }
                Box(modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp)) {
                    Button(onClick = { showDeleteConfirm.value = true }, modifier = Modifier.align(Alignment.BottomEnd), colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(0.7f))) { Text("Delete") }
                }
            }
            if (showDeleteConfirm.value) {
                AlertDialog(onDismissRequest = { showDeleteConfirm.value = false },
                    title = { Text("Delete Photo?") }, text = { Text("Permanently delete this file?") },
                    confirmButton = {
                        TextButton(onClick = {
                            val uri = activeSessionList.value[currentIndex.value]
                            try {
                                DocumentsContract.deleteDocument(context.contentResolver, uri)
                                activeSessionList.value = activeSessionList.value.toMutableList().apply { removeAt(currentIndex.value) }
                                pickedFolderImages.value = pickedFolderImages.value.filter { it != uri }
                                if (currentIndex.value >= activeSessionList.value.size) currentIndex.value = 0
                            } catch (e: Exception) {}
                            showDeleteConfirm.value = false
                        }) { Text("Delete", color = Color.Red) }
                    }, dismissButton = { TextButton(onClick = { showDeleteConfirm.value = false }) { Text("Cancel") } }
                )
            }
        }
    }
}