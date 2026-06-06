package com.patgrady64.picroulette

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PicRouletteWidget : GlanceAppWidget() {

    override val stateDefinition = PreferencesGlanceStateDefinition

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // 1. Read the state directly inside provideContent
            val prefs = currentState<Preferences>()
            val currentClickCount = prefs[WidgetKeys.clickCountKey] ?: 0

            // 2. Pass the click count down to the picker logic.
            // This forces Glance to re-run the method whenever the click count updates.
            val bitmap = rememberBitmap(context, currentClickCount)

            Box(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .clickable(actionRunCallback<RefreshWidgetCallback>()),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        provider = ImageProvider(bitmap),
                        contentDescription = "Random Favorite",
                        modifier = GlanceModifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Box(modifier = GlanceModifier.fillMaxSize()) {}
                }
            }
        }
    }

    // Helper function that recalculates whenever the click counter alters
    @androidx.compose.runtime.Composable
    private fun rememberBitmap(context: Context, seed: Int): Bitmap? {
        val bitmapState = androidx.compose.runtime.produceState<Bitmap?>(initialValue = null, seed) {
            value = getRandomFavoriteBitmap(context)
        }
        return bitmapState.value
    }

    private suspend fun getRandomFavoriteBitmap(context: Context): Bitmap? {
        return withContext(Dispatchers.IO) {
            val list = mutableListOf<Uri>()
            val projection = arrayOf(MediaStore.Images.Media._ID)
            val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("%Pictures/PicRoulette_Favorites%")

            try {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    projection, selection, args, null
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = android.content.ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                        )
                        list.add(uri)
                    }
                }
            } catch (e: Exception) {
                return@withContext null
            }

            if (list.isEmpty()) return@withContext null

            val randomUri = list.random()

            try {
                context.contentResolver.openInputStream(randomUri)?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    BitmapFactory.decodeStream(stream, null, options)
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}

// --- KEYS ---
object WidgetKeys {
    val clickCountKey = intPreferencesKey("widget_click_count")
}

// --- REFRESH ACTION CALLBACK ---
class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        // FIX: Mutate and explicitly return the modified preferences instance
        // back to the underlying DataStore transaction block.
        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            val current = prefs[WidgetKeys.clickCountKey] ?: 0
            val mutablePrefs = prefs.toMutablePreferences()
            mutablePrefs[WidgetKeys.clickCountKey] = current + 1
            mutablePrefs // This forces the transaction to save changes
        }

        // Notify the widget engine to refresh the views
        PicRouletteWidget().update(context, glanceId)
    }
}

// --- RECEIVER ---
class PicRouletteWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PicRouletteWidget()
}