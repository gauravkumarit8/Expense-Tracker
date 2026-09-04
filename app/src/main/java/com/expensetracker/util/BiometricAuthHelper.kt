package com.autoexpensetracker.util

import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    fun authenticate(
        activity: FragmentActivity,
        title: String = "Unlock Expense Tracker",
        subtitle: String = "Use your fingerprint, face, or device PIN",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onError(errString.toString())
                }

                override fun onAuthenticationFailed() {
                    // A single failed attempt (e.g. wrong fingerprint) — the
                    // prompt stays open for another try, nothing to do here.
                }
            }
        )

        // Combining BIOMETRIC_WEAK with DEVICE_CREDENTIAL gives biometric as
        // the primary method with device PIN/pattern/password as a built-in
        // fallback. setNegativeButtonText() must NOT be called when
        // DEVICE_CREDENTIAL is included — the system supplies its own
        // "use PIN" affordance automatically, and calling it anyway throws.
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()

        prompt.authenticate(promptInfo)
    }
}