package au.com.setec.rvmaster.ota

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.com.setec.rvmaster.Util
import au.com.setec.rvmaster.ota.appota.RemoteConfigResponse
import au.com.setec.rvmaster.ota.appota.RemoteConfigType
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import au.com.setec.rvmaster.logI
import au.com.setec.rvmaster.logW
import au.com.setec.sysotaconnector.BuildConfig.AUTO_UPDATE_INTERVAL_IN_MINUTE
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class AppUpdateViewModel @Inject constructor(
    private val appUpdateUseCase: AppUpdateUseCase,
    @Named("FIREBASE_REMOTE_CONFIG_KEY") private val firebaseConfigKey: String = "",
) : ViewModel() {

    private val gson = Gson()
    private var downloadJob: Job? = null
    private var currentWorkObserver: Observer<WorkInfo>? = null
    private var currentWorkLiveData: LiveData<WorkInfo>? = null

    var latestAvailableConfig: RemoteConfigResponse? = null
        private set

    private val _uiState = MutableLiveData<AppUpdateUiState>(AppUpdateUiState.Idle)
    val uiState: LiveData<AppUpdateUiState> = _uiState

    private var wasInternetOn: Boolean = false

    /**
     * Starts periodic AppOTA checking using UI-Chained WorkManager (like code snippet).
     * Runs immediately for the first-time check (isDelayed = false), then chains recurring executions every [intervalMinutes] minutes.
     */
    fun startPeriodicCheck(
        context: Context,
        intervalMinutes: Long=AUTO_UPDATE_INTERVAL_IN_MINUTE
    ) {
        logI("startPeriodicCheck: Starting UI-chained WorkManager periodic check (cadence=$intervalMinutes min)...")
        enqueueAndObserveWork(context.applicationContext, isDelayed = false, intervalMinutes = intervalMinutes)
    }

    private fun enqueueAndObserveWork(
        appContext: Context,
        isDelayed: Boolean,
        intervalMinutes: Long
    ) {
        val workDataBuilder = workDataOf(
            AppUpdateWorker.KEY_INTERVAL_MINUTES to intervalMinutes,
            AppUpdateWorker.KEY_FIREBASE_REMOTE_CONFIG_KEY to firebaseConfigKey
        )
        val workRequest = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setInputData(workDataBuilder)
            .addTag(AppUpdateWorker.TAG_APP_OTA)

        if (isDelayed) {
            workRequest.setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
        }

        val request = workRequest.build()
        WorkManager.getInstance(appContext).enqueueUniqueWork(
            AppUpdateWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        logI("[AppUpdateViewModel] Enqueued unique work (${AppUpdateWorker.UNIQUE_WORK_NAME}), isDelayed=$isDelayed, intervalMinutes=$intervalMinutes min (id=${request.id})")
        observeAppUpdate(appContext, request.id, intervalMinutes)
    }

    private fun observeAppUpdate(
        appContext: Context,
        workRequestId: UUID,
        intervalMinutes: Long
    ) {
        // Clean up previous observer if any
        currentWorkObserver?.let { obs ->
            currentWorkLiveData?.removeObserver(obs)
        }

        val liveData = WorkManager.getInstance(appContext).getWorkInfoByIdLiveData(workRequestId)
        currentWorkLiveData = liveData

        val observer = object : Observer<WorkInfo> {
            override fun onChanged(value: WorkInfo) {
                if (value.state.isFinished) {
                    liveData.removeObserver(this)
                    if (currentWorkObserver == this) {
                        currentWorkObserver = null
                        currentWorkLiveData = null
                    }

                    val configJson = value.outputData.getString(AppUpdateWorker.CONFIG_RESPONSE)
                    if (!configJson.isNullOrBlank()) {
                        try {
                            val remoteConfigResponse = gson.fromJson(configJson, RemoteConfigResponse::class.java)
                            latestAvailableConfig = remoteConfigResponse

                            val isCriticalType = remoteConfigResponse.type.equals(RemoteConfigType.CRITICAL.name, ignoreCase = true)
                            val isPromptType = remoteConfigResponse.type.equals(RemoteConfigType.PROMPT.name, ignoreCase = true)
                            if (isCriticalType) {
                                logI("CRITICAL update detected. Directly starting download...")
                                startDownload(remoteConfigResponse, appContext)
                            } else  if (isPromptType) {
                                logI("PROMPT update detected. Emitting PromptConfirmation state...")
                                _uiState.postValue(AppUpdateUiState.PromptConfirmation(remoteConfigResponse))
                            }
                            else {
                                logI("${RemoteConfigType.MAINTENANCE.name} update detected. Nothing to do")
                            }
                        } catch (e: Exception) {
                            logE("Error parsing CONFIG_RESPONSE: ${e.message}", e)
                            _uiState.postValue(AppUpdateUiState.Error("Error parsing update info", e))
                        }
                    } else {
                        val errorMsg = value.outputData.getString(AppUpdateWorker.CONFIG_ERROR_RESPONSE)
                        logD("OTA App Update:$errorMsg")
                        latestAvailableConfig = null
                        _uiState.postValue(AppUpdateUiState.Idle)
                    }

                    // UI-chaining: Schedule the next delayed check every intervalMinutes
                    enqueueAndObserveWork(appContext, isDelayed = true, intervalMinutes = intervalMinutes)
                }
            }
        }

        currentWorkObserver = observer
        liveData.observeForever(observer)
    }

    fun fetchAndCheckConfig(
        context: Context
    ) {
        logI("fetchAndCheckConfig: Triggering immediate check via WorkManager...")
        enqueueAndObserveWork(
            context.applicationContext,
            isDelayed = false,
            intervalMinutes = AUTO_UPDATE_INTERVAL_IN_MINUTE
        )
    }

    fun startDownload(remoteConfig: RemoteConfigResponse, context: Context) {
        if (downloadJob?.isActive == true || _uiState.value is AppUpdateUiState.Downloading) {
            logW("startDownload ignored: Download is already in progress")
            return
        }

        if (!isInternetConnected(context)) {
            logE("startDownload aborted: No internet connection")
            _uiState.value = AppUpdateUiState.Error("No internet connection")
            return
        }

        logI("startDownload: Downloading APK '${remoteConfig.apkFileName}' via storageType='${remoteConfig.storageType}'...")
        _uiState.value = AppUpdateUiState.Downloading(0, true)
        downloadJob = viewModelScope.launch {
            try {
                val result = appUpdateUseCase.downloadAndVerifyApk(
                    config = remoteConfig,
                    context = context,
                    onProgress = { progress, isIndeterminate ->
                        _uiState.postValue(AppUpdateUiState.Downloading(progress, isIndeterminate))
                    }
                )

                result.fold(
                    onSuccess = { localFile ->
                        logI("Download & checksum verification succeeded! File: ${localFile.absolutePath}")
                        val intent = appUpdateUseCase.createInstallIntent(context, localFile)
                        _uiState.value = AppUpdateUiState.ReadyToInstall(intent)
                    },
                    onFailure = { throwable ->
                        if (throwable is ChecksumMismatchException) {
                            logE("Checksum mismatch during update download: ${throwable.message}")
                            _uiState.value = AppUpdateUiState.ChecksumMismatch(remoteConfig, throwable.message ?: "Checksum mismatch")
                        } else {
                            logE("Download failed: ${throwable.message}", throwable)
                            _uiState.value = AppUpdateUiState.Error(throwable.message ?: "Download failed", throwable)
                        }
                    }
                )
            } finally {
                downloadJob = null
            }
        }
    }

    fun retry(context: Context) {
        logI("Retry requested — fetching config freshly and bypassing prompt")
        fetchAndCheckConfig(context = context)
    }


    fun isInternetConnected(context: Context): Boolean {
        return Util.isInternetConnected(context)
    }

    fun checkInternetStateChanged(context: Context, onInternetRestored: () -> Unit) {
        val currentInternetOn = isInternetConnected(context)
        if (!wasInternetOn && currentInternetOn) {
            logI("Wi-Fi / Internet state changed from OFF -> ON. Triggering onInternetRestored...")
            onInternetRestored()
        }
        wasInternetOn = currentInternetOn
    }


    override fun onCleared() {
        super.onCleared()
        currentWorkObserver?.let { obs ->
            currentWorkLiveData?.removeObserver(obs)
        }
        currentWorkObserver = null
        currentWorkLiveData = null
        downloadJob?.cancel()
    }
}
