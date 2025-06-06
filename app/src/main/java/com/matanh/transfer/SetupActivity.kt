package com.matanh.transfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

class SetupActivity : AppCompatActivity() {

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                Utils.persistUriPermission(this, uri) // Persist permission
                // Store the URI so MainActivity can pick it up
                val prefs = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putString(Constants.EXTRA_FOLDER_URI, uri.toString()).apply()

                Toast.makeText(this, getString(R.string.folder_setup_complete), Toast.LENGTH_SHORT).show()
                launchMainActivity()
            } else {
                Toast.makeText(this, getString(R.string.folder_selection_cancelled), Toast.LENGTH_SHORT).show()
                // User might try again or exit; stay on this activity
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check if a folder is already configured and permission persists
//        val persistedUriString = Utils.getPersistedUri(this)?.toString()
        val prefs = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)

        val persistedUriString = prefs.getString(Constants.EXTRA_FOLDER_URI, null)

        if (!persistedUriString.isNullOrEmpty()) {
            val persistedUri = persistedUriString.toUri()
            Log.d("SetupActivity", "Persisted URI: $persistedUri")
            // Check if permissions are still valid for the persisted URI
            if (Utils.isUriPermissionPersisted(this, persistedUri)) {
                // Attempt to access the DocumentFile to further validate
                val docFile = DocumentFile.fromTreeUri(this, persistedUri)
                if (docFile != null && docFile.canRead()) {
                    launchMainActivity()
                    return // Skip setup layout
                } else {
                    // URI persisted but not accessible, clear it and proceed with setup
                    Utils.clearPersistedUri(this)
                }
            } else {
                Utils.clearPersistedUri(this) // Persisted but no permissions, clear it.
            }
        }

        setContentView(R.layout.activity_setup)
        val btnChooseFolder: Button = findViewById(R.id.btnChooseFolder)
        btnChooseFolder.setOnClickListener {
            selectFolderLauncher.launch(null)
        }
    }

    private fun launchMainActivity() {
        Log.d("SetupActivity", "Launching MainActivity")
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish() // Finish SetupActivity so user can't go back to it
    }
}