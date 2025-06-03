package com.matanh.transfer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.Html
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import android.widget.Toast.makeText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var tvIpAddress: TextView
    private lateinit var tvServerStatus: TextView
    private lateinit var tvSelectedFolder: TextView
    private lateinit var btnSelectFolder: Button
    private lateinit var btnCopyIp: ImageButton
    private lateinit var fabSettings: FloatingActionButton
    private lateinit var btnDeviceUpload: Button
    private lateinit var btnDevicePaste: Button
    private lateinit var btnDeviceDownload: Button



    private var fileServerService: FileServerService? = null
    private var isServiceBound = false
    private var currentSelectedFolderUri: Uri? = null

    private val ipPermissionDialogs = mutableMapOf<String, AlertDialog>()
    private val pendingIpApprovals = mutableMapOf<String, CompletableDeferred<Boolean>>()


    private val FOLDER_REQUEST_CODE = 123 // For initial check
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 124

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FileServerService.LocalBinder
            fileServerService = binder.getService()
            isServiceBound = true
            Log.d("MainActivity", "Service bound")
            observeServerState()
            observeIpPermissionRequests()

            // If a folder was already selected and service is now bound, try to start server
            currentSelectedFolderUri?.let {
                if (fileServerService?.serverState?.value is ServerState.Stopped) {
                    startFileServer(it)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileServerService = null
            isServiceBound = false
            Log.d("MainActivity", "Service unbound")
        }
    }

    private val selectFolderLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                Log.d("MainActivity", "Folder selected: $uri")
                // Persist permission
                Utils.persistUriPermission(this, uri)
                currentSelectedFolderUri = uri
                tvSelectedFolder.text = getString(R.string.selected_folder_is, DocumentFile.fromTreeUri(this,uri)?.name ?: uri.path)
                viewModel.setSelectedFolderUri(uri) // Save to ViewModel
                startFileServer(uri)
            } else {
                makeText(this, getString(R.string.folder_selection_cancelled), Toast.LENGTH_SHORT).show()
            }
        }

    private val uploadFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { sourceUri ->
                currentSelectedFolderUri?.let { destinationDirUri ->
                    val fileName = Utils.getFileName(this, sourceUri)
                    val copiedFile = Utils.copyUriToAppDir(this, sourceUri, destinationDirUri, fileName)
                    if (copiedFile != null && copiedFile.exists()) {
                        makeText(this, getString(R.string.file_uploaded_successfully) + ": ${copiedFile.name}", Toast.LENGTH_LONG).show()
                    } else {
                        makeText(this, getString(R.string.file_upload_failed), Toast.LENGTH_LONG).show()
                    }
                } ?: makeText(this, getString(R.string.no_folder_selected), Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        initViews()
        setupClickListeners()
        observeViewModel()

        requestNotificationPermissionIfNeeded()

        // Restore selected folder and try to start server if app was killed and restarted
        currentSelectedFolderUri = Utils.getPersistedUri(this)
        currentSelectedFolderUri?.let {
            if (Utils.isUriPermissionPersisted(this, it)) {
                tvSelectedFolder.text = getString(R.string.selected_folder_is, DocumentFile.fromTreeUri(this,it)?.name ?: it.path)
                viewModel.setSelectedFolderUri(it) // Ensure ViewModel is also updated
                // Server will be started in onServiceConnected if service wasn't running
            } else {
                // Permission might have been revoked or folder is gone
                makeText(this, R.string.folder_not_accessible_select_again, Toast.LENGTH_LONG).show()
                Utils.clearPersistedUri(this)
                currentSelectedFolderUri = null
            }
        }


        // Bind to service if it's already running or to start it
        // Intent(this, FileServerService::class.java).also { intent ->
        //    bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        // }
        // The service should ideally be started explicitly when a folder is chosen.
        // We bind to interact with it if it's running.

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent?.let {
            handleIntent(it)
        }
    }


    private fun handleIntent(intent: Intent) {
        if (currentSelectedFolderUri == null) {
            Snackbar.make(findViewById(android.R.id.content), "Please select a shared folder first to receive files.", Snackbar.LENGTH_LONG)
                .setAction("Select Folder") { btnSelectFolder.performClick() }
                .show()
            return
        }

        val destinationFolderUri = currentSelectedFolderUri ?: return

        when (intent.action) {
            Intent.ACTION_SEND -> {
                if ("text/plain" == intent.type) {
                    intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        saveSharedText(text, destinationFolderUri)
                    }
                } else if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("application/") == true || intent.type?.startsWith("*/*") == true) {
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uri ->
                        saveSharedFile(uri, destinationFolderUri)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (intent.type?.startsWith("image/") == true || intent.type?.startsWith("application/") == true || intent.type?.startsWith("*/*") == true) {
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris ->
                        uris.forEach { uri -> saveSharedFile(uri, destinationFolderUri) }
                    }
                }
            }
        }
    }

    private fun saveSharedText(text: String, dirUri: Uri) {
        val file = Utils.createTextFileInDir(this, dirUri, "text_share_${System.currentTimeMillis()}.txt", text)
        if (file != null && file.exists()) {
            makeText(this, getString(R.string.shared_text_saved) + " as ${file.name}", Toast.LENGTH_LONG).show()
        } else {
            makeText(this, getString(R.string.error_saving_shared_content), Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSharedFile(fileUri: Uri, dirUri: Uri) {
        val copiedFile = Utils.copyUriToAppDir(this, fileUri, dirUri)
        if (copiedFile != null && copiedFile.exists()) {
            makeText(this, getString(R.string.shared_file_saved) + copiedFile.name, Toast.LENGTH_LONG).show()
        } else {
            makeText(this, getString(R.string.error_saving_shared_content), Toast.LENGTH_LONG).show()
        }
    }


    override fun onStart() {
        super.onStart()
        // Bind to service, this won't start it unless it was already running
        // or if we decide to auto-start here based on persisted URI
        Intent(this, FileServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }

    private fun initViews() {
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tvSelectedFolder = findViewById(R.id.tvSelectedFolder)
        btnSelectFolder = findViewById(R.id.btnSelectFolder)
        btnCopyIp = findViewById(R.id.btnCopyIp)
        fabSettings = findViewById(R.id.fabSettings)
        btnDeviceUpload = findViewById(R.id.btnDeviceUpload)
        btnDevicePaste = findViewById(R.id.btnDevicePaste)
        btnDeviceDownload = findViewById(R.id.btnDeviceDownload)


        updateActionButtonStates(false) // Initially disabled
    }




    private fun setupClickListeners() {
        btnSelectFolder.setOnClickListener {
            selectFolderLauncher.launch(null) // Launch SAF to select a folder
        }

        btnCopyIp.setOnClickListener {
            val ipText = tvIpAddress.text.toString()
            if (ipText != getString(R.string.waiting_for_network)) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Server IP", ipText)
                clipboard.setPrimaryClip(clip)
                makeText(this, getString(R.string.ip_copied_to_clipboard), Toast.LENGTH_SHORT).show()
            }
        }

        fabSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnDeviceUpload.setOnClickListener {
            if (currentSelectedFolderUri != null) {
                uploadFileLauncher.launch("*/*") // Or specific MIME types
            } else {
                makeText(this, getString(R.string.no_folder_selected), Toast.LENGTH_SHORT).show()
            }
        }

        btnDevicePaste.setOnClickListener {
            if (currentSelectedFolderUri != null) {
                pasteClipboardContent(currentSelectedFolderUri!!)
            } else {
                makeText(this, getString(R.string.no_folder_selected), Toast.LENGTH_SHORT).show()
            }
        }

         btnDeviceDownload.setOnClickListener {
             Log.i("MainActivity", "open folder:$currentSelectedFolderUri")
             openFolder(currentSelectedFolderUri!!)
         }
    }

    private fun pasteClipboardContent(destinationDirUri: Uri) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/plain") == true) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val textToPaste = item?.text.toString()
            if (textToPaste.isNotEmpty()) {
                val file = Utils.createTextFileInDir(this, destinationDirUri, "paste.txt", textToPaste)
                if (file != null && file.exists()) {
                    makeText(this, getString(R.string.text_pasted_to_file), Toast.LENGTH_LONG).show()
                } else {
                    makeText(this, getString(R.string.error_writing_paste_file, "Failed to create file"), Toast.LENGTH_LONG).show()
                }
            } else {
                makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
            }
        } else {
            makeText(this, getString(R.string.clipboard_empty), Toast.LENGTH_SHORT).show()
        }
    }


    private fun observeViewModel() {
        viewModel.selectedFolderUri.observe(this) { uri ->
            currentSelectedFolderUri = uri
            if (uri != null) {
                val docFile = DocumentFile.fromTreeUri(this, uri)
                tvSelectedFolder.text = getString(R.string.selected_folder_is, docFile?.name ?: uri.path)
                if (Utils.isUriPermissionPersisted(this, uri)) {
                    // If service is already bound, and server not running, it implies folder was just selected.
                    if(isServiceBound && fileServerService?.serverState?.value is ServerState.Stopped){
                        startFileServer(uri)
                    }
                } else {
                    makeText(this, R.string.folder_not_accessible_select_again, Toast.LENGTH_LONG).show()
                    Utils.clearPersistedUri(this) // Clear invalid stored URI
                    viewModel.setSelectedFolderUri(null) // Clear from ViewModel too
                    tvSelectedFolder.text = getString(R.string.no_folder_selected)
                    stopFileServer() // Stop server if running with invalid folder
                }
            } else {
                tvSelectedFolder.text = getString(R.string.no_folder_selected)
                stopFileServer()
            }
            updateActionButtonStates(uri != null)
        }
    }

    private fun observeServerState() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.serverState.collect { state ->
                    when (state) {
                        is ServerState.Running -> {
                            tvServerStatus.text = getString(R.string.server_running_on, "${state.ip}:${state.port}")
                            tvIpAddress.text = "${state.ip}:${state.port}"
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, R.color.purple_500)) // Or a green color
                            btnSelectFolder.text = getString(R.string.stop_server) // Change button to "Stop Server"
                        }
                        is ServerState.Stopped -> {
                            tvServerStatus.text = getString(R.string.server_stopped)
                            tvIpAddress.text = getString(R.string.waiting_for_network)
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, com.google.android.material.R.color.material_on_surface_emphasis_medium))
                            btnSelectFolder.text = getString(R.string.select_shared_folder)
                        }
                        is ServerState.Error -> {
                            tvServerStatus.text = getString(R.string.error_starting_server, state.message)
                            tvIpAddress.text = getString(R.string.waiting_for_network)
                            tvServerStatus.setTextColor(ContextCompat.getColor(this@MainActivity, com.google.android.material.R.color.design_default_color_error))
                            btnSelectFolder.text = getString(R.string.select_shared_folder)
                        }
                    }
                }
            }
        }
    }
    private fun openFolder(folderUri: Uri){
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(folderUri, "vnd.android.document/directory")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)

    }
    // Helper methods for file actions:
    private fun shareFile(file: DocumentFile) {
        if (!file.canRead()) {
            Toast.makeText(this, "Cannot read file to share.", Toast.LENGTH_SHORT).show()
            return
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = file.type ?: "*/*" // Get MIME type
            putExtra(Intent.EXTRA_STREAM, file.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Share ${file.name}"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteFile(file: DocumentFile) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_delete_file_title))
            .setMessage(getString(R.string.confirm_delete_file_message, file.name))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteFile(applicationContext, file) { success ->
                    if (success) {
                        Toast.makeText(this, getString(R.string.file_deleted_successfully, file.name), Toast.LENGTH_SHORT).show()
                        // List will auto-refresh via ViewModel's loadFiles call
                    } else {
                        Toast.makeText(this, getString(R.string.file_delete_failed, file.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .show()
    }


    private fun observeIpPermissionRequests() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.ipPermissionRequests.collect { request ->
                    // 'request' is the IpPermissionRequest from the service, containing the specific IP and Deferred.
                    val currentRequestIp = request.ipAddress
                    val currentRequestDeferred = request.deferred


                    // Dismiss any existing dialog for this IP first
                    ipPermissionDialogs[request.ipAddress]?.dismiss()
                    // Forward declaration of the dialog variable so it can be captured by listeners if needed,
                    // though direct use in listeners for map checking is complex.
                    lateinit var dialogInstance: AlertDialog

                    dialogInstance = MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(getString(R.string.permission_dialog_title))
                        .setMessage(getString(R.string.permission_dialog_message, currentRequestIp))
                        .setCancelable(false)
                        .setPositiveButton(getString(R.string.allow)) { _, _ ->
                            // Complete THIS dialog's specific deferred with true.
                            currentRequestDeferred.complete(true)
                            // Clean up: If this IP's current mapped deferred is the one we just completed, remove it.
                            if (pendingIpApprovals[currentRequestIp] == currentRequestDeferred) {
                                pendingIpApprovals.remove(currentRequestIp)
                            }
                            // If the dialog instance in the map is the one we are handling, remove it.
                            if (ipPermissionDialogs[currentRequestIp] == dialogInstance) {
                                ipPermissionDialogs.remove(currentRequestIp)
                            }
                        }
                        .setNegativeButton(getString(R.string.deny)) { _, _ -> // *** This line sets the "Deny" button ***
                            // Negative button logic
                            currentRequestDeferred.complete(false)
                            if (pendingIpApprovals[currentRequestIp] == currentRequestDeferred) {
                                pendingIpApprovals.remove(currentRequestIp)
                            }
                            if (ipPermissionDialogs[currentRequestIp] == dialogInstance) {
                                ipPermissionDialogs.remove(currentRequestIp)
                            }
                        }


                        .setOnDismissListener {
                            // This dialog (for currentRequestDeferred) is dismissed.
                            // If not already completed by buttons, mark as denied.
                            // complete() is idempotent.
                            currentRequestDeferred.complete(false)

                            if (pendingIpApprovals[currentRequestIp] == currentRequestDeferred) {
                                pendingIpApprovals.remove(currentRequestIp)
                            }
                            // 'dialogInstance' refers to the dialog this listener is attached to.
                            if (ipPermissionDialogs[currentRequestIp] == dialogInstance) {
                                ipPermissionDialogs.remove(currentRequestIp)
                            }
                        }

                        .create() // 'dialog' is now the created AlertDialog instance

                    // Update the maps with the new request's deferred and dialog instance.
                    pendingIpApprovals[currentRequestIp] = currentRequestDeferred
                    ipPermissionDialogs[currentRequestIp] = dialogInstance
                    dialogInstance.show()

                }
            }
        }
    }


    private fun startFileServer(folderUri: Uri) {
        Log.d("MainActivity", "Attempting to start file server with folder: $folderUri")
        if (!Utils.isUriPermissionPersisted(this, folderUri)) {
            makeText(this, getString(R.string.storage_permission_required), Toast.LENGTH_LONG).show()
            // Try to re-request, though persistUriPermission should have handled it
            Utils.persistUriPermission(this, folderUri) // Re-affirm
            if (!Utils.isUriPermissionPersisted(this, folderUri)) { // Check again
                Log.e("MainActivity", "Failed to get persistent permission for $folderUri")
                tvSelectedFolder.text = getString(R.string.no_folder_selected) // Reset
                Utils.clearPersistedUri(this)
                currentSelectedFolderUri = null
                viewModel.setSelectedFolderUri(null)
                return
            }
        }

        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_START_SERVICE
            putExtra("FOLDER_URI", folderUri.toString())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        // Binding will happen in onStart or if already bound, serviceConnection will handle updates
        if (!isServiceBound) { // If not bound yet, bind now
            bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
        btnSelectFolder.text = getString(R.string.stop_server)
        updateActionButtonStates(true)
    }

    private fun stopFileServer() {
        Log.d("MainActivity", "Attempting to stop file server.")
        if (isServiceBound) {
            val serviceIntent = Intent(this, FileServerService::class.java).apply {
                action = Constants.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(this, serviceIntent) // Tell service to stop itself
        }
        btnSelectFolder.text = getString(R.string.select_shared_folder)
        updateActionButtonStates(false) // Disable actions when server is stopped
        tvIpAddress.text = getString(R.string.waiting_for_network)
        tvServerStatus.text = getString(R.string.server_stopped)
    }

    private fun updateActionButtonStates(isFolderSelectedAndServerPotentiallyRunning: Boolean) {
        btnDeviceUpload.isEnabled = isFolderSelectedAndServerPotentiallyRunning
        btnDevicePaste.isEnabled = isFolderSelectedAndServerPotentiallyRunning
         btnDeviceDownload.isEnabled = isFolderSelectedAndServerPotentiallyRunning
    }


    override fun onCreateOptionsMenu(menu: Menu): Boolean {
//        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (!(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                makeText(this, "Notification permission denied. The server status might not be visible in notifications.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        // Clean up dialogs to prevent leaks if activity is destroyed
        ipPermissionDialogs.values.forEach { if (it.isShowing) it.dismiss() }
        ipPermissionDialogs.clear()
        pendingIpApprovals.values.forEach { it.cancel() } // Cancel any pending deferred
        pendingIpApprovals.clear()
        super.onDestroy()
    }
}