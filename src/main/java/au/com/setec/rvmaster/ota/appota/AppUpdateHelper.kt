package au.com.setec.rvmaster.ota.appota

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import au.com.setec.rvmaster.logD
import au.com.setec.rvmaster.logE
import au.com.setec.rvmaster.logI
import au.com.setec.rvmaster.logW
import au.com.setec.rvmaster.ota.AppUpdateUiState
import au.com.setec.rvmaster.ota.AppUpdateViewModel
import au.com.setec.sysotaconnector.R

class AppUpdateHelper {

    private var autoUpdateAppDialog: Dialog? = null

    fun createInvalidFileDialog(
        context: Context,
        onRetry: () -> Unit,
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle("App Update")
            .setCancelable(false)
            .setMessage("Invalid checksum, please contact admin")
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .setPositiveButton("Retry") { dialog, _ ->
                dialog.dismiss()
                onRetry()
            }
            .create()
    }

    fun createAppDownloadingDialog(context: Context): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle("App Downloading")
            .setCancelable(false)
            .setView(R.layout.view_upgrade_progressbar)
            .create()
    }

    fun createUpdateDialog(
        context: Context,
        version: String? = null,
        onConfirmUpdate: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        val message = if (!version.isNullOrBlank()) {
            "New version ($version) found. Do you want to update it?"
        } else {
            "New version found. Do you want to update it?"
        }
        return AlertDialog.Builder(context)
            .setTitle("App Update")
            .setMessage(message)
            .setPositiveButton("Yes") { dialog, _ ->
                dialog.dismiss()
                onConfirmUpdate()
            }
            .setNegativeButton("No") { dialog, _ ->
                dialog.dismiss()
                onDismiss?.invoke()
            }
            .setOnCancelListener {
                onDismiss?.invoke()
            }
            .create()
    }

    private fun dismissDialog() {
        try {
            if (autoUpdateAppDialog?.isShowing == true) {
                autoUpdateAppDialog?.dismiss()
            }
        } catch (e: Exception) {
            logW("Failed to dismiss autoUpdateAppDialog: ${e.message}")
        } finally {
            autoUpdateAppDialog = null
        }
    }

    private fun showDialogSafely(activity: ComponentActivity, dialog: Dialog) {
        if (!activity.isFinishing && !activity.isDestroyed) {
            dialog.show()
        }
    }

    /**
     * Attaches AppOTA monitoring and UI handling to [activity].
     */
    fun attach(
        activity: ComponentActivity,
        viewModel: AppUpdateViewModel,
        otaIconView: View? = null,
    ) {
       fun showUpdateDialog(config: RemoteConfigResponse) {
            if (!viewModel.isInternetConnected()) {
                logW("No internet connection; suppressing autoUpdateAppDialog")
                dismissDialog()
                return
            }
            if (autoUpdateAppDialog?.isShowing == true) return
            dismissDialog()
            val dialog = createUpdateDialog(
                context = activity,
                version = config.version,
                onConfirmUpdate = {
                    logI("User confirmed update for code=${config.code}")
                    viewModel.startDownload(config)
                },
                onDismiss = {
                    logD("User dismissed update prompt")
                }
            )
            autoUpdateAppDialog = dialog
            showDialogSafely(activity, dialog)
        }


        viewModel.uiState.observe(activity, Observer { state ->
            when (state) {
                is AppUpdateUiState.Idle -> {
                    logD("State is Idle")
                    dismissDialog()
                }
                is AppUpdateUiState.ConfigEvaluated -> {
                    logD("Config evaluated: updateAvailable=${state.updateAvailable}, currentCode=${state.currentVersionCode}, remoteCode=${state.remoteVersionCode}")
                    if (!state.updateAvailable) {
                        dismissDialog()
                    }
                }
                is AppUpdateUiState.PromptConfirmation -> {
                    logD("Prompting user for update confirmation for code=${state.config.code}")
                    showUpdateDialog(state.config)
                }
                is AppUpdateUiState.Downloading -> {
                    val isProgressDialog = autoUpdateAppDialog?.findViewById<ProgressBar>(R.id.update_progress) != null
                    if (autoUpdateAppDialog?.isShowing != true || !isProgressDialog) {
                        dismissDialog()
                        val dialog = createAppDownloadingDialog(activity)
                        autoUpdateAppDialog = dialog
                        showDialogSafely(activity, dialog)
                    }
                    val progressBar = autoUpdateAppDialog?.findViewById<ProgressBar>(R.id.update_progress)
                    progressBar?.isIndeterminate = state.isIndeterminate
                    if (!state.isIndeterminate) {
                        progressBar?.progress = state.progress
                    }
                }
                is AppUpdateUiState.ReadyToInstall -> {
                    logI("Download complete and verified! Launching install intent...")
                    dismissDialog()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        val installIntent = viewModel.createInstallIntent(state.apkFile, activity)
                        activity.startActivity(installIntent)
                    }
                }
                is AppUpdateUiState.ChecksumMismatch -> {
                    logE("Checksum mismatch! Showing invalid file dialog")
                    dismissDialog()
                    val dialog = createInvalidFileDialog(activity) {
                        logI("User requested retry after checksum mismatch")
                        viewModel.retry()
                    }
                    autoUpdateAppDialog = dialog
                    showDialogSafely(activity, dialog)
                }
                is AppUpdateUiState.Error -> {
                    logE("AppOTA error: ${state.message}", state.throwable)
                    dismissDialog()
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
                AppUpdateUiState.Loading -> {
                    logD("State is Loading")
                }
                else -> {
                    logW("Unhandled  AppUpdateUiState: $state")
                }
            }
        })

        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                viewModel.checkInternetStateChanged {}
                viewModel.syncDateTimeAndCheckUpdate()
            }

            override fun onResume(owner: LifecycleOwner) {
                viewModel.checkInternetStateChanged {
                    logI("[AppUpdateHelper] Wi-Fi / Internet state changed from OFF -> ON in onResume. Triggering syncDateTimeAndCheckUpdate()...")
                    viewModel.syncDateTimeAndCheckUpdate()
                }
            }

            override fun onDestroy(owner: LifecycleOwner) {
                dismissDialog()
                otaIconView?.setOnClickListener(null)
            }
        })
    }
}
