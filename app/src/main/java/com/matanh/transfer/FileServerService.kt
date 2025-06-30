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
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

data class IpPermissionRequest(val ipAddress: String, val deferred: CompletableDeferred<Boolean>)

class FileServerService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    private val binder = LocalBinder()
    private var ktorServer: EmbeddedServer<*, *>? = null
    private val serviceJob = SupervisorJob() // Use SupervisorJob for resilience
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped(isFirst = true))
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _ipPermissionRequests = MutableSharedFlow<IpPermissionRequest>(
        replay = 0, extraBufferCapacity = 1
    )

    val ipPermissionRequests = _ipPermissionRequests.asSharedFlow()

    private val _pullRefresh = MutableSharedFlow<Unit>(replay = 0)
    val pullRefresh: SharedFlow<Unit> = _pullRefresh.asSharedFlow()


    private lateinit var sharedPreferences: SharedPreferences
    private val approvedIps = mutableMapOf<String, Long>() // IP to expiry timestamp

    // Store the selected folder URI to pass to Ktor module
    var currentSharedFolderUri: Uri? = null
        private set

    // New properties for handling background IP permission
    @Volatile
    private var isActivityInForeground = false
    private val pendingNotificationRequests = mutableMapOf<String, CompletableDeferred<Boolean>>()

    private val pendingIntentFlags by lazy {
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }

    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }

    companion object {
        private val logger = Timber.tag("FileServerServiceKtor")

        // Constants for IP Permission Notification
        const val PERMISSION_NOTIFICATION_CHANNEL_ID = "ip_permission_channel"
        const val ACTION_IP_PERMISSION_RESPONSE =
            "com.matanh.transfer.ACTION_IP_PERMISSION_RESPONSE"
        const val EXTRA_IP_ADDRESS = "extra_ip_address"
        const val EXTRA_IP_PERMISSION_APPROVED = "extra_ip_permission_approved"
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences(Constants.SHARED_PREFS_NAME, MODE_PRIVATE)

        sharedPreferences.registerOnSharedPreferenceChangeListener(this) // track changes
        createNotificationChannel()
        logger.d("FileServerService onCreate")
    }

    // Public methods for activity to report its state
    fun activityResumed() {
        isActivityInForeground = true
        // If there are pending notifications, cancel them and forward to the activity
        if (pendingNotificationRequests.isNotEmpty()) {
            serviceScope.launch {
                val requestsToForward = pendingNotificationRequests.toMap()
                pendingNotificationRequests.clear() // Clear immediately
                requestsToForward.forEach { (ip, deferred) ->
                    val notificationManager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    notificationManager.cancel(ip.hashCode())
                    logger.d("Forwarding background IP request for $ip to foreground activity.")
                    _ipPermissionRequests.emit(IpPermissionRequest(ip, deferred))
                }
            }
        }
    }

    fun activityPaused() {
        isActivityInForeground = false
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        val passwordKey = getString(R.string.pref_key_server_password)
        val ipPermissionKey = getString(R.string.pref_key_ip_permission_enabled)

        // Check if a setting that requires a server restart was changed
        if (key == passwordKey || key == ipPermissionKey) {
            // Only restart if the server is currently running
            if (ktorServer != null) {
                logger.i("A server setting changed. Restarting Ktor server.")
                startKtorServer() // The restart is now handled by startKtorServer
            }
        }
    }

    // Called from the server to create a “refresh” event
    fun notifyFilePushed() {
        CoroutineScope(Dispatchers.Default).launch {
            _pullRefresh.emit(Unit)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logger.d("onStartCommand: ${intent?.action}")
        when (intent?.action) {
            Constants.ACTION_START_SERVICE -> {
                val folderUriString = intent.getStringExtra(Constants.EXTRA_FOLDER_URI)
                if (folderUriString != null) {
                    currentSharedFolderUri = folderUriString.toUri()
                    startKtorServer()
                } else {
                    logger.e("Folder URI missing, stopping service")
                    _serverState.value = ServerState.Error("Folder URI missing.")
                    stopSelf()
                }
            }

            Constants.ACTION_STOP_SERVICE -> {
                stopKtorServer()
                stopSelf()
            }

            ACTION_IP_PERMISSION_RESPONSE -> {
                handleIpPermissionResponse(intent)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleIpPermissionResponse(intent: Intent) {
        val ipAddress = intent.getStringExtra(EXTRA_IP_ADDRESS)
        val approved = intent.getBooleanExtra(EXTRA_IP_PERMISSION_APPROVED, false)

        if (ipAddress == null) {
            logger.e("IP address missing in permission response intent.")
            return
        }

        val deferred = pendingNotificationRequests.remove(ipAddress)
        if (deferred != null) {
            if (deferred.isCompleted) {
                logger.w("Deferred for IP $ipAddress was already completed.")
            } else {
                logger.i("Completing permission for $ipAddress with result: $approved via notification.")
                deferred.complete(approved)
            }
        } else {
            logger.w("No pending notification request found for IP $ipAddress.")
        }

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(ipAddress.hashCode())
    }

    private fun startKtorServer() {
        serviceScope.launch {

            // If the server is already running, stop it first.
            if (ktorServer != null) {
                logger.i("Stopping existing Ktor server for restart...")
                ktorServer?.stop(500, 1000) // Gracefully stop the server
                ktorServer = null
            }

            if (currentSharedFolderUri == null) {
                logger.e("Cannot start server: shared folder URI is null.")
                _serverState.value = ServerState.Error("Shared folder not set.")
                stopSelf()
                return@launch
            }
            // Ensure DocumentFile is valid before starting server
            val baseDocFile =
                DocumentFile.fromTreeUri(this@FileServerService, currentSharedFolderUri!!)
            if (baseDocFile == null || !baseDocFile.canRead()) {
                logger.e("Cannot start server: shared folder URI is not accessible or readable.")
                _serverState.value = ServerState.Error("Shared folder not accessible.")
                stopSelf()
                return@launch
            }


            try {
                val ipAddress = Utils.getLocalIpAddress(this@FileServerService)
                if (ipAddress == null) {
                    _serverState.value = ServerState.Error("Wi-Fi not connected or IP not found.")
                    logger.e("Failed to get local IP address.")
                    stopSelf() // Stop service if IP cannot be obtained
                    return@launch
                }

                // Pass `this` (FileServerService instance) to the Ktor module for callbacks
                val serviceProvider = { this@FileServerService }

                ktorServer =
                    embeddedServer(CIO, port = Constants.SERVER_PORT, host = "0.0.0.0", module = {
                        transferServerModule(
                            applicationContext, serviceProvider, currentSharedFolderUri!!
                        )
                    }).apply {
                        start(wait = false) // Start non-blocking
                    }

                _serverState.value = ServerState.Running(ipAddress, Constants.SERVER_PORT)
                logger.i("Ktor Server started on $ipAddress:${Constants.SERVER_PORT}")
                startForeground(Constants.NOTIFICATION_ID, createNotification())

            } catch (e: Exception) { // Catch broader exceptions during server setup/start
                logger.e(e)
                _serverState.value =
                    ServerState.Error("Failed to start server: ${e.localizedMessage}")
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
                logger.e(e, "Exception while stopping Ktor server $e")
            } finally {
                ktorServer = null
                _serverState.value = ServerState.Stopped(isFirst = false)
                logger.i("Ktor Server stopped.")
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    // --- Callbacks for Ktor Module ---
    fun isIpPermissionRequired(): Boolean {
        return sharedPreferences.getBoolean(
            getString(R.string.pref_key_ip_permission_enabled), true
        )
    }

    suspend fun requestIpApprovalFromClient(ipAddress: String): Boolean {
        logger.d("Requesting IP approval from $ipAddress")

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

        if (isActivityInForeground) {
            logger.d("Activity is in foreground. Emitting request to UI.")
            _ipPermissionRequests.emit(IpPermissionRequest(ipAddress, deferred))
        } else {
            logger.d("Activity is in background. Showing notification for IP permission.")
            pendingNotificationRequests[ipAddress] = deferred
            showIpPermissionNotification(ipAddress)
        }

        val approved = try {
            deferred.await()
        } catch (e: CancellationException) {
            logger.w("IP approval for $ipAddress was cancelled.")
            pendingNotificationRequests.remove(ipAddress)
            false
        }

        if (approved) {
            approvedIps[ipAddress] = now + Constants.IP_PERMISSION_VALIDITY_MS
        }
        return approved
    }


    fun isPasswordProtectionEnabled(): Boolean {
        val password =
            sharedPreferences.getString(getString(R.string.pref_key_server_password), null)
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

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        serviceChannel.description = getString(R.string.notification_channel_description)

        val permissionChannel = NotificationChannel(
            PERMISSION_NOTIFICATION_CHANNEL_ID,
            "IP Permission Requests", // Should be a string resource
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows notifications to allow or deny connections from new IP addresses."
        }

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(permissionChannel)
    }

    private fun showIpPermissionNotification(ipAddress: String) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val uniqueId = ipAddress.hashCode()

        val contentIntent = Intent(this, MainActivity::class.java).let {
            PendingIntent.getActivity(this, uniqueId, it, pendingIntentFlags)
        }

        val allowIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, true)
        }
        val allowPendingIntent =
            PendingIntent.getService(this, uniqueId * 2, allowIntent, pendingIntentFlags)

        val denyIntent = Intent(this, FileServerService::class.java).apply {
            action = ACTION_IP_PERMISSION_RESPONSE
            putExtra(EXTRA_IP_ADDRESS, ipAddress)
            putExtra(EXTRA_IP_PERMISSION_APPROVED, false)
        }
        val denyPendingIntent =
            PendingIntent.getService(this, uniqueId * 2 + 1, denyIntent, pendingIntentFlags)

        val notification = NotificationCompat.Builder(this, PERMISSION_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.permission_request_title))
            .setContentText(getString(R.string.permission_request_message, ipAddress))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(contentIntent)
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, getString(R.string.allow), allowPendingIntent)
            .addAction(0, getString(R.string.deny), denyPendingIntent).build()

        notificationManager.notify(uniqueId, notification)
    }

    private fun createNotification(): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent =
            PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags)

        val stopIntent = Intent(this, FileServerService::class.java).apply {
            action = Constants.ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, pendingIntentFlags)

        val ipAddress =
            (_serverState.value as? ServerState.Running)?.ip ?: Utils.getLocalIpAddress(this)
            ?: "N/A"
        val port = (_serverState.value as? ServerState.Running)?.port ?: Constants.SERVER_PORT

        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.file_server_notification_title))
            .setContentText(getString(R.string.file_server_notification_text, ipAddress, port))
            .setSmallIcon(R.drawable.ic_stat_name).setContentIntent(pendingIntent).setOngoing(true)
            .addAction(R.drawable.ic_stop_black, getString(R.string.stop_server), stopPendingIntent)
            .build()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        logger.d("FileServerService onDestroy")
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        stopKtorServer() // Ensure server is stopped
        serviceJob.cancel() // Cancel all coroutines in this scope
        super.onDestroy()
    }

}