package com.expensetracker.update

import android.app.Activity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

/**
 * Checks whether a newer version is available on Play Store and, if so,
 * launches Google's own update flow. No backend of ours involved — this
 * talks directly to Play Store's own version data.
 *
 * Uses FLEXIBLE update type (dismissible in-app banner, user keeps using
 * the current version while it downloads in the background) rather than
 * IMMEDIATE (full-screen blocking) by default. IMMEDIATE is reserved for
 * critical fixes and would need to be triggered deliberately for a
 * specific release — see REQUIREMENTS.md ยง2.17.
 */
class AppUpdateHelper(activity: Activity) {

    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity)

    fun checkForUpdate(
        launcher: ActivityResultLauncher<IntentSenderRequest>,
        onUpdateAvailable: () -> Unit = {}
    ) {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            val available = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            val flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)

            if (available && flexibleAllowed) {
                onUpdateAvailable()
                // app-update-ktx's ActivityResultLauncher-based overload —
                // no manual IntentSender plumbing needed, unlike the older
                // startActivityForResult-era API this was first (incorrectly)
                // written against.
                manager.startUpdateFlowForResult(
                    info,
                    launcher,
                    AppUpdateOptions.newBuilder(AppUpdateType.FLEXIBLE).build()
                )
            }
        }
    }

    /** Call from onResume — completes a FLEXIBLE update that finished
     *  downloading while the app was backgrounded. */
    fun completeUpdateIfDownloaded() {
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                manager.completeUpdate()
            }
        }
    }
}