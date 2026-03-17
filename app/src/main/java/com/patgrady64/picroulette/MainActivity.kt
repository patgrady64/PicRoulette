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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
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
import org.json.JSONArray
import org.json.JSONObject

// --- DATA MODELS ---
data class FavoriteFile(val fileNameOnDisk: String, val mediaUri: Uri)
data class FolderConfig(val uri: Uri, val includeSubfolders: Boolean = true)

// --- STORAGE HELPERS ---
fun saveFolders(context: Context, folders: List<FolderConfig>) {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    folders.forEach {
        val obj = JSONObject()
        obj.put("uri", it.uri.toString())
        obj.put("subfolders", it.includeSubfolders)
        array.put(obj)
    }
    prefs.edit().putString("folder_configs_json", array.toString()).apply()
}

fun getSavedFolders(context: Context): List<FolderConfig> {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    val json = prefs.getString("folder_configs_json", null) ?: return emptyList()
    val list = mutableListOf<FolderConfig>()
    try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(FolderConfig(Uri.parse(obj.getString("uri")), obj.getBoolean("subfolders")))
        }
    } catch (e: Exception) {}
    return list
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

// --- SCANNING LOGIC ---
fun queryImagesInFolder(context: Context, config: FolderConfig): List<Uri> {
    val allImages = mutableListOf<Uri>()
    val rootDocId = try { DocumentsContract.getTreeDocumentId(config.uri) } catch (e: Exception) { return emptyList() }
    scanDirectory(context, config.uri, rootDocId, allImages, config.includeSubfolders)
    return allImages
}

