package com.matanh.transfer.util

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class FileRepository(private val context: Context) {
    /**
    single source of truth for creating, reading, and deleting files
     */

    private val contentResolver = context.contentResolver

    // Coroutine context for file operations
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO

    suspend fun getFiles(folderUri: Uri): List<FileItem> = withContext(dispatcher) {
        val baseDocFile = DocumentFile.fromTreeUri(context, folderUri)
        baseDocFile?.listFiles()?.filter { it.isFile }?.map { docFile ->
            FileItem(
                name = docFile.name ?: "Unknown",
                size = docFile.length(),
                lastModified = docFile.lastModified(),
                uri = docFile.uri
            )
        } ?: emptyList()
    }

    suspend fun copyUriToAppDir(sourceUri: Uri, destFolder: Uri, filename: String): Result<DocumentFile> = withContext(dispatcher) {
        runCatching {
            // All the logic from your old FileUtils.copyUriToAppDir can go here
            val docDir = DocumentFile.fromTreeUri(context, destFolder)
                ?: throw IOException("Destination folder not found")
            // ... (rest of the copy logic)
            // Assuming FileUtils is now part of this repository or a helper
            val copiedFile = FileUtils.copyUriToAppDir(context, sourceUri, destFolder, filename)
            copiedFile ?: throw IOException("Failed to copy file.")
        }
    }

    suspend fun createTextFile(folderUri: Uri, name: String, content: String): Result<DocumentFile> = withContext(dispatcher) {
        runCatching {
            val file = FileUtils.createTextFileInDir(context, folderUri, name, "txt", content)
            file ?: throw IOException("Failed to create text file.")
        }
    }

    suspend fun deleteFiles(files: List<FileItem>): Result<Unit> = withContext(dispatcher) {
        runCatching {
            files.forEach { fileItem ->
                DocumentFile.fromSingleUri(context, fileItem.uri)?.delete()
            }
        }
    }
}