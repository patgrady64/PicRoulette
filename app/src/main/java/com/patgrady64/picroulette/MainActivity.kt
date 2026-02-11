package com.patgrady64.picroulette

import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
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

fun triggerVibration(context: Context, long: Boolean = false) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = if (long) VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        else VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE)
        vibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        if (long) vibrator.vibrate(50) else vibrator.vibrate(10)
    }
}

// Translate SAF Document URI to MediaStore URI to prevent deletion crashes
fun getMediaStoreUriFromDocumentUri(context: Context, documentUri: Uri): Uri? {
    val projection = arrayOf(MediaStore.Images.Media._ID)
    val fileName = documentUri.lastPathSegment?.split(":")?.lastOrNull()?.split("/")?.lastOrNull() ?: return null
    val selection = "${MediaStore.Images.Media.DISPLAY_NAME} = ?"

    return try {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf(fileName),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
            } else null
        }
    } catch (e: Exception) { null }
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
    private var isAppReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        hideSystemBars()
        splashScreen.setKeepOnScreenCondition { !isAppReady }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        lifecycleScope.launch {
            delay(1500)
            isAppReady = true
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    PicRouletteApp()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
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
    var uiVisibleAnim by remember { mutableStateOf(false) }

    val currentIndex = remember { mutableIntStateOf(0) }
    val uiVisible = remember { mutableStateOf(true) }
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { z, o, _ -> scale.floatValue *= z; offset.value += o }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(animation = tween(1000, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )

    fun refreshFavs() { scope.launch(Dispatchers.IO) { favoriteFiles = getFavoritesList(context) } }

    LaunchedEffect(Unit) {
        refreshFavs()
        delay(1600)
        uiVisibleAnim = true
    }

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
            containerColor = Color(0xFF0A0A0A),
            topBar = {
                Column(modifier = Modifier.padding(top = 24.dp)) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PicRoulette", fontWeight = FontWeight.Black, fontSize = 32.sp, letterSpacing = (-1).sp)
                                Text("Rediscover your library", style = MaterialTheme.typography.labelMedium, color = Color.Gray.copy(0.8f))
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { triggerVibration(context); scope.launch { scanAllFolders(); refreshFavs() } },
                                modifier = Modifier.padding(end = 16.dp).scale(if (isScanning.value) pulseScale else 1f).background(if (isScanning.value) MaterialTheme.colorScheme.primary.copy(0.15f) else Color.White.copy(0.05f), CircleShape)
                            ) { Icon(Icons.Rounded.Refresh, null, tint = if (isScanning.value) MaterialTheme.colorScheme.primary else Color.White) }
                        }
                    )
                }
            }
        ) { padding ->
            AnimatedVisibility(
                visible = uiVisibleAnim,
                enter = fadeIn(tween(800)) + slideInVertically(tween(800)) { it / 15 }
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
                    Spacer(Modifier.height(24.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(240.dp).clip(RoundedCornerShape(32.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), Color(0xFF00BFA5))))
                        .clickable {
                            if (pickedFolderImages.value.isNotEmpty()) {
                                triggerVibration(context, true)
                                activeSessionList.clear()
                                activeSessionList.addAll(pickedFolderImages.value.shuffled())
                                currentIndex.intValue = 0
                                isPlaying.value = true
                            }
                        }
                    ) {
                        val shimmerAlpha by infiniteTransition.animateFloat(
                            initialValue = 0.1f, targetValue = 0.25f,
                            animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse), label = ""
                        )
                        Box(Modifier.fillMaxSize().background(Color.White.copy(shimmerAlpha)))
                        Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(260.dp).align(Alignment.CenterEnd).offset(x = 80.dp).graphicsLayer(alpha = 0.15f), tint = Color.White)
                        Column(modifier = Modifier.padding(32.dp).align(Alignment.BottomStart)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isScanning.value) CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.White.copy(0.7f), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.PhotoLibrary, null, modifier = Modifier.size(16.dp), tint = Color.White.copy(0.7f))
                                Spacer(Modifier.width(8.dp))
                                Text("${pickedFolderImages.value.size} PHOTOS", color = Color.White.copy(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                            }
                            Text(if (isScanning.value) "Syncing..." else "Start Roulette", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(48.dp))
                    Text("COLLECTIONS", color = Color.Gray.copy(0.5f), fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    DashboardActionCard("Library Folders", "${folderList.value.size} folders linked", Icons.Rounded.FolderCopy, Color(0xFFBB86FC)) { triggerVibration(context); showSheet = true }
                    Spacer(Modifier.height(16.dp))
                    DashboardActionCard("Your Favorites", "${favoriteFiles.size} images saved", Icons.Rounded.AutoAwesome, Color(0xFFFFD700)) {
                        if (favoriteFiles.isNotEmpty()) {
                            triggerVibration(context); activeSessionList.clear()
                            activeSessionList.addAll(favoriteFiles.map { it.mediaUri }.shuffled())
                            currentIndex.intValue = 0; isPlaying.value = true
                        }
                    }
                }
            }
        }
    } else {
        // --- VIEWER MODE ---
        val safeIndex = currentIndex.intValue.coerceIn(0, activeSessionList.size - 1)
        val currentUri = activeSessionList[safeIndex]
        var showDeleteDialog by remember { mutableStateOf(false) }

        val currentFileName = remember(currentUri) {
            var name = ""
            try {
                context.contentResolver.query(currentUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0)
                }
            } catch (e: Exception) {}
            name.ifEmpty { currentUri.lastPathSegment ?: "unknown" }
        }
        val matchedFav = favoriteFiles.find { it.fileNameOnDisk == currentFileName || it.fileNameOnDisk == "PR_$currentFileName" || currentFileName == "PR_${it.fileNameOnDisk}" }
        val isStarred = matchedFav != null

        val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            // Use -1 for RESULT_OK to avoid unresolved reference errors
            if (result.resultCode == -1) {
                activeSessionList.removeAt(safeIndex)
                if (activeSessionList.isEmpty()) isPlaying.value = false
            }
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = Color(0xFF1A1A1A),
                title = { Text("Delete Permanently?", color = Color.White) },
                text = { Text("This will delete the original file from your device entirely. This cannot be undone.", color = Color.Gray) },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteDialog = false
                        scope.launch(Dispatchers.IO) {
                            val mediaStoreUri = getMediaStoreUriFromDocumentUri(context, currentUri)
                            withContext(Dispatchers.Main) {
                                if (mediaStoreUri != null) {
                                    val pendingIntent = MediaStore.createDeleteRequest(context.contentResolver, listOf(mediaStoreUri))
                                    deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
                                } else {
                                    // Fallback if not indexed by MediaStore
                                    try {
                                        DocumentsContract.deleteDocument(context.contentResolver, currentUri)
                                        activeSessionList.removeAt(safeIndex)
                                        if (activeSessionList.isEmpty()) isPlaying.value = false
                                    } catch (e: Exception) {
                                        activeSessionList.removeAt(safeIndex)
                                    }
                                }
                            }
                        }
                    }) { Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel", color = Color.White) }
                }
            )
        }

        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.fillMaxSize()
                .transformable(state = transformState)
                .combinedClickable(
                    onClick = { if (activeSessionList.isNotEmpty()) currentIndex.intValue = (currentIndex.intValue + 1) % activeSessionList.size },
                    onLongClick = { uiVisible.value = !uiVisible.value }
                )
            ) {
                key(currentUri) {
                    AsyncImage(
                        model = currentUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale.floatValue, scaleY = scale.floatValue, translationX = offset.value.x, translationY = offset.value.y),
                        contentScale = ContentScale.Fit,
                        onError = { if (activeSessionList.isNotEmpty()) activeSessionList.removeAt(safeIndex) }
                    )
                }
            }

            AnimatedVisibility(visible = uiVisible.value, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(20.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Button(onClick = { isPlaying.value = false }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.12f)), shape = RoundedCornerShape(16.dp)) { Text("Exit") }
                    IconButton(
                        onClick = {
                            triggerVibration(context)
                            scope.launch {
                                if (isStarred && matchedFav != null) {
                                    withContext(Dispatchers.IO) { context.contentResolver.delete(matchedFav.mediaUri, null, null) }
                                    favoriteFiles = favoriteFiles.filter { it.mediaUri != matchedFav.mediaUri }
                                } else {
                                    val newUri = saveToFavoritesFolder(context, currentUri, currentFileName)
                                    if (newUri != null) favoriteFiles = favoriteFiles + FavoriteFile("PR_$currentFileName", newUri)
                                    delay(500); refreshFavs()
                                }
                            }
                        },
                        modifier = Modifier.size(56.dp).background(Color.White.copy(0.12f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(imageVector = if (isStarred) Icons.Rounded.Star else Icons.Outlined.StarBorder, contentDescription = "Star", tint = if (isStarred) Color(0xFFFFD700) else Color.White, modifier = Modifier.size(28.dp))
                    }
                    Button(onClick = { if (activeSessionList.isNotEmpty()) currentIndex.intValue = if (currentIndex.intValue > 0) currentIndex.intValue - 1 else activeSessionList.size - 1 }, colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(0.12f)), shape = RoundedCornerShape(16.dp)) { Text("Back") }
                }
            }

            AnimatedVisibility(visible = uiVisible.value, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
                FloatingActionButton(
                    onClick = { triggerVibration(context, true); showDeleteDialog = true },
                    containerColor = Color(0xFF2D1616),
                    contentColor = Color.Red,
                    shape = CircleShape,
                    modifier = Modifier.padding(32.dp).navigationBarsPadding()
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = "Delete Permanent", modifier = Modifier.size(32.dp))
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF141414), dragHandle = { BottomSheetDefaults.DragHandle(color = Color.DarkGray) }) {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { folderList.value += it; saveFolders(context, folderList.value); scope.launch { scanAllFolders() } }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp).padding(horizontal = 28.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Manage Folders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = { launcher.launch(null) }, modifier = Modifier.background(Color(0xFF00BFA5), CircleShape)) {
                        Icon(Icons.Rounded.Add, null, tint = Color.Black)
                    }
                }
                Spacer(Modifier.height(24.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                    items(folderList.value.toList()) { uri ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(0.04f)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Folder, null, tint = Color(0xFF00BFA5))
                            Spacer(Modifier.width(16.dp))
                            Text(uri.path?.split("/")?.lastOrNull() ?: "Folder", modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { folderList.value -= uri; saveFolders(context, folderList.value); scope.launch { scanAllFolders() } }) {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = Color.Red.copy(0.6f))
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(0.04f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(18.dp)).background(color.copy(0.12f)), Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp)
                Text(subtitle, color = Color.Gray.copy(0.8f), fontSize = 14.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(0.2f))
        }
    }
}