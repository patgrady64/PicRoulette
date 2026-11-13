package com.pgdevhouse.picroulette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.pgdevhouse.picroulette.utils.FavoritesZipExportPhase
import com.pgdevhouse.picroulette.utils.FavoritesZipExportProgress
import com.pgdevhouse.picroulette.utils.FavoritesZipImportPhase
import com.pgdevhouse.picroulette.utils.FavoritesZipImportProgress
import com.pgdevhouse.picroulette.utils.FavoritesZipImportResult
import com.pgdevhouse.picroulette.utils.exportFavoritesZip
import com.pgdevhouse.picroulette.utils.importFavoritesZip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Foreground transfer state is shared with the UI, not owned by any screen. */
internal object BackupRestoreCoordinator {
    sealed class Request {
        data class Export(
            val destination: Uri, val favorites: List<FavoriteFile>,
            val mappings: List<FavoriteMapping>, val albums: List<PhotoAlbum>,
            val roots: List<Uri>, val images: List<Uri>
        ) : Request()
        data class Import(
            val source: Uri, val root: Uri, val images: List<Uri>,
            val favorites: List<FavoriteFile>, val mappings: List<FavoriteMapping>
        ) : Request()
    }

    var running by mutableStateOf(false)
        private set
    var operation by mutableStateOf("")
        private set
    var exportProgress by mutableStateOf<FavoritesZipExportProgress?>(null)
        private set
    var importProgress by mutableStateOf<FavoritesZipImportProgress?>(null)
        private set
    var resultMessage by mutableStateOf("")
        private set
    var failed by mutableStateOf(false)
        private set
    var finishSerial by mutableIntStateOf(0)
        private set
    var importResult: FavoritesZipImportResult? = null
        private set
    internal var pending: Request? = null

    @Synchronized
    fun start(context: Context, request: Request): Boolean {
        if (running) return false
        pending = request
        operation = if (request is Request.Export) "backup" else "restore"
        exportProgress = null
        importProgress = null
        importResult = null
        resultMessage = ""
        failed = false
        running = true
        return try {
            context.startForegroundService(Intent(context, BackupRestoreService::class.java))
            true
        } catch (e: Exception) {
            pending = null
            running = false
            failed = true
            resultMessage = e.message ?: "Unable to start background transfer"
            finishSerial++
            false
        }
    }

    internal fun exportUpdate(value: FavoritesZipExportProgress) { exportProgress = value }
    internal fun importUpdate(value: FavoritesZipImportProgress) { importProgress = value }

    @Synchronized
    internal fun complete(message: String, error: Boolean, restored: FavoritesZipImportResult? = null) {
        importResult = restored
        resultMessage = message
        failed = error
        running = false
        pending = null
        finishSerial++
    }
}

