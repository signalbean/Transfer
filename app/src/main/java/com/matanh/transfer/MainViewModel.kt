package com.matanh.transfer

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val _selectedFolderUri = MutableLiveData<Uri?>()
    val selectedFolderUri: LiveData<Uri?> = _selectedFolderUri

    private val _files = MutableLiveData<List<FileItem>>()
    val files: LiveData<List<FileItem>> = _files

    fun setSelectedFolderUri(uri: Uri?) {
        _selectedFolderUri.value = uri
        if (uri != null) {
            loadFiles(uri)
        }
    }

    fun loadFiles(uri: Uri) {
        viewModelScope.launch {
            val files = withContext(Dispatchers.IO) {
                val baseDocFile = DocumentFile.fromTreeUri(getApplication(), uri)
                baseDocFile?.listFiles()?.filter { it.isFile }?.map { docFile ->
                    FileItem(
                        name = docFile.name ?: "Unknown",
                        size = docFile.length(),
                        lastModified = docFile.lastModified(),
                        uri = docFile.uri
                    )
                } ?: emptyList()
            }
            _files.value = files
        }
    }
}