package au.com.setec.rvmaster.ota

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import au.com.setec.rvmaster.TAG_APP_OTA
import au.com.setec.rvmaster.Util
import au.com.setec.rvmaster.autodatetime.AutoDateTimeUseCase
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import au.com.setec.rvmaster.logI
import au.com.setec.rvmaster.logW
import au.com.setec.rvmaster.ota.appota.RemoteConfigResponse
import au.com.setec.rvmaster.ota.appota.RemoteConfigType
import au.com.setec.sysotaconnector.BuildConfig.AUTO_UPDATE_INTERVAL_IN_MINUTE
import com.google.gson.Gson
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named

class AppUpdateViewModel @Inject constructor(
    private val context: Context,
    private val appUpdateUseCase: AppUpdateUseCase,
    private val autoDateTimeUseCase: AutoDateTimeUseCase,
    @Named("FIREBASE_REMOTE_CONFIG_KEY") private val firebaseConfigKey: String = "",
) : ViewModel() {

    private val gson = Gson()
    private var downloadJob: Job? = null
    private var workJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var latestAvailableConfig: RemoteConfigResponse? = null
        private set

    private val _uiState = MutableLiveData<AppUpdateUiState>(AppUpdateUiState.Idle)
    val uiState: LiveData<AppUpdateUiState> = _uiState

    private var wasInternetOn: Boolean = false
    private var isRetryRequested: Boolean = false

    init {
        startNetworkMonitoring()
    }

    private fun startNetworkMonitoring() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        if (networkCallback != null) return

        wasInternetOn = isInternetConnected()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                checkAndTriggerInternetRestored()
            }

            override fun onLost(network: Network) {
                val currentConnected = isInternetConnected()
                wasInternetOn = currentConnected
                logD("Network lost. wasInternetOn=$wasInternetOn")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    checkAndTriggerInternetRestored()
                } else {
                    wasInternetOn = false
                }
            }
        }

        networkCallback = callback
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(request, callback)
            }
        } catch (e: Exception) {
            logW("Failed to register network callback: ${e.message}")
        }
    }

    private fun checkAndTriggerInternetRestored() {
        val currentConnected = isInternetConnected()
        if (!wasInternetOn && currentConnected) {
            logI("Wi-Fi / Internet state changed from OFF -> ON. Triggering syncDateTimeAndCheckUpdate()...")
            wasInternetOn = true
            syncDateTimeAndCheckUpdate()
        } else {
            wasInternetOn = currentConnected
        }
    }

    private fun stopNetworkMonitoring() {
        networkCallback?.let { callback ->
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(callback)
            } catch (e: Exception) {
                logW("Failed to unregister network callback: ${e.message}")
            }
        }
        networkCallback = null
    }

    /**
     * Synchronizes system date & time via NTP when internet is available,
     * and schedules periodic AppOTA checking.
     */
    fun syncDateTimeAndCheckUpdate() {
        viewModelScope.launch {
            val connected = isInternetConnected()
            logI("syncDateTimeAndCheckUpdate invoked (isInternetConnected=$connected)")
            if (connected) {
                logI("Internet connected -> Executing AutoDateTimeUseCase...")
                autoDateTimeUseCase()
            } else {
                logW("Internet not connected -> Skipping AutoDateTimeUseCase this time")
            }
            logI("Scheduling AppOTA periodic check...")
            startPeriodicCheck()
        }
    }

    /**
     * Starts periodic AppOTA checking using UI-Chained WorkManager.
     * Runs immediately for the first-time check (isDelayed = false), then chains recurring executions every [intervalMinutes] minutes.
     */
    fun startPeriodicCheck(
        intervalMinutes: Long = AUTO_UPDATE_INTERVAL_IN_MINUTE
    ) {
        logI("startPeriodicCheck: Starting UI-chained WorkManager periodic check (cadence=$intervalMinutes min)...")
        enqueueAndObserveWork(isDelayed = false, intervalMinutes = intervalMinutes)
    }

    private fun enqueueAndObserveWork(
        isDelayed: Boolean,
        intervalMinutes: Long
    ) {
        val workDataBuilder = workDataOf(
            AppUpdateWorker.KEY_INTERVAL_MINUTES to intervalMinutes,
            AppUpdateWorker.KEY_FIREBASE_REMOTE_CONFIG_KEY to firebaseConfigKey
        )
        val workRequest = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setInputData(workDataBuilder)
            .addTag(TAG_APP_OTA)

        if (isDelayed) {
            workRequest.setInitialDelay(intervalMinutes, TimeUnit.MINUTES)
        }

        val request = workRequest.build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            AppUpdateWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )

        logI("[AppUpdateViewModel] Enqueued unique work (${AppUpdateWorker.UNIQUE_WORK_NAME}), isDelayed=$isDelayed, intervalMinutes=$intervalMinutes min (id=${request.id})")
        observeAppUpdate(request.id, intervalMinutes)
    }

    private fun observeAppUpdate(
        workRequestId: UUID,
        intervalMinutes: Long
    ) {
        workJob?.cancel()
        workJob = WorkManager.getInstance(context)
            .getWorkInfoByIdFlow(workRequestId)
            .onEach { workInfo ->
                if (workInfo != null && workInfo.state.isFinished) {
                    workJob?.cancel()
                    handleWorkFinished(workInfo, intervalMinutes)
                }
            }
            .launchIn(viewModelScope)
    }

    private fun handleWorkFinished(
        workInfo: WorkInfo,
        intervalMinutes: Long
    ) {
        val configJson = workInfo.outputData.getString(AppUpdateWorker.CONFIG_RESPONSE)
        if (!configJson.isNullOrBlank()) {
            try {
                val remoteConfigResponse = gson.fromJson(configJson, RemoteConfigResponse::class.java)
                latestAvailableConfig = remoteConfigResponse

                if (isRetryRequested) {
                    isRetryRequested = false
                    logI("Retry mode: Fresh remote config fetched. Directly starting download without prompt...")
                    startDownload(remoteConfigResponse)
                } else {
                    val isCriticalType = remoteConfigResponse.type.equals(RemoteConfigType.CRITICAL.name, ignoreCase = true)
                    val isPromptType = remoteConfigResponse.type.equals(RemoteConfigType.PROMPT.name, ignoreCase = true)
                    if (isCriticalType) {
                        logI("CRITICAL update detected. Directly starting download...")
                        startDownload(remoteConfigResponse)
                    } else if (isPromptType) {
                        logI("PROMPT update detected. Emitting PromptConfirmation state...")
                        _uiState.postValue(AppUpdateUiState.PromptConfirmation(remoteConfigResponse))
                    } else {
                        logI("${RemoteConfigType.MAINTENANCE.name} update detected. Nothing to do")
                    }
                }
            } catch (e: Exception) {
                isRetryRequested = false
                logE("Error parsing CONFIG_RESPONSE: ${e.message}", e)
                _uiState.postValue(AppUpdateUiState.Error("Error parsing update info", e))
            }
        } else {
            isRetryRequested = false
            val errorMsg = workInfo.outputData.getString(AppUpdateWorker.CONFIG_ERROR_RESPONSE)
            logD("OTA App Update: $errorMsg")
            latestAvailableConfig = null
            _uiState.postValue(AppUpdateUiState.Idle)
        }

        // UI-chaining: Schedule the next delayed check every intervalMinutes
        enqueueAndObserveWork(isDelayed = true, intervalMinutes = intervalMinutes)
    }

    fun fetchAndCheckConfig() {
        logI("fetchAndCheckConfig: Triggering immediate check via WorkManager...")
        enqueueAndObserveWork(
            isDelayed = false,
            intervalMinutes = AUTO_UPDATE_INTERVAL_IN_MINUTE
        )
    }

    fun startDownload(remoteConfig: RemoteConfigResponse) {
        if (downloadJob?.isActive == true || _uiState.value is AppUpdateUiState.Downloading) {
            logW("startDownload ignored: Download is already in progress")
            return
        }

        if (!isInternetConnected()) {
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
                    onProgress = { progress, isIndeterminate ->
                        _uiState.postValue(AppUpdateUiState.Downloading(progress, isIndeterminate))
                    }
                )

                result.fold(
                    onSuccess = { localFile ->
                        logI("Download & checksum verification succeeded! File: ${localFile.absolutePath}")
                        _uiState.value = AppUpdateUiState.ReadyToInstall(localFile)
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

    fun createInstallIntent(file: File, ctx: Context = context): Intent {
        return appUpdateUseCase.createInstallIntent(file, ctx)
    }

    fun retry() {
        logI("Retry requested — fetching remote config freshly and bypassing prompt")
        isRetryRequested = true
        fetchAndCheckConfig()
    }

    fun isInternetConnected(): Boolean {
        return Util.isInternetConnected(context.applicationContext)
    }

    override fun onCleared() {
        super.onCleared()
        stopNetworkMonitoring()
        isRetryRequested = false
        workJob?.cancel()
        downloadJob?.cancel()
    }
}
