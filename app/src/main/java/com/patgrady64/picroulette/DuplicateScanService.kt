package com.patgrady64.picroulette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Active app-process scan state. The foreground service keeps scans running when UI closes. */
internal object DuplicateScanCoordinator {
    var scanning by mutableStateOf(false)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var phase by mutableStateOf("")
    var error by mutableStateOf("")
    var photosToScan by mutableIntStateOf(0)
    var lastVisual by mutableStateOf(false)
    var startedAt by mutableLongStateOf(0L)
    var completedAt by mutableLongStateOf(0L)
    var result by mutableStateOf<DuplicateScan?>(null)

    // These are passed in memory rather than as a huge intent that may exceed Binder's limit.
    internal data class Request(
        val source: String, val visual: Boolean, val library: List<Uri>,
        val folders: List<FolderConfig>, val favorites: List<Uri>
    )
    internal var pending: Request? = null

    fun start(context: Context, source: String, visual: Boolean,
              library: List<Uri>, folders: List<FolderConfig>, favorites: List<Uri>) {
        if (scanning) return
        pending = Request(source, visual, library.toList(), folders.toList(), favorites.toList())
        result = null
        done = 0; total = 0; photosToScan = 0; lastVisual = visual; error = ""; phase = "Preparing scan..."
        completedAt = 0L
        startedAt = SystemClock.elapsedRealtime()
        scanning = true
        try {
            val intent = Intent(context, DuplicateScanService::class.java)
            context.startForegroundService(intent)
        } catch (ex: Exception) {
            scanning = false
            error = ex.message ?: "Unable to launch background scan"
        }
    }

    fun stop(context: Context) {
        context.startService(Intent(context, DuplicateScanService::class.java).apply {
            action = DuplicateScanService.ACTION_STOP
        })
    }
}

/** User-initiated foreground work: can continue during screen-off and when the dialog closes. */
class DuplicateScanService : Service() {
    companion object {
        const val ACTION_STOP = "com.patgrady64.picroulette.STOP_DUPLICATE_SCAN"
        private const val CHANNEL = "duplicate_scans"
        private const val NOTIFICATION_ID = 9201
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var scanJob: Job? = null
    private var lastNotificationAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Duplicate photo scans", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scanJob?.cancel()
            DuplicateScanCoordinator.scanning = false
            DuplicateScanCoordinator.phase = "Scan stopped"
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (scanJob?.isActive == true) return START_NOT_STICKY
        val request = DuplicateScanCoordinator.pending
        if (request == null) { stopSelf(); return START_NOT_STICKY }
        DuplicateScanCoordinator.pending = null
        startForeground(NOTIFICATION_ID, makeNotification("Finding photos to check", ongoing = true))
        scanJob = scope.launch {
            try {
                val all = gatherPhotos(request)
                ensureActive()
                if (all.isEmpty()) {
                    DuplicateScanCoordinator.error = "No pictures were found. Check your configured folders and storage permissions."
                    DuplicateScanCoordinator.phase = "No photos found"
                } else {
                    DuplicateScanCoordinator.photosToScan = all.size
                    DuplicateScanCoordinator.phase = "Checking ${all.size} photos"
                    DuplicateScanCoordinator.total = all.size
                    notifyProgress()
                    val progress: (Int, Int) -> Unit = { done, total ->
                        DuplicateScanCoordinator.done = done
                        DuplicateScanCoordinator.total = total
                        DuplicateScanCoordinator.phase = if (request.visual) "Comparing visual fingerprints" else "Checking exact file matches"
                        notifyProgress()
                    }
                    val results = if (request.visual) findVisualDuplicates(this@DuplicateScanService, all, progress)
                        else findExactDuplicates(this@DuplicateScanService, all, progress)
                    ensureActive()
                    DuplicateScanCoordinator.result = results
                    DuplicateScanCoordinator.phase = "Scan complete"
                    DuplicateScanCoordinator.completedAt = SystemClock.elapsedRealtime()
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                        makeNotification("Finished: ${results.sets.size} duplicate groups found", ongoing = false))
                }
            } catch (_: CancellationException) {
                DuplicateScanCoordinator.phase = "Scan stopped"
            } catch (e: Exception) {
                DuplicateScanCoordinator.error = e.message ?: "Scan failed"
                DuplicateScanCoordinator.phase = "Scan failed"
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
                    makeNotification("Duplicate scan failed", ongoing = false))
            } finally {
                DuplicateScanCoordinator.scanning = false
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun gatherPhotos(request: DuplicateScanCoordinator.Request): List<Uri> {
        if (request.source == "favorites") return request.favorites.distinctBy { it.toString() }
        val folders = if (request.source.startsWith("folder:")) request.folders.filter {
            it.uri.toString() == request.source.removePrefix("folder:")
        } else request.folders
        DuplicateScanCoordinator.phase = "Reading configured photo folders"
        val scan = scanPhotoLibrary(this, folders) { progress ->
            DuplicateScanCoordinator.phase = "Collecting folders ${progress.foldersCompleted}/${progress.totalFolders}: ${progress.photosFound} photos"
            notifyProgress()
        }
        val cached = loadCachedPhotoLibraryByFolder(this)
        val found = linkedSetOf<Uri>()
        scan.images.forEach { found.add(it) }
        // Only use cached contents for failed folder scans; stale folder data could be deleted.
        scan.failures.forEach { failure ->
            cached[failure.folderUri.toString()].orEmpty().forEach { found.add(it) }
        }
        if (request.source == "library") {
            // The live list can be incomplete; the fresh folder scan is authoritative.
            // When no folder is configured, use the existing library cache as fallback.
            if (folders.isEmpty()) {
                request.library.forEach { found.add(it) }
                loadCachedPhotoLibrary(this).forEach { found.add(it) }
            }
            request.favorites.forEach { found.add(it) }
        }
        return found.toList()
    }

    private fun makeNotification(text: String, ongoing: Boolean): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("PicRoulette duplicate finder")
            .setContentText(text)
            .setContentIntent(pending)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun notifyProgress() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationAt < 1200) return
        lastNotificationAt = now
        val state = DuplicateScanCoordinator
        val text = if (state.total > 0) "${state.done}/${state.total} photos checked" else state.phase
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,
            makeNotification(text, ongoing = true))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
