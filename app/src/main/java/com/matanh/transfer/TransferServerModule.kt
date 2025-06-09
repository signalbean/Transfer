package com.matanh.transfer // Or com.matanh.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.basic
import io.ktor.server.http.content.CompressedFileType
import io.ktor.server.http.content.resolveResource
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

const val TAG_KTOR_MODULE = "TransferKtorModule"

// --- custom plugin to detect curl
private val IsCurlRequestKey = AttributeKey<Boolean>("IsCurlRequestKey")

val CurlDetectorPlugin = createApplicationPlugin(name = "CurlDetectorPlugin") {
    onCall { call ->
        val userAgent = call.request.headers[HttpHeaders.UserAgent]
        if (userAgent != null && userAgent.contains("curl", ignoreCase = true)) {
            call.attributes.put(IsCurlRequestKey, true)
        }
    }
}


// --- Custom IP Approval Plugin ---
val IpAddressApprovalPlugin = createApplicationPlugin(name = "IpAddressApprovalPlugin") {
    // Get the service instance via a provider lambda to avoid direct dependency issues if structure changes
    val serviceProvider = application.attributes[KEY_SERVICE_PROVIDER]

    onCall { call ->
        val service = serviceProvider() ?: run {
            Log.e(TAG_KTOR_MODULE, "FileServerService not available to IPAddressApprovalPlugin")
            call.respond(HttpStatusCode.InternalServerError, "Server configuration error.")
            return@onCall
        }

        val clientIp = call.request.origin.remoteHost
        Log.d(TAG_KTOR_MODULE, "IP Approval: Checking IP $clientIp")

        if (service.isIpPermissionRequired()) {
            Log.d(TAG_KTOR_MODULE, "IP Approval: Checking IP $clientIp")
            val approved = service.requestIpApprovalFromClient(clientIp) // This is a suspend fun
            if (!approved) {
                Log.w(TAG_KTOR_MODULE, "IP Approval: IP $clientIp denied access.")
                call.respond(HttpStatusCode.Forbidden, "Access denied by host device.")
                return@onCall
            } else {
                Log.d(TAG_KTOR_MODULE, "IP Approval: IP $clientIp approved.")
            }
        }
    }
}
private val KEY_SERVICE_PROVIDER =
    AttributeKey<() -> FileServerService?>("ServiceProviderKey")


