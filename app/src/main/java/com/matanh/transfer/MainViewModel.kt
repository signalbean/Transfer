package com.matanh.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    private val _selectedFolderUri = MutableLiveData<Uri?>()
    val selectedFolderUri: LiveData<Uri?> = _selectedFolderUri

    private val _filesInSharedFolder = MutableStateFlow<List<DocumentFile>>(emptyList())
    val filesInSharedFolder: StateFlow<List<DocumentFile>> = _filesInSharedFolder.asStateFlow()

    private val _isLoadingFiles = MutableStateFlow(false)
    val isLoadingFiles: StateFlow<Boolean> = _isLoadingFiles.asStateFlow()

    fun setSelectedFolderUri(uri: Uri?) {
        _selectedFolderUri.value = uri
        if (uri != null) {
            // loadFilesFromUri(uri) // Context needed, so call from Activity/Fragment
        } else {
            _filesInSharedFolder.value = emptyList()
        }
    }

    fun loadFilesFromUri(context: Context, folderUri: Uri) {
        if (_isLoadingFiles.value) return
        _isLoadingFiles.value = true
        viewModelScope.launch {
            try {
                val files = withContext(Dispatchers.IO) {
                    val docTree = DocumentFile.fromTreeUri(context.applicationContext, folderUri)
                    docTree?.listFiles()?.filter { it.isFile }?.sortedBy { it.name?.lowercase() } ?: emptyList()
                }
                _filesInSharedFolder.value = files
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error loading files", e)
                _filesInSharedFolder.value = emptyList() // Clear on error
            } finally {
                _isLoadingFiles.value = false
            }
        }
    }

    fun deleteFile(context: Context, file: DocumentFile,  onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                file.delete()
            } catch (e: Exception) {
                Log.e("MainViewModel", "Failed to delete file: ${file.name}", e)
                false
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
                if (success) {
                    // Refresh file list
                    _selectedFolderUri.value?.let { loadFilesFromUri(context, it) }
                }
            }
        }
    }
}