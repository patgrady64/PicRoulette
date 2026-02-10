package com.patgrady64.picroulette

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- DATA MODELS ---
data class FavoriteFile(val fileNameOnDisk: String, val mediaUri: Uri)

// --- HELPERS ---
fun saveFolders(context: Context, uris: Set<Uri>) {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    prefs.edit().putStringSet("folder_uris", uris.map { it.toString() }.toSet()).apply()
}

fun getSavedFolders(context: Context): Set<Uri> {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    return prefs.getStringSet("folder_uris", emptySet())?.map { Uri.parse(it) }?.toSet() ?: emptySet()
}

fun triggerVibration(context: Context) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
}

// --- FILE LOGIC ---
fun queryAllImagesInTree(context: Context, treeUri: Uri): List<Uri> {
    val allImages = mutableListOf<Uri>()
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    scanDirectoryRecursive(context, treeUri, rootDocId, allImages)
    return allImages
}

private fun scanDirectoryRecursive(context: Context, treeUri: Uri, parentDocId: String, resultList: MutableList<Uri>) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                if (cursor.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    scanDirectoryRecursive(context, treeUri, docId, resultList)
                } else if (cursor.getString(mimeCol)?.startsWith("image/") == true) {
                    resultList.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, docId))
                }
            }
        }
    } catch (e: Exception) {}
}

fun getFavoritesList(context: Context): List<FavoriteFile> {
    val list = mutableListOf<FavoriteFile>()
    val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME)
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("%Pictures/PicRoulette_Favorites%")
    try {
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol))
                list.add(FavoriteFile(cursor.getString(nameCol), uri))
            }
        }
    } catch (e: Exception) {}
    return list
}

