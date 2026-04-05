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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
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

// --- UTILS ---
fun saveFolders(context: Context, folders: List<FolderConfig>) {
    val prefs = context.getSharedPreferences("PicRoulettePrefs", Context.MODE_PRIVATE)
    val array = JSONArray()
    folders.forEach {
        val obj = JSONObject()
        obj.put("uri", it.uri.toString()); obj.put("subfolders", it.includeSubfolders)
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

fun triggerVibration(context: Context, style: VibrationStyle = VibrationStyle.TICK) {
    val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = when(style) {
            VibrationStyle.TICK -> VibrationEffect.createOneShot(10, 80)
            VibrationStyle.HEARTBEAT -> VibrationEffect.createWaveform(longArrayOf(0, 20, 100, 30), -1)
            VibrationStyle.LONG -> VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }
}

enum class VibrationStyle { TICK, HEARTBEAT, LONG }

// --- SCANNING ---
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

suspend fun saveToFavoritesFolder(context: Context, sourceUri: Uri, fileName: String, scale: Float, offset: Offset, containerSize: IntSize): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            val fullBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext null
            val imgW = fullBitmap.width.toFloat(); val imgH = fullBitmap.height.toFloat()
            val cropW = (imgW / scale).toInt().coerceIn(1, fullBitmap.width)
            val cropH = (imgH / scale).toInt().coerceIn(1, fullBitmap.height)
            val startX = ((imgW - cropW) / 2 - (offset.x * (imgW / containerSize.width))).toInt().coerceIn(0, (imgW - cropW).toInt())
            val startY = ((imgH - cropH) / 2 - (offset.y * (imgH / containerSize.height))).toInt().coerceIn(0, (imgH - cropH).toInt())
            val cropped = Bitmap.createBitmap(fullBitmap, startX, startY, cropW, cropH)
            val finalName = if (fileName.startsWith("PR_")) fileName else "PR_$fileName"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Zoom_$finalName")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PicRoulette_Favorites")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
            fullBitmap.recycle(); cropped.recycle()
            uri
        } catch (e: Exception) { null }
    }
}

