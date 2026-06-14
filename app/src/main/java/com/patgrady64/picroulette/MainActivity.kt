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
import androidx.compose.ui.zIndex
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

// --- DATA STORAGE UTILITIES ---
data class FavoriteFile(val fileNameOnDisk: String, val mediaUri: Uri)
data class FolderConfig(val uri: Uri, val includeSubfolders: Boolean)

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
    val args = arrayOf("%Pictures/PR_FAVS%")
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

// --- NAME SCRUBBER UTILITY ---
fun scrubFileName(rawName: String): String {
    // 1. Remove extension (e.g., "IMG_123.jpg" -> "IMG_123")
    val nameWithoutExt = rawName.substringBeforeLast(".")

    // 2. Remove "IMG_" or "PR_FAV_" or any other prefix you might have
    // This regex replaces any known prefix at the start of the string
    return nameWithoutExt.replace(Regex("^(?i)(IMG_|PR_FAV_|Zoom_|Screenshot_|PR_SCREENShot_|_\\d+)*"), "")
}

suspend fun saveToFavoritesFolder(context: Context, sourceUri: Uri, fileName: String, scale: Float, offset: Offset, containerSize: IntSize): Uri? {
    return withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
            val fullBitmap = BitmapFactory.decodeStream(inputStream) ?: return@withContext null

            // --- HANDLE EXIF ORIENTATION ---
            val exif = androidx.exifinterface.media.ExifInterface(context.contentResolver.openInputStream(sourceUri)!!)
            val orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

            val matrix = android.graphics.Matrix()
            when (orientation) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            }

            // 1. Create rotated version
            val rotatedBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, fullBitmap.width, fullBitmap.height, matrix, true)

            // FIX: Only recycle fullBitmap if it is NOT the same object as rotatedBitmap
            if (rotatedBitmap != fullBitmap) {
                fullBitmap.recycle()
            }

            // 2. Use rotatedBitmap dimensions for calculations
            val imgW = rotatedBitmap.width.toFloat()
            val imgH = rotatedBitmap.height.toFloat()
            val viewW = containerSize.width.toFloat()
            val viewH = containerSize.height.toFloat()

            // 3. Math and crop
            val baseScale = minOf(viewW / imgW, viewH / imgH)
            val totalScale = baseScale * scale
            val cropW = (viewW / totalScale).coerceAtMost(imgW)
            val cropH = (viewH / totalScale).coerceAtMost(imgH)

            val centerX = imgW / 2f - (offset.x / totalScale)
            val centerY = imgH / 2f - (offset.y / totalScale)

            val left = (centerX - cropW / 2f).toInt().coerceIn(0, (imgW - cropW).toInt())
            val top = (centerY - cropH / 2f).toInt().coerceIn(0, (imgH - cropH).toInt())

            val cropped = Bitmap.createBitmap(rotatedBitmap, left, top, cropW.toInt().coerceAtLeast(1), cropH.toInt().coerceAtLeast(1))

            // 4. Cleanup rotatedBitmap only if it wasn't the original
            if (rotatedBitmap != fullBitmap) {
                rotatedBitmap.recycle()
            }

            val coreIdentifier = scrubFileName(fileName)
            val finalName = "$coreIdentifier.jpg"

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, finalName)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PR_FAVS")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { out -> cropped.compress(Bitmap.CompressFormat.JPEG, 95, out) } }

            cropped.recycle()
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}

// --- MAIN ACTIVITY ENTRY ---

class MainActivity : ComponentActivity() {
    private var isAppReady = false
    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)

        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

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

