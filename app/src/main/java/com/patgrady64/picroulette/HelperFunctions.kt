package com.patgrady64.picroulette

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    } catch (exception: Exception) {
        Log.e("PR_STORAGE", "Could not load saved folders", exception)
    }
    return list
}

fun triggerVibration(context: Context, style: VibrationStyle = VibrationStyle.TICK) {
    if (!isHapticFeedbackEnabled(context)) {
        return
    }

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
    } catch (exception: Exception) {
        Log.e(
            "PR_STORAGE",
            "Could not scan folder document ID: $parentDocId",
            exception
        )
    }
}

fun readFavoritesList(
    context: Context
): Result<List<FavoriteFile>> = runCatching {
    val list = mutableListOf<FavoriteFile>()
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME
    )
    val selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?"
    val args = arrayOf("Pictures/PR_FAVS/")

    val cursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        selection,
        args,
        null
    ) ?: throw IOException("The Favorites folder could not be read.")

    cursor.use {
        val idCol = it.getColumnIndexOrThrow(
            MediaStore.Images.Media._ID
        )
        val nameCol = it.getColumnIndexOrThrow(
            MediaStore.Images.Media.DISPLAY_NAME
        )

        while (it.moveToNext()) {
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                it.getLong(idCol)
            )
            list.add(
                FavoriteFile(
                    fileNameOnDisk = it.getString(nameCol),
                    mediaUri = uri
                )
            )
        }
    }

    list
}.onFailure { exception ->
    Log.e(
        "PR_FAV",
        "Could not read the Favorites folder",
        exception
    )
}

fun getFavoritesList(context: Context): List<FavoriteFile> =
    readFavoritesList(context).getOrDefault(emptyList())

fun saveFavoriteMappings(
    context: Context,
    mappings: List<FavoriteMapping>
) {
    val prefs =
        context.getSharedPreferences(
            "PicRoulettePrefs",
            Context.MODE_PRIVATE
        )

    val array = JSONArray()

    mappings.forEach { mapping ->

        val obj = JSONObject()

        obj.put("originalUri", mapping.originalUri)
        obj.put("favoriteUri", mapping.favoriteUri)
        obj.put("originalFileName", mapping.originalFileName)
        obj.put("originalRelativePath", mapping.originalRelativePath)
        obj.put("originalSha256", mapping.originalSha256)
        obj.put("dateAdded", mapping.dateAdded)
        obj.put("isDeleted", mapping.isDeleted)

        array.put(obj)
    }

    prefs.edit()
        .putString(
            "favorite_mappings",
            array.toString()
        )
        .apply()
}

fun getFavoriteMappings(
    context: Context
): MutableList<FavoriteMapping> {

    val prefs =
        context.getSharedPreferences(
            "PicRoulettePrefs",
            Context.MODE_PRIVATE
        )

    val json =
        prefs.getString(
            "favorite_mappings",
            null
        ) ?: return mutableListOf()

    val list = mutableListOf<FavoriteMapping>()

    try {

        val array = JSONArray(json)

        for (i in 0 until array.length()) {

            val obj = array.getJSONObject(i)

            list.add(
                FavoriteMapping(
                    originalUri =
                        obj.getString("originalUri"),

                    favoriteUri =
                        obj.getString("favoriteUri"),

                    originalFileName =
                        obj.getString("originalFileName"),

                    originalRelativePath =
                        obj.optString("originalRelativePath", ""),

                    originalSha256 =
                        obj.optString("originalSha256", ""),

                    dateAdded =
                        obj.getLong("dateAdded"),

                    isDeleted =
                        obj.optBoolean("isDeleted", false)
                )
            )
        }

    } catch (e: Exception) {
        e.printStackTrace()
    }

    return list
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

fun deleteFavorite(
    context: Context,
    uri: Uri?
): Boolean {
    if (uri == null || uri.toString().isBlank()) {
        Log.d("PR_FAV", "Skipping delete. Invalid URI.")
        return false
    }

    return try {
        val rows = context.contentResolver.delete(
            uri,
            null,
            null
        )

        Log.d("PR_FAV", "Deleted rows: $rows")
        Log.d("PR_FAV", "Tried to delete: $uri")

        rows > 0
    } catch (exception: Exception) {
        Log.e("PR_FAV", "Favorite delete failed: $uri", exception)
        false
    }
}

fun sameImage(uri1: Uri, uri2: Uri): Boolean {
    return try {
        DocumentsContract.getDocumentId(uri1) ==
                DocumentsContract.getDocumentId(uri2)
    } catch (e: Exception) {
        uri1.toString() == uri2.toString()
    }
}