class BackupRestoreService : Service() {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val manager by lazy { getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    private var lastNotification = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Backup and restore", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = BackupRestoreCoordinator.pending
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Android requires foreground promotion promptly after startForegroundService.
        try {
            startForeground(NOTIFICATION_ID, notification("Preparing ${BackupRestoreCoordinator.operation}", 0, 0, true))
        } catch (e: Exception) {
            BackupRestoreCoordinator.complete("Could not start foreground transfer: ${e.message}", true)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        BackupRestoreCoordinator.pending = null
        worker.launch {
            try {
                when (request) {
                    is BackupRestoreCoordinator.Request.Export -> doExport(request)
                    is BackupRestoreCoordinator.Request.Import -> doImport(request)
                }
            } catch (e: Exception) {
                BackupRestoreCoordinator.complete("${BackupRestoreCoordinator.operation.replaceFirstChar { it.uppercase() }} failed: ${e.message ?: e.javaClass.simpleName}", true)
                showFinalNotification("Transfer failed", e.message ?: "See PicRoulette for details")
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun doExport(r: BackupRestoreCoordinator.Request.Export) {
        val result = exportFavoritesZip(this, r.destination, r.favorites, r.mappings, r.albums, r.roots, r.images) { p ->
            BackupRestoreCoordinator.exportUpdate(p)
            updateNotification(exportStage(p.phase), p.completed, p.total)
        }
        val message = "${result.exportedCount} favorites backed up${if (result.failedCount > 0) ", ${result.failedCount} failed" else ""}"
        BackupRestoreCoordinator.complete(message, false)
        showFinalNotification("Backup complete", message)
    }

    private fun doImport(r: BackupRestoreCoordinator.Request.Import) {
        val result = importFavoritesZip(this, r.source, r.favorites, r.mappings, r.images, r.root) { p ->
            BackupRestoreCoordinator.importUpdate(p)
            updateNotification(importStage(p.phase), p.completed, p.total)
        }
        BackupRestoreCoordinator.importUpdate(FavoritesZipImportProgress(
            phase = FavoritesZipImportPhase.FINISHING,
            importedCount = result.importedCount,
            skippedDuplicateCount = result.skippedDuplicateCount,
            failedCount = result.failedCount
        ))
        updateNotification("Saving restored links and albums", 0, 0, true)
        // Commit mappings in the foreground service; finishing no longer depends on a UI callback.
        check(saveFavoriteMappings(this, result.updatedMappings, synchronous = true)) {
            "Unable to persist restored favorite mappings"
        }
        val message = "${result.importedCount} imported, ${result.skippedDuplicateCount} already present, ${result.failedCount} failed; ${result.restoredLinkCount} links and ${result.restoredAlbumCount} albums restored"
        BackupRestoreCoordinator.complete(message, false, result)
        showFinalNotification("Restore complete", message)
    }

    private fun updateNotification(stage: String, done: Int, total: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotification < 500L && (total == 0 || done < total)) return
        lastNotification = now
        runCatching { manager.notify(NOTIFICATION_ID, notification(stage, done, total, true)) }
    }

    private fun notification(stage: String, done: Int, total: Int, ongoing: Boolean): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val label = if (total > 0) "$stage • $done / $total" else stage
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("PicRoulette ${BackupRestoreCoordinator.operation}")
            .setContentText(label)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(0), done.coerceAtLeast(0), total <= 0)
            .build()
    }

    private fun showFinalNotification(title: String, message: String) {
        runCatching { manager.notify(NOTIFICATION_ID + 1, Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("PicRoulette — $title")
            .setContentText(message)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()) }
    }

    override fun onDestroy() {
        worker.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "picroulette_backup_restore"
        private const val NOTIFICATION_ID = 2714
        private fun exportStage(p: FavoritesZipExportPhase) = when (p) {
            FavoritesZipExportPhase.PREPARING -> "Preparing backup"
            FavoritesZipExportPhase.COPYING_FAVORITES -> "Saving favorites"
            FavoritesZipExportPhase.SAVING_LIBRARY_INDEX -> "Saving photo library index"
            FavoritesZipExportPhase.SAVING_ALBUMS -> "Saving albums"
            FavoritesZipExportPhase.FINALIZING -> "Finalizing backup ZIP"
        }
        private fun importStage(p: FavoritesZipImportPhase) = when (p) {
            FavoritesZipImportPhase.READING_BACKUP -> "Reading backup"
            FavoritesZipImportPhase.CHECKING_EXISTING_FAVORITES -> "Checking favorites"
            FavoritesZipImportPhase.IMPORTING_FAVORITES -> "Importing favorites"
            FavoritesZipImportPhase.PREPARING_SOURCE_LIBRARY -> "Scanning photo library"
            FavoritesZipImportPhase.RESTORING_LINKS -> "Restoring photo links"
            FavoritesZipImportPhase.COMPARING_SOURCE_PHOTOS -> "Matching originals"
            FavoritesZipImportPhase.RESTORING_ALBUMS -> "Restoring albums"
            FavoritesZipImportPhase.RELINKING_ALBUMS -> "Checking album links"
            FavoritesZipImportPhase.FINISHING -> "Finalizing restore"
        }
    }
}
