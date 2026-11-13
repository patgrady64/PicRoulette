package com.pgdevhouse.picroulette

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.graphics.BitmapFactory
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.activity.compose.BackHandler
import android.os.SystemClock
import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

internal data class DuplicateCandidate(val uri: Uri, val size: Long, val name: String, val folder: String = "Unknown", val visualHash: Long? = null)
internal data class DuplicateSet(val hash: String, val images: List<DuplicateCandidate>)
internal data class DuplicateScan(val sets: List<DuplicateSet>, val unreadable: Int)
private enum class DuplicatePage { SETUP, RESULTS, VIEWER }

private fun perceptualHash(context: Context, uri: Uri): Long? = runCatching {
    val resolver = context.contentResolver
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    if (options.outWidth <= 0 || options.outHeight <= 0) return@runCatching null
    val sample = (maxOf(options.outWidth / 500, options.outHeight / 500)).coerceAtLeast(1)
    val decode = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
        ?: return@runCatching null
    val scaled = android.graphics.Bitmap.createScaledBitmap(bitmap, 9, 8, true)
    if (scaled !== bitmap) bitmap.recycle()
    var bits = 0L
    for (y in 0 until 8) for (x in 0 until 8) {
        val a = scaled.getPixel(x, y)
        val b = scaled.getPixel(x + 1, y)
        fun lum(c: Int): Int = (android.graphics.Color.red(c) * 299 +
            android.graphics.Color.green(c) * 587 + android.graphics.Color.blue(c) * 114) / 1000
        bits = (bits shl 1) or if (lum(a) > lum(b)) 1L else 0L
    }
    scaled.recycle()
    bits
}.getOrNull()

private fun photoFolder(context: Context, uri: Uri): String {
    val projection = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DATA)
    return runCatching {
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            val rel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            val data = c.getColumnIndex(MediaStore.MediaColumns.DATA)
            when {
                rel >= 0 && !c.isNull(rel) -> c.getString(rel)
                data >= 0 && !c.isNull(data) -> c.getString(data).substringBeforeLast('/', "")
                else -> null
            }
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.toString().substringBeforeLast('/', "Unknown")
}

/** Visual candidates are suggestions only, not proof that files are interchangeable. */
internal suspend fun findVisualDuplicates(
    context: Context, uris: List<Uri>, progress: (Int, Int) -> Unit
): DuplicateScan = withContext(Dispatchers.IO) {
    val unique = uris.distinctBy { it.toString() }
    var unreadable = 0
    // Hashing needs only the URI and hash. Expensive content-provider metadata and
    // folder lookups are deferred until we know an image belongs in the results.
    val fingerprints = mutableListOf<Pair<Uri, Long>>()
    unique.forEachIndexed { index, uri ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val hash = perceptualHash(context, uri)
        if (hash == null) unreadable++ else fingerprints += uri to hash
        // Avoid dispatching 10,000 Compose recompositions on a large scan.
        if ((index + 1) % 25 == 0 || index == unique.lastIndex) {
            withContext(Dispatchers.Main) { progress(index + 1, unique.size) }
        }
    }
    // Compare all pairs before assembling groups. The old anchor-and-remove strategy
    // skipped valid matches: once B matched A, B could no longer find C even when
    // B and C met the same similarity threshold.
    // Connected components preserve these relationships. Every image still has to
    // be reviewed manually: visual similarity does not guarantee interchangeability.
    val parent = IntArray(fingerprints.size) { it }
    val rank = IntArray(fingerprints.size)
    fun root(index: Int): Int {
        var node = index
        while (parent[node] != node) {
            parent[node] = parent[parent[node]]
            node = parent[node]
        }
        return node
    }
    fun join(a: Int, b: Int) {
        val ra = root(a)
        val rb = root(b)
        if (ra == rb) return
        when {
            rank[ra] < rank[rb] -> parent[ra] = rb
            rank[ra] > rank[rb] -> parent[rb] = ra
            else -> { parent[rb] = ra; rank[ra]++ }
        }
    }
    for (i in fingerprints.indices) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        for (j in i + 1 until fingerprints.size) {
            if (java.lang.Long.bitCount(fingerprints[i].second xor fingerprints[j].second) <= 6) {
                join(i, j)
            }
        }
    }
    val pairs = fingerprints.indices.groupBy { root(it) }.values
        .filter { it.size > 1 }
        .map { indices -> indices.map { fingerprints[it] } }
    fun describe(uri: Uri, hash: Long): DuplicateCandidate {
        val metadata = runCatching {
            context.contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) ?: "Photo" else "Photo"
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else 0L
                    name to size
                }
            }
        }.getOrNull()
        return DuplicateCandidate(uri, metadata?.second ?: 0L,
            metadata?.first ?: (uri.lastPathSegment ?: "Photo"), photoFolder(context, uri), hash)
    }
    val groups = pairs.map { pair ->
        DuplicateSet("visual:${pair.first().first}", pair.map { (uri, hash) -> describe(uri, hash) })
    }
    DuplicateScan(groups, unreadable)
}


