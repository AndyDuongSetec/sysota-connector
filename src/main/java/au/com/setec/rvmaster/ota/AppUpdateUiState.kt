package au.com.setec.rvmaster.ota

import android.content.Intent
import au.com.setec.rvmaster.ota.appota.RemoteConfigResponse

sealed class AppUpdateUiState {
    object Idle : AppUpdateUiState()
    object Loading : AppUpdateUiState()

    data class ConfigEvaluated(
        val config: RemoteConfigResponse,
        val updateAvailable: Boolean,
        val currentVersionCode: Int,
        val remoteVersionCode: Int,
    ) : AppUpdateUiState()

    data class PromptConfirmation(
        val config: RemoteConfigResponse,
    ) : AppUpdateUiState()

    data class Downloading(
        val progress: Int,
        val isIndeterminate: Boolean,
    ) : AppUpdateUiState()

    data class ReadyToInstall(
        val installIntent: Intent,
    ) : AppUpdateUiState()

    data class ChecksumMismatch(
        val config: RemoteConfigResponse,
        val message: String,
    ) : AppUpdateUiState()

    data class Error(
        val message: String,
        val throwable: Throwable? = null,
    ) : AppUpdateUiState()
}

class ChecksumMismatchException(message: String) : Exception(message)
