package com.matanh.transfer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.math.BigInteger
import java.net.InetAddress
import java.nio.ByteOrder

object Utils {
    fun getLocalIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) return null

        val connectionInfo = wifiManager.connectionInfo
        val ipAddress = connectionInfo.ipAddress

        // Convert little-endian to big-endian if needed
        val ip = if (ByteOrder.nativeOrder().equals(ByteOrder.LITTLE_ENDIAN)) {
            Integer.reverseBytes(ipAddress)
        } else {
            ipAddress
        }

        val ipByteArray = BigInteger.valueOf(ip.toLong()).toByteArray()
        return try {
            InetAddress.getByAddress(ipByteArray).hostAddress
        } catch (ex: Exception) {
            null
        }
    }

    fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        name = it.getString(nameIndex)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path
            val cut = name?.lastIndexOf('/')
            if (cut != -1 && cut != null) {
                name = name?.substring(cut + 1)
            }
        }
        return name ?: "unknown_file"
    }

    fun copyUriToAppDir(context: Context, sourceUri: Uri, destinationDirUri: Uri, filename: String? = null): DocumentFile? {
        val resolver = context.contentResolver
        val docDir = DocumentFile.fromTreeUri(context, destinationDirUri) ?: return null
        val targetFileName = filename ?: getFileName(context, sourceUri) ?: "file_${System.currentTimeMillis()}"

        // Check if file exists, if so, create a unique name
        var finalFileName = targetFileName
        var count = 1
        while (docDir.findFile(finalFileName) != null) {
            val nameWithoutExt = targetFileName.substringBeforeLast(".")
            val ext = targetFileName.substringAfterLast(".", "")
            finalFileName = if (ext.isEmpty()) "${nameWithoutExt}_$count" else "${nameWithoutExt}_$count.$ext"
            count++
        }

        val MimeType = resolver.getType(sourceUri) ?: "application/octet-stream"
        val newFile = docDir.createFile(MimeType, finalFileName) ?: return null

        try {
            resolver.openInputStream(sourceUri)?.use { inputStream ->
                resolver.openOutputStream(newFile.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                    return newFile
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFile.delete() // Clean up partially created file
        }
        return null
    }

    fun createTextFileInDir(context: Context, dirUri: Uri, fileName: String, content: String): DocumentFile? {
        val docDir = DocumentFile.fromTreeUri(context, dirUri) ?: return null
        // Overwrite if exists for simplicity, or implement unique naming
        var targetFile = docDir.findFile(fileName)
        if (targetFile != null && targetFile.exists()) {
            targetFile.delete()
        }
        targetFile = docDir.createFile("text/plain", fileName) ?: return null
        try {
            context.contentResolver.openOutputStream(targetFile.uri)?.use { outputStream ->
                outputStream.writer().use { it.write(content) }
                return targetFile
            }
        } catch (e: Exception) {
            e.printStackTrace()
            targetFile.delete()
        }
        return null
    }

    fun persistUriPermission(context: Context, uri: Uri) {
        val contentResolver = context.contentResolver
        val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, takeFlags)

        // Store the URI string for later use
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.EXTRA_FOLDER_URI, uri.toString()).apply()
    }

    fun getPersistedUri(context: Context): Uri? {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = prefs.getString(Constants.EXTRA_FOLDER_URI, null)
        return uriString?.let { Uri.parse(it) }
    }

    fun isUriPermissionPersisted(context: Context, uri: Uri): Boolean {
        val persistedUriPermissions = context.contentResolver.persistedUriPermissions
        return persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
    }

    fun clearPersistedUri(context: Context) {
        val prefs = context.getSharedPreferences(Constants.SHARED_PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(Constants.EXTRA_FOLDER_URI).apply()
    }

    fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format("%.1f %s", size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
    fun canWriteToUri(context: Context, uri: Uri): Boolean {
        val docFile = DocumentFile.fromTreeUri(context, uri) // Or fromSingleUri if it's not a tree
        return docFile?.canWrite() == true
    }

}