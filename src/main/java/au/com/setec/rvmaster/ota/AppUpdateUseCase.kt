package au.com.setec.rvmaster.ota

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import au.com.setec.rvmaster.calculateChecksum
import au.com.setec.rvmaster.ota.appota.AppUpdateDownloader
import au.com.setec.rvmaster.ota.appota.RemoteConfigResponse
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import au.com.setec.rvmaster.logI
import au.com.setec.rvmaster.logW
import au.com.setec.sysotaconnector.BuildConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.google.gson.Gson
import io.appwrite.exceptions.AppwriteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.resume

class AppUpdateUseCase @Inject constructor(
    private val downloader: AppUpdateDownloader
) {

    private companion object {
        private const val COMMANDER_PACKAGE_PREFIX = BuildConfig.COMMANDER_PACKAGE_PREFIX
        private const val COMMANDER_RECEIVER_CLASS = "$COMMANDER_PACKAGE_PREFIX.SystemCommandReceiver"
        private const val ACTION_REQUEST_MONITOR_APP = "$COMMANDER_PACKAGE_PREFIX.REQUEST_MONITOR_APP"
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val MIME_TYPE_APK = "application/vnd.android.package-archive"
        private const val APK_EXTENSION = ".apk"
        private const val PROVIDER_AUTHORITY_SUFFIX = ".provider"
    }

    fun createInstallIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}$PROVIDER_AUTHORITY_SUFFIX",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, MIME_TYPE_APK)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun sendMonitorCommandToCommander(context: Context) {
        val intent = Intent(ACTION_REQUEST_MONITOR_APP).apply {
            component = ComponentName(COMMANDER_PACKAGE_PREFIX, COMMANDER_RECEIVER_CLASS)
            putExtra(EXTRA_PACKAGE_NAME, context.packageName)
        }
        context.sendBroadcast(intent)
    }

    private suspend fun fetchRemoteConfig(
        configKey: String
    ): String? = withContext(Dispatchers.IO) {
        // 1. Try Firebase Remote Config (minimum fetch interval = 0 to bypass local cache)
        try {
            val firebaseRemoteConfig = FirebaseRemoteConfig.getInstance()
            try {
                val settings = remoteConfigSettings {
                    minimumFetchIntervalInSeconds = 0L
                }
                firebaseRemoteConfig.setConfigSettingsAsync(settings)
            } catch (e: Exception) {
                logW("Could not set minimumFetchIntervalInSeconds to 0", e)
            }

            val fetchedAndActivated = suspendCancellableCoroutine { continuation ->
                firebaseRemoteConfig.fetchAndActivate()
                    .addOnCompleteListener { fetchTask ->
                        if (continuation.isActive) {
                            continuation.resume(fetchTask.isSuccessful)
                        }
                    }
                    .addOnFailureListener {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }
            }

            val json = if (configKey.isNotBlank()) firebaseRemoteConfig.getString(configKey) else ""
            if (json.isNotBlank()) {
                logD("Fetched remote config (key=$configKey, activated=$fetchedAndActivated): $json")
                return@withContext json
            }
        } catch (e: Exception) {
            logW("Firebase Remote Config fetch failed or not available", e)
        }

        null
    }

    suspend fun fetchAndCheckConfig(
        context: Context,
        configKey: String
    ): Result<RemoteConfigResponse> = withContext(Dispatchers.IO) {
        try {
            if (!au.com.setec.rvmaster.Util.isInternetConnected(context)) {
                logW("fetchAndCheckConfig aborted: No internet connection")
                return@withContext Result.failure(IllegalStateException("No internet connection"))
            }

            val jsonString = fetchRemoteConfig(configKey)
            if (jsonString.isNullOrBlank()) {
                return@withContext Result.failure(IllegalStateException("Config file is empty or failed to get connection from firebase server"))
            }

            val gson = Gson()
            val remoteConfig = gson.fromJson(jsonString, RemoteConfigResponse::class.java)

            val currentPackage = context.packageName
            val currentCode = getCurrentVersionCode(context)
            val remoteCode = remoteConfig.code ?: 0

            val targetPackage = remoteConfig.packageName
            val isPackageMatch = targetPackage.isNullOrBlank() || targetPackage.equals(currentPackage, ignoreCase = true)
            val isNewerVersion = remoteCode > currentCode

            logD("Package check: currentPackage=$currentPackage, remotePackage=$targetPackage, isPackageMatch=$isPackageMatch")
            logD("Version check: currentCode=$currentCode, remoteCode=$remoteCode")

            if (!isPackageMatch || !isNewerVersion) {
                logD("App is up to date or package mismatch (currentCode=$currentCode, remoteCode=$remoteCode)")
                return@withContext Result.failure(IllegalStateException("Package name or Version code is not valid"))
            }

            if (remoteConfig.apkFileName.isNullOrBlank()) {
                logW("New version detected (remoteCode=$remoteCode > currentCode=$currentCode), but apkFileName is empty")
                return@withContext Result.failure(IllegalStateException("Apk file name is empty"))
            }

            logI("New update available! Current code: $currentCode, Remote code: $remoteCode")
            Result.success(remoteConfig)
        } catch (e: Exception) {
            logE("Error during $configKey fetchAndCheckConfig: ${e.message}", e)
            Result.failure(e)
        }
    }



    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            logW("Failed to retrieve current version code", e)
            0
        }
    }

    suspend fun downloadAndVerifyApk(
        config: RemoteConfigResponse,
        context: Context,
        onProgress: (progress: Int, isIndeterminate: Boolean) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val apkFileName = config.apkFileName ?: ""
        val md5CheckSum = config.checksum

        sendMonitorCommandToCommander(context)
        cleanOldApkCache(context)

        val candidateFileIds = buildSet {
            if (apkFileName.isNotBlank()) {
                add(apkFileName)
                val simpleName = File(apkFileName).name
                if (simpleName.isNotBlank()) add(simpleName)
            }
        }.toList()

        val localFile = createTempApkFile(context, apkFileName)

        logD("Starting download - storageType=${config.storageType}, candidateFileIds=$candidateFileIds, tempFile=${localFile.absolutePath}")

        try {
            val (downloaded, lastException) = downloader.downloadApk(
                storageType = config.storageType,
                candidateFileIds = candidateFileIds,
                config = config,
                context = context,
                localFile = localFile,
                onProgress = onProgress
            )

            if (!downloaded) {
                return@withContext Result.failure(
                    lastException ?: AppwriteException("Requested file could not be found for storageType=${config.storageType}")
                )
            }

            if (!localFile.exists()) {
                return@withContext Result.failure(IllegalStateException("Downloaded file does not exist"))
            }

            if (verifyFileChecksum(localFile, md5CheckSum)) {
                logD("Checksum matches! APK ready at ${localFile.absolutePath}")
                Result.success(localFile)
            } else {
                logW("Checksum mismatch! Downloaded file checksum does not match expected checksum ($md5CheckSum)")
                Result.failure(ChecksumMismatchException("Checksum mismatch"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun cleanOldApkCache(context: Context) {
        try {
            context.cacheDir.listFiles()?.filter { it.name.endsWith(APK_EXTENSION) }?.forEach { oldFile ->
                oldFile.delete()
            }
        } catch (_: Exception) {}
    }

    private fun createTempApkFile(context: Context, apkFileName: String): File {
        val safePrefix = File(apkFileName).nameWithoutExtension
            .replace(Regex("[^a-zA-Z0-9_-]"), "_")
            .let { if (it.length < 3) "ota_$it" else it }
        return File.createTempFile(safePrefix, APK_EXTENSION, context.cacheDir)
    }
}

fun verifyFileChecksum(file: File, expectedChecksum: String?): Boolean {
    if (expectedChecksum.isNullOrBlank()) {
        logW("No expected checksum provided — skipping checksum verification")
        return true
    }

    val trimmedExpected = expectedChecksum.trim()
    val algorithm = when (trimmedExpected.length) {
        64 -> "SHA-256"
        40 -> "SHA-1"
        32 -> "MD5"
        else -> "MD5"
    }

    val calculated = file.calculateChecksum(algorithm)
    val isMatch = calculated.equals(trimmedExpected, ignoreCase = true)
    logD("Checksum verification ($algorithm) - Calculated: $calculated, Expected: $trimmedExpected, Match: $isMatch")
    return isMatch
}

