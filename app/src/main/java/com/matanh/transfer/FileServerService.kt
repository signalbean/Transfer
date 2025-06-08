package com.matanh.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IpPermissionRequest(val ipAddress: String, val deferred: CompletableDeferred<Boolean>)

class FileServerService : Service() {

    private val binder = LocalBinder()
    private var ktorServer: ApplicationEngine? = null
    private val serviceJob = SupervisorJob() // Use SupervisorJob for resilience
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _ipPermissionRequests = MutableSharedFlow<IpPermissionRequest>(
        replay = 0,
        extraBufferCapacity = 1
    )

    val ipPermissionRequests = _ipPermissionRequests.asSharedFlow()

    private lateinit var sharedPreferences: SharedPreferences
    private val approvedIps = mutableMapOf<String, Long>() // IP to expiry timestamp

    // Store the selected folder URI to pass to Ktor module
    var currentSharedFolderUri: Uri? = null
        private set


    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this)
        createNotificationChannel()
        Log.d(TAG, "FileServerService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand: ${intent?.action}")
        when (intent?.action) {
            Constants.ACTION_START_SERVICE -> {
                val folderUriString = intent.getStringExtra("FOLDER_URI")
                if (folderUriString != null) {
                    currentSharedFolderUri = Uri.parse(folderUriString)
                    startKtorServer()
                } else {
                    Log.e(TAG, "Folder URI missing, stopping service")
                    _serverState.value = ServerState.Error("Folder URI missing.")
                    stopSelf()
                }
            }
            Constants.ACTION_STOP_SERVICE -> {
                stopKtorServer()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startKtorServer() {
        if (ktorServer?.application?.isActive == true) {
            Log.d(TAG, "Ktor server already running.")
            // Update state if IP changed, etc. For now, assume it's okay or re-fetch IP.
            (_serverState.value as? ServerState.Running)?.let {
                _serverState.value = ServerState.Running(Utils.getLocalIpAddress(this) ?: it.ip, Constants.SERVER_PORT)
            }
            updateNotification()
            return
        }

        if (currentSharedFolderUri == null) {
            Log.e(TAG, "Cannot start server: shared folder URI is null.")
            _serverState.value = ServerState.Error("Shared folder not set.")
            stopSelf()
            return
        }
        // Ensure DocumentFile is valid before starting server
        val baseDocFile = DocumentFile.fromTreeUri(this, currentSharedFolderUri!!)
        if (baseDocFile == null || !baseDocFile.canRead()) {
            Log.e(TAG, "Cannot start server: shared folder URI is not accessible or readable.")
            _serverState.value = ServerState.Error("Shared folder not accessible.")
            stopSelf()
            return
        }


        serviceScope.launch {
            try {
                val ipAddress = Utils.getLocalIpAddress(this@FileServerService)
                if (ipAddress == null) {
                    _serverState.value = ServerState.Error("Wi-Fi not connected or IP not found.")
                    Log.e(TAG, "Failed to get local IP address.")
                    stopSelf() // Stop service if IP cannot be obtained
                    return@launch
                }

                // Pass `this` (FileServerService instance) to the Ktor module for callbacks
                val serviceProvider = { this@FileServerService }

                ktorServer = embeddedServer(CIO, port = Constants.SERVER_PORT, host = "0.0.0.0", module = {
                    transferServerModule(applicationContext,serviceProvider, currentSharedFolderUri!!)
                }).apply {
                    start(wait = false) // Start non-blocking
                }

                _serverState.value = ServerState.Running(ipAddress, Constants.SERVER_PORT)
                Log.i(TAG, "Ktor Server started on $ipAddress:${Constants.SERVER_PORT}")
                startForeground(Constants.NOTIFICATION_ID, createNotification())

            } catch (e: Exception) { // Catch broader exceptions during server setup/start
                Log.e(TAG, "Error starting Ktor server", e)
                _serverState.value = ServerState.Error("Failed to start server: ${e.localizedMessage}")
                ktorServer?.stop(1000, 2000) // Attempt to stop if partially started
                ktorServer = null
                stopSelf() // Stop service if server fails to start
            }
        }
    }

    private fun stopKtorServer() {
        serviceScope.launch {
            try {
                ktorServer?.stop(1000, 2000) // Grace period and timeout
            } catch (e: Exception) {
                Log.e(TAG, "Exception while stopping Ktor server", e)
            } finally {
                ktorServer = null
                _serverState.value = ServerState.Stopped
                Log.i(TAG, "Ktor Server stopped.")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    // --- Callbacks for Ktor Module ---
    fun isIpPermissionRequired(): Boolean {
        return sharedPreferences.getBoolean(getString(R.string.pref_key_ip_permission_enabled), true)
    }

    suspend fun requestIpApprovalFromClient(ipAddress: String): Boolean {
        Log.d(TAG, "Requesting IP approval from $ipAddress")

        // Clean up expired IPs
        val now = System.currentTimeMillis()
        approvedIps.entries.removeIf { (_, expiryTime) -> expiryTime <= now }

        // If we already approved this IP (and it hasn’t expired), return immediately
        val cachedExpiry = approvedIps[ipAddress]
        if (cachedExpiry != null && cachedExpiry > now) {
            return true
        }
        approvedIps.remove(ipAddress)

        // Create a Deferred<Boolean> that the UI will complete
        val deferred = CompletableDeferred<Boolean>()

        // Emit into the SharedFlow. Because extraBufferCapacity = 1 above, this never hangs.
        _ipPermissionRequests.emit(IpPermissionRequest(ipAddress, deferred))

        // Now suspend until the UI calls `deferred.complete(true)` or `.complete(false)`
        val approved = try {
            deferred.await()
        } catch (e: CancellationException) {
            false
        }

        if (approved) {
            approvedIps[ipAddress] = now + Constants.IP_PERMISSION_VALIDITY_MS
        }
        return approved
    }


    fun isPasswordProtectionEnabled(): Boolean {
        val password = sharedPreferences.getString(getString(R.string.pref_key_server_password), null)
        return !password.isNullOrEmpty()
    }

    fun getServerPassword(): String? {
        return sharedPreferences.getString(getString(R.string.pref_key_server_password), null)
    }

    fun checkPassword(providedPassword: String): Boolean {
        val storedPassword = getServerPassword()
        return storedPassword != null && storedPassword == providedPassword
    }

    // --- Notification and Binder ---
    // createNotificationChannel, createNotification, updateNotification methods remain the same
    // onBind, onDestroy methods remain largely the same (ensure ktorServer is stopped in onDestroy)

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                Constants.NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            serviceChannel.description = getString(R.string.notification_channel_description)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val ipAddress = (_serverState.value as? ServerState.Running)?.ip ?: Utils.getLocalIpAddress(this) ?: "N/A"
        val port = (_serverState.value as? ServerState.Running)?.port ?: Constants.SERVER_PORT

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.file_server_notification_title))
            .setContentText(getString(R.string.file_server_notification_text, ipAddress, port))
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        if (_serverState.value is ServerState.Running) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(Constants.NOTIFICATION_ID, createNotification())
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        Log.d(TAG, "FileServerService onDestroy")
        stopKtorServer() // Ensure server is stopped
        serviceJob.cancel() // Cancel all coroutines in this scope
        super.onDestroy()
    }

    companion object {
        private const val TAG = "FileServerServiceKtor"
    }
}