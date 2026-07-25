package com.patgrady64.picroulette

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.patgrady64.picroulette.utils.exportFavoritesZip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.plus
import com.patgrady64.picroulette.utils.importFavoritesZip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp(themeColor: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State ---
    var folderConfigs by remember { mutableStateOf(getSavedFolders(context)) }
    val pickedFolderImages = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val activeSessionList = remember { mutableStateListOf<Uri>() }
    var favoriteFiles by remember { mutableStateOf<List<FavoriteFile>>(emptyList()) }
    var favoriteMappings by remember {
        mutableStateOf(
            getFavoriteMappings(context)
        )
    }
    var isExportingFavorites by remember {
        mutableStateOf(false)
    }
    var isImportingFavorites by remember {
        mutableStateOf(false)
    }

    var isMigratingFavoriteLinks by remember {
        mutableStateOf(false)
    }

    var favoriteRepairProgress by remember {
        mutableFloatStateOf(0f)
    }

    var favoriteRepairStatus by remember {
        mutableStateOf("")
    }

    var pendingFavoriteLinkReviews by remember {
        mutableStateOf<List<FavoriteLinkReview>>(
            emptyList()
        )
    }

    var currentFavoriteLinkReviewIndex by remember {
        mutableIntStateOf(0)
    }
    var isPlaying by remember { mutableStateOf(false) }
    var isFavoritesMode by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val isScanning = remember { mutableStateOf(false) }
    var scanPhotosFound by remember { mutableIntStateOf(0) }
    var scanFoldersCompleted by remember { mutableIntStateOf(0) }
    var scanTotalFolders by remember { mutableIntStateOf(0) }
    var scanCurrentFolder by remember { mutableStateOf("") }
    var showFoldersSheet by remember { mutableStateOf(false) }
    var showOptionsSheet by remember { mutableStateOf(false) }
    var showBackupRestore by remember { mutableStateOf(false) }
    var showAboutSupport by remember { mutableStateOf(false) }
    var hapticFeedbackEnabled by remember {
        mutableStateOf(isHapticFeedbackEnabled(context))
    }
    val currentIndex = remember { mutableIntStateOf(0) }
    var uiVisible by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var showCountSetting by remember { mutableStateOf(false) }
    var showShuffleToast by remember { mutableStateOf(false) }

    /*
     * Briefly shows a filled heart when an already-favorited photo
     * appears in the normal viewer.
     */
    var showFavoriteIndicator by remember {
        mutableStateOf(false)
    }

    var showMetadata by remember { mutableStateOf(false) }

    var currentOriginalUri by remember {
        mutableStateOf<Uri?>(null)
    }

    // --- Animation Logic ---
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
            // Always enter the viewer with a clean fullscreen image.
            uiVisible = false
            showCountSetting = false
            showMetadata = false
            showDeleteDialog = false
            showShuffleToast = false
            showResolution = false

            // Cancel any previous transform/resolution display state.
            lastTransformTime = 0L

            // Reset zoom and image position.
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

    fun refreshFavs(context: Context, onResult: (List<FavoriteFile>) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            // 1. Get physical files currently in the directory
            val diskFiles = getFavoritesList(context)

            // 2. Load current mappings from SharedPrefs
            val currentMappings = getFavoriteMappings(context)

            // 3. Clean up: Only keep mappings for files that exist on disk
            val validMappings = currentMappings.filter { mapping ->
                diskFiles.any { it.mediaUri.toString() == mapping.favoriteUri }
            }

            // 4. Save the cleaned-up list back to SharedPrefs
            saveFavoriteMappings(context, validMappings.toMutableList())

            // 5. Update UI with the accurate count
            withContext(Dispatchers.Main) {
                onResult(diskFiles)
            }
        }
    }

    val scanAllFolders: suspend () -> Unit = {
        isScanning.value = true
        scanPhotosFound = 0
        scanFoldersCompleted = 0
        scanTotalFolders = folderConfigs.size
        scanCurrentFolder = ""

        try {
            val scannedImages = scanPhotoLibrary(
                context = context,
                folders = folderConfigs
            ) { progress ->
                scanPhotosFound = progress.photosFound
                scanFoldersCompleted = progress.foldersCompleted
                scanTotalFolders = progress.totalFolders
                scanCurrentFolder = progress.currentFolderName
            }

            pickedFolderImages.value = scannedImages
        } finally {
            isScanning.value = false
            scanPhotosFound = pickedFolderImages.value.size
            scanFoldersCompleted = scanTotalFolders
        }
    }

    fun advanceFavoriteLinkReview() {
        if (
            currentFavoriteLinkReviewIndex >=
            pendingFavoriteLinkReviews.lastIndex
        ) {
            pendingFavoriteLinkReviews =
                emptyList()

            currentFavoriteLinkReviewIndex = 0
        } else {
            currentFavoriteLinkReviewIndex++
        }
    }

    val importFavoritesZipLauncher =
        rememberLauncherForActivityResult(
            contract =
                ActivityResultContracts.OpenDocument()
        ) { sourceZipUri ->

            if (sourceZipUri == null) {
                return@rememberLauncherForActivityResult
            }

            val existingFavoritesSnapshot =
                favoriteFiles.toList()

            val existingMappingsSnapshot =
                favoriteMappings.toList()

            val sourceImagesSnapshot =
                pickedFolderImages.value.toList()

            isImportingFavorites = true

            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        importFavoritesZip(
                            context = context,
                            sourceZipUri = sourceZipUri,
                            existingFavorites = existingFavoritesSnapshot,
                            existingMappings = existingMappingsSnapshot,
                            sourceImages = sourceImagesSnapshot
                        )
                    }
                }

                isImportingFavorites = false

                result.onSuccess { importResult ->

                    favoriteMappings =
                        importResult.updatedMappings

                    saveFavoriteMappings(
                        context,
                        favoriteMappings
                    )

                    pendingFavoriteLinkReviews =
                        importResult.reviews

                    currentFavoriteLinkReviewIndex = 0

                    /*
                     * Refresh the physical files and dashboard count after the
                     * restored mappings have been saved.
                     */
                    refreshFavs(context) { updatedFavorites ->
                        favoriteFiles = updatedFavorites
                    }

                    showBackupRestore = false

                    val sizeMb =
                        importResult.importedBytes /
                                1024.0 /
                                1024.0

                    val message = buildString {
                        append(
                            "${importResult.importedCount} favorites imported"
                        )

                        append(
                            " • %.1f MB".format(sizeMb)
                        )

                        if (importResult.skippedDuplicateCount > 0) {
                            append(
                                " • ${importResult.skippedDuplicateCount} already present"
                            )
                        }

                        if (importResult.restoredLinkCount > 0) {
                            append(
                                " • ${importResult.restoredLinkCount} links restored"
                            )
                        }

                        if (importResult.existingLinkCount > 0) {
                            append(
                                " • ${importResult.existingLinkCount} links already present"
                            )
                        }

                        if (importResult.reviews.isNotEmpty()) {
                            append(
                                " • ${importResult.reviews.size} need review"
                            )
                        }

                        if (importResult.unresolvedLinkCount > 0) {
                            append(
                                " • ${importResult.unresolvedLinkCount} originals not found"
                            )
                        }

                        if (importResult.failedCount > 0) {
                            append(
                                " • ${importResult.failedCount} failed"
                            )
                        }

                        if (
                            !importResult.containsSourceLinks ||
                            importResult.missingSourceMetadataCount > 0
                        ) {
                            append(
                                " • Repair can check older links"
                            )
                        }
                    }

                    android.widget.Toast.makeText(
                        context,
                        message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                result.onFailure { exception ->
                    exception.printStackTrace()

                    android.widget.Toast.makeText(
                        context,
                        exception.message
                            ?: "Favorites import failed",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }


    val folderLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri: Uri? ->
            uri?.let { selectedUri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        selectedUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                if (folderConfigs.none { it.uri == selectedUri }) {
                    folderConfigs = folderConfigs +
                            FolderConfig(
                                uri = selectedUri,
                                includeSubfolders = true
                            )

                    saveFolders(context, folderConfigs)
                }

                showFoldersSheet = false

                scope.launch {
                    scanAllFolders()
                }
            }
        }

    val exportFavoritesZipLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(
                "application/zip"
            )
        ) { destinationUri ->
            if (destinationUri == null) {
                return@rememberLauncherForActivityResult
            }

            val favoritesSnapshot = favoriteFiles.toList()
            val mappingsSnapshot = favoriteMappings.toList()

            isExportingFavorites = true

            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        exportFavoritesZip(
                            context = context,
                            destinationUri = destinationUri,
                            favorites = favoritesSnapshot,
                            mappings = mappingsSnapshot
                        )
                    }
                }

                isExportingFavorites = false

                result.onSuccess { exportResult ->
                    val sizeMb =
                        exportResult.sourceBytesCopied /
                                1024.0 /
                                1024.0

                    val message = buildString {
                        append(
                            "${exportResult.exportedCount} favorites backed up"
                        )
                        append(" • %.1f MB".format(sizeMb))

                        if (exportResult.failedCount > 0) {
                            append(
                                " • ${exportResult.failedCount} failed"
                            )
                        }
                    }

                    android.widget.Toast.makeText(
                        context,
                        message,
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }

                result.onFailure { exception ->
                    exception.printStackTrace()

                    android.widget.Toast.makeText(
                        context,
                        "Favorites backup failed",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

    val repairFavoriteLinks: () -> Unit = repair@{
        if (
            isMigratingFavoriteLinks ||
            isImportingFavorites ||
            isExportingFavorites ||
            isScanning.value ||
            favoriteFiles.isEmpty() ||
            pickedFolderImages.value.isEmpty()
        ) {
            return@repair
        }

        val favoritesSnapshot = favoriteFiles.toList()
        val sourceImagesSnapshot = pickedFolderImages.value.toList()
        val mappingsSnapshot = favoriteMappings.toList()

        isMigratingFavoriteLinks = true
        favoriteRepairProgress = 0f
        favoriteRepairStatus = "Upgrading Saved Links"

        scope.launch {
            try {
                val firstBackfill =
                    backfillFavoriteMappingMetadata(
                        context = context,
                        mappings = mappingsSnapshot
                    ) { completed, total ->
                        withContext(Dispatchers.Main) {
                            favoriteRepairStatus =
                                "Upgrading Saved Links"

                            favoriteRepairProgress =
                                if (total == 0) {
                                    1f
                                } else {
                                    completed.toFloat() /
                                            total.toFloat()
                                }
                        }
                    }

                favoriteMappings = firstBackfill.updatedMappings
                favoriteRepairStatus = "Finding Original Photos"
                favoriteRepairProgress = 0f

                val migrationResult =
                    withContext(Dispatchers.Default) {
                        migrateExistingFavoriteLinks(
                            favoriteFiles = favoritesSnapshot,
                            sourceImages = sourceImagesSnapshot,
                            existingMappings =
                                firstBackfill.updatedMappings
                        )
                    }

                favoriteRepairStatus = "Finishing New Links"
                favoriteRepairProgress = 0f

                val finalBackfill =
                    backfillFavoriteMappingMetadata(
                        context = context,
                        mappings = migrationResult.updatedMappings
                    ) { completed, total ->
                        withContext(Dispatchers.Main) {
                            favoriteRepairStatus =
                                "Finishing New Links"

                            favoriteRepairProgress =
                                if (total == 0) {
                                    1f
                                } else {
                                    completed.toFloat() /
                                        total.toFloat()
                                }
                        }
                    }

                favoriteMappings = finalBackfill.updatedMappings
                saveFavoriteMappings(context, favoriteMappings)

                pendingFavoriteLinkReviews = migrationResult.reviews
                currentFavoriteLinkReviewIndex = 0
                showBackupRestore = false

                val upgradedCount =
                    firstBackfill.updatedCount +
                        finalBackfill.updatedCount

                val hashFailureCount =
                    firstBackfill.hashFailureCount +
                        finalBackfill.hashFailureCount

                val message = buildString {
                    append("$upgradedCount links upgraded")
                    append(
                        " • ${migrationResult.automaticallyLinked} linked automatically"
                    )
                    append(
                        " • ${migrationResult.reviews.size} need review"
                    )

                    if (migrationResult.noMatchCount > 0) {
                        append(
                            " • ${migrationResult.noMatchCount} not found"
                        )
                    }

                    if (hashFailureCount > 0) {
                        append(
                            " • $hashFailureCount hashes unavailable"
                        )
                    }
                }

                android.widget.Toast.makeText(
                    context,
                    message,
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } catch (exception: Exception) {
                exception.printStackTrace()

                android.widget.Toast.makeText(
                    context,
                    exception.message
                        ?: "Favorite link repair failed",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            } finally {
                isMigratingFavoriteLinks = false
                favoriteRepairProgress = 0f
                favoriteRepairStatus = ""
            }
        }
    }

    LaunchedEffect(Unit) { refreshFavs(context) { updatedList ->
        favoriteFiles = updatedList
    }; scanAllFolders() }
    LaunchedEffect(currentIndex.intValue) {
        scale.floatValue = 1f;
        offset.value = Offset.Zero;
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!isPlaying) {
            // --- MAIN DASHBOARD UI ---
            PicRouletteHomeScreen(
                modifier = Modifier.padding(padding),
                themeColor = themeColor,
                photoCount = pickedFolderImages.value.size,
                favoriteCount = favoriteFiles.size,
                folderCount = folderConfigs.size,
                isScanning = isScanning.value,
                scanPhotosFound = scanPhotosFound,
                scanFoldersCompleted = scanFoldersCompleted,
                scanTotalFolders = scanTotalFolders,
                scanCurrentFolder = scanCurrentFolder,
                onStartRoulette = {
                    if (
                        !isScanning.value &&
                        pickedFolderImages.value.isNotEmpty()
                    ) {
                        triggerVibration(
                            context,
                            VibrationStyle.LONG
                        )

                        isFavoritesMode = false
                        activeSessionList.clear()
                        activeSessionList.addAll(
                            pickedFolderImages.value.shuffled()
                        )
                        currentIndex.intValue = 0
                        isPlaying = true
                    }
                },
                onOpenFavorites = {
                    if (favoriteFiles.isNotEmpty()) {
                        triggerVibration(
                            context,
                            VibrationStyle.LONG
                        )

                        isFavoritesMode = true
                        activeSessionList.clear()
                        activeSessionList.addAll(
                            favoriteFiles
                                .map { it.mediaUri }
                                .shuffled()
                        )
                        currentIndex.intValue = 0
                        isPlaying = true
                    }
                },
                onOpenFolders = {
                    triggerVibration(context)
                    showFoldersSheet = true
                },
                onOpenOptions = {
                    triggerVibration(context)
                    showOptionsSheet = true
                },
                onOpenAboutSupport = {
                    triggerVibration(context)
                    showAboutSupport = true
                },
                onRefresh = {
                    triggerVibration(context)

                    scope.launch {
                        scanAllFolders()
                        refreshFavs(context) { updatedList ->
                            favoriteFiles = updatedList
                        }
                    }
                }
            )

            if (showFoldersSheet) {
                LibraryFoldersSheet(
                    folders = folderConfigs,
                    isScanning = isScanning.value,
                    photosFound = scanPhotosFound,
                    currentFolderName = scanCurrentFolder,
                    themeColor = themeColor,
                    onDismiss = {
                        showFoldersSheet = false
                    },
                    onAddFolder = {
                        folderLauncher.launch(null)
                    },
                    onToggleSubfolders = { config, includeSubfolders ->
                        folderConfigs = folderConfigs.map { current ->
                            if (current == config) {
                                current.copy(
                                    includeSubfolders = includeSubfolders
                                )
                            } else {
                                current
                            }
                        }

                        saveFolders(context, folderConfigs)

                        scope.launch {
                            scanAllFolders()
                        }
                    },
                    onRemoveFolder = { config ->
                        folderConfigs = folderConfigs.filterNot {
                            it == config
                        }

                        saveFolders(context, folderConfigs)

                        scope.launch {
                            scanAllFolders()
                        }
                    },
                    onRescan = {
                        scope.launch {
                            scanAllFolders()
                            refreshFavs(context) { updatedList ->
                                favoriteFiles = updatedList
                            }
                        }
                    }
                )
            }

            if (showOptionsSheet) {
                PicRouletteOptionsSheet(
                    hapticFeedbackEnabled = hapticFeedbackEnabled,
                    favoriteCount = favoriteFiles.size,
                    themeColor = themeColor,
                    onHapticFeedbackChanged = { enabled ->
                        hapticFeedbackEnabled = enabled
                        setHapticFeedbackEnabled(
                            context = context,
                            enabled = enabled
                        )

                        if (enabled) {
                            triggerVibration(context)
                        }
                    },
                    onOpenBackupRestore = {
                        showOptionsSheet = false
                        showBackupRestore = true
                    },
                    onDismiss = {
                        showOptionsSheet = false
                    }
                )
            }

            if (showBackupRestore) {
                BackupRestoreSheet(
                    favoriteCount = favoriteFiles.size,
                    linkedFavoriteCount = favoriteMappings.count {
                        it.originalUri.isNotBlank() &&
                            it.favoriteUri.isNotBlank()
                    },
                    sourcePhotoCount = pickedFolderImages.value.size,
                    isExporting = isExportingFavorites,
                    isImporting = isImportingFavorites,
                    isRepairing = isMigratingFavoriteLinks,
                    repairProgress = favoriteRepairProgress,
                    repairStatus = favoriteRepairStatus,
                    isLibraryScanning = isScanning.value,
                    themeColor = themeColor,
                    onDismiss = {
                        showBackupRestore = false
                    },
                    onExport = {
                        if (
                            favoriteFiles.isNotEmpty() &&
                            !isExportingFavorites &&
                            !isImportingFavorites &&
                            !isMigratingFavoriteLinks
                        ) {
                            val timestamp =
                                SimpleDateFormat(
                                    "yyyyMMddHHmmss",
                                    Locale.US
                                ).format(Date())

                            exportFavoritesZipLauncher.launch(
                                "PicRoulette-Favorites-Backup-$timestamp.zip"
                            )
                        }
                    },
                    onImport = {
                        if (
                            !isImportingFavorites &&
                            !isExportingFavorites &&
                            !isMigratingFavoriteLinks
                        ) {
                            importFavoritesZipLauncher.launch(
                                arrayOf(
                                    "application/zip",
                                    "application/x-zip-compressed",
                                    "application/octet-stream"
                                )
                            )
                        }
                    },
                    onRepair = repairFavoriteLinks
                )
            }

            if (showAboutSupport) {
                AboutSupportSheet(
                    themeColor = themeColor,
                    onDismiss = {
                        showAboutSupport = false
                    }
                )
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

                    /*
                     * Each time a new normal-mode photo appears, briefly show
                     * the filled heart when that original photo is favorited.
                     * Changing photos automatically cancels the previous delay.
                     */
                    LaunchedEffect(
                        currentUri,
                        isHeartFilled,
                        isFavoritesMode
                    ) {
                        showFavoriteIndicator = false

                        if (
                            !isFavoritesMode &&
                            isHeartFilled
                        ) {
                            showFavoriteIndicator = true
                            delay(2000)
                            showFavoriteIndicator = false
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

                    /*
                     * Heart-only notification. This is separate from the full
                     * viewer menu and is not clickable.
                     */
                    AnimatedVisibility(
                        visible =
                            showFavoriteIndicator &&
                            !uiVisible &&
                            !isFavoritesMode &&
                            isHeartFilled,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 48.dp)
                            .zIndex(2f),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.55f),
                            shadowElevation = 8.dp,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Favorite,
                                    contentDescription =
                                        "Already in Favorites",
                                    tint = Color.Red,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }
                    }

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
                                                            val mapping = if (isFavoritesMode) {
                                                                favoriteMappings.find { it.favoriteUri == currentUri.toString() }
                                                            } else {
                                                                favoriteMappings.find { it.originalUri == currentUri.toString() }
                                                            }

                                                            mapping?.let { mapToDelete ->
                                                                // 1. Physically delete
                                                                if (mapToDelete.favoriteUri.isNotBlank()) {
                                                                    deleteFavorite(context, Uri.parse(mapToDelete.favoriteUri))
                                                                }

                                                                // 2. Remove mapping entirely or clear URI
                                                                favoriteMappings = favoriteMappings.filter { it.originalUri != mapToDelete.originalUri }.toMutableList()
                                                                saveFavoriteMappings(context, favoriteMappings)

                                                                // 3. Handle UI state & Navigation
                                                                if (isFavoritesMode) {
                                                                    activeSessionList.remove(currentUri)

                                                                    // Force a sync with the disk before exiting
                                                                    val updatedFavs = getFavoritesList(context)
                                                                    favoriteFiles = updatedFavs

                                                                    if (activeSessionList.isEmpty()) {
                                                                        isPlaying = false
                                                                        return@launch
                                                                    }

                                                                    if (currentIndex.intValue >= activeSessionList.size) {
                                                                        currentIndex.intValue = activeSessionList.lastIndex
                                                                    }
                                                                } else {
                                                                    // Refresh list in non-fav mode to update the "heart" state
                                                                    refreshFavs(context) { favoriteFiles = it }
                                                                }
                                                            }
                                                        } else {
                                                            // --- SAVE LOGIC ---
                                                            hScale.animateTo(1.4f, spring())
                                                            hScale.animateTo(1f, spring())

                                                            val sourceUri = if (isFavoritesMode) (currentOriginalUri ?: currentUri) else currentUri
                                                            val originalRelativePath =
                                                                getOriginalRelativePath(sourceUri)

                                                            val originalSha256 =
                                                                calculateOriginalSha256(
                                                                    context = context,
                                                                    uri = sourceUri
                                                                )
                                                            val favUri = saveToFavoritesFolder(context, sourceUri, displayFileName, scale.floatValue, offset.value, containerSize)

                                                            if (favUri != null) {
                                                                val existingIndex = favoriteMappings.indexOfFirst { sameImage(Uri.parse(it.originalUri), sourceUri) }
                                                                val updated = favoriteMappings.toMutableList()

                                                                if (existingIndex >= 0) {
                                                                    updated[existingIndex] =
                                                                        updated[existingIndex].copy(
                                                                            favoriteUri =
                                                                                favUri.toString(),

                                                                            originalFileName =
                                                                                displayFileName,

                                                                            originalRelativePath =
                                                                                originalRelativePath,

                                                                            originalSha256 =
                                                                                originalSha256,

                                                                            dateAdded =
                                                                                System.currentTimeMillis()
                                                                        )
                                                                } else {
                                                                    updated.add(
                                                                        FavoriteMapping(
                                                                            originalUri =
                                                                                sourceUri.toString(),

                                                                            favoriteUri =
                                                                                favUri.toString(),

                                                                            originalFileName =
                                                                                displayFileName,

                                                                            originalRelativePath =
                                                                                originalRelativePath,

                                                                            originalSha256 =
                                                                                originalSha256,

                                                                            dateAdded =
                                                                                System.currentTimeMillis()
                                                                        )
                                                                    )
                                                                }
                                                                favoriteMappings = updated
                                                                saveFavoriteMappings(context, favoriteMappings)

                                                                if (isFavoritesMode) activeSessionList[currentIndex.intValue] = favUri

                                                                refreshFavs(context) { favoriteFiles = it }
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
    val currentLinkReview =
        pendingFavoriteLinkReviews.getOrNull(
            currentFavoriteLinkReviewIndex
        )

    if (currentLinkReview != null) {

        /*
         * Remove candidates that may have been assigned while
         * resolving an earlier duplicate.
         */
        val availableCandidates =
            currentLinkReview.candidates.filterNot {
                    candidate ->

                favoriteMappings.any { mapping ->
                    mapping.originalUri ==
                            candidate.sourceUri.toString()
                }
            }

        AlertDialog(
            onDismissRequest = {
                /*
                 * Closing does not lose anything.
                 * Rerunning Repair Favorite Links will recreate
                 * the unresolved review queue.
                 */
                pendingFavoriteLinkReviews =
                    emptyList()

                currentFavoriteLinkReviewIndex = 0
            },

            title = {
                Column {
                    Text("Choose the Original Photo")

                    Text(
                        text =
                            "Review ${currentFavoriteLinkReviewIndex + 1} of ${pendingFavoriteLinkReviews.size}",

                        style =
                            MaterialTheme.typography.labelMedium,

                        color = Color.Gray
                    )
                }
            },

            text = {
                Column {
                    Text(
                        text = "Saved favorite copy",
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    AsyncImage(
                        model =
                            currentLinkReview
                                .favoriteFile
                                .mediaUri,

                        contentDescription =
                            "Saved favorite preview",

                        contentScale =
                            ContentScale.Fit,

                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(
                                RoundedCornerShape(16.dp)
                            )
                            .background(Color.Black)
                    )

                    Spacer(
                        modifier = Modifier.height(18.dp)
                    )

                    Text(
                        text =
                            "Which original image created this favorite?",

                        fontWeight = FontWeight.Bold
                    )

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    if (availableCandidates.isEmpty()) {
                        Text(
                            text =
                                "No unused candidates remain for this favorite.",

                            color = Color.Gray
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(
                                max = 360.dp
                            )
                        ) {
                            items(
                                items = availableCandidates,
                                key = {
                                        candidate ->
                                    candidate.sourceUri.toString()
                                }
                            ) { candidate ->

                                Surface(
                                    onClick = {
                                        scope.launch {
                                            val originalSha256 =
                                                calculateOriginalSha256(
                                                    context = context,
                                                    uri = candidate.sourceUri
                                                )

                                            val updatedMappings =
                                                favoriteMappings
                                                    .filterNot { mapping ->

                                                        mapping.favoriteUri ==
                                                                currentLinkReview
                                                                    .favoriteFile
                                                                    .mediaUri
                                                                    .toString() ||

                                                                mapping.originalUri ==
                                                                candidate
                                                                    .sourceUri
                                                                    .toString()
                                                    }
                                                    .toMutableList()

                                            updatedMappings.add(
                                                FavoriteMapping(
                                                    originalUri =
                                                        candidate
                                                            .sourceUri
                                                            .toString(),

                                                    favoriteUri =
                                                        currentLinkReview
                                                            .favoriteFile
                                                            .mediaUri
                                                            .toString(),

                                                    originalFileName =
                                                        candidate.fileName,

                                                    originalRelativePath =
                                                        candidate.relativePath,

                                                    originalSha256 =
                                                        originalSha256,

                                                    dateAdded =
                                                        System.currentTimeMillis()
                                                )
                                            )

                                            favoriteMappings =
                                                updatedMappings

                                            saveFavoriteMappings(
                                                context,
                                                updatedMappings
                                            )

                                            advanceFavoriteLinkReview()
                                        }
                                    },

                                    shape =
                                        RoundedCornerShape(16.dp),

                                    color =
                                        Color.White.copy(
                                            alpha = 0.06f
                                        ),

                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(
                                            vertical = 5.dp
                                        )
                                ) {
                                    Row(
                                        modifier = Modifier.padding(
                                            10.dp
                                        ),

                                        verticalAlignment =
                                            Alignment.CenterVertically
                                    ) {
                                        AsyncImage(
                                            model =
                                                candidate.sourceUri,

                                            contentDescription =
                                                candidate.relativePath,

                                            contentScale =
                                                ContentScale.Crop,

                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(
                                                    RoundedCornerShape(
                                                        12.dp
                                                    )
                                                )
                                                .background(
                                                    Color.Black
                                                )
                                        )

                                        Spacer(
                                            modifier =
                                                Modifier.width(12.dp)
                                        )

                                        Column(
                                            modifier =
                                                Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text =
                                                    candidate.fileName,

                                                fontWeight =
                                                    FontWeight.Bold
                                            )

                                            Spacer(
                                                modifier =
                                                    Modifier.height(3.dp)
                                            )

                                            Text(
                                                text =
                                                    candidate.relativePath,

                                                color = Color.Gray,

                                                style =
                                                    MaterialTheme
                                                        .typography
                                                        .bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },

            confirmButton = {
                TextButton(
                    onClick = {
                        advanceFavoriteLinkReview()
                    }
                ) {
                    Text("Skip for Now")
                }
            },

            dismissButton = {
                TextButton(
                    onClick = {
                        pendingFavoriteLinkReviews =
                            emptyList()

                        currentFavoriteLinkReviewIndex =
                            0
                    }
                ) {
                    Text("Close")
                }
            }
        )
    }
}