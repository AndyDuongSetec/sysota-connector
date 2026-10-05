package au.com.setec.rvmaster.ota.appota

import android.content.Context
import android.os.Build
import android.widget.ProgressBar
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logW
import au.com.setec.sysotaconnector.BuildConfig
import io.appwrite.services.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Named
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

class AppUpdateDownloader @Inject constructor(
    private val storage: Storage,
    @param:Named("APPWRITE_ENDPOINT") val appwriteEndpoint: String = "",
    @param:Named("APPWRITE_PROJECT_ID") val appwriteProjectId: String = "",
) {

    private companion object {
        private const val RESOURCE_NAME_STORAGE_BUCKET = "google_storage_bucket"
        private const val RESOURCE_TYPE_STRING = "string"
    }

    suspend fun downloadApk(
        storageType: String?,
        candidateFileIds: List<String>,
        config: RemoteConfigResponse,
        context: Context,
        localFile: File,
        onProgress: (progress: Int, isIndeterminate: Boolean) -> Unit,
    ): Pair<Boolean, Exception?> = withContext(Dispatchers.IO) {
        val appWriteBucketId = BuildConfig.APPWRITE_STORAGE_BUCKET_ID

        when {
            isDirectUrl(storageType) -> {
                downloadDirectUrl(storageType!!.trim(), localFile, onProgress)
            }

            storageType.equals(RemoteConfigStorageType.FIREBASE.name, ignoreCase = true) -> {
                downloadFromFirebase(candidateFileIds, context, localFile, onProgress)
            }

            storageType.equals(RemoteConfigStorageType.CLOUDFLARE.name, ignoreCase = true) -> {
                downloadFromCloudflare(candidateFileIds, config.r2DevUrl, localFile, onProgress)
            }

            storageType.equals(RemoteConfigStorageType.APPWRITE.name, ignoreCase = true) -> {
                downloadFromAppwrite(candidateFileIds, appWriteBucketId, localFile, onProgress)
            }

            else -> {
                Pair(false, IllegalArgumentException("Invalid storage type: $storageType"))
            }
        }
    }

    private fun isDirectUrl(storageType: String?): Boolean {
        return (storageType?.startsWith("http://", ignoreCase = true) == true) ||
                (storageType?.startsWith("https://", ignoreCase = true) == true) ||
                (storageType?.contains("://") == true)
    }

    private suspend fun downloadDirectUrl(
        url: String,
        localFile: File,
        onProgress: (Int, Boolean) -> Unit,
    ): Pair<Boolean, Exception?> {
        logD("Attempting direct link download via storageType=$url")
        return downloadFromUrlStream(url, localFile, null, onProgress = onProgress)
    }

    private suspend fun downloadFromFirebase(
        candidateFileIds: List<String>,
        context: Context,
        localFile: File,
        onProgress: (Int, Boolean) -> Unit,
    ): Pair<Boolean, Exception?> {
        val bucket = try {
            val resId = context.resources.getIdentifier(RESOURCE_NAME_STORAGE_BUCKET, RESOURCE_TYPE_STRING, context.packageName)
            context.getString(resId)
        } catch (_: Exception) {
            ""
        }

        var lastEx: Exception? = null
        for (candidateId in candidateFileIds) {
            val firebaseUrl = if (candidateId.startsWith("http://", ignoreCase = true) || candidateId.startsWith("https://", ignoreCase = true)) {
                candidateId
            } else {
                val encodedId = URLEncoder.encode(candidateId, "UTF-8").replace("+", "%20")
                "https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encodedId?alt=media"
            }

            logD("Attempting Firebase download URL=$firebaseUrl for candidateId=$candidateId")
            val (success, ex) = downloadFromUrlStream(firebaseUrl, localFile, null, onProgress = onProgress)
            if (success) {
                return Pair(true, null)
            }
            lastEx = ex
        }
        return Pair(false, lastEx)
    }

    private suspend fun downloadFromCloudflare(
        candidateFileIds: List<String>,
        r2DevUrl: String?,
        localFile: File,
        onProgress: (Int, Boolean) -> Unit,
    ): Pair<Boolean, Exception?> {
        var lastEx: Exception? = null
        for (candidateId in candidateFileIds) {
            val cloudflareCandidateUrl = "https://$r2DevUrl/$candidateId"
            logD("Attempting CloudFlare download URL=$cloudflareCandidateUrl for candidateId=$candidateId")
            val (success, ex) = downloadFromUrlStream(cloudflareCandidateUrl, localFile, null, onProgress = onProgress)
            if (success) {
                return Pair(true, null)
            }
            lastEx = ex
        }
        return Pair(false, lastEx)
    }

    private suspend fun downloadFromAppwrite(
        candidateFileIds: List<String>,
        appWriteBucketId: String,
        localFile: File,
        onProgress: (Int, Boolean) -> Unit,
    ): Pair<Boolean, Exception?> {
        var lastEx: Exception? = null
        for (candidateId in candidateFileIds) {
            try {
                logD("Attempting AppWrite download for candidate fileId=$candidateId, bucketId=$appWriteBucketId, endpoint=$appwriteEndpoint")
                var totalSize = 0L
                try {
                    val fileMeta = storage.getFile(appWriteBucketId, candidateId)
                    totalSize = fileMeta.sizeOriginal
                    logD("Retrieved AppWrite file metadata sizeOriginal=$totalSize bytes")
                } catch (e: Exception) {
                    logW("Could not fetch AppWrite file metadata for $candidateId via SDK", e)
                }

                val cleanEndpoint = appwriteEndpoint.trimEnd('/')
                val appwriteUrl = "$cleanEndpoint/storage/buckets/$appWriteBucketId/files/$candidateId/download?project=$appwriteProjectId"

                val (streamSuccess, streamException) = downloadFromUrlStream(
                    urlString = appwriteUrl,
                    localFile = localFile,
                    progressBar = null,
                    expectedTotalSize = totalSize,
                    onProgress = onProgress
                )

                if (streamSuccess) {
                    return Pair(true, null)
                }

                logW(
                    "AppWrite HTTP stream download failed, attempting SDK getFileDownload fallback...",
                    streamException
                )

                val sdkSuccess = try {
                    val response = storage.getFileDownload(bucketId = appWriteBucketId, fileId = candidateId)
                    localFile.writeBytes(response)
                    true
                } catch (e: Exception) {
                    lastEx = e
                    false
                }

                if (sdkSuccess) {
                    onProgress(100, false)
                    return Pair(true, null)
                }
            } catch (e: Exception) {
                logW("AppWrite download failed for candidateId=$candidateId", e)
                lastEx = e
            }
        }
        return Pair(false, lastEx)
    }

    fun transformToDirectDownloadUrl(urlString: String): String {
        if (urlString.isBlank()) return urlString
        var url = urlString.trim()

        // 1. Google Drive transformations: ignore now due to not work

        // 2. Dropbox transformations
        if (url.contains("dropbox.com", ignoreCase = true)) {
            if (url.contains("dl=0")) {
                url = url.replace("dl=0", "dl=1")
            } else if (!url.contains("dl=1") && !url.contains("raw=1")) {
                url = if (url.contains("?")) "$url&dl=1" else "$url?dl=1"
            }
        }

        // 3. OneDrive & SharePoint transformations
        if (url.contains("onedrive.live.com", ignoreCase = true) ||
            url.contains("1drv.ms", ignoreCase = true) ||
            url.contains("sharepoint.com", ignoreCase = true)
        ) {
            if (!url.contains("download=1", ignoreCase = true)) {
                url = if (url.contains("?")) "$url&download=1" else "$url?download=1"
            }
        }

        return url
    }

    suspend fun downloadFromUrlStream(
        urlString: String,
        localFile: File,
        progressBar: ProgressBar? = null,
        headers: Map<String, String> = emptyMap(),
        expectedTotalSize: Long = 0L,
        onProgress: ((progress: Int, isIndeterminate: Boolean) -> Unit)? = null,
    ): Pair<Boolean, Exception?> = withContext(Dispatchers.IO) {
        try {
            var formattedUrlString = transformToDirectDownloadUrl(urlString)

            var connection: HttpURLConnection
            var redirectCount = 0
            val maxRedirects = 10
            val cookieMap = mutableMapOf<String, String>()

            while (true) {
                val url = URL(formattedUrlString)
                val currentConn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    useCaches = false
                    setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                    setRequestProperty("Pragma", "no-cache")
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    if (cookieMap.isNotEmpty()) {
                        val cookieHeader = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
                        setRequestProperty("Cookie", cookieHeader)
                    }
                    headers.forEach { (key, value) -> setRequestProperty(key, value) }
                    connectTimeout = 20000
                    readTimeout = 60000
                    instanceFollowRedirects = false
                }
                connection = currentConn

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && currentConn is HttpsURLConnection) {
                    try {
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, null, null)
                        currentConn.sslSocketFactory = Tls12SocketFactory(sslContext.socketFactory)
                    } catch (e: Exception) {
                        logW("Failed to set custom Tls12SocketFactory", e)
                    }
                }

                val responseCode = currentConn.responseCode
                logD("HTTP $responseCode for $formattedUrlString")

                currentConn.headerFields["Set-Cookie"]?.forEach { cookieHeader ->
                    cookieHeader.split(";").firstOrNull()?.let { cookie ->
                        val parts = cookie.split("=", limit = 2)
                        if (parts.size == 2) {
                            cookieMap[parts[0].trim()] = parts[1].trim()
                        }
                    }
                }

                if (responseCode in listOf(301, 302, 303, 307, 308) && redirectCount < maxRedirects) {
                    val redirectLocation = currentConn.getHeaderField("Location")
                    currentConn.disconnect()
                    if (!redirectLocation.isNullOrBlank()) {
                        redirectCount++
                        val targetUrl = URL(url, redirectLocation).toString()
                        logD("Following redirect ($redirectCount) to: $targetUrl")
                        formattedUrlString = targetUrl
                        continue
                    }
                }
                break
            }

            try {
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    return@withContext Pair(
                        false,
                        java.io.IOException("HTTP error $responseCode: ${connection.responseMessage}")
                    )
                }

                val contentType = connection.contentType ?: ""
                if (contentType.contains("text/html", ignoreCase = true) && !urlString.contains(".html", ignoreCase = true)) {
                    logW("Warning - response content type is $contentType for $formattedUrlString")
                }

                val contentLengthHeader = connection.getHeaderField("Content-Length")?.toLongOrNull()
                val totalSize = if (expectedTotalSize > 0) {
                    expectedTotalSize
                } else {
                    contentLengthHeader ?: connection.contentLength.toLong()
                }

                withContext(Dispatchers.Main) {
                    if (totalSize > 0) {
                        progressBar?.isIndeterminate = false
                        progressBar?.max = 100
                        progressBar?.progress = 0
                        onProgress?.invoke(0, false)
                    } else {
                        progressBar?.isIndeterminate = true
                        onProgress?.invoke(0, true)
                    }
                }

                if (localFile.exists()) {
                    localFile.delete()
                }
                localFile.parentFile?.mkdirs()

                connection.inputStream.use { input ->
                    FileOutputStream(localFile, false).use { output ->
                        val buffer = ByteArray(32768)
                        var bytesRead: Int
                        var downloadedBytes = 0L
                        var lastReportedProgress = -1

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            if (totalSize > 0) {
                                val progress = ((downloadedBytes * 100) / totalSize).toInt().coerceIn(0, 100)
                                if (progress != lastReportedProgress) {
                                    lastReportedProgress = progress
                                    withContext(Dispatchers.Main) {
                                        progressBar?.progress = progress
                                        onProgress?.invoke(progress, false)
                                    }
                                }
                            }
                        }
                        output.flush()
                        try {
                            output.fd.sync()
                        } catch (e: Exception) {
                            logW("File descriptor sync failed", e)
                        }
                    }
                }
                logD("Download stream completed for $formattedUrlString - file size: ${localFile.length()} bytes")
                Pair(true, null)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Pair(false, e)
        }
    }
}
