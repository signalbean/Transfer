package com.matanh.transfer

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputLayout
import com.matanh.transfer.server.FileServerService
import com.matanh.transfer.server.ServerState
import com.matanh.transfer.ui.AboutActivity
import com.matanh.transfer.ui.MainViewModel
import com.matanh.transfer.ui.ReportErrorActivity
import com.matanh.transfer.ui.SettingsActivity
import com.matanh.transfer.util.Constants
import com.matanh.transfer.util.FileAdapter
import com.matanh.transfer.util.FileItem
import com.matanh.transfer.util.FileUtils
import com.matanh.transfer.util.ShareHandler
import kotlinx.coroutines.launch
import timber.log.Timber

class MainActivity : AppCompatActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var tvServerStatus: TextView

    private lateinit var actvIps: AutoCompleteTextView
    private lateinit var tilIps: TextInputLayout
    private lateinit var ipsAdapter: ArrayAdapter<String>

    private lateinit var btnCopyIp: ImageButton
    private lateinit var rvFiles: RecyclerView
    private lateinit var fileAdapter: FileAdapter
    private lateinit var fabUpload: FloatingActionButton
    private lateinit var viewStatusIndicator: View
    private lateinit var tvNoFilesMessage: TextView
    private lateinit var btnStartServer: Button
    private lateinit var shareHandler: ShareHandler

    private var fileServerService: FileServerService? = null
    private var isServiceBound = false
    private var currentSelectedFolderUri: Uri? = null
    private val ipPermissionDialogs = mutableMapOf<String, AlertDialog>()

    private var actionMode: ActionMode? = null
    private val logger = Timber.tag("MainActivity")

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as FileServerService.LocalBinder
            fileServerService = binder.getService()
            isServiceBound = true
            fileServerService?.activityResumed() // Notify service that UI is active and ready
            observeServerState()
            observeIpPermissionRequests()
            observePullRefresh()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            fileServerService = null
            isServiceBound = false
        }
    }

    private val uploadFileLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { sourceUri ->
                currentSelectedFolderUri?.let { folder ->
                    val fileName = FileUtils.getFileName(this, sourceUri)
                    val copiedFile = FileUtils.copyUriToAppDir(
                        this, sourceUri, folder, fileName ?: "upload.txt"
                    )
                    if (copiedFile != null && copiedFile.exists()) {
                        Toast.makeText(
                            this,
                            getString(R.string.file_uploaded, copiedFile.name),
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.loadFiles(folder)
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.file_upload_failed),
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                } ?: Toast.makeText(
                    this, getString(R.string.shared_folder_not_selected), Toast.LENGTH_SHORT
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        // Initialize ViewModel and ShareHandler
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]
        shareHandler = ShareHandler(this, viewModel)

        initViews()
        setupClickListeners()
        setupFileListAndObservers()

        // Observe the shared folder URI from the ViewModel
        viewModel.selectedFolderUri.observe(this) { uri ->
            currentSelectedFolderUri = uri
            if (uri != null) {
                // If URI is valid, ensure the server is started with it
                startFileServer(uri)
            } else {
                // If no URI is set, guide the user to settings
                navigateToSettingsWithMessage(getString(R.string.select_shared_folder_prompt))
            }
        }

        // Bind to the FileServerService
        Intent(this, FileServerService::class.java).also { intent ->
            bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        }

        // Handle initial intent (e.g., from a share action)
        shareHandler.handleIntent(intent, currentSelectedFolderUri)
    }

    private fun navigateToSettingsWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Handle intents that arrive while the activity is already running
        shareHandler.handleIntent(intent, currentSelectedFolderUri)
    }

    private fun initViews() {
        tvServerStatus = findViewById(R.id.tvServerStatus)
        tilIps   = findViewById(R.id.tilIps)
        actvIps  = findViewById(R.id.actvIps)
        btnCopyIp = findViewById(R.id.btnCopyIp)
        rvFiles = findViewById(R.id.rvFiles)
        fabUpload = findViewById(R.id.fabUpload)
        viewStatusIndicator = findViewById(R.id.viewStatusIndicator)
        tvNoFilesMessage = findViewById(R.id.tvNoFilesMessage)
        btnStartServer = findViewById(R.id.btnStartServer)

        ipsAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf<String>()
        )
        actvIps.setAdapter(ipsAdapter)

    }

    private fun setupClickListeners() {
        btnCopyIp.setOnClickListener {
            val display = actvIps.text?.toString() ?: return@setOnClickListener
            val raw     = display.substringAfter(": ").trim()

            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("IP", raw))
            Toast.makeText(this, R.string.ip_copied_to_clipboard, Toast.LENGTH_SHORT).show()
        }
        fabUpload.setOnClickListener { uploadFileLauncher.launch("*/*") }
        btnStartServer.setOnClickListener {
            currentSelectedFolderUri?.let { startFileServer(it) }
                ?: navigateToSettingsWithMessage(getString(R.string.select_shared_folder_prompt))
        }
    }

    private fun setupFileListAndObservers() {
        fileAdapter = FileAdapter(emptyList(), onItemClick = { _, position ->
            if (actionMode != null) {
                toggleSelection(position)
            } else {
                // Handle regular item click if needed (e.g., open file preview)
                // For now, we can share it as a default action or do nothing
                // shareFile(fileItem) // Example: share on single tap when not in CAB mode
            }
        }, onItemLongClick = { _, position ->
            if (actionMode == null) {
                startSupportActionMode(actionModeCallback)
            }
            toggleSelection(position)
            true
        })
        rvFiles.layoutManager = LinearLayoutManager(this)
        rvFiles.adapter = fileAdapter

        // Observe files LiveData from the ViewModel
        viewModel.files.observe(this) { files ->
            fileAdapter.updateFiles(files)
            val isEmpty = files.isEmpty()
            tvNoFilesMessage.visibility = if (isEmpty) View.VISIBLE else View.GONE
            rvFiles.visibility = if (isEmpty) View.GONE else View.VISIBLE
        }
    }

    private fun toggleSelection(position: Int) {
        fileAdapter.toggleSelection(position)
        val count = fileAdapter.getSelectedItemCount()
        if (count == 0) {
            actionMode?.finish()
        } else {
            actionMode?.title = getString(R.string.selected_items_count, count)
            actionMode?.invalidate() // Refresh CAB menu if needed (e.g. select all state)
        }
    }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            actionMode = mode
            mode?.menuInflater?.inflate(R.menu.contextual_action_menu, menu)
            fabUpload.hide() // Hide FAB when action mode is active
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?): Boolean {
            // You can dynamically show/hide menu items here based on selection
            menu?.findItem(R.id.action_select_all)?.isVisible = fileAdapter.itemCount > 0
            return true
        }

        override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?): Boolean {
            val selectedFiles = fileAdapter.getSelectedFileItems()
            if (selectedFiles.isEmpty()) {
                Toast.makeText(
                    this@MainActivity, getString(R.string.no_files_selected), Toast.LENGTH_SHORT
                ).show()
                return false
            }
            return when (item?.itemId) {
                R.id.action_delete_contextual -> {
                    confirmDeleteMultipleFiles(selectedFiles)
                    true
                }

                R.id.action_share_contextual -> {
                    shareMultipleFiles(selectedFiles)
                    true
                }

                R.id.action_select_all -> {
                    fileAdapter.selectAll()
                    val count = fileAdapter.getSelectedItemCount()
                    if (count == 0) { // All were deselected
                        actionMode?.finish()
                    } else {
                        actionMode?.title = getString(R.string.selected_items_count, count)
                    }
                    true
                }

                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            actionMode = null
            fileAdapter.clearSelections()
            fabUpload.show() // Show FAB again
        }
    }

    @SuppressLint("SetTextI18n")
    private fun observeServerState() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.serverState.collect { state ->
                    logger.d("Server state changed: $state ")
                    when (state) {
                        is ServerState.Starting -> {
                            tvServerStatus.text = getString(R.string.server_starting)
                            tvServerStatus.setTextColor(
                                ContextCompat.getColor(
                                    this@MainActivity,
                                    R.color.colorPrimary
                                )
                            )
                            viewStatusIndicator.background = ContextCompat.getDrawable(
                                this@MainActivity,
                                R.drawable.status_indicator_running
                            )
                            updateIpDropdown(emptyList(),getString(R.string.server_starting))

                            btnStartServer.visibility = View.GONE
                            btnCopyIp.visibility = View.INVISIBLE
                        }

                        is ServerState.Running -> {
                            tvServerStatus.text = getString(R.string.server_running)
                            tvServerStatus.setTextColor(
                                ContextCompat.getColor(
                                    this@MainActivity,
                                    R.color.green
                                )
                            )
                            viewStatusIndicator.background = ContextCompat.getDrawable(
                                this@MainActivity,
                                R.drawable.status_indicator_running
                            )
//                            spinnerIps.text = "${state.hosts.mainIp}:${state.port}"
                            val hosts = state.hosts

                            val entries = listOfNotNull(
                                hosts.localIp?.let       { "Local IP: $it:${state.port}" },
                                hosts.localHostname?.let { "Hostname: $it:${state.port}" },
                                hosts.hotspotIp?.let     { "Hotspot IP: $it:${state.port}" },
                            )
                            updateIpDropdown(entries)

                            btnStartServer.visibility = View.GONE
                            btnCopyIp.visibility = View.VISIBLE
                        }

                        ServerState.UserStopped,
                        ServerState.AwaitNetwork -> {
                            tvServerStatus.text = getString(R.string.server_stopped)
                            tvServerStatus.setTextColor(
                                ContextCompat.getColor(
                                    this@MainActivity,
                                    R.color.red
                                )
                            )
                            viewStatusIndicator.background = ContextCompat.getDrawable(
                                this@MainActivity,
                                R.drawable.status_indicator_stopped
                            )
                            updateIpDropdown(emptyList(),getString(R.string.waiting_for_network))
                            btnStartServer.visibility = View.VISIBLE
                            btnCopyIp.visibility = View.INVISIBLE
                        }

                        is ServerState.Error -> {
                            tvServerStatus.text =
                                getString(R.string.server_error_format, state.message)
                            tvServerStatus.setTextColor(
                                ContextCompat.getColor(
                                    this@MainActivity,
                                    R.color.red
                                )
                            )
                            viewStatusIndicator.background = ContextCompat.getDrawable(
                                this@MainActivity,
                                R.drawable.status_indicator_stopped
                            )
                            updateIpDropdown(emptyList(),getString(R.string.server_error_format))
                            btnStartServer.visibility = View.VISIBLE
                            btnCopyIp.visibility = View.INVISIBLE
                        }
                    }
                }
            }
        }
    }
    private fun updateIpDropdown(newEntries: List<String>,placeholder:String?=null) {
        ipsAdapter.apply {
            clear()
            addAll(newEntries)
            notifyDataSetChanged()
        }
        // Show either first entry or a placeholder
        actvIps.setText(
            newEntries.firstOrNull() ?: (placeholder ?: getString(R.string.waiting_for_network)),
            false      // don't trigger filtering
        )
        // Visibility of copy button
        btnCopyIp.visibility = if (newEntries.isEmpty()) View.INVISIBLE else View.VISIBLE
    }


    private fun observeIpPermissionRequests() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.ipPermissionRequests.collect { request ->
                    val ip = request.ipAddress
                    val deferred = request.deferred
                    if (ipPermissionDialogs.containsKey(ip) || deferred.isCompleted) return@collect
                    val dialog =
                        MaterialAlertDialogBuilder(this@MainActivity).setTitle(getString(R.string.permission_request_title))
                            .setMessage(getString(R.string.permission_request_message, ip))
                            .setPositiveButton(getString(R.string.allow)) { _, _ ->
                                deferred.complete(
                                    true
                                )
                            }
                            .setNegativeButton(getString(R.string.deny)) { _, _ ->
                                deferred.complete(
                                    false
                                )
                            }
                            .setOnDismissListener {
                                if (!deferred.isCompleted) deferred.complete(false)
                                ipPermissionDialogs.remove(ip)
                            }.create()
                    ipPermissionDialogs[ip] = dialog
                    dialog.show()
                }
            }
        }
    }

    private fun observePullRefresh() {
        if (!isServiceBound || fileServerService == null) return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fileServerService!!.pullRefresh.collect {
                    currentSelectedFolderUri?.let { viewModel.loadFiles(it) }
                }
            }
        }
    }

    private fun startFileServer(folderUri: Uri) {
        if (!FileUtils.canWriteToUri(this, folderUri)) {
            Toast.makeText(this, getString(R.string.no_write_permission), Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val serviceIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_START_SERVICE
            putExtra(Constants.EXTRA_FOLDER_URI, folderUri.toString())
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        // 2. (Re)bind so we always have a fresh Binder reference
        if (!isServiceBound) {
            bindService(
                Intent(this, FileServerService::class.java),
                serviceConnection,
                BIND_AUTO_CREATE
            )
        }
    }

    private fun shareMultipleFiles(files: List<FileItem>) {
        if (files.isEmpty()) return
        val urisToShare = ArrayList<Uri>()
        files.forEach { fileItem ->
            DocumentFile.fromSingleUri(this, fileItem.uri)?.let { docFile ->
                if (docFile.canRead()) urisToShare.add(docFile.uri)
            }
        }
        if (urisToShare.isEmpty()) {
            Toast.makeText(this, getString(R.string.no_readable_files_share), Toast.LENGTH_SHORT)
                .show()
            return
        }
        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, urisToShare)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(
                Intent.createChooser(
                    shareIntent,
                    getString(R.string.share_multiple_files_title, urisToShare.size)
                )
            )
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.share_file_error, e.message),
                Toast.LENGTH_SHORT
            ).show()
        }
        actionMode?.finish()
    }

    private fun confirmDeleteMultipleFiles(files: List<FileItem>) {
        if (files.isEmpty()) return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.confirm_delete_multiple_title, files.size))
            .setMessage(getString(R.string.confirm_delete_multiple_message, files.size))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteFiles(files)
                actionMode?.finish()
            }.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_paste -> {
                viewModel.pasteFromClipboard()
                true
            }

            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }

            R.id.action_report_error -> {
                startActivity(Intent(this, ReportErrorActivity::class.java))
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        fileServerService?.activityResumed()
        // Let the ViewModel handle reloading the URI from prefs if needed
        viewModel.checkSharedFolderUri()

        if (!isServiceBound) {
            Intent(this, FileServerService::class.java).also { intent ->
                bindService(intent, serviceConnection, BIND_AUTO_CREATE)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        fileServerService?.activityPaused() // Notify service that UI is no longer in the foreground
    }

    override fun onStop() {
        super.onStop()
        // Unbind from the service when the activity is no longer visible
        // to prevent leaks if the service is not a foreground service or is stopped.
        // If the service is a long-running foreground service, you might choose to unbind
        // but not stop the service here. The service binding is for UI interaction.
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (e: IllegalArgumentException) {
                logger.e("Service not registered or already unbound: ${e.message}")
            }
            isServiceBound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Dismiss any lingering dialogs to prevent window leaks
        ipPermissionDialogs.values.forEach { if (it.isShowing) it.dismiss() }
        ipPermissionDialogs.clear()

        // If action mode is active, finish it
        actionMode?.finish()
    }
}