/** Exact byte-for-byte duplicates only; never delete based on name or similar appearance. */
internal suspend fun findExactDuplicates(
    context: Context,
    uris: List<Uri>,
    progress: (Int, Int) -> Unit
): DuplicateScan = withContext(Dispatchers.IO) {
    val unique = uris.distinctBy { it.toString() }
    val candidates = mutableListOf<DuplicateCandidate>()
    var unreadable = 0
    unique.forEachIndexed { metaIndex, uri ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val details = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si < 0 || c.isNull(si)) null else DuplicateCandidate(
                        uri, c.getLong(si), if (ni >= 0) c.getString(ni) ?: "Photo" else "Photo", photoFolder(context, uri)
                    )
                }
            }
        }.getOrNull()
        if (details == null) unreadable++ else candidates += details
        withContext(Dispatchers.Main) { progress(metaIndex + 1, unique.size) }
    }
    val potential = candidates.groupBy { it.size }.filter { it.key >= 0 && it.value.size > 1 }
        .values.flatten()
    val hashed = mutableMapOf<Pair<Long, String>, MutableList<DuplicateCandidate>>()
    withContext(Dispatchers.Main) { progress(unique.size, unique.size + potential.size) }
    potential.forEachIndexed { index, item ->
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        val hash = calculateOriginalSha256(context, item.uri)
        if (hash.isEmpty()) unreadable++ else hashed.getOrPut(item.size to hash) { mutableListOf() }.add(item)
        withContext(Dispatchers.Main) { progress(unique.size + index + 1, unique.size + potential.size) }
    }
    DuplicateScan(
        hashed.filterValues { it.size > 1 }.map { (key, images) -> DuplicateSet(key.second, images) },
        unreadable
    )
}

