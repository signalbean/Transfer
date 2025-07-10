package com.matanh.transfer.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.matanh.transfer.util.FileItem
import com.matanh.transfer.util.FileRepository
import com.matanh.transfer.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _selectedFolderUri = MutableLiveData<Uri?>()
    val selectedFolderUri: LiveData<Uri?> = _selectedFolderUri

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    private val fileRepository = FileRepository(application)
    private val _toastMessage = MutableSharedFlow<String>()
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()


    fun setSelectedFolderUri(uri: Uri?) {
        _selectedFolderUri.value = uri
        if (uri != null) {
            loadFiles(uri)
        }
    }

    fun loadFiles(uri: Uri) {
        viewModelScope.launch {
            _files.value = fileRepository.getFiles(uri)
        }
    }
    fun handleDeleteAction(selectedFiles: List<FileItem>) {
        viewModelScope.launch {
            fileRepository.deleteFiles(selectedFiles).onSuccess {
                _toastMessage.emit("Deleted ${selectedFiles.size} files.")
                // Refresh the file list
                _selectedFolderUri.value?.let { loadFiles(it) }
            }.onFailure {
                _toastMessage.emit("Error deleting files.")
            }
        }
    }
    fun handleShareIntent(intent: Intent) {
        val folderUri = _selectedFolderUri.value ?: run {
            viewModelScope.launch {
                _toastMessage.emit("Please select a shared folder first.")
            }
            return
        }

        viewModelScope.launch {
            when (intent.action) {
                Intent.ACTION_SEND -> {
                    if (intent.type?.startsWith("text/plain") == true) {
                        handleSharedText(intent, folderUri)
                    } else {
                        handleSharedFile(intent, folderUri)
                    }
                }

                Intent.ACTION_SEND_MULTIPLE -> {
                    handleMultipleFiles(intent, folderUri)
                }
            }
        }
    }

    private suspend fun handleSharedText(intent: Intent, folderUri: Uri) {
        val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (sharedText.isNullOrEmpty()) return

        fileRepository.createTextFile(folderUri, "share", sharedText)
            .onSuccess { file ->
                _toastMessage.emit("Text saved to ${file.name}")
                loadFiles(folderUri)
            }
            .onFailure {
                _toastMessage.emit("Error saving shared text.")
            }
    }

    private suspend fun handleSharedFile(intent: Intent, folderUri: Uri) {
        val fileUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val context = getApplication<Application>().applicationContext
        val fileName = FileUtils.getFileName(context, fileUri) ?: "shared_file"

        fileRepository.copyUriToAppDir(fileUri, folderUri, fileName)
            .onSuccess { file ->
                _toastMessage.emit("File saved: ${file.name}")
                loadFiles(folderUri)
            }
            .onFailure {
                _toastMessage.emit("Error saving shared file.")
            }
    }

    private suspend fun handleMultipleFiles(intent: Intent, folderUri: Uri) {
        val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
        val context = getApplication<Application>().applicationContext
        var successCount = 0

        for (uri in uris) {
            val fileName = FileUtils.getFileName(context, uri) ?: "file_${System.currentTimeMillis()}"
            fileRepository.copyUriToAppDir(uri, folderUri, fileName)
                .onSuccess { successCount++ }
        }

        if (successCount > 0) {
            _toastMessage.emit("$successCount files saved successfully.")
            loadFiles(folderUri)
        } else {
            _toastMessage.emit("Error saving shared files.")
        }
    }

}