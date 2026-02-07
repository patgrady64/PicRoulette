package com.patgrady64.picroulette // <-- MAKE SURE THIS MATCHES YOUR PROJECT

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import android.provider.DocumentsContract
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                PicRouletteApp()
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PicRouletteApp() {
    val context = LocalContext.current

    // Using Explicit .value access to avoid compiler inference errors
    val imageList = remember { mutableStateOf<List<Uri>>(emptyList()) }
    val currentIndex = remember { mutableStateOf<Int>(0) }
    val isPlaying = remember { mutableStateOf<Boolean>(false) }
    val isScanning = remember { mutableStateOf<Boolean>(false) }
    val uiVisible = remember { mutableStateOf<Boolean>(true) }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { folderUri ->
            isScanning.value = true
            try {
                context.contentResolver.takePersistableUriPermission(
                    folderUri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                Thread {
                    val foundImages = queryAllImagesInTree(context, folderUri).shuffled()
                    (context as ComponentActivity).runOnUiThread {
                        imageList.value = foundImages
                        isScanning.value = false
                    }
                }.start()
            } catch (e: Exception) {
                isScanning.value = false
            }
        }
    }

    if (!isPlaying.value) {
        // --- SETUP SCREEN ---
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🎰 PicRoulette", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(20.dp))

            if (isScanning.value) {
                CircularProgressIndicator()
                Text("Finding images...", modifier = Modifier.padding(top = 8.dp))
            } else {
                Button(onClick = { launcher.launch(null) }) {
                    Text(if (imageList.value.isEmpty()) "Pick Folder" else "Change Folder")
                }

                if (imageList.value.isNotEmpty()) {
                    Text("${imageList.value.size} images found", modifier = Modifier.padding(8.dp))
                    Button(onClick = { isPlaying.value = true }) {
                        Text("START ROULETTE")
                    }
                }
            }
        }
    } else {
        // --- VIEWER SCREEN ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    onClick = {
                        if (imageList.value.isNotEmpty()) {
                            currentIndex.value = (currentIndex.value + 1) % imageList.value.size
                        }
                    },
                    onLongClick = { uiVisible.value = !uiVisible.value }
                )
        ) {
            // THE IMAGE
            if (imageList.value.isNotEmpty()) {
                AsyncImage(
                    model = imageList.value[currentIndex.value],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            // OVERLAY UI
            if (uiVisible.value) {
                // Top Row: Stop (Left) and Back (Right)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(onClick = { isPlaying.value = false }) { Text("Stop") }
                    Button(onClick = {
                        currentIndex.value = if (currentIndex.value > 0) currentIndex.value - 1 else imageList.value.size - 1
                    }) { Text("Back") }
                }

                // Bottom Row: Counter (Left) and Delete (Right)
                Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(
                        text = "${currentIndex.value + 1} / ${imageList.value.size}",
                        modifier = Modifier.align(Alignment.BottomStart),
                        style = MaterialTheme.typography.labelLarge
                    )

                    Button(
                        onClick = {
                            val uriToDelete = imageList.value[currentIndex.value]
                            try {
                                DocumentsContract.deleteDocument(context.contentResolver, uriToDelete)
                                val newList = imageList.value.toMutableList()
                                newList.removeAt(currentIndex.value)
                                imageList.value = newList
                                if (currentIndex.value >= imageList.value.size && imageList.value.isNotEmpty()) {
                                    currentIndex.value = 0
                                }
                            } catch (e: Exception) {
                                // Handle delete failure (read-only files)
                            }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                }
            }
        }
    }
}

// Fixed Search Function (Recursive)
fun queryAllImagesInTree(context: Context, rootUri: Uri): List<Uri> {
    val results = mutableListOf<Uri>()
    val documentId = DocumentsContract.getTreeDocumentId(rootUri)
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, documentId)

    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )

    fun queryRecursive(parentUri: Uri) {
        context.contentResolver.query(parentUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val id = cursor.getString(idColumn)
                val mime = cursor.getString(mimeColumn)
                val itemUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, id)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val nextChildrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, id)
                    queryRecursive(nextChildrenUri)
                } else if (mime != null && mime.startsWith("image/")) {
                    results.add(itemUri)
                }
            }
        }
    }

    try {
        queryRecursive(childrenUri)
    } catch (e: Exception) {
        e.printStackTrace()
    }
    return results
}