/** The caller refreshes library, album and favorite state after successfully removed files. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DuplicateReviewSheet(
    libraryPhotos: List<Uri>,
    folders: List<FolderConfig>,
    albums: List<PhotoAlbum>,
    favoriteFiles: List<FavoriteFile>,
    mappings: List<FavoriteMapping>,
    onDeleted: (List<Uri>) -> Unit,
    onAlbumsChanged: (List<PhotoAlbum>) -> Unit,
    onToggleFavorite: suspend (Uri) -> Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visualMode by remember { mutableStateOf(DuplicateScanCoordinator.lastVisual) }
    // Choose the source collection first; only those photos are eligible for removal.
    var source by remember { mutableStateOf("library") }
    // Global job state survives closing this screen while the foreground service scans.
    val worker = DuplicateScanCoordinator
    val scan = worker.result
    val scanning = worker.scanning
    val scannedCount = worker.done
    val scanTotal = worker.total
    var stopConfirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var chosenForDeletion by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(if (DuplicateScanCoordinator.result != null && !DuplicateScanCoordinator.scanning) DuplicatePage.RESULTS else DuplicatePage.SETUP) }
    LaunchedEffect(worker.scanning, worker.result) {
        if (!worker.scanning && worker.result != null && page == DuplicatePage.SETUP && worker.completedAt > 0L) page = DuplicatePage.RESULTS
    }
    var previewPhoto by remember { mutableStateOf<DuplicateCandidate?>(null) }
    val scanStartedAt = worker.startedAt
    var currentAlbums by remember(albums) { mutableStateOf(albums) }
    var albumPhoto by remember { mutableStateOf<DuplicateCandidate?>(null) }
    var newAlbumName by remember { mutableStateOf("") }
    var savingAlbum by remember { mutableStateOf(false) }
    var albumError by remember { mutableStateOf("") }
    var changingFavorite by remember { mutableStateOf(false) }
    var favoriteMessage by remember { mutableStateOf("") }
    // Immediately reflect successful favorite operations in result cards and viewer.
    val favoriteOverrides = remember { mutableStateMapOf<String, Boolean>() }
    var favoritesChangedDuringReview by remember { mutableStateOf(false) }

    // Album assignments are references to the original image identity, not copied files.
    // Reload after every change so the duplicate cards and the viewer stay in sync.
    fun updateAlbum(photo: DuplicateCandidate, albumId: String, included: Boolean) {
        if (savingAlbum) return
        savingAlbum = true
        albumError = ""
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    setPhotoInAlbum(context, currentAlbums, albumId, photo.uri, included)
                }
                currentAlbums = updated
                onAlbumsChanged(updated)
            } catch (e: Exception) {
                albumError = e.message ?: "Could not update album"
            } finally {
                savingAlbum = false
            }
        }
    }

    fun makeAlbum(photo: DuplicateCandidate, name: String) {
        if (savingAlbum || name.isBlank()) return
        savingAlbum = true
        albumError = ""
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) {
                    createAlbum(context, currentAlbums, name.trim(), photo.uri)
                }
                currentAlbums = updated
                onAlbumsChanged(updated)
                newAlbumName = ""
            } catch (e: Exception) {
                albumError = e.message ?: "Could not create album"
            } finally {
                savingAlbum = false
            }
        }
    }

    fun goBack() {
        when (page) {
            DuplicatePage.VIEWER -> { previewPhoto = null; page = DuplicatePage.RESULTS }
            DuplicatePage.RESULTS -> page = DuplicatePage.SETUP
            DuplicatePage.SETUP -> if (!deleting) onDismiss()
        }
    }
    BackHandler(enabled = !deleting) { goBack() }

    val sourceOptions = remember(folders) {
        listOf("library" to "Whole library", "favorites" to "Favorites") +
            folders.map { "folder:${it.uri}" to "Folder: ${Uri.decode(it.uri.lastPathSegment.orEmpty()).takeLast(45)}" }
    }
    val favoriteUris = favoriteFiles.map { it.mediaUri.toString() }.toSet()
    val linkedFavoriteUris = mappings.filter { !it.isDeleted }.map { it.favoriteUri }.toSet()
    val selectedCandidates = scan?.sets.orEmpty().flatMap { it.images }
        .filter { it.uri.toString() in chosenForDeletion }
    val protectedCopies = scan?.sets.orEmpty().flatMap { it.images }
        .map { it.uri.toString() }.filter { it !in chosenForDeletion }.toSet()
    val fullySelectedGroups = scan?.sets.orEmpty().count { group ->
        group.images.isNotEmpty() && group.images.all { it.uri.toString() in chosenForDeletion }
    }
    val affectedFavoriteCopies = selectedCandidates.flatMap { item ->
        mappings.filter { !it.isDeleted && it.originalUri == item.uri.toString() && it.favoriteUri.isNotBlank() }
            .map { it.favoriteUri }
    }.distinct().filter { it !in chosenForDeletion && it !in protectedCopies }

    Dialog(onDismissRequest = { if (!deleting) goBack() },
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (page != DuplicatePage.SETUP) {
                        IconButton(onClick = { if (!deleting) goBack() }, enabled = !deleting) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Text(when (page) {
                            DuplicatePage.SETUP -> "Duplicate Finder"
                            DuplicatePage.RESULTS -> "Duplicate Results"
                            DuplicatePage.VIEWER -> "Photo Preview"
                        }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(when (page) {
                            DuplicatePage.SETUP -> "Find matching images in your library"
                            DuplicatePage.RESULTS -> "Compare, inspect and select copies"
                            DuplicatePage.VIEWER -> "Back returns to your results"
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onDismiss, enabled = !deleting) {
                        Icon(Icons.Default.Close, contentDescription = "Close duplicate finder")
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                when (page) {
                    DuplicatePage.SETUP -> {
                        Text("1  •  Match type", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = !visualMode, onClick = { if (!scanning) {
                                visualMode = false; chosenForDeletion = emptySet()
                            } }, label = { Text("Exact matches") })
                            FilterChip(selected = visualMode, onClick = { if (!scanning) {
                                visualMode = true; chosenForDeletion = emptySet()
                            } }, label = { Text("Visually similar") })
                        }
                        Text(if (visualMode) "Visual similarity can include different photos. Always inspect before deleting."
                            else "Exact matches have identical file contents.", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(14.dp))
                        Text("2  •  Where to look", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Choose the photos to compare. Album memberships are shown in results, without counting the same photo twice.",
                            style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            sourceOptions.forEach { (key, title) ->
                                FilterChip(selected = source == key, onClick = {
                                    if (!scanning) { source = key; chosenForDeletion = emptySet(); message = "" }
                                }, label = { Text(title) })
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(enabled = !scanning, modifier = Modifier.fillMaxWidth(), onClick = {
                            message = ""; chosenForDeletion = emptySet(); favoritesChangedDuringReview = false
                            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            DuplicateScanCoordinator.start(context, source, visualMode,
                                libraryPhotos, folders, favoriteFiles.map { it.mediaUri })
                        }) { Text(if (scanning) "Scanning photos…" else "Scan for duplicates") }
                        if (scanning) {
                            OutlinedButton(onClick = { stopConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Stop scan")
                            }
                            Text("You can close this screen or turn off the display. Scanning continues in the background.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (scanning) {
                            Spacer(Modifier.height(16.dp))
                            val fraction = if (scanTotal > 0) scannedCount.toFloat() / scanTotal else 0f
                            val elapsed = (SystemClock.elapsedRealtime() - scanStartedAt).coerceAtLeast(1L) / 1000.0
                            val rate = if (scannedCount > 0) scannedCount / elapsed else 0.0
                            val secondsLeft = if (rate > 0.0 && scanTotal >= scannedCount)
                                ((scanTotal - scannedCount) / rate).toLong() else -1L
                            if (scanTotal == 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(worker.phase, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(6.dp))
                            Text(if (scanTotal == 0) "Preparing scan and identifying possible matches..."
                                else "${scannedCount} of ${scanTotal} scan steps • ${(fraction * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyMedium)
                            Text("Photos found: ${worker.photosToScan}", style = MaterialTheme.typography.bodySmall)
                            Text(if (secondsLeft < 0) "Calculating estimated time remaining..."
                                else "About ${secondsLeft / 60}m ${secondsLeft % 60}s remaining • ${"%.1f".format(rate)} photos/sec",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (worker.error.isNotBlank()) Text(worker.error, color = MaterialTheme.colorScheme.error)
                        if (message.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(message, color = MaterialTheme.colorScheme.error)
                        }
                        if (scan != null && !scanning) {
                            OutlinedButton(onClick = { page = DuplicatePage.RESULTS }, modifier = Modifier.fillMaxWidth()) {
                                Text("Return to previous results")
                            }
                        }
                    }
                    DuplicatePage.RESULTS -> {
                        val result = scan
                        if (result == null) {
                            Text("No results available. Run a scan first.")
                        } else {
                            Text("${result.sets.size} groups • ${result.sets.sumOf { it.images.size }} matching photos" +
                                if (result.unreadable > 0) " • ${result.unreadable} skipped" else "",
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Review each group before selecting files. Nothing is deleted during scanning, and deleting every copy requires extra confirmation.",
                                style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(10.dp))
                            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                            if (result.sets.isEmpty()) {
                                Text("No duplicates found. Your photos have not been changed.")
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(onClick = { page = DuplicatePage.SETUP }) { Text("Change scan settings") }
                            }
                            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(result.sets, key = { it.hash }) { group ->
                                    val deletedInGroup = group.images.count { it.uri.toString() in chosenForDeletion }
                                    Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 2.dp) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                            Text("${group.images.size} ${if (visualMode) "similar photos" else "identical copies"}",
                                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                            Text("${group.images.size - deletedInGroup} kept • $deletedInGroup selected to delete",
                                                style = MaterialTheme.typography.bodySmall)
                                            group.images.forEach { photo ->
                                                val id = photo.uri.toString()
                                                val marked = id in chosenForDeletion
                                                val linkedUris = mappings.filter { !it.isDeleted &&
                                                    (it.originalUri == id || it.favoriteUri == id) }
                                                    .flatMap { listOf(it.originalUri, it.favoriteUri) }.toSet() + id
                                                val fileAlbums = currentAlbums.filter { album ->
                                                    album.photoUris.any { it in linkedUris }
                                                }.map { it.name }
                                                val isFavorite = favoriteOverrides[id] ?: (id in favoriteUris || id in linkedFavoriteUris ||
                                                    mappings.any { !it.isDeleted && it.originalUri == id })
                                                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                    Box(Modifier.size(110.dp).clip(RoundedCornerShape(12.dp))
                                                        .clickable { previewPhoto = photo; page = DuplicatePage.VIEWER }) {
                                                        AsyncImage(model = photo.uri, contentDescription = "Open ${photo.name}",
                                                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                                                        Icon(Icons.Default.ZoomIn, contentDescription = null,
                                                            modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                                                    }
                                                    Column(Modifier.weight(1f)) {
                                                        Text(photo.name, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                                            fontWeight = FontWeight.SemiBold)
                                                        Text("${photo.size / 1024} KB • ${if (isFavorite) "Favorite" else "Not a favorite"}",
                                                            style = MaterialTheme.typography.bodySmall)
                                                        Text("Folder: ${photo.folder}", style = MaterialTheme.typography.bodySmall)
                                                        Text("Albums: ${if (fileAlbums.isEmpty()) "None" else fileAlbums.joinToString()}",
                                                            style = MaterialTheme.typography.bodySmall)
                                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                            TextButton(onClick = { previewPhoto = photo; page = DuplicatePage.VIEWER }) {
                                                                Text("View")
                                                            }
                                                            TextButton(onClick = { albumPhoto = photo }) {
                                                                Text("Edit albums")
                                                            }
                                                        }
                                                    }
                                                    Checkbox(checked = marked,

                                                        onCheckedChange = { checked ->
                                                            chosenForDeletion = if (checked) chosenForDeletion + id else chosenForDeletion - id
                                                        })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            if (favoritesChangedDuringReview) {
                                Text("Favorites changed since this scan. Start a new scan before deleting photos.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                OutlinedButton(onClick = { page = DuplicatePage.SETUP }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Start a fresh scan")
                                }
                            }
                            Button(enabled = chosenForDeletion.isNotEmpty() && !deleting && !favoritesChangedDuringReview,
                                modifier = Modifier.fillMaxWidth(), onClick = { confirm = true }) {
                                Text("Review deletion (${chosenForDeletion.size})")
                            }
                        }
                    }
                    DuplicatePage.VIEWER -> {
                        val photo = previewPhoto
                        if (photo != null) {
                            AsyncImage(model = photo.uri, contentDescription = photo.name,
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().weight(1f))
                            Text(photo.name, fontWeight = FontWeight.SemiBold)
                            Text("Folder: ${photo.folder} • ${photo.size / 1024} KB",
                                style = MaterialTheme.typography.bodySmall)
                            val viewerLinks = linkedAlbumPhotoUris(context, photo.uri)
                            val viewerAlbums = currentAlbums.filter { album -> album.photoUris.any { it in viewerLinks } }
                            Text("Albums: ${if (viewerAlbums.isEmpty()) "None" else viewerAlbums.joinToString { it.name }}",
                                style = MaterialTheme.typography.bodySmall)
                            val previewId = photo.uri.toString()
                            val previewFavorited = favoriteOverrides[previewId] ?: (
                                favoriteFiles.any { it.mediaUri.toString() == previewId } ||
                                mappings.any { !it.isDeleted && (it.originalUri == previewId || it.favoriteUri == previewId) })
                            Button(
                                enabled = !changingFavorite,
                                onClick = {
                                    changingFavorite = true
                                    favoriteMessage = ""
                                    scope.launch {
                                        try {
                                            if (!onToggleFavorite(photo.uri)) {
                                                favoriteMessage = "Could not change favorite. Please try again."
                                            } else {
                                                favoriteOverrides[previewId] = !previewFavorited
                                                favoritesChangedDuringReview = true
                                                chosenForDeletion = emptySet()
                                                favoriteMessage = "Favorite updated. Run a fresh scan before deleting to keep results accurate."
                                            }
                                        } catch (e: Exception) {
                                            favoriteMessage = e.message ?: "Could not change favorite."
                                        } finally {
                                            changingFavorite = false
                                        }
                                    }
                                }, modifier = Modifier.fillMaxWidth()
                            ) { Text(if (changingFavorite) "Updating favorite..." else if (previewFavorited) "★ Unfavorite photo" else "☆ Add to favorites") }
                            if (favoriteMessage.isNotBlank()) Text(favoriteMessage, color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { albumPhoto = photo }, modifier = Modifier.fillMaxWidth()) {
                                Text("Add to / remove from albums")
                            }
                            Button(onClick = { previewPhoto = null; page = DuplicatePage.RESULTS },
                                modifier = Modifier.fillMaxWidth()) { Text("Back to duplicate results") }
                        }
                    }
                }
            }
        }
    }
    if (stopConfirm) AlertDialog(
        onDismissRequest = { stopConfirm = false },
        title = { Text("Stop duplicate scan?") },
        text = { Text("The current scan will stop. No photos will be deleted.") },
        confirmButton = { TextButton(onClick = {
            stopConfirm = false
            DuplicateScanCoordinator.stop(context)
        }) { Text("Stop scan") } },
        dismissButton = { TextButton(onClick = { stopConfirm = false }) { Text("Keep scanning") } }
    )
    // Keep this dialog over either the result cards or the full-image viewer.
    // Do not close on every checkbox: one retained photo may belong to several albums.
    albumPhoto?.let { photo ->
        val linked = linkedAlbumPhotoUris(context, photo.uri)
        AlertDialog(
            onDismissRequest = { if (!savingAlbum) { albumPhoto = null; albumError = "" } },
            title = { Text("Albums for ${photo.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text("Choose every album this photo should appear in. This changes album references, not image files.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(10.dp))
                    if (currentAlbums.isEmpty()) Text("No albums yet. Create one below.")
                    currentAlbums.forEach { album ->
                        val checked = album.photoUris.any { it in linked }
                        Row(Modifier.fillMaxWidth().clickable(enabled = !savingAlbum) {
                            updateAlbum(photo, album.id, !checked)
                        }, verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = checked, enabled = !savingAlbum,
                                onCheckedChange = { updateAlbum(photo, album.id, it) })
                            Text(album.name, Modifier.weight(1f))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    OutlinedTextField(value = newAlbumName, onValueChange = { newAlbumName = it },
                        label = { Text("New album name") }, singleLine = true,
                        enabled = !savingAlbum, modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !savingAlbum && newAlbumName.isNotBlank(),
                        onClick = { makeAlbum(photo, newAlbumName) }) { Text("Create album and add photo") }
                    if (savingAlbum) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (albumError.isNotBlank()) Text(albumError, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = { albumPhoto = null; albumError = "" }, enabled = !savingAlbum) { Text("Done") }
            }
        )
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false; confirmDeleteAll = false },
            title = { Text(if (fullySelectedGroups > 0) "WARNING: Delete every copy?" else "Permanently delete selected photos?") },
            text = { Column {
                Text((if (visualMode) "These files only look similar; verify each selection carefully. " else "") + "Delete ${chosenForDeletion.size} selected files" +
                (if (affectedFavoriteCopies.isNotEmpty()) " and ${affectedFavoriteCopies.size} linked favorite copies" else "") +
                ". Favorite links and album memberships for deleted files will be removed. Copies explicitly kept in this review are protected. " +
                (if (fullySelectedGroups > 0) "WARNING: You selected ALL photos in $fullySelectedGroups duplicate group(s). No photo from those groups will remain. " else "") +
                "This cannot be undone.")
                if (fullySelectedGroups > 0) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmDeleteAll, onCheckedChange = { confirmDeleteAll = it })
                        Text("I understand that every copy in the selected groups will be deleted.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            } },
            confirmButton = {
                TextButton(enabled = fullySelectedGroups == 0 || confirmDeleteAll, onClick = {
                    confirm = false; confirmDeleteAll = false; deleting = true
                    val requested = selectedCandidates.map { it.uri }
                    scope.launch {
                        val successfullyDeleted = mutableListOf<Uri>()
                        var failures = 0
                        for (uri in requested) {
                            val result = deletePhotoFile(context, uri)
                            if (result.isSuccess) {
                                successfullyDeleted += uri
                                // Only delete a linked cropped favorite after its source was deleted.
                                val links = mappings.filter { !it.isDeleted && it.originalUri == uri.toString() }
                                for (link in links) {
                                    val favoriteUri = runCatching { Uri.parse(link.favoriteUri) }.getOrNull()
                                    if (favoriteUri != null && link.favoriteUri.isNotBlank() &&
                                        favoriteUri != uri && favoriteUri.toString() !in chosenForDeletion &&
                                        favoriteUri.toString() !in protectedCopies) {
                                        if (deletePhotoFile(context, favoriteUri).isSuccess) successfullyDeleted += favoriteUri
                                        else failures++
                                    }
                                }
                            } else failures++
                        }
                        if (successfullyDeleted.isNotEmpty()) {
                            onDeleted(successfullyDeleted.distinct())
                            val removedIds = successfullyDeleted.map { it.toString() }.toSet()
                            worker.result = scan?.copy(sets = scan!!.sets.mapNotNull { group ->
                                val remaining = group.images.filter { it.uri.toString() !in removedIds }
                                if (remaining.size < 2) null else group.copy(images = remaining)
                            })
                        }
                        chosenForDeletion = emptySet()
                        message = "${successfullyDeleted.size} file(s) deleted" +
                            if (failures > 0) "; $failures deletion(s) failed. Check storage permissions." else "."
                        deleting = false
                    }
                }) { Text("Delete permanently", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirm = false; confirmDeleteAll = false }) { Text("Cancel") } }
        )
    }
}