class MainActivity : ComponentActivity() {
    private var isAppReady = false
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !isAppReady }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        lifecycleScope.launch { delay(800); isAppReady = true }
        setContent {
            val rouletteYellow = Color(0xFFFFD700)
            MaterialTheme(colorScheme = darkColorScheme(primary = rouletteYellow, surface = Color(0xFF0A0A0A), background = Color(0xFF0A0A0A))) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0A0A0A)) { PicRouletteApp(rouletteYellow) }
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

    // --- State Management ---
    var folderConfigs by remember { mutableStateOf(getSavedFolders(context)) }
    val pickedFolderImages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val scanningUris = remember { mutableStateListOf<Uri>() }
    val activeSessionList = remember { mutableStateListOf<Uri>() }
    var favoriteFiles by remember { mutableStateOf<List<FavoriteFile>>(emptyList()) }
    var isPlaying by remember { mutableStateOf(false) }
    var isFavoritesMode by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    var showSheet by remember { mutableStateOf(false) }
    val currentIndex = remember { mutableIntStateOf(0) }
    var uiVisible by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    // --- Real-time Progress Animation ---
    // Correct Left-to-Right Progress calculation
    val scanProgress by animateFloatAsState(
        targetValue = if (!isScanning.value) 1f
        else if (folderConfigs.isEmpty()) 0f
        else (folderConfigs.size - scanningUris.size).toFloat() / folderConfigs.size.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scanProgress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Reverse), label = "pulse"
    )

    val currentView = LocalView.current
    DisposableEffect(isPlaying) {
        if (isPlaying) currentView.keepScreenOn = true
        onDispose { currentView.keepScreenOn = false }
    }

    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    var isTransforming by remember { mutableStateOf(false) }
    val transformState = rememberTransformableState { z, o, _ ->
        if (!uiVisible) { isTransforming = true; scale.floatValue *= z; offset.value += o }
    }

    LaunchedEffect(isTransforming) { if (isTransforming) { delay(1000); isTransforming = false } }

    fun refreshFavs() {
        scope.launch(Dispatchers.IO) {
            val list = getFavoritesList(context)
            withContext(Dispatchers.Main) { favoriteFiles = list.toList() }
        }
    }

    val scanAllFolders: suspend () -> Unit = {
        isScanning.value = true
        scanningUris.clear()
        scanningUris.addAll(folderConfigs.map { it.uri })
        val allImages = mutableListOf<Uri>()
        withContext(Dispatchers.IO) {
            folderConfigs.forEach { config ->
                // Small delay to ensure the animation is visible to the human eye
                delay(150)
                try {
                    context.contentResolver.takePersistableUriPermission(config.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    val images = queryImagesInFolder(context, config)
                    allImages.addAll(images)
                } catch (e: Exception) {}
                launch(Dispatchers.Main) { scanningUris.remove(config.uri) }
            }
        }
        pickedFolderImages.value = allImages
        isScanning.value = false
    }

    LaunchedEffect(Unit) { refreshFavs(); scanAllFolders() }
    LaunchedEffect(currentIndex.intValue) { scale.floatValue = 1f; offset.value = Offset.Zero; uiVisible = false }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!isPlaying) {
            Box(Modifier.padding(padding)) {
                Column(modifier = Modifier.padding(top = 32.dp)) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Color.Transparent, titleContentColor = Color.White),
                        title = { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("PicRoulette", fontWeight = FontWeight.Black, fontSize = 32.sp); Text("Rediscover your library", style = MaterialTheme.typography.labelMedium, color = Color.Gray) } },
                        actions = { IconButton(onClick = { triggerVibration(context); scope.launch { scanAllFolders(); refreshFavs() } }) { Icon(Icons.Rounded.Refresh, null, tint = if (isScanning.value) themeColor else Color.White) } }
                    )
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(24.dp))

                        // --- CORRECTED PROGRESS-DRIVEN "POWER-UP" BOX ---
                        Box(modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF050112)) // Dark base
                            .clickable { if (pickedFolderImages.value.isNotEmpty()) { triggerVibration(context, VibrationStyle.LONG); isFavoritesMode = false; activeSessionList.clear(); activeSessionList.addAll(pickedFolderImages.value.shuffled()); currentIndex.intValue = 0; isPlaying = true } }) {

                            // Background Fill (Grows Left -> Right)
                            Box(modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.linearGradient(
                                        colors = if (isScanning.value) {
                                            listOf(Color(0xFF7C4DFF), themeColor, Color.Transparent)
                                        } else {
                                            listOf(Color(0xFF7C4DFF), themeColor)
                                        },
                                        start = Offset(0f, 0f),
                                        // Expand the end point based on progress
                                        end = Offset(if (isScanning.value) (scanProgress * 2000f) else Float.POSITIVE_INFINITY, 1000f)
                                    )
                                )
                            )

                            // Bright Leading Edge "Blade"
                            if (isScanning.value) {
                                Box(modifier = Modifier
                                    .fillMaxWidth(scanProgress.coerceIn(0.01f, 1f))
                                    .fillMaxHeight()
                                    .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(0.4f))))
                                )
                            }

                            Icon(Icons.Rounded.PlayArrow, null,
                                modifier = Modifier.size(240.dp).align(Alignment.CenterEnd).offset(x = 60.dp)
                                    .graphicsLayer { alpha = if (isScanning.value) pulseAlpha * 0.3f else 0.1f }, tint = Color.White)

                            Column(modifier = Modifier.padding(32.dp).align(Alignment.BottomStart)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isScanning.value) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 3.dp)
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(
                                        text = if (isScanning.value) "INITIALIZING: ${(scanProgress * 100).toInt()}%" else "${pickedFolderImages.value.size} PHOTOS",
                                        color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text("Start Roulette", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Black)
                            }
                        }

                        Spacer(Modifier.height(40.dp))
                        DashboardActionCard("Library Folders", "${folderConfigs.size} folders", Icons.Rounded.FolderCopy, Color(0xFFBB86FC)) { triggerVibration(context); showSheet = true }
                        Spacer(Modifier.height(16.dp))
                        DashboardActionCard("Your Favorites", "${favoriteFiles.size} images", Icons.Rounded.Favorite, Color(0xFFFF4081)) {
                            if (favoriteFiles.isNotEmpty()) {
                                triggerVibration(context, VibrationStyle.LONG); isFavoritesMode = true; activeSessionList.clear()
                                activeSessionList.addAll(favoriteFiles.map { it.mediaUri }.shuffled()); currentIndex.intValue = 0; isPlaying = true
                            }
                        }
                    }
                }
            }
        } else {
            // --- VIEW MODE ---
            Box(modifier = Modifier.fillMaxSize().background(Color.Black).onGloballyPositioned { containerSize = it.size }.transformable(state = transformState)
                .combinedClickable(
                    onClick = { if (!uiVisible && activeSessionList.isNotEmpty()) { triggerVibration(context, VibrationStyle.TICK); if (currentIndex.intValue >= activeSessionList.size - 1) { activeSessionList.shuffle(); currentIndex.intValue = 0 } else currentIndex.intValue += 1 } else if (uiVisible) uiVisible = false },
                    onLongClick = { triggerVibration(context, VibrationStyle.LONG); uiVisible = !uiVisible }
                )
            ) {
                val currentUri = activeSessionList.getOrNull(currentIndex.intValue)
                if (currentUri != null) {
                    val currentFileName = remember(currentUri) {
                        var name = ""
                        context.contentResolver.query(currentUri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) }
                        name.ifEmpty { currentUri.lastPathSegment ?: "img" }
                    }
                    val isHeartFilled = favoriteFiles.any { it.fileNameOnDisk == "PR_$currentFileName" || it.fileNameOnDisk == currentFileName || it.fileNameOnDisk == "Zoom_$currentFileName" }

                    AsyncImage(model = currentUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(40.dp).graphicsLayer(alpha = 0.4f))
                    AsyncImage(model = currentUri, contentDescription = null, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale.floatValue, scaleY = scale.floatValue, translationX = offset.value.x, translationY = offset.value.y), contentScale = ContentScale.Fit)

                    AnimatedVisibility(visible = (scale.floatValue > 1.05f && isTransforming), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp), enter = fadeIn(), exit = fadeOut()) {
                        Surface(color = Color.Black.copy(0.6f), shape = CircleShape) {
                            val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            context.contentResolver.openInputStream(currentUri)?.use { BitmapFactory.decodeStream(it, null, opt) }
                            val resText = "${(opt.outWidth / scale.floatValue).toInt()}x${(opt.outHeight / scale.floatValue).toInt()} px"
                            Text(resText, color = Color.White, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    }

                    AnimatedVisibility(visible = uiVisible, enter = fadeIn() + slideInVertically(), exit = fadeOut() + slideOutVertically()) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 40.dp).align(Alignment.TopCenter), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                                Button(onClick = { isPlaying = false }) { Text("Exit") }
                                key(currentUri, isHeartFilled) {
                                    val hScale = remember { Animatable(1f) }
                                    IconButton(onClick = { triggerVibration(context, VibrationStyle.HEARTBEAT); scope.launch { hScale.animateTo(1.4f, spring()); hScale.animateTo(1f, spring()); if (!isHeartFilled) saveToFavoritesFolder(context, currentUri, currentFileName, scale.floatValue, offset.value, containerSize); refreshFavs() } }, modifier = Modifier.background(Color.Black.copy(0.5f), CircleShape).size(56.dp).scale(hScale.value)) { Icon(if (isHeartFilled) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = Color.Red, modifier = Modifier.size(36.dp)) }
                                }
                                Button(onClick = { if (currentIndex.intValue > 0) currentIndex.intValue -= 1 }) { Text("Back") }
                            }
                            if (!isFavoritesMode) IconButton(onClick = { triggerVibration(context, VibrationStyle.LONG); showDeleteDialog = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(64.dp).background(Color.Red.copy(0.2f), CircleShape)) { Icon(Icons.Rounded.DeleteOutline, "Delete", tint = Color.Red, modifier = Modifier.size(32.dp)) }
                        }
                    }
                    if (showDeleteDialog) {
                        AlertDialog(onDismissRequest = { showDeleteDialog = false }, title = { Text("Delete?") }, text = { Text("Permanently remove?") }, confirmButton = { TextButton(onClick = { showDeleteDialog = false; scope.launch { activeSessionList.remove(currentUri); if (activeSessionList.isEmpty()) isPlaying = false; try { context.contentResolver.delete(currentUri, null, null) } catch (e: Exception) {}; scanAllFolders() } }) { Text("Delete", color = Color.Red) } }, dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } })
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, containerColor = Color(0xFF141414)) {
            val fLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { folderConfigs = folderConfigs + FolderConfig(it); saveFolders(context, folderConfigs); scope.launch { scanAllFolders() } } }
            Column(modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 48.dp)) {
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) { Text("Manage Folders", style = MaterialTheme.typography.headlineSmall, color = Color.White); IconButton(onClick = { fLauncher.launch(null) }, modifier = Modifier.background(themeColor, CircleShape)) { Icon(Icons.Rounded.Add, null, tint = Color.Black) } }
                Spacer(Modifier.height(24.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(folderConfigs) { config ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(0.04f)).padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Folder, null, tint = themeColor); Spacer(Modifier.width(12.dp)); Text(config.uri.path?.split("/")?.lastOrNull() ?: "Folder", modifier = Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.Bold); IconButton(onClick = { folderConfigs = folderConfigs.filter { it.uri != config.uri }; saveFolders(context, folderConfigs); scope.launch { scanAllFolders() } }) { Icon(Icons.Rounded.DeleteOutline, null, tint = Color.Red.copy(0.7f)) } }
                            Row(verticalAlignment = Alignment.CenterVertically) { Text("Include subfolders", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.weight(1f)); Switch(checked = config.includeSubfolders, onCheckedChange = { isChecked -> folderConfigs = folderConfigs.map { if (it.uri == config.uri) it.copy(includeSubfolders = isChecked) else it }; saveFolders(context, folderConfigs); scope.launch { scanAllFolders() } }, colors = SwitchDefaults.colors(checkedThumbColor = themeColor)) }
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
            Column(Modifier.weight(1f)) { Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(subtitle, color = Color.Gray.copy(0.8f), fontSize = 13.sp) }
            Icon(Icons.Rounded.ChevronRight, null, tint = Color.White.copy(0.2f))
        }
    }
}