package com.matanh.transfer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var tvServerStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var btnToggleServer: Button
    private lateinit var btnCopyIp: ImageButton
    private lateinit var rvFiles: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private var fileServerService: FileServerService? = null
    private var isServiceBound = false
    private var currentSelectedFolderUri: Uri? = null
    private val ipPermissionDialogs = mutableMapOf<String, AlertDialog>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FileServerService.LocalBinder
            fileServerService = binder.getService()
            isServiceBound = true
            observeServerState()
            observeIpPermissionRequests()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileServerService = null
            isServiceBound = false
        }
    }

    private val uploadFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { sourceUri ->
            val fileName = Utils.getFileName(this, sourceUri)
            val copiedFile = Utils.copyUriToAppDir(this, sourceUri, currentSelectedFolderUri!!, fileName)
            if (copiedFile != null && copiedFile.exists()) {
                Toast.makeText(this, "File uploaded: ${copiedFile.name}", Toast.LENGTH_SHORT).show()
                viewModel.loadFiles(currentSelectedFolderUri!!)
            } else {
                Toast.makeText(this, "Failed to upload file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        viewModel = ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(application))
            .get(MainViewModel::class.java)

        initViews()
        setupClickListeners()
        setupFileList()

        val prefs =  getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)
        val folderUriString = prefs.getString(Constants.PREF_KEY_SELECTED_FOLDER_URI, null)
        if (folderUriString != null) {
            currentSelectedFolderUri = Uri.parse(folderUriString)
            viewModel.setSelectedFolderUri(currentSelectedFolderUri)
            startFileServer(currentSelectedFolderUri!!)
        } else {
            Toast.makeText(this, "Please select a shared folder in settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        Intent(this, FileServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun initViews() {
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        btnToggleServer = findViewById(R.id.btnToggleServer)
        btnCopyIp = findViewById(R.id.btnCopyIp)
        rvFiles = findViewById(R.id.rvFiles)
    }

    private fun setupClickListeners() {
        btnToggleServer.setOnClickListener {
            if (fileServerService?.serverState?.value is ServerState.Running) {
                stopFileServer()
            } else {
                startFileServer(currentSelectedFolderUri!!)
            }
        }
        btnCopyIp.setOnClickListener {
            val ipText = tvIpAddress.text.toString()
            if (ipText != getString(R.string.waiting_for_network)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Server IP", ipText)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "IP copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupFileList() {
        fileAdapter = FileAdapter(emptyList(), { file -> shareFile(file) }, { file -> confirmDeleteFile(file) })
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = fileAdapter

        viewModel.files.observe(this) { files ->
            fileAdapter.updateFiles(files)
        }
    }

    private fun observeServerState() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.serverState.collect { state ->
                    when (state) {
                        is ServerState.Running -> {
                            tvServerStatus.text = "Server Running"
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.green))
                            tvIpAddress.text = "${state.ip}:${state.port}"
                            btnToggleServer.text = "Stop Server"
                        }
                        is ServerState.Stopped -> {
                            tvServerStatus.text = "Server Stopped"
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
                            tvIpAddress.text = "Waiting for network"
                            btnToggleServer.text = "Start Server"
                        }
                        is ServerState.Error -> {
                            tvServerStatus.text = "Error: ${state.message}"
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.red))
                            tvIpAddress.text = "Waiting for network"
                            btnToggleServer.text = "Start Server"
                        }
                    }
                }
            }
        }
    }

    private fun observeIpPermissionRequests() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.ipPermissionRequests.collect { request ->
                    val ip = request.ipAddress
                    val deferred = request.deferred

                    if (ipPermissionDialogs.containsKey(ip)) return@collect

                    val dialog = MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle("Permission Request")
                        .setMessage("Allow access from $ip?")
                        .setPositiveButton("Allow") { _, _ ->
                            deferred.complete(true)
                            ipPermissionDialogs.remove(ip)
                        }
                        .setNegativeButton("Deny") { _, _ ->
                            deferred.complete(false)
                            ipPermissionDialogs.remove(ip)
                        }
                        .setOnDismissListener {
                            if (!deferred.isCompleted) deferred.complete(false)
                            ipPermissionDialogs.remove(ip)
                        }
                        .create()

                    ipPermissionDialogs[ip] = dialog
                    dialog.show()
                }
            }
        }
    }

    private fun startFileServer(folderUri: Uri) {
        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_START_SERVICE
            putExtra("FOLDER_URI", folderUri.toString())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun stopFileServer() {
        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        ContextCompat.startForegroundService(this, serviceIntent)
    }

    private fun shareFile(file: FileItem) {
        val docFile = DocumentFile.fromSingleUri(this, file.uri)
        if (docFile != null && docFile.canRead()) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = docFile.type ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, docFile.uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(shareIntent, "Share ${docFile.name}"))
            } catch (e: Exception) {
                Toast.makeText(this, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Cannot read file to share.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteFile(file: FileItem) {
        val docFile = DocumentFile.fromSingleUri(this, file.uri)
        if (docFile != null) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.confirm_delete_file_title))
                .setMessage(getString(R.string.confirm_delete_file_message, docFile.name))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                    if (docFile.delete()) {
                        Toast.makeText(this, getString(R.string.file_deleted_successfully, docFile.name), Toast.LENGTH_SHORT).show()
                        viewModel.loadFiles(currentSelectedFolderUri!!)
                    } else {
                        Toast.makeText(this, getString(R.string.file_delete_failed, docFile.name), Toast.LENGTH_SHORT).show()
                    }
                }
                .show()
        }
    }

    private fun pasteClipboardContent() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/plain") == true) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val textToPaste = item?.text.toString()
            if (textToPaste.isNotEmpty()) {
                val fileName = "paste_${System.currentTimeMillis()}.txt"
                val file = Utils.createTextFileInDir(this, currentSelectedFolderUri!!, fileName, textToPaste)
                if (file != null && file.exists()) {
                    Toast.makeText(this, "Text pasted to $fileName", Toast.LENGTH_SHORT).show()
                    viewModel.loadFiles(currentSelectedFolderUri!!)
                } else {
                    Toast.makeText(this, "Failed to paste text", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No text in clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadFile() {
        uploadFileLauncher.launch("*/*")
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_paste -> {
                pasteClipboardContent()
                true
            }
            R.id.action_upload -> {
                uploadFile()
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ipPermissionDialogs.values.forEach { it.dismiss() }
        ipPermissionDialogs.clear()
    }
}