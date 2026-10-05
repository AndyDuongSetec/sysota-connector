package au.com.setec.rvmaster.ota

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.com.setec.rvmaster.ota.appota.AppUpdateDownloader
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import com.google.gson.Gson
import io.appwrite.Client
import io.appwrite.services.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppUpdateWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "OTAAppUpdateWorker"
        const val CONFIG_RESPONSE = "CONFIG_RESPONSE"
        const val CONFIG_ERROR_RESPONSE = "CONFIG_ERROR_RESPONSE"
        const val KEY_INTERVAL_MINUTES = "KEY_INTERVAL_MINUTES"
        const val KEY_FIREBASE_REMOTE_CONFIG_KEY = "FIREBASE_REMOTE_CONFIG_KEY"
        const val TAG_APP_OTA = "AppOTA"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        logD("[AppUpdateWorker] Executing AppOTA check in doWork()...")
        try {
            val client = Client(appContext)
            val storage = Storage(client)
            val downloader = AppUpdateDownloader(storage = storage)
            val useCase = AppUpdateUseCase(downloader = downloader)

            val configKey = inputData.getString(KEY_FIREBASE_REMOTE_CONFIG_KEY) ?: ""
            val checkResult = useCase.fetchAndCheckConfig(
                appContext,
                configKey
            )

            checkResult.fold(
                onSuccess = { remoteConfig ->
                    val output = workDataOf(CONFIG_RESPONSE to Gson().toJson(remoteConfig))
                    Result.success(output)
                },
                onFailure = { throwable ->
                    val errorMsg = throwable.message ?: "Failed to get connection from firebase server"
                    logD("[AppUpdateWorker] Check result: $errorMsg")
                    val output = workDataOf(CONFIG_ERROR_RESPONSE to errorMsg)
                    Result.failure(output)
                }
            )
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Worker exception"
            logE("[AppUpdateWorker] Exception in doWork: $errorMsg", e)
            val output = workDataOf(CONFIG_ERROR_RESPONSE to errorMsg)
            Result.failure(output)
        }
    }
}
