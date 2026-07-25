package com.patgrady64.picroulette

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val PIC_ROULETTE_PREFS = "PicRoulettePrefs"
private const val HAPTIC_FEEDBACK_KEY = "haptic_feedback_enabled"
private const val KEEP_SCREEN_AWAKE_KEY = "keep_screen_awake_enabled"
private const val PHOTO_COUNTER_DEFAULT_KEY = "photo_counter_default_enabled"
private const val SCAN_LIBRARY_ON_START_KEY = "scan_library_on_start_enabled"
private const val DEFAULT_PHOTO_DISPLAY_KEY = "default_photo_display_mode"
private const val PHOTO_LIBRARY_CACHE_FILE = "photo_library_cache.txt"

enum class PhotoDisplayMode {
    FIT,
    FILL
}

private fun preferences(context: Context) =
    context.getSharedPreferences(
        PIC_ROULETTE_PREFS,
        Context.MODE_PRIVATE
    )

fun isHapticFeedbackEnabled(context: Context): Boolean {
    return preferences(context)
        .getBoolean(HAPTIC_FEEDBACK_KEY, true)
}

fun setHapticFeedbackEnabled(
    context: Context,
    enabled: Boolean
) {
    preferences(context)
        .edit()
        .putBoolean(HAPTIC_FEEDBACK_KEY, enabled)
        .apply()
}

fun isKeepScreenAwakeEnabled(context: Context): Boolean {
    return preferences(context)
        .getBoolean(KEEP_SCREEN_AWAKE_KEY, true)
}

fun setKeepScreenAwakeEnabled(
    context: Context,
    enabled: Boolean
) {
    preferences(context)
        .edit()
        .putBoolean(KEEP_SCREEN_AWAKE_KEY, enabled)
        .apply()
}

fun isPhotoCounterDefaultEnabled(context: Context): Boolean {
    return preferences(context)
        .getBoolean(PHOTO_COUNTER_DEFAULT_KEY, false)
}

fun setPhotoCounterDefaultEnabled(
    context: Context,
    enabled: Boolean
) {
    preferences(context)
        .edit()
        .putBoolean(PHOTO_COUNTER_DEFAULT_KEY, enabled)
        .apply()
}

fun isScanLibraryOnStartEnabled(context: Context): Boolean {
    return preferences(context)
        .getBoolean(SCAN_LIBRARY_ON_START_KEY, true)
}

fun setScanLibraryOnStartEnabled(
    context: Context,
    enabled: Boolean
) {
    preferences(context)
        .edit()
        .putBoolean(SCAN_LIBRARY_ON_START_KEY, enabled)
        .apply()
}

fun getDefaultPhotoDisplayMode(context: Context): PhotoDisplayMode {
    val savedValue = preferences(context)
        .getString(
            DEFAULT_PHOTO_DISPLAY_KEY,
            PhotoDisplayMode.FIT.name
        )

    return runCatching {
        PhotoDisplayMode.valueOf(
            savedValue ?: PhotoDisplayMode.FIT.name
        )
    }.getOrDefault(PhotoDisplayMode.FIT)
}

fun setDefaultPhotoDisplayMode(
    context: Context,
    mode: PhotoDisplayMode
) {
    preferences(context)
        .edit()
        .putString(DEFAULT_PHOTO_DISPLAY_KEY, mode.name)
        .apply()
}

/**
 * Stores the last completed scan outside SharedPreferences so large photo
 * libraries do not create an oversized preferences entry.
 */
suspend fun saveCachedPhotoLibrary(
    context: Context,
    images: List<Uri>
) = withContext(Dispatchers.IO) {
    runCatching {
        File(context.filesDir, PHOTO_LIBRARY_CACHE_FILE)
            .writeText(
                images
                    .distinctBy { it.toString() }
                    .joinToString(separator = "\n") { it.toString() }
            )
    }
}

suspend fun loadCachedPhotoLibrary(
    context: Context
): List<Uri> = withContext(Dispatchers.IO) {
    val cacheFile = File(
        context.filesDir,
        PHOTO_LIBRARY_CACHE_FILE
    )

    if (!cacheFile.exists()) {
        return@withContext emptyList()
    }

    runCatching {
        cacheFile
            .readLines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .map(Uri::parse)
            .distinctBy { it.toString() }
            .toList()
    }.getOrDefault(emptyList())
}