suspend fun saveToFavoritesFolder(context: Context, sourceUri: Uri, fileName: String): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val bitmap = context.contentResolver.openInputStream(sourceUri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
            val finalName = if (fileName.startsWith("PR_")) fileName else "PR_$fileName"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PicRoulette_Favorites")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
            uri
        } catch (e: Exception) { null }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF121212)) { PicRouletteApp() }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folderList = remember { mutableStateOf(getSavedFolders(context)) }
    val pickedFolderImages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val activeSessionList = remember { mutableStateListOf<Uri>() }
    var favoriteFiles by remember { mutableStateOf<List<FavoriteFile>>(emptyList()) }

    val isPlaying = remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }

    val currentIndex = remember { mutableIntStateOf(0) }
    val uiVisible = remember { mutableStateOf(true) }
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { z, o, _ -> scale.floatValue *= z; offset.value += o }

    fun refreshFavs() { scope.launch(Dispatchers.IO) { favoriteFiles = getFavoritesList(context) } }
    LaunchedEffect(Unit) { refreshFavs() }

    LaunchedEffect(currentIndex.intValue) {
        scale.floatValue = 1f
        offset.value = Offset.Zero
    }

    val scanAllFolders: suspend () -> Unit = {
        isScanning.value = true
        val all = mutableListOf<Uri>()
        withContext(Dispatchers.IO) {
            folderList.value.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    all.addAll(queryAllImagesInTree(context, uri))
                } catch (e: Exception) {}
            }
        }
        pickedFolderImages.value = all
        isScanning.value = false
    }
    LaunchedEffect(Unit) { scanAllFolders() }

    if (!isPlaying.value) {
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("PICROULETTE", fontWeight = FontWeight.Black, letterSpacing = 2.sp) },
                    actions = { IconButton(onClick = { scope.launch { scanAllFolders(); refreshFavs() } }) { Icon(Icons.Rounded.Refresh, null) } }
                )
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp)) {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(24.dp))
                    .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary)))
                    .clickable {
                        if (pickedFolderImages.value.isNotEmpty()) {
                            triggerVibration(context)
                            activeSessionList.clear()
                            activeSessionList.addAll(pickedFolderImages.value.shuffled())
                            currentIndex.intValue = 0
                            isPlaying.value = true
                        }
                    }.padding(24.dp)
                ) {
                    Column(modifier = Modifier.align(Alignment.CenterStart)) {
                        Text("Total Library", color = Color.White.copy(0.7f), style = MaterialTheme.typography.labelLarge)
                        Text("${pickedFolderImages.value.size}", color = Color.White, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(if (isScanning.value) "Scanning..." else "Tap to start", color = Color.White.copy(0.9f))
                    }
                    Icon(Icons.Rounded.PlayCircleFilled, null, modifier = Modifier.size(100.dp).align(Alignment.CenterEnd).graphicsLayer(alpha = 0.15f))
                }
                Spacer(Modifier.height(24.dp))
                DashboardActionCard("Manage Folders", "${folderList.value.size} Folders", Icons.Rounded.Folder, MaterialTheme.colorScheme.secondary) { showSheet = true }
                Spacer(Modifier.height(12.dp))
                DashboardActionCard("Favorites", "${favoriteFiles.size} Images", Icons.Rounded.Star, Color(0xFFFFD700)) {
                    if (favoriteFiles.isNotEmpty()) {
                        activeSessionList.clear()
                        activeSessionList.addAll(favoriteFiles.map { it.mediaUri }.shuffled())
                        currentIndex.intValue = 0
                        isPlaying.value = true
                    }
                }
            }
        }
    } else {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black).transformable(state = transformState)
            .combinedClickable(
                onClick = {
                    if (activeSessionList.isNotEmpty()) {
                        currentIndex.intValue = (currentIndex.intValue + 1) % activeSessionList.size
                    }
                },
                onLongClick = { uiVisible.value = !uiVisible.value }
            )
        ) {
            // Safety check: if list is empty, exit viewer
            if (activeSessionList.isEmpty()) {
                isPlaying.value = false
            } else {
                // Ensure index is within bounds (prevents crashes)
                val safeIndex = currentIndex.intValue.coerceIn(0, activeSessionList.size - 1)
                val currentUri = activeSessionList[safeIndex]

                val isViewingFavoritesMode = remember(activeSessionList.size) {
                    activeSessionList.any { it.toString().contains("PicRoulette_Favorites") }
                }

                val currentFileName = remember(currentUri) {
                    var name = ""
                    try {
                        context.contentResolver.query(currentUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) name = it.getString(0)
                        }
                    } catch (e: Exception) {}
                    name.ifEmpty { currentUri.lastPathSegment ?: "unknown" }
                }

                val matchedFav = favoriteFiles.find {
                    it.fileNameOnDisk == currentFileName || it.fileNameOnDisk == "PR_$currentFileName" || currentFileName == "PR_${it.fileNameOnDisk}"
                }
                val isStarred = matchedFav != null

                key(currentUri) {
                    AsyncImage(
                        model = currentUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale.floatValue, scaleY = scale.floatValue, translationX = offset.value.x, translationY = offset.value.y),
                        contentScale = ContentScale.Fit,
                        onError = {
                            if (activeSessionList.isNotEmpty()) {
                                activeSessionList.removeAt(safeIndex)
                            }
                        }
                    )
                }

                if (uiVisible.value) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                        Button(onClick = { isPlaying.value = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(0.7f))) { Text("Stop") }

                        IconButton(
                            onClick = {
                                triggerVibration(context)
                                scope.launch {
                                    if (isStarred && matchedFav != null) {
                                        // DELETE
                                        withContext(Dispatchers.IO) { context.contentResolver.delete(matchedFav.mediaUri, null, null) }
                                        favoriteFiles = favoriteFiles.filter { it.mediaUri != matchedFav.mediaUri }
                                        activeSessionList.remove(currentUri)

                                        if (isViewingFavoritesMode) {
                                            if (activeSessionList.isEmpty()) {
                                                isPlaying.value = false
                                            } else {
                                                // Hard reshuffle to kill ghosts and keep index safe
                                                val shuffled = activeSessionList.shuffled()
                                                activeSessionList.clear()
                                                activeSessionList.addAll(shuffled)
                                                currentIndex.intValue = 0
                                            }
                                        }
                                    } else {
                                        // SAVE
                                        val newUri = saveToFavoritesFolder(context, currentUri, currentFileName)
                                        if (newUri != null) favoriteFiles = favoriteFiles + FavoriteFile("PR_$currentFileName", newUri)
                                        delay(500); refreshFavs()
                                    }
                                }
                            },
                            modifier = Modifier.background(Color.DarkGray.copy(0.5f), RoundedCornerShape(12.dp))
                        ) {
                            Icon(imageVector = if (isStarred) Icons.Rounded.Star else Icons.Outlined.StarBorder, contentDescription = "Star", tint = if (isStarred) Color(0xFFFFD700) else Color.White)
                        }

                        Button(onClick = {
                            if (activeSessionList.isNotEmpty()) {
                                currentIndex.intValue = if (currentIndex.intValue > 0) currentIndex.intValue - 1 else activeSessionList.size - 1
                            }
                        }, colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(0.7f))) { Text("Back") }
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }) {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { folderList.value += it; saveFolders(context, folderList.value); scope.launch { scanAllFolders() } }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp).padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Linked Folders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { launcher.launch(null) }, modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)) {
                        Icon(imageVector = Icons.Rounded.Add, contentDescription = null, tint = Color.Black)
                    }
                }
                Spacer(Modifier.height(16.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(folderList.value.toList()) { uri ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Rounded.Folder, contentDescription = null, tint = Color.Cyan)
                            Spacer(Modifier.width(12.dp))
                            Text(uri.path?.split("/")?.lastOrNull() ?: "Folder", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { folderList.value -= uri; saveFolders(context, folderList.value); scope.launch { scanAllFolders() } }) {
                                Icon(imageVector = Icons.Rounded.Delete, contentDescription = null, tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DashboardActionCard(title: String, subtitle: String, icon: ImageVector, color: Color, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(color.copy(0.2f)), Alignment.Center) {
                Icon(imageVector = icon, contentDescription = null, tint = color)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Color.Gray, fontSize = 14.sp)
            }
        }
    }
}