// --- Ktor Application Module Definition ---
fun Application.transferServerModule(
    context: Context,
    serviceProviderLambda: () -> FileServerService?,
    sharedDirUri: Uri
) {
    val applicationContext = context // Get Android Context

    // Store service provider for the IP plugin
    attributes.put(KEY_SERVICE_PROVIDER, serviceProviderLambda)

    val fileServerService = serviceProviderLambda() // Get the service instance for other uses

    if (fileServerService == null) {
        log.error("FileServerService is null in Ktor module. Server might not function correctly.")
        // Potentially stop the application or log a critical error
        return
    }

    val baseDocumentFile = DocumentFile.fromTreeUri(applicationContext, sharedDirUri)
    if (baseDocumentFile == null || !baseDocumentFile.isDirectory || !baseDocumentFile.canRead()) {
        log.error("Shared directory URI is not accessible: $sharedDirUri")
        // This is a critical error; the server can't serve files.
        // Consider how to handle this - maybe stop the Ktor application or return errors for all requests.
        // For now, routes that depend on it will fail.
    }
    install(CurlDetectorPlugin)


    // Install Ktor Features (Plugins)
    install(CallLogging) // Logs requests, useful for debugging
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            Log.e(TAG_KTOR_MODULE, "Unhandled error: ${cause.localizedMessage}", cause)
            call.respondText(
                text = "500: ${cause.localizedMessage}",
                status = HttpStatusCode.InternalServerError
            )
        }
        status(HttpStatusCode.NotFound) { call, status ->
            call.respondText(text = "404: Page Not Found", status = status)
        }
        // Add more status pages as needed
    }

    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-File-Name") // For uploads if client sends it
        anyHost() // For development; you might want to restrict this in production
        // For this app's purpose (local network), anyHost is usually fine.
        allowCredentials = true // If you were using cookies/session auth
    }

    install(Authentication) {
        basic("auth-basic") {
            realm = applicationContext.getString(R.string.app_name) // Or "Transfer App"
            validate { credentials ->
                if (fileServerService.isPasswordProtectionEnabled()) {
                    if (fileServerService.checkPassword(credentials.password)) {
                        UserIdPrincipal(credentials.name) // Name can be anything, not used here
                    } else {
                        null // Invalid password
                    }
                } else {
                    UserIdPrincipal(credentials.name) // Password protection off, allow access
                }
            }
        }
    }

    // Install the custom IP Address Approval plugin AFTER Authentication,
    // so auth happens first if both are enabled. Or before, if IP check is primary.
    // Let's do IP check first, as it's a device-level permission.
    install(IpAddressApprovalPlugin)


    install(ContentNegotiation) {
        json() // Using kotlinx.serialization.json
    }
    suspend fun PipelineContext<Unit, ApplicationCall>.handleFileDownload(
        applicationContext: Context,
        baseDocumentFile: DocumentFile?,
        fileNameEncoded: String
    ) {
        val fileName = try {
            URLDecoder.decode(fileNameEncoded, "UTF-8")
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, "Invalid file name encoding.")
            return
        }
        if (baseDocumentFile == null) {
            call.respond(HttpStatusCode.InternalServerError, "Base directory not configured.")
            return
        }

        // Look up the DocumentFile
        val targetFile = baseDocumentFile.findFile(fileName)
        if (targetFile == null || !targetFile.isFile || !targetFile.canRead()) {
            call.respond(HttpStatusCode.NotFound, "File not found: $fileName")
            return
        }

        try {
            val mimeType = targetFile.type ?: ContentType.Application.OctetStream.toString()
            call.response.header(
                HttpHeaders.ContentDisposition,
                ContentDisposition.Attachment.withParameter(
                    ContentDisposition.Parameters.FileName,
                    targetFile.name ?: "downloaded_file"
                ).toString()
            )

            applicationContext.contentResolver.openInputStream(targetFile.uri)
                ?.use { inputStream ->
                    call.respondOutputStream(ContentType.parse(mimeType), HttpStatusCode.OK) {
                        inputStream.copyTo(this)
                    }
                } ?: run {
                call.respond(HttpStatusCode.InternalServerError, "Could not open file stream.")
            }
        } catch (e: Exception) {
            Log.e(TAG_KTOR_MODULE, "Error serving file $fileName", e)
            call.respond(
                HttpStatusCode.InternalServerError,
                "Error serving file: ${e.localizedMessage}"
            )
        }
    }




    // --- Routing ---
    routing {
        // Serve static assets (index.html, css, js) from src/main/assets/web/
        // Ktor expects a subfolder in assets if you specify one like "web"
        staticResources("/assets", "assets") {
            preCompressed(CompressedFileType.GZIP) // If you have pre-gzipped assets
            default("index.html") // Serve index.html for requests to /assets/
        }



        // API routes should be authenticated if password is set
        // And all routes (including static after this point if not careful) are subject to IP approval
        authenticate("auth-basic", optional = !fileServerService.isPasswordProtectionEnabled()) {
        get("/") {
            val isCurl = call.attributes.getOrNull(IsCurlRequestKey) == true
            if (isCurl) { // cURL: List files as plain text
                if (baseDocumentFile == null || !baseDocumentFile.isDirectory) {
                    call.respondText(
                        "Error: Shared directory not accessible.",
                        status = HttpStatusCode.InternalServerError
                    )
                    return@get
                }
                val fileNames = baseDocumentFile.listFiles()
                    .filter { it.isFile }
                    .joinToString("\n") { it.name ?: "unknown_file" }
                call.respondText(fileNames, ContentType.Text.Plain)
            }
            val resource =
                call.resolveResource("index.html", "assets") // "assets" is the package in resources
            if (resource != null) {
                call.respond(resource)
            } else {
                call.respond(
                    HttpStatusCode.NotFound,
                    "UI not found (index.html missing in assets)."
                )
            }
        }



            route("/api") {
                get("/ping") {
                    call.respondText("pong")
                }

                get("/files") {
                    if (baseDocumentFile == null || !baseDocumentFile.isDirectory) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Base directory not accessible")
                        )
                        return@get
                    }
                    try {
                        val filesList = baseDocumentFile.listFiles()
                            .filter { it.isFile && it.canRead() }
                            .mapNotNull { docFile ->
                                val lastModifiedDate = Date(docFile.lastModified())
                                val dateFormat = SimpleDateFormat(
                                    "yyyy-MM-dd HH:mm:ss",
                                    Locale.getDefault()
                                ).apply {
                                    timeZone = TimeZone.getDefault()
                                }
                                FileInfo(
                                    name = docFile.name ?: "Unknown",
                                    size = docFile.length(),
                                    formattedSize = Utils.formatFileSize(docFile.length()),
                                    lastModified = dateFormat.format(lastModifiedDate),
                                    type = docFile.type ?: "unknown",
                                    downloadUrl = "/api/download/${
                                        URLEncoder.encode(
                                            docFile.name,
                                            "UTF-8"
                                        )
                                    }"
                                )
                            }
                        Log.d(TAG_KTOR_MODULE, "Files list: $filesList")
                        call.respond(FileListResponse(filesList))
                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Error listing files", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Error listing files: ${e.localizedMessage}")
                        )
                    }
                }

                get("/download/{fileNameEncoded}") {
                    val fileNameEncoded = call.parameters["fileNameEncoded"] ?: run {
                        call.respond(HttpStatusCode.BadRequest, "File name missing.")
                        return@get
                    }

                    handleFileDownload(applicationContext, baseDocumentFile, fileNameEncoded)
                }

                post("/upload") {
                    if (baseDocumentFile == null || !baseDocumentFile.canWrite()) {
                        Log.e(TAG_KTOR_MODULE, "Base directory not writable or not set for upload.")
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Cannot write to storage.")
                        )
                        return@post
                    }

                    var filesUploadedCount = 0
                    val uploadedFileNames = mutableListOf<String>()

                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FileItem -> {
                                    val originalFileName = part.originalFileName
                                        ?: "unknown_upload_${System.currentTimeMillis()}"
                                    Log.d(TAG_KTOR_MODULE, "Receiving file: $originalFileName")

                                    // Sanitize and ensure unique filename
                                    val sanitizedFileName =
                                        originalFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                                    var targetFileDoc = baseDocumentFile.findFile(sanitizedFileName)
                                    var counter = 1
                                    var uniqueFileName = sanitizedFileName
                                    while (targetFileDoc != null && targetFileDoc.exists()) {
                                        val nameWithoutExt = sanitizedFileName.substringBeforeLast(
                                            '.',
                                            sanitizedFileName
                                        )
                                        val extension =
                                            sanitizedFileName.substringAfterLast('.', "")
                                        uniqueFileName =
                                            if (extension.isNotEmpty()) "$nameWithoutExt($counter).$extension" else "$nameWithoutExt($counter)"
                                        targetFileDoc = baseDocumentFile.findFile(uniqueFileName)
                                        counter++
                                    }

                                    val mimeType = part.contentType?.toString()
                                        ?: ContentType.Application.OctetStream.toString()
                                    val newFileDoc =
                                        baseDocumentFile.createFile(mimeType, uniqueFileName)

                                    if (newFileDoc == null || !newFileDoc.canWrite()) {
                                        Log.e(
                                            TAG_KTOR_MODULE,
                                            "Failed to create document file for upload: $uniqueFileName"
                                        )
                                        // part.dispose() - already happens
                                        return@forEachPart // continue to next part
                                    }

                                    part.streamProvider().use { inputStream ->
                                        applicationContext.contentResolver.openOutputStream(
                                            newFileDoc.uri
                                        ).use { outputStream ->
                                            if (outputStream == null) throw Exception("Cannot open output stream for ${newFileDoc.uri}")
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                    uploadedFileNames.add(uniqueFileName)
                                    filesUploadedCount++
                                    Log.i(
                                        TAG_KTOR_MODULE,
                                        "File '$uniqueFileName' uploaded successfully."
                                    )
                                }

                                is PartData.FormItem -> {
                                    // Handle other form items if any
                                    Log.d(
                                        TAG_KTOR_MODULE,
                                        "Form item: ${part.name} = ${part.value}"
                                    )
                                }

                                else -> { /* Other part types */
                                }
                            }
                            part.dispose()
                        }

                        if (filesUploadedCount > 0) {
                            fileServerService.notifyFilePushed()
                            call.respondText(
                                "Successfully uploaded: ${
                                    uploadedFileNames.joinToString(
                                        ", "
                                    )
                                }"
                            )
                        } else {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                "No files were uploaded or upload failed."
                            )
                        }

                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Exception during file upload", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            "Upload error: ${e.localizedMessage}"
                        )
                    }
                }

                post("/delete") {
                    if (baseDocumentFile == null) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Base directory not configured.")
                        )
                        return@post
                    }
                    try {
                        val requestBody =
                            call.receiveText() // Expecting {"filename": "somefile.txt"}
                        val jsonObject = JSONObject(requestBody)
                        val fileNameToDelete = jsonObject.optString("filename", "")

                        if (fileNameToDelete.isNullOrEmpty()) {
                            call.respond(
                                HttpStatusCode.BadRequest,
                                ErrorResponse("Filename not provided.")
                            )
                            return@post
                        }
                        val decodedFileName = URLDecoder.decode(fileNameToDelete, "UTF-8")
                        val fileToDeleteDoc = baseDocumentFile.findFile(decodedFileName)

                        if (fileToDeleteDoc == null || !fileToDeleteDoc.exists()) {
                            call.respond(
                                HttpStatusCode.NotFound,
                                ErrorResponse("File not found: $decodedFileName")
                            )
                            return@post
                        }
                        if (fileToDeleteDoc.delete()) {
                            Log.i(
                                TAG_KTOR_MODULE,
                                "File deleted successfully via API: $decodedFileName"
                            )
                            fileServerService.notifyFilePushed()
                            call.respond(
                                HttpStatusCode.OK,
                                SuccessResponse("File '$decodedFileName' deleted.")
                            )
                        } else {
                            Log.e(
                                TAG_KTOR_MODULE,
                                "Failed to delete file via API: $decodedFileName"
                            )
                            call.respond(
                                HttpStatusCode.InternalServerError,
                                ErrorResponse("Failed to delete file: $decodedFileName")
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Error processing delete request", e)
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse("Server error during delete: ${e.localizedMessage}")
                        )
                    }
                }
            } // end /api route
            // curl interface
            // cURL: PUT /filename (Upload)
            put("/{fileName}") {
                val fileName = call.parameters["fileName"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Filename missing in path for PUT.")
                    return@put
                }
                if (baseDocumentFile == null || !baseDocumentFile.canWrite()) {
                    call.respond(HttpStatusCode.InternalServerError, "Storage not writable.")
                    return@put
                }
                // Sanitize and ensure unique filename (similar to /api/upload)
                var targetFileDoc = baseDocumentFile.findFile(fileName)
                // ... (unique name logic if needed, or overwrite) ...
                if (targetFileDoc != null && targetFileDoc.exists()) { // Simple overwrite for cURL PUT
                    targetFileDoc.delete()
                }
                targetFileDoc = baseDocumentFile.createFile(
                    ContentType.Application.OctetStream.toString(), // Guess MIME or let it be generic
                    fileName
                )
                if (targetFileDoc == null) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Could not create file for PUT."
                    )
                    return@put
                }
                try {
                    val receivedChannel: ByteReadChannel = call.receiveChannel()
                    applicationContext.contentResolver.openOutputStream(targetFileDoc.uri)
                        .use { outputStream ->
                            if (outputStream == null) throw Exception("Cannot open output stream")
                            receivedChannel.copyTo(outputStream)
                        }
                    fileServerService.notifyFilePushed()
                    call.respond(HttpStatusCode.Created, "File '$fileName' uploaded via PUT.")
                } catch (e: Exception) {
                    targetFileDoc.delete() // Clean up
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        "Error during PUT upload: ${e.localizedMessage}"
                    )
                }
            }
            get("/{fileName}") {
                val rawName = call.parameters["fileName"]
                if (rawName == null) { // should not be possible
                    call.respond(HttpStatusCode.BadRequest, "Filename missing in path.")
                    return@get
                }
                handleFileDownload(applicationContext, baseDocumentFile, rawName)
            }
            delete("/{fileName}") {
                val fileName = call.parameters["fileName"]!!
                val fileToDeleteDoc = baseDocumentFile!!.findFile(fileName)
                if (fileToDeleteDoc == null || !fileToDeleteDoc.exists()) {
                    call.respondText("Error: File '$fileName' not found.", status = HttpStatusCode.NotFound)
                    return@delete
                }
                if (fileToDeleteDoc.delete()) {
                    call.respondText("File '$fileName' deleted.", status = HttpStatusCode.OK)
                    fileServerService.notifyFilePushed()
                } else {
                    call.respondText("Error: Could not delete file '$fileName'.", status = HttpStatusCode.InternalServerError)
                }

            }
        } // end authenticated block
    } // end routing
}

// --- Serializable data classes for API responses ---
@Serializable
data class FileInfo(
    val name: String,
    val size: Long,
    val formattedSize: String,
    val lastModified: String,
    val type: String,
    val downloadUrl: String
)

@Serializable
data class FileListResponse(val files: List<FileInfo>)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class SuccessResponse(val message: String)