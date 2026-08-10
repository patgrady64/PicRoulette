package com.patgrady64.picroulette

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
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
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PinDrop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.plus
import com.patgrady64.picroulette.utils.FavoritesZipImportPhase
import com.patgrady64.picroulette.utils.FavoritesZipImportProgress
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
    var favoritesRefreshGeneration by remember { mutableIntStateOf(0) }
    var isExportingFavorites by remember {
        mutableStateOf(false)
    }
    var isImportingFavorites by remember {
        mutableStateOf(false)
    }

    var favoritesImportProgress by remember {
        mutableStateOf<FavoritesZipImportProgress?>(null)
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
    var keepScreenAwakeEnabled by remember {
        mutableStateOf(isKeepScreenAwakeEnabled(context))
    }
    var showPhotoCounterByDefault by remember {
        mutableStateOf(isPhotoCounterDefaultEnabled(context))
    }
    var scanLibraryOnStart by remember {
        mutableStateOf(isScanLibraryOnStartEnabled(context))
    }
    var defaultPhotoDisplayMode by remember {
        mutableStateOf(getDefaultPhotoDisplayMode(context))
    }
    var currentPhotoDisplayMode by remember {
        mutableStateOf(defaultPhotoDisplayMode)
    }
    val currentIndex = remember { mutableIntStateOf(0) }
    var uiVisible by remember { mutableStateOf(false) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }

    var showCountSetting by remember {
        mutableStateOf(showPhotoCounterByDefault)
    }
    var showShuffleToast by remember { mutableStateOf(false) }

    /*
     * Briefly shows a filled heart when an already-favorited photo
     * appears in the normal viewer.
     */
    var showFavoriteIndicator by remember {
        mutableStateOf(false)
    }

    var showMetadata by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameBaseName by remember { mutableStateOf("") }
    var renameErrorMessage by remember { mutableStateOf<String?>(null) }
    var isRenaming by remember { mutableStateOf(false) }
    var metadataRefreshVersion by remember { mutableIntStateOf(0) }

    /*
     * Favorites Viewer only: tapping the filled heart arms the current
     * favorite for replacement without deleting it. The old favorite stays
     * safely on disk until the replacement has been written successfully.
     */
    var pendingFavoriteReplacementUri by remember {
        mutableStateOf<Uri?>(null)
    }
    var isReplacingFavorite by remember { mutableStateOf(false) }

    // --- Animation Logic ---
    val currentView = LocalView.current
    DisposableEffect(
        isPlaying,
        keepScreenAwakeEnabled,
        isImportingFavorites
    ) {
        currentView.keepScreenOn =
            isImportingFavorites ||
                (isPlaying && keepScreenAwakeEnabled)

        onDispose {
            currentView.keepScreenOn = false
        }
    }

    val scale = remember { mutableFloatStateOf(1f) }
    val offset = remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(showShuffleToast) {
        if (showShuffleToast) {
            delay(1800)
            showShuffleToast = false
        }
    }

    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            // Start each new viewer session with the saved default mode.
            currentPhotoDisplayMode = defaultPhotoDisplayMode

            // Always enter the viewer with a clean fullscreen image.
            uiVisible = false
            showCountSetting = showPhotoCounterByDefault
            showMetadata = false
            showRenameDialog = false
            renameErrorMessage = null
            isRenaming = false
            showDeleteDialog = false
            showShuffleToast = false

            // Reset zoom and image position.
            scale.floatValue = 1f
            offset.value = Offset.Zero
        }
    }

    val transformState = rememberTransformableState { z, o, _ ->
        if (!uiVisible) {
            scale.floatValue *= z
            offset.value += o
        }
    }

    fun refreshFavs(context: Context, onResult: (List<FavoriteFile>) -> Unit) {
        /*
         * Capture which mappings existed when this refresh started. A slower,
         * older refresh must never erase a favorite/mapping created by a newer
         * user action while the disk query is running.
         */
        val mappingsAtRefreshStart = favoriteMappings.toList()
        favoritesRefreshGeneration++
        val thisRefreshGeneration = favoritesRefreshGeneration

        scope.launch(Dispatchers.IO) {
            val diskFiles = getFavoritesList(context)
            val diskFavoriteUris =
                diskFiles.mapTo(mutableSetOf()) {
                    it.mediaUri.toString()
                }

            val staleUrisFromThisSnapshot =
                mappingsAtRefreshStart
                    .filter { mapping ->
                        mapping.favoriteUri.isBlank() ||
                            mapping.favoriteUri !in diskFavoriteUris
                    }
                    .mapTo(mutableSetOf()) { mapping ->
                        mapping.favoriteUri
                    }

            withContext(Dispatchers.Main) {
                /*
                 * If another refresh started after this one, its disk snapshot
                 * is newer. Ignore this result instead of letting an older
                 * favorite list/count overwrite it.
                 */
                if (thisRefreshGeneration != favoritesRefreshGeneration) {
                    return@withContext
                }

                /*
                 * Apply the cleanup to the latest in-memory list, not the old
                 * snapshot. New or replaced mappings therefore survive even
                 * if this refresh began before they were created.
                 */
                val cleanedMappings =
                    favoriteMappings.filterNot { mapping ->
                        mapping.favoriteUri in staleUrisFromThisSnapshot
                    }.toMutableList()

                if (cleanedMappings != favoriteMappings) {
                    favoriteMappings = cleanedMappings
                    saveFavoriteMappings(
                        context,
                        cleanedMappings
                    )
                }

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
            saveCachedPhotoLibrary(
                context = context,
                images = scannedImages
            )
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

            favoritesImportProgress =
                FavoritesZipImportProgress(
                    phase =
                        FavoritesZipImportPhase.READING_BACKUP
                )

            isImportingFavorites = true

            scope.launch {
                val result = runCatching {
                    withContext(Dispatchers.IO) {
                        importFavoritesZip(
                            context = context,
                            sourceZipUri = sourceZipUri,
                            existingFavorites =
                                existingFavoritesSnapshot,
                            existingMappings =
                                existingMappingsSnapshot,
                            sourceImages =
                                sourceImagesSnapshot,
                            onProgress = { progress ->
                                scope.launch {
                                    if (isImportingFavorites) {
                                        favoritesImportProgress =
                                            progress
                                    }
                                }
                            }
                        )
                    }
                }

                result.onSuccess { importResult ->

                    favoritesImportProgress =
                        FavoritesZipImportProgress(
                            phase =
                                FavoritesZipImportPhase.FINISHING,
                            completed = 1,
                            total = 1,
                            importedCount =
                                importResult.importedCount,
                            skippedDuplicateCount =
                                importResult
                                    .skippedDuplicateCount,
                            failedCount =
                                importResult.failedCount
                        )

                    favoriteMappings =
                        importResult.updatedMappings

                    saveFavoriteMappings(
                        context,
                        favoriteMappings
                    )

                    pendingFavoriteLinkReviews =
                        importResult.reviews

                    currentFavoriteLinkReviewIndex = 0

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

                    /*
                     * Keep the progress dialog visible until the physical
                     * favorites list has been refreshed on the main thread.
                     */
                    refreshFavs(context) { updatedFavorites ->
                        favoriteFiles = updatedFavorites
                        showBackupRestore = false
                        isImportingFavorites = false
                        favoritesImportProgress = null

                        android.widget.Toast.makeText(
                            context,
                            message,
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                result.onFailure { exception ->
                    exception.printStackTrace()

                    isImportingFavorites = false
                    favoritesImportProgress = null

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
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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

    LaunchedEffect(Unit) {
        refreshFavs(context) { updatedList ->
            favoriteFiles = updatedList
        }

        if (scanLibraryOnStart) {
            scanAllFolders()
        } else {
            val cachedImages = loadCachedPhotoLibrary(context)
            pickedFolderImages.value = cachedImages
            scanPhotosFound = cachedImages.size
            scanTotalFolders = folderConfigs.size
        }
    }
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
                        runCatching {
                            context.contentResolver
                                .releasePersistableUriPermission(
                                    config.uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                        }.recoverCatching {
                            // Older saved folders may only have a read grant.
                            context.contentResolver
                                .releasePersistableUriPermission(
                                    config.uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                        }

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
                    keepScreenAwakeEnabled = keepScreenAwakeEnabled,
                    showPhotoCounterByDefault =
                        showPhotoCounterByDefault,
                    scanLibraryOnStart = scanLibraryOnStart,
                    defaultPhotoDisplayMode =
                        defaultPhotoDisplayMode,
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
                    onKeepScreenAwakeChanged = { enabled ->
                        keepScreenAwakeEnabled = enabled
                        setKeepScreenAwakeEnabled(
                            context = context,
                            enabled = enabled
                        )
                    },
                    onPhotoCounterDefaultChanged = { enabled ->
                        showPhotoCounterByDefault = enabled
                        setPhotoCounterDefaultEnabled(
                            context = context,
                            enabled = enabled
                        )
                    },
                    onScanLibraryOnStartChanged = { enabled ->
                        scanLibraryOnStart = enabled
                        setScanLibraryOnStartEnabled(
                            context = context,
                            enabled = enabled
                        )
                    },
                    onDefaultPhotoDisplayModeChanged = { mode ->
                        defaultPhotoDisplayMode = mode
                        setDefaultPhotoDisplayMode(
                            context = context,
                            mode = mode
                        )
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

                    val currentFileDetails = remember(
                        currentUri,
                        metadataRefreshVersion
                    ) {
                        queryPhotoFileDetails(
                            context = context,
                            uri = currentUri
                        )
                    }

                    val currentFileName =
                        currentFileDetails.displayName

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

                    /*
                     * Every item in Favorites Viewer is physically in the
                     * favorites folder, so it starts with a filled heart even
                     * if an older/imported favorite has no mapping record.
                     * The only empty-heart state there is the temporary
                     * replacement state requested by the user.
                     */
                    val isHeartFilled =
                        if (isFavoritesMode) {
                            pendingFavoriteReplacementUri != currentUri
                        } else {
                            favoriteMappings.any {
                                sameImage(
                                    Uri.parse(it.originalUri),
                                    currentUri
                                ) && it.favoriteUri.isNotEmpty()
                            }
                        }

                    /*
                     * Moving to another favorite cancels a pending replacement.
                     * Nothing has been deleted yet, so cancellation is free.
                     */
                    LaunchedEffect(currentUri, isFavoritesMode) {
                        pendingFavoriteReplacementUri = null
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
                        contentScale =
                            if (
                                currentPhotoDisplayMode ==
                                PhotoDisplayMode.FIT
                            ) {
                                ContentScale.Fit
                            } else {
                                ContentScale.Crop
                            }
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
                                                    if (isReplacingFavorite) {
                                                        return@IconButton
                                                    }

                                                    triggerVibration(
                                                        context,
                                                        VibrationStyle.HEARTBEAT
                                                    )

                                                    /*
                                                     * In Favorites Viewer, the heart is an edit/replace
                                                     * toggle rather than a delete button. First tap only
                                                     * arms the current favorite. Second tap saves the
                                                     * current zoom/pan as a replacement.
                                                     */
                                                    if (isFavoritesMode && isHeartFilled) {
                                                        pendingFavoriteReplacementUri = currentUri
                                                        return@IconButton
                                                    }

                                                    scope.launch {
                                                        if (isFavoritesMode) {
                                                            isReplacingFavorite = true

                                                            try {
                                                                hScale.animateTo(1.4f, spring())
                                                                hScale.animateTo(1f, spring())

                                                                val oldFavoriteUri = currentUri
                                                                val favoriteIndex = currentIndex.intValue
                                                                val existingMapping =
                                                                    favoriteMappings.find { mapping ->
                                                                        mapping.favoriteUri ==
                                                                            oldFavoriteUri.toString()
                                                                    }

                                                                /*
                                                                 * Re-crop exactly what is currently visible.
                                                                 * If this favorite has an original mapping, the
                                                                 * mapping itself is preserved below.
                                                                 */
                                                                val replacementUri =
                                                                    saveToFavoritesFolder(
                                                                        context = context,
                                                                        sourceUri = oldFavoriteUri,
                                                                        fileName = displayFileName,
                                                                        scale = scale.floatValue,
                                                                        offset = offset.value,
                                                                        containerSize = containerSize,
                                                                        displayMode =
                                                                            currentPhotoDisplayMode
                                                                    )

                                                                if (replacementUri == null) {
                                                                    snackbarHostState.showSnackbar(
                                                                        "The updated favorite could not be saved."
                                                                    )
                                                                    return@launch
                                                                }

                                                                /*
                                                                 * Never delete first. If the old favorite cannot
                                                                 * be removed, roll back the newly-created file so
                                                                 * the user is not left with duplicate favorites.
                                                                 */
                                                                val oldDeleted =
                                                                    withContext(Dispatchers.IO) {
                                                                        deleteFavorite(
                                                                            context,
                                                                            oldFavoriteUri
                                                                        )
                                                                    }

                                                                if (!oldDeleted) {
                                                                    withContext(Dispatchers.IO) {
                                                                        deleteFavorite(
                                                                            context,
                                                                            replacementUri
                                                                        )
                                                                    }
                                                                    snackbarHostState.showSnackbar(
                                                                        "The old favorite could not be replaced."
                                                                    )
                                                                    return@launch
                                                                }

                                                                if (existingMapping != null) {
                                                                    favoriteMappings =
                                                                        favoriteMappings.map { mapping ->
                                                                            if (
                                                                                mapping.favoriteUri ==
                                                                                oldFavoriteUri.toString()
                                                                            ) {
                                                                                mapping.copy(
                                                                                    favoriteUri =
                                                                                        replacementUri
                                                                                            .toString()
                                                                                )
                                                                            } else {
                                                                                mapping
                                                                            }
                                                                        }.toMutableList()

                                                                    saveFavoriteMappings(
                                                                        context,
                                                                        favoriteMappings
                                                                    )
                                                                }

                                                                if (
                                                                    favoriteIndex in
                                                                    activeSessionList.indices &&
                                                                    activeSessionList[favoriteIndex] ==
                                                                        oldFavoriteUri
                                                                ) {
                                                                    activeSessionList[favoriteIndex] =
                                                                        replacementUri
                                                                }

                                                                pendingFavoriteReplacementUri = null
                                                                scale.floatValue = 1f
                                                                offset.value = Offset.Zero

                                                                refreshFavs(context) {
                                                                    favoriteFiles = it
                                                                }
                                                            } finally {
                                                                isReplacingFavorite = false
                                                            }

                                                            return@launch
                                                        }

                                                        /*
                                                         * Normal Roulette Viewer keeps the traditional
                                                         * add/remove favorite toggle.
                                                         */
                                                        if (isHeartFilled) {
                                                            val mapping =
                                                                favoriteMappings.find {
                                                                    sameImage(
                                                                        Uri.parse(it.originalUri),
                                                                        currentUri
                                                                    )
                                                                }

                                                            mapping?.let { mapToDelete ->
                                                                val favoriteDeleted =
                                                                    if (mapToDelete.favoriteUri.isBlank()) {
                                                                        true
                                                                    } else {
                                                                        withContext(Dispatchers.IO) {
                                                                            deleteFavorite(
                                                                                context,
                                                                                Uri.parse(
                                                                                    mapToDelete.favoriteUri
                                                                                )
                                                                            )
                                                                        }
                                                                    }

                                                                if (!favoriteDeleted) {
                                                                    snackbarHostState.showSnackbar(
                                                                        "The favorite could not be removed."
                                                                    )
                                                                    return@launch
                                                                }

                                                                favoriteMappings =
                                                                    favoriteMappings.filter {
                                                                        it.originalUri !=
                                                                            mapToDelete.originalUri
                                                                    }.toMutableList()
                                                                saveFavoriteMappings(
                                                                    context,
                                                                    favoriteMappings
                                                                )

                                                                refreshFavs(context) {
                                                                    favoriteFiles = it
                                                                }
                                                            }
                                                        } else {
                                                            hScale.animateTo(1.4f, spring())
                                                            hScale.animateTo(1f, spring())

                                                            val sourceUri = currentUri
                                                            val originalRelativePath =
                                                                getOriginalRelativePath(sourceUri)

                                                            val originalSha256 =
                                                                calculateOriginalSha256(
                                                                    context = context,
                                                                    uri = sourceUri
                                                                )
                                                            val favUri =
                                                                saveToFavoritesFolder(
                                                                    context = context,
                                                                    sourceUri = sourceUri,
                                                                    fileName = displayFileName,
                                                                    scale = scale.floatValue,
                                                                    offset = offset.value,
                                                                    containerSize = containerSize,
                                                                    displayMode =
                                                                        currentPhotoDisplayMode
                                                                )

                                                            if (favUri != null) {
                                                                val existingIndex =
                                                                    favoriteMappings.indexOfFirst {
                                                                        sameImage(
                                                                            Uri.parse(it.originalUri),
                                                                            sourceUri
                                                                        )
                                                                    }
                                                                val updated =
                                                                    favoriteMappings.toMutableList()

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
                                                                saveFavoriteMappings(
                                                                    context,
                                                                    favoriteMappings
                                                                )

                                                                refreshFavs(context) {
                                                                    favoriteFiles = it
                                                                }
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

                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .padding(bottom = 24.dp),
                                        shape = RoundedCornerShape(18.dp),
                                        color = Color.Black.copy(alpha = 0.68f),
                                        shadowElevation = 8.dp
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(4.dp),
                                            horizontalArrangement =
                                                Arrangement.spacedBy(4.dp),
                                            verticalAlignment =
                                                Alignment.CenterVertically
                                        ) {
                                            ViewerDisplayModeChoice(
                                                title = "Fit",
                                                selected =
                                                    currentPhotoDisplayMode ==
                                                        PhotoDisplayMode.FIT,
                                                themeColor = themeColor,
                                                onClick = {
                                                    if (
                                                        currentPhotoDisplayMode !=
                                                        PhotoDisplayMode.FIT
                                                    ) {
                                                        triggerVibration(
                                                            context,
                                                            VibrationStyle.TICK
                                                        )
                                                        currentPhotoDisplayMode =
                                                            PhotoDisplayMode.FIT
                                                        scale.floatValue = 1f
                                                        offset.value = Offset.Zero
                                                    }
                                                }
                                            )

                                            ViewerDisplayModeChoice(
                                                title = "Fill",
                                                selected =
                                                    currentPhotoDisplayMode ==
                                                        PhotoDisplayMode.FILL,
                                                themeColor = themeColor,
                                                onClick = {
                                                    if (
                                                        currentPhotoDisplayMode !=
                                                        PhotoDisplayMode.FILL
                                                    ) {
                                                        triggerVibration(
                                                            context,
                                                            VibrationStyle.TICK
                                                        )
                                                        currentPhotoDisplayMode =
                                                            PhotoDisplayMode.FILL
                                                        scale.floatValue = 1f
                                                        offset.value = Offset.Zero
                                                    }
                                                }
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
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(0.6f))
                                        .clickable { showMetadata = false },
                                    contentAlignment = Alignment.Center
                                ) {
                                    val imageBounds = remember(currentUri) {
                                        BitmapFactory.Options().apply {
                                            inJustDecodeBounds = true

                                            context.contentResolver
                                                .openInputStream(currentUri)
                                                ?.use { input ->
                                                    BitmapFactory.decodeStream(
                                                        input,
                                                        null,
                                                        this
                                                    )
                                                }
                                        }
                                    }

                                    val sizeText =
                                        currentFileDetails.sizeBytes?.let { bytes ->
                                            if (bytes >= 1024L * 1024L) {
                                                "%.1f MB".format(
                                                    Locale.US,
                                                    bytes / 1024.0 / 1024.0
                                                )
                                            } else {
                                                "${bytes / 1024L} KB"
                                            }
                                        } ?: "Unknown"

                                    Surface(
                                        color = Color(0xFF1A1A1A),
                                        shape = RoundedCornerShape(24.dp),
                                        modifier = Modifier
                                            .padding(24.dp)
                                            .fillMaxWidth()
                                            .heightIn(max = 620.dp)
                                            .clickable { }
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .padding(24.dp)
                                                .verticalScroll(
                                                    rememberScrollState()
                                                )
                                        ) {
                                            Text(
                                                "Photo Metadata",
                                                style =
                                                    MaterialTheme.typography
                                                        .headlineSmall,
                                                color = themeColor
                                            )

                                            Spacer(Modifier.height(16.dp))

                                            MetadataRow(
                                                "Filename",
                                                currentFileDetails.displayName
                                            )
                                            MetadataRow("Size", sizeText)
                                            MetadataRow(
                                                "Resolution",
                                                "${imageBounds.outWidth} x " +
                                                    "${imageBounds.outHeight} px"
                                            )
                                            MetadataRow(
                                                "URI Path",
                                                currentUri.path ?: "N/A"
                                            )

                                            Spacer(Modifier.height(24.dp))

                                            Button(
                                                onClick = {
                                                    renameBaseName =
                                                        splitFileName(
                                                            currentFileDetails
                                                                .displayName
                                                        ).stem
                                                    renameErrorMessage = null
                                                    showRenameDialog = true
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Rename File")
                                            }

                                            Spacer(Modifier.height(10.dp))

                                            TextButton(
                                                onClick = {
                                                    showMetadata = false
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Close")
                                            }
                                        }
                                    }
                                }
                            }

                            if (showRenameDialog) {
                                val currentNameParts =
                                    splitFileName(
                                        currentFileDetails.displayName
                                    )

                                AlertDialog(
                                    onDismissRequest = {
                                        if (!isRenaming) {
                                            showRenameDialog = false
                                            renameErrorMessage = null
                                        }
                                    },
                                    title = { Text("Rename File") },
                                    text = {
                                        Column {
                                            Text(
                                                "Enter a new name. The image " +
                                                    "type will stay the same."
                                            )

                                            Spacer(Modifier.height(14.dp))

                                            OutlinedTextField(
                                                value = renameBaseName,
                                                onValueChange = {
                                                    renameBaseName = it
                                                    renameErrorMessage = null
                                                },
                                                label = { Text("Filename") },
                                                singleLine = true,
                                                enabled = !isRenaming,
                                                modifier = Modifier.fillMaxWidth()
                                            )

                                            if (
                                                currentNameParts.extension
                                                    .isNotEmpty()
                                            ) {
                                                Spacer(Modifier.height(6.dp))
                                                Text(
                                                    text =
                                                        "File type: " +
                                                            currentNameParts
                                                                .extension,
                                                    color = Color.Gray,
                                                    fontSize = 13.sp
                                                )
                                            }

                                            renameErrorMessage?.let { message ->
                                                Spacer(Modifier.height(10.dp))
                                                Text(
                                                    text = message,
                                                    color = Color.Red,
                                                    fontSize = 13.sp
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(
                                            enabled = !isRenaming,
                                            onClick = {
                                                val validation =
                                                    validateRenamedFileName(
                                                        originalFileName =
                                                            currentFileDetails
                                                                .displayName,
                                                        requestedStem =
                                                            renameBaseName
                                                    )

                                                if (!validation.isValid) {
                                                    renameErrorMessage =
                                                        validation.errorMessage
                                                    return@TextButton
                                                }

                                                val newDisplayName =
                                                    validation.completeFileName
                                                        ?: return@TextButton

                                                scope.launch {
                                                    isRenaming = true
                                                    renameErrorMessage = null

                                                    val oldUri = currentUri
                                                    val renameResult =
                                                        renamePhotoFile(
                                                            context = context,
                                                            uri = oldUri,
                                                            newDisplayName =
                                                                newDisplayName
                                                        )

                                                    if (renameResult.isSuccess) {
                                                        val renamedUri =
                                                            renameResult
                                                                .renamedUri
                                                                ?: oldUri

                                                        val actualDisplayName =
                                                            withContext(
                                                                Dispatchers.IO
                                                            ) {
                                                                queryPhotoFileDetails(
                                                                    context = context,
                                                                    uri = renamedUri
                                                                ).displayName
                                                            }.takeUnless {
                                                                it == "Unknown"
                                                            } ?: newDisplayName

                                                        if (
                                                            currentIndex.intValue in
                                                            activeSessionList.indices
                                                        ) {
                                                            activeSessionList[
                                                                currentIndex.intValue
                                                            ] = renamedUri
                                                        }

                                                        var mappingsChanged = false

                                                        favoriteMappings =
                                                            favoriteMappings.map { mapping ->
                                                                if (isFavoritesMode) {
                                                                    if (
                                                                        mapping.favoriteUri ==
                                                                        oldUri.toString()
                                                                    ) {
                                                                        mappingsChanged = true
                                                                        mapping.copy(
                                                                            favoriteUri =
                                                                                renamedUri
                                                                                    .toString()
                                                                        )
                                                                    } else {
                                                                        mapping
                                                                    }
                                                                } else if (
                                                                    sameImage(
                                                                        Uri.parse(
                                                                            mapping.originalUri
                                                                        ),
                                                                        oldUri
                                                                    )
                                                                ) {
                                                                    mappingsChanged = true
                                                                    mapping.copy(
                                                                        originalUri =
                                                                            renamedUri
                                                                                .toString(),
                                                                        originalFileName =
                                                                            actualDisplayName,
                                                                        originalRelativePath =
                                                                            getOriginalRelativePath(
                                                                                renamedUri
                                                                            )
                                                                    )
                                                                } else {
                                                                    mapping
                                                                }
                                                            }.toMutableList()

                                                        if (mappingsChanged) {
                                                            saveFavoriteMappings(
                                                                context,
                                                                favoriteMappings
                                                            )
                                                        }

                                                        if (isFavoritesMode) {
                                                            favoriteFiles =
                                                                withContext(
                                                                    Dispatchers.IO
                                                                ) {
                                                                    getFavoritesList(
                                                                        context
                                                                    )
                                                                }
                                                        } else {
                                                            pickedFolderImages.value =
                                                                pickedFolderImages.value
                                                                    .map { libraryUri ->
                                                                        if (
                                                                            sameImage(
                                                                                libraryUri,
                                                                                oldUri
                                                                            )
                                                                        ) {
                                                                            renamedUri
                                                                        } else {
                                                                            libraryUri
                                                                        }
                                                                    }

                                                            saveCachedPhotoLibrary(
                                                                context = context,
                                                                images =
                                                                    pickedFolderImages
                                                                        .value
                                                            )
                                                        }

                                                        metadataRefreshVersion++
                                                        showRenameDialog = false

                                                        snackbarHostState
                                                            .showSnackbar(
                                                                "Renamed to " +
                                                                    actualDisplayName
                                                            )
                                                    } else {
                                                        renameErrorMessage =
                                                            renameResult.errorMessage
                                                    }

                                                    isRenaming = false
                                                }
                                            }
                                        ) {
                                            if (isRenaming) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier.size(18.dp),
                                                    strokeWidth = 2.dp
                                                )
                                            } else {
                                                Text("Rename")
                                            }
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            enabled = !isRenaming,
                                            onClick = {
                                                showRenameDialog = false
                                                renameErrorMessage = null
                                            }
                                        ) {
                                            Text("Cancel")
                                        }
                                    }
                                )
                            }

                            if (showDeleteDialog) {
                                AlertDialog(
                                    onDismissRequest = {
                                        showDeleteDialog = false
                                    },
                                    title = { Text("Delete Photo?") },
                                    text = {
                                        Text(
                                            "This permanently removes the " +
                                                "original photo from the device."
                                        )
                                    },
                                    confirmButton = {
                                        TextButton(
                                            onClick = {
                                                showDeleteDialog = false

                                                scope.launch {
                                                    val deleteResult =
                                                        deletePhotoFile(
                                                            context = context,
                                                            uri = currentUri
                                                        )

                                                    deleteResult.onSuccess {
                                                        activeSessionList.remove(
                                                            currentUri
                                                        )

                                                        if (
                                                            activeSessionList
                                                                .isEmpty()
                                                        ) {
                                                            isPlaying = false
                                                        } else if (
                                                            currentIndex.intValue >=
                                                            activeSessionList.size
                                                        ) {
                                                            currentIndex.intValue =
                                                                activeSessionList
                                                                    .lastIndex
                                                        }

                                                        scanAllFolders()
                                                    }.onFailure { exception ->
                                                        snackbarHostState
                                                            .showSnackbar(
                                                                exception.message
                                                                    ?: "The photo could not be deleted."
                                                            )
                                                    }
                                                }
                                            }
                                        ) {
                                            Text(
                                                "Delete",
                                                color = Color.Red
                                            )
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(
                                            onClick = {
                                                showDeleteDialog = false
                                            }
                                        ) {
                                            Text("Cancel")
                                        }
                                    }
                                )
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

    if (isImportingFavorites) {
        FavoritesImportProgressDialog(
            progress = favoritesImportProgress,
            themeColor = themeColor
        )
    }
}

@Composable
private fun ViewerDisplayModeChoice(
    title: String,
    selected: Boolean,
    themeColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = if (selected) {
            themeColor
        } else {
            Color.Transparent
        },
        modifier = Modifier
            .width(72.dp)
            .height(42.dp)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (selected) "✓ $title" else title,
                color = if (selected) Color.Black else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
    }
}

@Composable
private fun FavoritesImportProgressDialog(
    progress: FavoritesZipImportProgress?,
    themeColor: Color
) {
    val phase = progress?.phase
        ?: FavoritesZipImportPhase.READING_BACKUP

    val completed = progress?.completed ?: 0
    val total = progress?.total ?: 0

    val fraction =
        if (total > 0) {
            (completed.toFloat() / total.toFloat())
                .coerceIn(0f, 1f)
        } else {
            null
        }

    val statusText = when (phase) {
        FavoritesZipImportPhase.READING_BACKUP -> {
            if (completed > 0) {
                "Reading backup • $completed entries checked"
            } else {
                "Reading backup"
            }
        }

        FavoritesZipImportPhase.CHECKING_EXISTING_FAVORITES -> {
            if (total > 0) {
                "Checking existing favorites • $completed of $total"
            } else {
                "Checking existing favorites"
            }
        }

        FavoritesZipImportPhase.IMPORTING_FAVORITES -> {
            if (total > 0) {
                "Importing favorites • $completed of $total"
            } else {
                "Importing favorites"
            }
        }

        FavoritesZipImportPhase.PREPARING_SOURCE_LIBRARY -> {
            if (total > 0) {
                "Preparing source library • $completed of $total"
            } else {
                "Preparing source library"
            }
        }

        FavoritesZipImportPhase.RESTORING_LINKS -> {
            if (total > 0) {
                "Restoring original-photo links • $completed of $total"
            } else {
                "Restoring original-photo links"
            }
        }

        FavoritesZipImportPhase.COMPARING_SOURCE_PHOTOS -> {
            if (total > 0) {
                "Comparing source photos • $completed of $total"
            } else {
                "Comparing source photos"
            }
        }

        FavoritesZipImportPhase.FINISHING -> {
            "Finishing import"
        }
    }

    AlertDialog(
        onDismissRequest = {
            /*
             * Imports cannot be dismissed midway because files and mappings
             * may still be actively written.
             */
        },
        title = {
            Text(
                text = "Restoring Favorites",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                if (fraction == null) {
                    Row(
                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = themeColor,
                            strokeWidth = 3.dp
                        )

                        Spacer(
                            modifier = Modifier.width(12.dp)
                        )

                        Text(
                            text = statusText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = statusText,
                        fontWeight = FontWeight.SemiBold
                    )

                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(
                                RoundedCornerShape(999.dp)
                            )
                            .background(
                                Color.White.copy(alpha = 0.12f)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(themeColor)
                        )
                    }

                    Spacer(
                        modifier = Modifier.height(8.dp)
                    )

                    Text(
                        text = "${(fraction * 100f).toInt()}%",
                        color = Color.Gray,
                        style =
                            MaterialTheme.typography.bodySmall
                    )
                }

                val currentFileName =
                    progress?.currentFileName.orEmpty()

                if (currentFileName.isNotBlank()) {
                    Spacer(
                        modifier = Modifier.height(12.dp)
                    )

                    Text(
                        text = currentFileName,
                        color = Color.Gray,
                        style =
                            MaterialTheme.typography.bodySmall,
                        maxLines = 2
                    )
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement =
                        Arrangement.SpaceBetween
                ) {
                    ImportProgressCount(
                        label = "Imported",
                        value = progress?.importedCount ?: 0
                    )

                    ImportProgressCount(
                        label = "Already there",
                        value =
                            progress
                                ?.skippedDuplicateCount
                                ?: 0
                    )

                    ImportProgressCount(
                        label = "Failed",
                        value = progress?.failedCount ?: 0
                    )
                }

                Spacer(
                    modifier = Modifier.height(16.dp)
                )

                Text(
                    text =
                        "Keep PicRoulette open until the import finishes.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ImportProgressCount(
    label: String,
    value: Int
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            fontWeight = FontWeight.Bold
        )

        Text(
            text = label,
            color = Color.Gray,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