private fun scanDirectory(context: Context, treeUri: Uri, parentDocId: String, resultList: MutableList<Uri>, recursive: Boolean) {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
    val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE)
    try {
        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val mime = cursor.getString(mimeCol)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (recursive) scanDirectory(context, treeUri, docId, resultList, true)
                } else if (mime?.startsWith("image/") == true) {
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

// --- MAIN ACTIVITY ---
class MainActivity : ComponentActivity() {
    private var isAppReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        splashScreen.setKeepOnScreenCondition { !isAppReady }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        lifecycleScope.launch { delay(800); isAppReady = true }

        setContent {
            val rouletteYellow = Color(0xFFFFD700)
            val customColorScheme = darkColorScheme(
                primary = rouletteYellow,
                onPrimary = Color.Black,
                secondary = rouletteYellow,
                onSecondary = Color.Black,
                surface = Color(0xFF0A0A0A),
                background = Color(0xFF0A0A0A)
            )

            MaterialTheme(colorScheme = customColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) {
                    PicRouletteApp(rouletteYellow)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp(themeColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // States
    var folderConfigs by remember { mutableStateOf(getSavedFolders(context)) }
    val pickedFolderImages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val scanningUris = remember { mutableStateListOf<Uri>() }
    val folderPhotoCounts = remember { mutableStateMapOf<Uri, Int>() }
    val activeSessionList = remember { mutableStateListOf<Uri>() }
    var favoriteFiles by remember { mutableStateOf<List<FavoriteFile>>(emptyList()) }

    var isPlaying by remember { mutableStateOf(false) }
    var isFavoritesMode by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    var uiVisibleAnim by remember { mutableStateOf(false) }
    val currentIndex = remember { mutableIntStateOf(0) }
    var uiVisible by remember { mutableStateOf(false) }

    // KEEP SCREEN ON LOGIC
    val currentView = LocalView.current
    DisposableEffect(isPlaying) {
        if (isPlaying) {
            currentView.keepScreenOn = true
        }
        onDispose {
            currentView.keepScreenOn = false
        }
    }

    // Gestures
    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { z, o, _ -> scale.floatValue *= z; offset.value += o }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 1.12f,
        animationSpec = infiniteRepeatable(animation = tween(1200, easing = FastOutSlowInEasing), repeatMode = RepeatMode.Reverse),
        label = "pulseScale"
    )

    fun refreshFavs() {
        scope.launch(Dispatchers.IO) {
            val list = getFavoritesList(context)
            withContext(Dispatchers.Main) { favoriteFiles = list.toList() }
        }
    }

    val scanAllFolders: suspend () -> Unit = {
        isScanning.value = true
        val allImages = mutableListOf<Uri>()
        withContext(Dispatchers.IO) {
            folderConfigs.forEach { config ->
                launch(Dispatchers.Main) { scanningUris.add(config.uri) }
                try {
                    context.contentResolver.takePersistableUriPermission(config.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val images = queryImagesInFolder(context, config)
                    launch(Dispatchers.Main) {
                        folderPhotoCounts[config.uri] = images.size
                        scanningUris.remove(config.uri)
                    }
                    allImages.addAll(images)
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { scanningUris.remove(config.uri) }
                }
            }
        }
        pickedFolderImages.value = allImages
        isScanning.value = false
    }

    LaunchedEffect(Unit) {
        refreshFavs()
        scanAllFolders()
        delay(500); uiVisibleAnim = true
    }

    LaunchedEffect(currentIndex.intValue) { scale.floatValue = 1f; offset.value = Offset.Zero }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = Color(0xFF0A0A0A)
    ) { padding ->
        if (!isPlaying) {
            Box(Modifier.padding(padding)) {
                Column(modifier = Modifier.padding(top = 32.dp)) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("PicRoulette", fontWeight = FontWeight.Black, fontSize = 32.sp)
                                Text("Rediscover your library", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { triggerVibration(context); scope.launch { scanAllFolders(); refreshFavs() } },
                                modifier = Modifier.padding(end = 16.dp).scale(if (isScanning.value) pulseScale else 1f)
                            ) { Icon(Icons.Rounded.Refresh, null, tint = if (isScanning.value) themeColor else Color.White) }
                        }
                    )
                    AnimatedVisibility(visible = uiVisibleAnim, enter = fadeIn() + slideInVertically()) {
                        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                            Spacer(Modifier.height(24.dp))
                            Box(modifier = Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(32.dp)).background(Brush.linearGradient(listOf(Color(0xFF7C4DFF), themeColor)))
                                .clickable {
                                    if (pickedFolderImages.value.isNotEmpty()) {
                                        triggerVibration(context, true)
                                        isFavoritesMode = false; uiVisible = false; activeSessionList.clear(); activeSessionList.addAll(pickedFolderImages.value.shuffled()); currentIndex.intValue = 0; isPlaying = true
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(240.dp).align(Alignment.CenterEnd).offset(x = 60.dp).graphicsLayer(alpha = 0.1f), tint = Color.White)
                                Column(modifier = Modifier.padding(32.dp).align(Alignment.BottomStart)) {
                                    Text("${pickedFolderImages.value.size} PHOTOS", color = Color.White.copy(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                    Text(if (isScanning.value) "Scanning..." else "Start Roulette", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                                }
                            }
                            Spacer(Modifier.height(40.dp))
                            DashboardActionCard("Library Folders", "${folderConfigs.size} folders linked", Icons.Rounded.FolderCopy, Color(0xFFBB86FC)) { triggerVibration(context); showSheet = true }
                            Spacer(Modifier.height(16.dp))
                            DashboardActionCard("Your Favorites", "${favoriteFiles.size} images saved", Icons.Rounded.Favorite, Color(0xFFFF4081)) {
                                if (favoriteFiles.isNotEmpty()) {
                                    triggerVibration(context); isFavoritesMode = true; uiVisible = false; activeSessionList.clear(); activeSessionList.addAll(favoriteFiles.map { it.mediaUri }.shuffled()); currentIndex.intValue = 0; isPlaying = true
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // VIEWER
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).transformable(state = transformState)
                .combinedClickable(
                    onClick = { if (activeSessionList.isNotEmpty()) { if (currentIndex.intValue >= activeSessionList.size - 1) { activeSessionList.shuffle(); currentIndex.intValue = 0 } else currentIndex.intValue += 1 } },
                    onLongClick = { triggerVibration(context); uiVisible = !uiVisible }
                )
            ) {
                val currentUri = activeSessionList.getOrNull(currentIndex.intValue)
                if (currentUri != null) {
                    val currentFileName = remember(currentUri) {
                        var name = ""
                        try {
                            context.contentResolver.query(currentUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use {
                                if (it.moveToFirst()) name = it.getString(0)
                            }
                        } catch (e: Exception) {}
                        name.ifEmpty { currentUri.lastPathSegment ?: "img" }
                    }

                    val isHeartFilled = favoriteFiles.any { it.fileNameOnDisk == "PR_$currentFileName" || it.fileNameOnDisk == currentFileName }

                    AsyncImage(model = currentUri, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale.floatValue, scaleY = scale.floatValue, translationX = offset.value.x, translationY = offset.value.y), contentScale = ContentScale.Fit)

                    AnimatedVisibility(visible = uiVisible, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.fillMaxWidth().height(140.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(0.6f), Color.Transparent))))

                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp).align(Alignment.TopCenter), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Button(onClick = { isPlaying = false }) { Text("Exit") }

                                key(currentUri, isHeartFilled) {
                                    IconButton(onClick = {
                                        triggerVibration(context)
                                        scope.launch {
                                            if (isHeartFilled) {
                                                val toDelete = favoriteFiles.find { it.fileNameOnDisk == "PR_$currentFileName" || it.fileNameOnDisk == currentFileName }
                                                toDelete?.let {
                                                    withContext(Dispatchers.IO) { context.contentResolver.delete(it.mediaUri, null, null) }
                                                }
                                            } else {
                                                saveToFavoritesFolder(context, currentUri, currentFileName)
                                            }
                                            refreshFavs()
                                        }
                                    }, modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape).size(56.dp)) {
                                        Icon(
                                            imageVector = if (isHeartFilled) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            null,
                                            tint = Color.Red,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                }

                                Button(onClick = { if (currentIndex.intValue > 0) currentIndex.intValue -= 1 }) { Text("Back") }
                            }

                            if (!isFavoritesMode) {
                                IconButton(
                                    onClick = { triggerVibration(context, true); showDeleteDialog = true },
                                    modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(64.dp).background(Color.Black.copy(0.3f), CircleShape)
                                ) {
                                    Box(Modifier.fillMaxSize().background(Color.Red.copy(0.15f), CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Color.Red, modifier = Modifier.size(32.dp))
                                    }
                                }
                            }
                        }
                    }

                    if (showDeleteDialog) {
                        AlertDialog(
                            onDismissRequest = { showDeleteDialog = false },
                            title = { Text("Delete Photo?") },
                            text = { Text("This image will be permanently removed from your device.") },
                            confirmButton = {
                                TextButton(onClick = {
                                    showDeleteDialog = false
                                    scope.launch {
                                        val photoToDelete = currentUri
                                        activeSessionList.remove(photoToDelete)
                                        if (activeSessionList.isEmpty()) isPlaying = false
                                        withContext(Dispatchers.IO) {
                                            try { context.contentResolver.delete(photoToDelete, null, null) } catch (e: Exception) {}
                                        }
                                        scanAllFolders()
                                    }
                                }) { Text("Delete", color = Color.Red) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF141414)) {
            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { folderConfigs = folderConfigs + FolderConfig(it); saveFolders(context, folderConfigs); scope.launch { scanAllFolders() } }
            }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 48.dp).padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text("Manage Folders", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
                    IconButton(onClick = { launcher.launch(null) }, modifier = Modifier.background(themeColor, CircleShape)) { Icon(Icons.Rounded.Add, null, tint = Color.Black) }
                }
                Spacer(Modifier.height(24.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(folderConfigs) { config ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.04f)).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Folder, null, tint = themeColor)
                                Spacer(Modifier.width(12.dp))
                                Text(config.uri.path?.split("/")?.lastOrNull() ?: "Folder", modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1)

                                if (scanningUris.contains(config.uri)) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = themeColor)
                                } else {
                                    Text("${folderPhotoCounts[config.uri] ?: 0}", color = themeColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                }

                                IconButton(onClick = { folderConfigs = folderConfigs.filter { it.uri != config.uri }; saveFolders(context, folderConfigs); scope.launch { scanAllFolders() } }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Color.Red.copy(0.7f)) }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                Text("Include subfolders", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                                Switch(
                                    checked = config.includeSubfolders,
                                    onCheckedChange = { isChecked ->
                                        folderConfigs = folderConfigs.map { if (it.uri == config.uri) it.copy(includeSubfolders = isChecked) else it }
                                        saveFolders(context, folderConfigs)
                                        scope.launch { scanAllFolders() }
                                    },
                                    colors = SwitchDefaults.colors(checkedThumbColor = themeColor)
                                )
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
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = Color.White.copy(0.04f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(color.copy(0.12f)), Alignment.Center) { Icon(icon, null, tint = color, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(subtitle, color = Color.Gray.copy(0.8f), fontSize = 13.sp)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(0.2f))
        }
    }
}