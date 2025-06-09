package com.matanh.transfer

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import io.ktor.http.*
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.PipelineContext
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

const val TAG_KTOR_MODULE = "TransferKtorModule"

// --- Custom Plugins (CurlDetectorPlugin, IpAddressApprovalPlugin) ---
private val IsCurlRequestKey = AttributeKey<Boolean>("IsCurlRequestKey")

val CurlDetectorPlugin = createApplicationPlugin(name = "CurlDetectorPlugin") {
    onCall { call ->
        val userAgent = call.request.headers[HttpHeaders.UserAgent]
        if (userAgent != null && userAgent.contains("curl", ignoreCase = true)) {
            call.attributes.put(IsCurlRequestKey, true)
        }
    }
}

val IpAddressApprovalPlugin = createApplicationPlugin(name = "IpAddressApprovalPlugin") {
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
            val approved = service.requestIpApprovalFromClient(clientIp)
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
private val KEY_SERVICE_PROVIDER = AttributeKey<() -> FileServerService?>("ServiceProviderKey")

// --- Shared File Handling Functions ---
suspend fun PipelineContext<Unit, ApplicationCall>.handleFileDownload(
    context: Context,
    baseDocumentFile: DocumentFile,
    fileNameEncoded: String
) {
    // 1) URL Decode filename
    val fileName = try {
        URLDecoder.decode(fileNameEncoded, "UTF-8")
    } catch (e: Exception) {
        return call.respond(HttpStatusCode.BadRequest, "Invalid file name encoding.")
    }
    // 2) Locate & validate
    val target = baseDocumentFile.findFile(fileName)
    if (target == null || !target.isFile || !target.canRead()) {
        return call.respond(HttpStatusCode.NotFound, "File not found: $fileName")
    }
    // 3) Determine mime & optional length
    val mime = ContentType.parse(target.type ?: ContentType.Application.OctetStream.toString())
    val length = target.length().takeIf { it > 0L }
    // 4) Set headers *before* streaming
    call.response.header(
        HttpHeaders.ContentDisposition,
        ContentDisposition.Attachment
            .withParameter(ContentDisposition.Parameters.FileName, fileName)
            .toString()
    )
    length?.let {
        call.response.header(HttpHeaders.ContentLength, it.toString())
    }

    // 5. Open the Android stream once
    val inputStream = context.contentResolver.openInputStream(target.uri)
        ?: return call.respond(HttpStatusCode.InternalServerError, "Could not open file stream.")

    // 6. Stream it out with a big buffer and proper context switching
    try {
        call.respondOutputStream(mime, HttpStatusCode.OK) {
            withContext(Dispatchers.IO) {
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val read = inputStream.read(buf)
                    if (read < 0) break
                    write(buf, 0, read)  // 'this' is the OutputStream
                }
                // flush any remaining bytes
                flush()
            }
        }
    } catch (e: Exception) {
        Log.e(TAG_KTOR_MODULE, "Error streaming file $fileName", e)
        call.respond(HttpStatusCode.InternalServerError, "Error serving file: ${e.localizedMessage}")
    } finally {
        inputStream.close()
    }
}


suspend fun PipelineContext<Unit, ApplicationCall>.handleFileUpload(
    context: Context,
    baseDocumentFile: DocumentFile,
    originalFileName: String,
    mimeType: String?,
    inputStreamProvider: suspend () -> InputStream,
    notifyService: () -> Unit
): Pair<String?, String?> {

    // 1. Sanitize and ensure unique filename
    val sanitizedFileName = originalFileName.replace(Regex("""(^\\s+|\\s+\$|^\\.\\.|[\\/])"""), "_")
    /*
    ^\s+ - Leading whitespace
    \s+$ - Trailing whitespace
    ^\.\. - ".." at start
    [\\/] - Path separators
     */

    var targetFileDoc = baseDocumentFile.findFile(sanitizedFileName)
    var counter = 1
    var uniqueFileName = sanitizedFileName
    while (targetFileDoc != null && targetFileDoc.exists()) {
        val nameWithoutExt = sanitizedFileName.substringBeforeLast('.', sanitizedFileName)
        val extension = sanitizedFileName.substringAfterLast('.', "")
        uniqueFileName = if (extension.isNotEmpty()) "$nameWithoutExt($counter).$extension" else "$nameWithoutExt($counter)"
        targetFileDoc = baseDocumentFile.findFile(uniqueFileName)
        counter++
    }

    // 3. Determine effective MIME type and create the target file
    val effectiveMimeType = mimeType ?: ContentType.Application.OctetStream.toString()
    val newFileDoc = baseDocumentFile.createFile(effectiveMimeType, uniqueFileName)
    if (newFileDoc == null || !newFileDoc.canWrite()) {
        Log.e(TAG_KTOR_MODULE, "Failed to create document file for upload: $uniqueFileName")
        return null to "Failed to create file."
    }
    // 5) Stream upload with a buffer
    try {
        inputStreamProvider().use { inputStream ->
            context.contentResolver.openOutputStream(newFileDoc.uri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
            } ?: throw Exception("Cannot open output stream for ${newFileDoc.uri}")
        }
        Log.i(TAG_KTOR_MODULE, "File '$uniqueFileName' uploaded successfully.")
        notifyService()
        return uniqueFileName to null
    } catch (e: Exception) {
        newFileDoc.delete() // Clean up
        Log.e(TAG_KTOR_MODULE, "Error during file upload: $uniqueFileName", e)
        return null to e.localizedMessage
    }
}

