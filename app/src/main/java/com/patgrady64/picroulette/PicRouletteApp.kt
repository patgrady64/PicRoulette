package com.patgrady64.picroulette

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.plus

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
    var favoriteMappings by remember {
        mutableStateOf(
            getFavoriteMappings(context)
        )
    }

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

    var currentOriginalUri by remember {
        mutableStateOf<Uri?>(null)
    }

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
            withContext(Dispatchers.Main) {
                favoriteFiles = list.toList()

                val existingUris =
                    list.map {
                        it.mediaUri.toString()
                    }.toSet()

                favoriteMappings =
                    favoriteMappings.map {
                            mapping ->
                        if (mapping.favoriteUri in existingUris) {
                            mapping
                        } else {
                            mapping.copy(
                                favoriteUri = ""
                            )
                        }
                    }.toMutableList()

                favoriteMappings =
                    favoriteMappings
                        .groupBy { it.originalUri }
                        .map { (_, list) ->
                            list.last()
                        }
                        .toMutableList()

                saveFavoriteMappings(
                    context,
                    favoriteMappings
                )
            }
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

                    val displayFileName = remember(
                        currentUri,
                        isFavoritesMode,
                        favoriteMappings
                    ) {
                        if (isFavoritesMode) {
                            favoriteMappings.find {
                                it.favoriteUri == currentUri.toString()
                            }?.originalFileName
                                ?: currentFileName
                        } else {
                            currentFileName
                        }
                    }

                    val isHeartFilled =
                        if (isFavoritesMode) {
                            favoriteMappings.any {
                                it.favoriteUri == currentUri.toString()
                            }
                        } else {
                            favoriteMappings.any {
                                sameImage(
                                    Uri.parse(it.originalUri),
                                    currentUri
                                ) && it.favoriteUri.isNotEmpty()
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
                                                    triggerVibration(context, VibrationStyle.HEARTBEAT)
                                                    scope.launch {
                                                        if (isHeartFilled) {
                                                            val mapping =
                                                                if (isFavoritesMode) {
                                                                    favoriteMappings.find {
                                                                        sameImage(
                                                                            Uri.parse(it.originalUri),
                                                                            currentUri
                                                                        )
                                                                    }
                                                                } else {
                                                                    favoriteMappings.find {
                                                                        sameImage(
                                                                            Uri.parse(it.originalUri),
                                                                            currentUri
                                                                        )                                                                    }
                                                                }
                                                            mapping?.let { mapToDelete ->

                                                                currentOriginalUri =
                                                                    Uri.parse(mapToDelete.originalUri)

                                                                deleteFavorite(
                                                                    context,
                                                                    Uri.parse(mapToDelete.favoriteUri)
                                                                )

                                                                favoriteMappings =
                                                                    favoriteMappings.map {
                                                                        if (it.originalUri == mapToDelete.originalUri) {
                                                                            it.copy(favoriteUri = "")
                                                                        } else {
                                                                            it
                                                                        }
                                                                    }.toMutableList()

                                                                saveFavoriteMappings(
                                                                    context,
                                                                    favoriteMappings
                                                                )

                                                                refreshFavs()
                                                            }
                                                        }
                                                        else {
                                                            hScale.animateTo(
                                                                1.4f,
                                                                spring()
                                                            ); hScale.animateTo(
                                                                1f,
                                                                spring()
                                                            );

                                                            val sourceUri =
                                                                if (isFavoritesMode) {
                                                                    currentOriginalUri ?: currentUri
                                                                } else {
                                                                    currentUri
                                                                }


                                                            val favUri =
                                                                saveToFavoritesFolder(
                                                                    context,
                                                                    sourceUri,
                                                                    displayFileName,
                                                                    scale.floatValue,
                                                                    offset.value,
                                                                    containerSize
                                                                )

                                                            if (favUri != null) {

                                                                val existingIndex =
                                                                    favoriteMappings.indexOfFirst {
                                                                        sameImage(
                                                                            Uri.parse(it.originalUri),
                                                                            sourceUri
                                                                        )
                                                                    }

                                                                if (existingIndex >= 0) {

                                                                    val updated =
                                                                        favoriteMappings.toMutableList()

                                                                    updated[existingIndex] =
                                                                        updated[existingIndex].copy(
                                                                            favoriteUri = favUri.toString(),
                                                                            dateAdded = System.currentTimeMillis()
                                                                        )

                                                                    favoriteMappings = updated

                                                                } else {

                                                                    val updated =
                                                                        favoriteMappings.toMutableList()

                                                                    updated.add(
                                                                        FavoriteMapping(
                                                                            originalUri = sourceUri.toString(),
                                                                            favoriteUri = favUri.toString(),
                                                                            originalFileName = displayFileName,
                                                                            dateAdded = System.currentTimeMillis()
                                                                        )
                                                                    )

                                                                    favoriteMappings = updated
                                                                }

                                                                saveFavoriteMappings(
                                                                    context,
                                                                    favoriteMappings
                                                                )

                                                                if (isFavoritesMode) {
                                                                    activeSessionList[currentIndex.intValue] = favUri
                                                                }

                                                                refreshFavs()
                                                                currentOriginalUri = null

                                                            }
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