package com.pgdevhouse.picroulette.utils

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.util.zip.ZipInputStream

data class PortableVerifyProgress(
    val stage: String,
    val foldersScanned: Int = 0,
    val photosScanned: Int = 0,
    val expectedPhotos: Int = 0,
    val matchingPhotos: Int = 0
)

data class PortableRestorePreview(
    val expectedFolders: Int,
    val matchedFolders: Int,
    val missingFolders: List<String>,
    val expectedPhotos: Int,
    val matchedPhotos: Int,
    val selectedPhotos: List<Uri>,
    val warning: String = ""
)

/** Reads only manifest.json; does not import favorites or mutate the library. */
fun verifyPortableRestore(
    context: Context,
    backup: Uri,
    selectedTree: Uri,
    onProgress: (PortableVerifyProgress) -> Unit = {}
): PortableRestorePreview {
    onProgress(PortableVerifyProgress("Reading backup inventory"))
    var manifest: JSONObject? = null
    context.contentResolver.openInputStream(backup)?.use { stream ->
        ZipInputStream(stream.buffered()).use { zip ->
            var entry = zip.nextEntry
            var zipEntriesScanned = 0
            while (entry != null) {
                zipEntriesScanned++
                if (zipEntriesScanned == 1 || zipEntriesScanned % 25 == 0) {
                    onProgress(PortableVerifyProgress("Reading backup inventory • $zipEntriesScanned ZIP entries checked"))
                }
                if (entry.name == "manifest.json") {
                    val bounded = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        require(bounded.size() + n <= 5 * 1024 * 1024) { "Backup manifest is too large" }
                        bounded.write(buffer, 0, n)
                    }
                    manifest = JSONObject(bounded.toString("UTF-8"))
                    break
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }
    val json = manifest ?: error("This ZIP has no PicRoulette manifest")
    val roots = json.optJSONArray("libraryRoots")
    val expectedFolders = linkedSetOf<String>()
    val expectedFiles = linkedSetOf<String>()
    if (roots != null) {
        for (i in 0 until roots.length()) {
            val root = roots.optJSONObject(i) ?: continue
            val folders = root.optJSONArray("folders")
            if (folders != null) for (j in 0 until folders.length()) expectedFolders.add(folders.optString(j))
            val files = root.optJSONArray("files")
            if (files != null) for (j in 0 until files.length()) expectedFiles.add(files.optString(j))
        }
    }
    // Older backups never recorded a complete folder inventory. Infer only
    // paths that are clearly present in favorite metadata; do not pretend this
    // is a complete verification.
    val oldBackup = roots == null || roots.length() == 0
    val multipleRoots = roots != null && roots.length() > 1
    if (oldBackup) {
        val files = json.optJSONArray("files")
        if (files != null) for (i in 0 until files.length()) {
            val path = files.optJSONObject(i)?.optString("originalRelativePath").orEmpty()
            if (path.isNotBlank()) expectedFiles.add(path)
        }
    }
    val tree = DocumentFile.fromTreeUri(context, selectedTree)
        ?: error("Could not open selected photo folder")
    val foundFolders = mutableSetOf<String>()
    val foundFiles = mutableSetOf<String>()
    val photoUris = mutableListOf<Uri>()
    val oldNames = if (oldBackup) expectedFiles.map { it.substringAfterLast('/') }.toSet() else emptySet()
    var scanned = 0
    var matched = 0
    fun update(force: Boolean = false) {
        if (force || scanned % 25 == 0) {
            onProgress(PortableVerifyProgress(
                stage = "Checking photo folders",
                foldersScanned = foundFolders.size,
                photosScanned = photoUris.size,
                expectedPhotos = expectedFiles.size,
                matchingPhotos = matched
            ))
        }
    }
    update(true)
    fun walk(directory: DocumentFile, prefix: String) {
        directory.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                foundFolders.add(path)
                scanned++
                update()
                walk(child, path)
            } else if (child.type?.startsWith("image/") == true ||
                name.substringAfterLast('.', "").lowercase() in
                    setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")) {
                foundFiles.add(path)
                photoUris.add(child.uri)
                if (path in expectedFiles || (oldBackup && name in oldNames)) matched++
                scanned++
                update()
            }
        }
    }
    walk(tree, "")
    onProgress(PortableVerifyProgress("Finishing photo verification", foundFolders.size, photoUris.size, expectedFiles.size, matched))
    val exactFolders = if (oldBackup) emptySet() else expectedFolders.intersect(foundFolders)
    val matches = if (oldBackup) {
        val names = foundFiles.map { it.substringAfterLast('/') }.toSet()
        expectedFiles.count { it.substringAfterLast('/') in names }
    } else expectedFiles.count { it in foundFiles }
    val missing = if (oldBackup) emptyList() else (expectedFolders - foundFolders).sorted()
    return PortableRestorePreview(
        expectedFolders = expectedFolders.size,
        matchedFolders = exactFolders.size,
        missingFolders = missing,
        expectedPhotos = expectedFiles.size,
        matchedPhotos = matches,
        selectedPhotos = photoUris,
        warning = when {
            oldBackup -> "Older backup: complete folder verification is unavailable. Only known photos could be compared."
            multipleRoots -> "This backup contains several photo roots. Only matching folders under the selected root can be restored now."
            else -> "File matches are based on folder and name; known fingerprints are checked during restore."
        }
    )
}
