package au.com.setec.rvmaster.ota.appota

import android.app.AlertDialog
import android.content.Context

import au.com.setec.sysotaconnector.R

class AppUpdateDialogHelper {

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
        onConfirmUpdate: () -> Unit,
        onDismiss: (() -> Unit)? = null,
    ): AlertDialog {
        return AlertDialog.Builder(context)
            .setTitle("App Update")
            .setMessage("New version found. Do you want to update it?")
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
}