// --- COMPOSE UI LAYER ---

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp(themeColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State ---
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

    var showCountSetting by remember { mutableStateOf(false) }
    var showShuffleToast by remember { mutableStateOf(false) }

    var showMetadata by remember { mutableStateOf(false) }

    // --- Animation Logic ---
    val scanProgress by animateFloatAsState(
        targetValue = if (!isScanning.value) 1f
        else if (folderConfigs.isEmpty()) 0f
        else (folderConfigs.size - scanningUris.size).toFloat() / folderConfigs.size.toFloat(),
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "scanProgress"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    val currentView = LocalView.current
    DisposableEffect(isPlaying) {
        if (isPlaying) currentView.keepScreenOn = true
        onDispose { currentView.keepScreenOn = false }
    }

    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    var showResolution by remember { mutableStateOf(false) }
    var lastTransformTime by remember { mutableLongStateOf(0L) }



    LaunchedEffect(lastTransformTime) {
        if (lastTransformTime > 0) {
            showResolution = true
            delay(1500)
            showResolution = false
        }
    }

    LaunchedEffect(showShuffleToast) {
        if (showShuffleToast) {
            delay(1800)
            showShuffleToast = false
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            scale.floatValue = 1f
            offset.value = Offset.Zero
        }
    }

    val transformState = rememberTransformableState { z, o, _ ->
        if (!uiVisible) {
            lastTransformTime = System.currentTimeMillis()
            scale.floatValue *= z
            offset.value += o
        }
    }

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
                delay(200)
                try {
                    context.contentResolver.takePersistableUriPermission(
                        config.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val images = queryImagesInFolder(context, config)
                    allImages.addAll(images)
                } catch (e: Exception) {
                }
                scanningUris.remove(config.uri)
            }
        }
        pickedFolderImages.value = allImages
        isScanning.value = false
    }

    LaunchedEffect(Unit) { refreshFavs(); scanAllFolders() }
    LaunchedEffect(currentIndex.intValue) {
        scale.floatValue = 1f;
        offset.value = Offset.Zero;
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!isPlaying) {
            // --- MAIN DASHBOARD UI ---
            Box(Modifier.padding(padding)) {
                Column(modifier = Modifier.padding(top = 32.dp)) {
                    CenterAlignedTopAppBar(
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = Color.Transparent,
                            titleContentColor = Color.White
                        ),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    "PicRoulette",
                                    fontWeight = FontWeight.Black,
                                    fontSize = 32.sp
                                ); Text(
                                "Rediscover your library",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.Gray
                            )
                            }
                        },
                        actions = {
                            IconButton(onClick = { triggerVibration(context); scope.launch { scanAllFolders(); refreshFavs() } }) {
                                Icon(
                                    Icons.Rounded.Refresh,
                                    null,
                                    tint = if (isScanning.value) themeColor else Color.White
                                )
                            }
                        }
                    )
                    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                        Spacer(Modifier.height(24.dp))
                        Box(
                            modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF050112))
                            .onGloballyPositioned { containerSize = it.size }
                            .clickable {
                                if (pickedFolderImages.value.isNotEmpty()) {
                                    triggerVibration(
                                        context,
                                        VibrationStyle.LONG
                                    ); isFavoritesMode =
                                        false; activeSessionList.clear(); activeSessionList.addAll(
                                        pickedFolderImages.value.shuffled()
                                    ); currentIndex.intValue = 0; isPlaying = true
                                }
                            }) {

                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.horizontalGradient(
                                        0.0f to Color(0xFF7C4DFF),
                                        scanProgress to themeColor,
                                        scanProgress to Color.Transparent
                                    )
                                )
                            )
                            if (isScanning.value && scanProgress > 0f && scanProgress < 1f) {
                                Box(
                                    modifier = Modifier.fillMaxHeight().width(6.dp).graphicsLayer {
                                        translationX =
                                            (scanProgress * containerSize.width.toFloat()) - 3.dp.toPx()
                                    }.background(
                                        Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                Color.White,
                                                Color.Transparent
                                            )
                                        )
                                    ).blur(2.dp)
                                )
                            }
                            Icon(
                                Icons.Rounded.PlayArrow,
                                null,
                                modifier = Modifier.size(240.dp).align(Alignment.CenterEnd)
                                    .offset(x = 60.dp).graphicsLayer {
                                    alpha = if (isScanning.value) pulseAlpha * 0.3f else 0.1f
                                },
                                tint = Color.White
                            )
                            Column(
                                modifier = Modifier.padding(32.dp).align(Alignment.BottomStart)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isScanning.value) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color.White,
                                            strokeWidth = 3.dp
                                        ); Spacer(Modifier.width(12.dp))
                                    }
                                    Text(
                                        text = if (isScanning.value) "INITIALIZING: ${(scanProgress * 100).toInt()}%" else "${pickedFolderImages.value.size} PHOTOS",
                                        color = Color.White,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    "Start Roulette",
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Black
                                )
                            }
                        }
                        Spacer(Modifier.height(32.dp))
                        DashboardActionCard(
                            "Library Folders",
                            "${folderConfigs.size} folders",
                            Icons.Rounded.FolderCopy,
                            Color(0xFFBB86FC)
                        ) { triggerVibration(context); showSheet = true }
                        Spacer(Modifier.height(16.dp))
                        DashboardActionCard(
                            "Your Favorites",
                            "${favoriteFiles.size} images",
                            Icons.Rounded.Favorite,
                            Color(0xFFFF4081)
                        ) {
                            if (favoriteFiles.isNotEmpty()) {
                                triggerVibration(context, VibrationStyle.LONG); isFavoritesMode =
                                    true; activeSessionList.clear(); activeSessionList.addAll(
                                    favoriteFiles.map { it.mediaUri }.shuffled()
                                ); currentIndex.intValue = 0; isPlaying = true
                            }
                        }
                    }
                }
            }

            // --- FOLDER MANAGEMENT SHEET ---
            if (showSheet) {
                ModalBottomSheet(onDismissRequest = { showSheet = false }) {
                    Column(modifier = Modifier.padding(16.dp).fillMaxWidth()) {
                        Text(
                            "Library Folders",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                            items(folderConfigs) { config ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // --- NEW: RECURSIVE TOGGLE ---
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Checkbox(
                                            checked = config.includeSubfolders,
                                            onCheckedChange = { isChecked ->
                                                // Update the specific config with the new value
                                                folderConfigs = folderConfigs.map {
                                                    if (it == config) it.copy(includeSubfolders = isChecked) else it
                                                }
                                                // Save the new state and re-scan
                                                saveFolders(context, folderConfigs)
                                                scope.launch { scanAllFolders() }
                                            }
                                        )
                                        Text("Recursive", fontSize = 10.sp, color = Color.Gray)
                                    }

                                    Spacer(Modifier.width(8.dp))

                                    Text(
                                        text = config.uri.path?.substringAfterLast("/") ?: "Folder",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )

                                    // Existing trashcan button
                                    IconButton(onClick = {
                                        folderConfigs = folderConfigs.filter { it != config }
                                        saveFolders(context, folderConfigs)
                                        scope.launch { scanAllFolders() }
                                    }) {
                                        Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Color.Red)
                                    }
                                }
                            }
                        }
                        val folderLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.OpenDocumentTree()
                        ) { uri: Uri? ->
                            uri?.let {
                                context.contentResolver.takePersistableUriPermission(
                                    it,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )

                                folderConfigs = folderConfigs + FolderConfig(it, true)
                                saveFolders(context, folderConfigs)
                                scope.launch { scanAllFolders() }

                                // FIX: Close the sheet after the folder is successfully added
                                showSheet = false
                            }
                        }

                        Button(
                            onClick = { folderLauncher.launch(null) },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) {
                            Text("Add Folder")
                        }
                    }
                }
            }

        } else {
            // --- PLAYBACK UI ---
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black)
                    .onGloballyPositioned { containerSize = it.size }
                    .transformable(state = transformState)
                    .combinedClickable(
                        onClick = {
                            if (activeSessionList.isNotEmpty()) {
                                triggerVibration(
                                    context,
                                    VibrationStyle.TICK
                                ); if (currentIndex.intValue >= activeSessionList.size - 1) {
                                    activeSessionList.shuffle(); currentIndex.intValue =
                                        0; showShuffleToast = true
                                } else {
                                    currentIndex.intValue += 1
                                }
                            }
                        },
                        onLongClick = {
                            triggerVibration(context, VibrationStyle.LONG); uiVisible = !uiVisible
                        })
            ) {
                val currentUri = activeSessionList.getOrNull(currentIndex.intValue)
                if (currentUri != null) {
                    val currentFileName = remember(currentUri) {
                        var name = ""
                        context.contentResolver.query(
                            currentUri,
                            arrayOf(MediaStore.Images.Media.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )?.use { if (it.moveToFirst()) name = it.getString(0) }
                        name.ifEmpty { currentUri.lastPathSegment ?: "img" }
                    }
                    val isHeartFilled = remember(
                        favoriteFiles,
                        currentFileName
                    ) {
                        favoriteFiles.any {
                            scrubFileName(it.fileNameOnDisk) == scrubFileName(
                                currentFileName
                            )
                        }
                    }

                    AsyncImage(
                        model = currentUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().blur(40.dp).graphicsLayer(alpha = 0.4f)
                    )
                    AsyncImage(
                        model = currentUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale.floatValue,
                            scaleY = scale.floatValue,
                            translationX = offset.value.x,
                            translationY = offset.value.y
                        ),
                        contentScale = ContentScale.Fit
                    )

                    if (showCountSetting) { // Now shows regardless of uiVisible state
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 20.dp, bottom = 48.dp)
                                .background(Color.Black.copy(0.4f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "${currentIndex.intValue + 1} / ${activeSessionList.size}",
                                color = Color.White.copy(0.8f),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = showShuffleToast,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp)
                            .zIndex(1f),
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Surface(
                            color = themeColor,
                            shape = RoundedCornerShape(16.dp),
                            shadowElevation = 8.dp
                        ) {
                            Text(
                                text = "Reshuffled Whole Deck!",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                                color = Color.Black,
                                fontWeight = FontWeight.Black,
                                fontSize = 14.sp
                            )
                        }
                    }

                    AnimatedVisibility(
                        visible = uiVisible,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            // ... your button Row starts here

                            AnimatedVisibility(
                                visible = uiVisible,
                                enter = fadeIn() + slideInVertically(),
                                exit = fadeOut() + slideOutVertically()
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 40.dp)
                                            .align(Alignment.TopCenter),
                                        Arrangement.SpaceBetween,
                                        Alignment.CenterVertically
                                    ) {
                                        Button(onClick = { isPlaying = false }) { Text("Exit") }
                                        IconButton(
                                            onClick = { showMetadata = !showMetadata },
                                            modifier = Modifier.size(64.dp)
                                        ) {
                                            Icon(
                                                Icons.Rounded.Info,
                                                "Info",
                                                tint = Color.White,
                                                modifier = Modifier.size(48.dp)
                                            )
                                        }
                                        key(currentUri, isHeartFilled) {
                                            val hScale = remember { Animatable(1f) }
                                            IconButton(
                                                onClick = {
                                                    triggerVibration(
                                                        context,
                                                        VibrationStyle.HEARTBEAT
                                                    ); scope.launch {
                                                    if (isHeartFilled) {
                                                        val f = favoriteFiles.find {
                                                            scrubFileName(it.fileNameOnDisk) == scrubFileName(
                                                                currentFileName
                                                            )
                                                        }; f?.let {
                                                            deleteFavorite(
                                                                context,
                                                                it.mediaUri
                                                            ); refreshFavs()
                                                        }
                                                    } else {
                                                        hScale.animateTo(
                                                            1.4f,
                                                            spring()
                                                        ); hScale.animateTo(
                                                            1f,
                                                            spring()
                                                        ); saveToFavoritesFolder(
                                                            context,
                                                            currentUri,
                                                            currentFileName,
                                                            scale.floatValue,
                                                            offset.value,
                                                            containerSize
                                                        ); refreshFavs()
                                                    }
                                                }
                                                },
                                                modifier = Modifier.background(
                                                    Color.Black.copy(0.5f),
                                                    CircleShape
                                                ).size(56.dp).scale(hScale.value)
                                            ) {
                                                Icon(
                                                    if (isHeartFilled) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                                    null,
                                                    tint = Color.Red,
                                                    modifier = Modifier.size(36.dp)
                                                )
                                            }
                                        }
                                        Button(onClick = { if (currentIndex.intValue > 0) currentIndex.intValue -= 1 }) {
                                            Text(
                                                "Back"
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            showCountSetting = !showCountSetting; triggerVibration(
                                            context
                                        )
                                        },
                                        modifier = Modifier.align(Alignment.BottomStart)
                                            .padding(24.dp)
                                            .background(Color.Black.copy(0.4f), CircleShape)
                                    ) {
                                        Icon(
                                            if (showCountSetting) Icons.Rounded.Pin else Icons.Rounded.PinDrop,
                                            "Toggle Count",
                                            tint = if (showCountSetting) themeColor else Color.White
                                        )
                                    }
                                    if (!isFavoritesMode) {
                                        IconButton(
                                            onClick = {
                                                triggerVibration(
                                                    context,
                                                    VibrationStyle.LONG
                                                ); showDeleteDialog = true
                                            },
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                                .padding(24.dp).size(64.dp)
                                                .background(Color.Red.copy(0.2f), CircleShape)
                                        ) {
                                            Icon(
                                                Icons.Rounded.DeleteOutline,
                                                "Delete",
                                                tint = Color.Red,
                                                modifier = Modifier.size(32.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = showMetadata,
                                enter = fadeIn() + scaleIn(),
                                exit = fadeOut() + scaleOut()
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize()
                                        .background(Color.Black.copy(0.6f))
                                        .clickable { showMetadata = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        color = Color(0xFF1A1A1A),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier.padding(24.dp).fillMaxWidth()
                                            .clickable(enabled = false) {}) {
                                        Column(modifier = Modifier.padding(24.dp)) {
                                            Text(
                                                "Photo Metadata",
                                                style = MaterialTheme.typography.headlineSmall,
                                                color = themeColor
                                            )
                                            Spacer(Modifier.height(16.dp))
                                            val fileDetails = context.contentResolver.query(
                                                currentUri,
                                                null,
                                                null,
                                                null,
                                                null
                                            )?.use { cursor ->
                                                val nIdx =
                                                    cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
                                                val sIdx =
                                                    cursor.getColumnIndex(MediaStore.Images.Media.SIZE); cursor.moveToFirst(); Pair(
                                                cursor.getString(nIdx) ?: "Unknown",
                                                cursor.getLong(sIdx)
                                            )
                                            }
                                            val opt = BitmapFactory.Options().apply {
                                                inJustDecodeBounds = true
                                            }; context.contentResolver.openInputStream(currentUri)
                                            ?.use { BitmapFactory.decodeStream(it, null, opt) }
                                            MetadataRow(
                                                "Filename",
                                                fileDetails?.first ?: "Unknown"
                                            ); MetadataRow(
                                            "Size",
                                            "${(fileDetails?.second ?: 0) / 1024} KB"
                                        ); MetadataRow(
                                            "Resolution",
                                            "${opt.outWidth} x ${opt.outHeight} px"
                                        ); MetadataRow("URI Path", currentUri.path ?: "N/A")
                                            Spacer(Modifier.height(24.dp)); Button(onClick = {
                                            showMetadata = false
                                        }, modifier = Modifier.fillMaxWidth()) { Text("Close") }
                                        }
                                    }
                                }
                            }
                            if (showDeleteDialog) {
                                AlertDialog(
                                    onDismissRequest = { showDeleteDialog = false },
                                    title = { Text("Delete?") },
                                    text = { Text("Permanently remove?") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showDeleteDialog = false; scope.launch {
                                            activeSessionList.remove(
                                                currentUri
                                            ); if (activeSessionList.isEmpty()) isPlaying =
                                            false; try {
                                            context.contentResolver.delete(currentUri, null, null)
                                        } catch (e: Exception) {
                                        }; scanAllFolders()
                                        }
                                        }) { Text("Delete", color = Color.Red) }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = {
                                            showDeleteDialog = false
                                        }) { Text("Cancel") }
                                    })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
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

fun deleteFavorite(context: Context, uri: Uri) {
    try {
        context.contentResolver.delete(uri, null, null)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}