fun PipelineContext<Unit, ApplicationCall>.handleFileDelete(
    baseDocumentFile: DocumentFile,
    fileName: String,
    notifyService: () -> Unit
): Pair<Boolean, String?> {

    val decodedFileName = try {
        URLDecoder.decode(fileName, "UTF-8")
    } catch (e: Exception) {
        return false to "Invalid file name encoding."
    }
    val fileToDeleteDoc = baseDocumentFile.findFile(decodedFileName)
    if (fileToDeleteDoc == null || !fileToDeleteDoc.exists()) {
        return false to "File not found: $decodedFileName"
    }
    return if (fileToDeleteDoc.delete()) {
        Log.i(TAG_KTOR_MODULE, "File deleted successfully: $decodedFileName")
        notifyService()
        true to null
    } else {
        Log.e(TAG_KTOR_MODULE, "Failed to delete file: $decodedFileName")
        false to "Failed to delete file: $decodedFileName"
    }
}

// --- Ktor Application Module ---
fun Application.transferServerModule(
    context: Context,
    serviceProviderLambda: () -> FileServerService?,
    sharedDirUri: Uri
) {
    val applicationContext = context
    attributes.put(KEY_SERVICE_PROVIDER, serviceProviderLambda)
    val fileServerService = serviceProviderLambda()
    if (fileServerService == null) {
        log.error("FileServerService is null in Ktor module. Server might not function correctly.")
        return
    }
    val baseDocumentFile = DocumentFile.fromTreeUri(applicationContext, sharedDirUri)
    if (baseDocumentFile == null || !baseDocumentFile.isDirectory || !baseDocumentFile.canRead()) {
        log.error("Shared directory URI is not accessible: $sharedDirUri")
        return
    }

    // Install Plugins
    install(CurlDetectorPlugin)
    install(CallLogging)
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
    }
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader("X-File-Name")
        anyHost()
        allowCredentials = true
    }
    install(Authentication) {
        basic("auth-basic") {
            realm = applicationContext.getString(R.string.app_name)
            validate { credentials ->
                if (fileServerService.isPasswordProtectionEnabled()) {
                    if (fileServerService.checkPassword(credentials.password)) {
                        UserIdPrincipal(credentials.name)
                    } else {
                        null
                    }
                } else {
                    UserIdPrincipal(credentials.name)
                }
            }
        }
    }
    install(IpAddressApprovalPlugin)
    install(ContentNegotiation) { json() }

    // Routing
    routing {
        staticResources("/assets", "assets") {
            preCompressed(CompressedFileType.GZIP)
            default("index.html")
        }

        authenticate("auth-basic", optional = !fileServerService.isPasswordProtectionEnabled()) {
            get("/") {
                val isCurl = call.attributes.getOrNull(IsCurlRequestKey) == true
                if (isCurl) {
                    val fileNames = baseDocumentFile.listFiles()
                        .filter { it.isFile }
                        .joinToString("\n") { it.name ?: "unknown_file" }
                    call.respondText(fileNames, ContentType.Text.Plain)
                }
                val resource = call.resolveResource("index.html", "assets")
                if (resource != null) {
                    call.respond(resource)
                } else {
                    call.respond(HttpStatusCode.NotFound, "UI not found (index.html missing in assets).")
                }
            }

            route("/api") {
                get("/ping") {
                    call.respondText("pong")
                }

                get("/files") {
                    try {
                        val filesList = baseDocumentFile.listFiles()
                            .filter { it.isFile && it.canRead() }
                            .mapNotNull { docFile ->
                                val lastModifiedDate = Date(docFile.lastModified())
                                val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).apply {
                                    timeZone = TimeZone.getDefault()
                                }
                                FileInfo(
                                    name = docFile.name ?: "Unknown",
                                    size = docFile.length(),
                                    formattedSize = Utils.formatFileSize(docFile.length()),
                                    lastModified = dateFormat.format(lastModifiedDate),
                                    type = docFile.type ?: "unknown",
                                    downloadUrl = "/api/download/${URLEncoder.encode(docFile.name, "UTF-8")}"
                                )
                            }
                        Log.d(TAG_KTOR_MODULE, "Files list: $filesList")
                        call.respond(FileListResponse(filesList))
                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Error listing files", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Error listing files: ${e.localizedMessage}"))
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
                    var filesUploadedCount = 0
                    val uploadedFileNames = mutableListOf<String>()
                    try {
                        val multipart = call.receiveMultipart()
                        multipart.forEachPart { part ->
                            when (part) {
                                is PartData.FileItem -> {
                                    val originalFileName = part.originalFileName ?: "unknown_upload_${System.currentTimeMillis()}"
                                    Log.d(TAG_KTOR_MODULE, "Receiving file: $originalFileName")
                                    val (fileName, error) = handleFileUpload(
                                        context = applicationContext,
                                        baseDocumentFile = baseDocumentFile,
                                        originalFileName = originalFileName,
                                        mimeType = part.contentType?.toString(),
                                        inputStreamProvider = { part.streamProvider() },
                                        notifyService = { fileServerService.notifyFilePushed() }
                                    )
                                    if (fileName != null) {
                                        uploadedFileNames.add(fileName)
                                        filesUploadedCount++
                                    } else {
                                        Log.e(TAG_KTOR_MODULE, "Upload failed for $originalFileName: $error")
                                    }
                                }
                                is PartData.FormItem -> {
                                    Log.d(TAG_KTOR_MODULE, "Form item: ${part.name} = ${part.value}")
                                }
                                else -> {}
                            }
                            part.dispose()
                        }
                        if (filesUploadedCount > 0) {
                            call.respondText("Successfully uploaded: ${uploadedFileNames.joinToString(", ")}")
                        } else {
                            call.respond(HttpStatusCode.BadRequest, "No files were uploaded or upload failed.")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Exception during file upload", e)
                        call.respond(HttpStatusCode.InternalServerError, "Upload error: ${e.localizedMessage}")
                    }
                }

                post("/delete") {
                    try {
                        val requestBody = call.receiveText()
                        val jsonObject = JSONObject(requestBody)
                        val fileNameToDelete = jsonObject.optString("filename", "")
                        if (fileNameToDelete.isEmpty()) {
                            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Filename not provided."))
                            return@post
                        }
                        val (success, error) = handleFileDelete(
                            baseDocumentFile = baseDocumentFile,
                            fileName = fileNameToDelete,
                            notifyService = { fileServerService.notifyFilePushed() }
                        )
                        if (success) {
                            call.respond(HttpStatusCode.OK, SuccessResponse("File '$fileNameToDelete' deleted."))
                        } else {
                            call.respond(HttpStatusCode.InternalServerError, ErrorResponse(error ?: "Failed to delete file."))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG_KTOR_MODULE, "Error processing delete request", e)
                        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Server error during delete: ${e.localizedMessage}"))
                    }
                }
            }

            // HTTP Interface
            put("/{fileName}") {
                val fileName = call.parameters["fileName"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Filename missing in path for PUT.")
                    return@put
                }
                val (uploadedFileName, error) = handleFileUpload(
                    context = applicationContext,
                    baseDocumentFile = baseDocumentFile,
                    originalFileName = fileName,
                    mimeType = ContentType.Application.OctetStream.toString(),
                    inputStreamProvider = { call.receiveChannel().toInputStream() },
                    notifyService = { fileServerService.notifyFilePushed() }
                )
                if (uploadedFileName != null) {
                    call.respond(HttpStatusCode.Created, "File '$uploadedFileName' uploaded via PUT.")
                } else {
                    call.respond(HttpStatusCode.InternalServerError, "Error during PUT upload: $error")
                }
            }

            get("/{fileName}") {
                val fileNameEncoded = call.parameters["fileName"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Filename missing in path.")
                    return@get
                }
                handleFileDownload(applicationContext, baseDocumentFile, fileNameEncoded)
            }

            delete("/{fileName}") {
                val fileName = call.parameters["fileName"] ?: run {
                    call.respond(HttpStatusCode.BadRequest, "Filename missing in path.")
                    return@delete
                }
                val (success, error) = handleFileDelete(
                    baseDocumentFile = baseDocumentFile,
                    fileName = fileName,
                    notifyService = { fileServerService.notifyFilePushed() }
                )
                if (success) {
                    call.respondText("File '$fileName' deleted.", status = HttpStatusCode.OK)
                } else {
                    call.respondText("Error: ${error ?: "Could not delete file '$fileName'."}", status = HttpStatusCode.InternalServerError)
                }
            }
        }
    }
}

// --- Serializable Data Classes ---
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