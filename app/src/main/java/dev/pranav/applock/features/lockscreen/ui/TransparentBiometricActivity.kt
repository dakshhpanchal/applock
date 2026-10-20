package dev.pranav.applock.features.lockscreen.ui

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.R
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

class TransparentBiometricActivity: FragmentActivity() {
    private val TAG = "TransparentBiometric"
    private var lockedPackageName: String? = null
    private val hideOverlay = Runnable { AppLockManager.hideLockRequester?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockedPackageName = intent.getStringExtra("locked_package")

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dev.pranav.applock.ui.theme.lockBackgroundColor(this)))
        setContent {
            AppLockTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainer
                ) {}
            }
        }

        AppLockManager.reportBiometricAuthStarted()
        // Take the PIN overlay down only once this screen has had time to draw.
        window.decorView.postDelayed(hideOverlay, 300)

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this, executor,
            object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    AppLockManager.reportBiometricAuthFinished()
                    window.decorView.removeCallbacks(hideOverlay) // the PIN overlay must stay up
                    // The overlay was removed when the prompt opened; let the service show it again.
                    AppLockManager.isLockScreenShown.set(false)
                    lockedPackageName?.let(AppLockManager::clearAppUnlockState)
                    // Cover the app with the PIN screen before this closes (no glimpse of it).
                    val pkg = lockedPackageName
                    val requester = AppLockManager.showLockRequester
                    if (pkg != null && requester != null) {
                        requester(pkg)
                        window.decorView.postDelayed({ finish() }, 250)
                    } else {
                        finish() // Close transparent activity if failed/canceled
                    }
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    AppLockManager.reportBiometricAuthFinished()
                    AppLockManager.isLockScreenShown.set(false)
                    lockedPackageName?.let {
                        AppLockManager.temporarilyUnlockAppWithBiometrics(it)
                    }
                    window.decorView.removeCallbacks(hideOverlay)
                    AppLockManager.hideLockRequester?.invoke()


                    // The Accessibility service will detect the unlock state
                    // and close the Service View automatically
                    finish()
                }
            })

        val appNameForPrompt = getString(R.string.this_app)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_app_title, appNameForPrompt))
            .setSubtitle(getString(R.string.confirm_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.use_pin_button))
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                        BiometricManager.Authenticators.BIOMETRIC_STRONG
            )
            .setConfirmationRequired(false)
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Biometric failed to start", e)
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) {
            AppLockManager.reportBiometricAuthFinished()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: if we were closed without an unlock, never leave the app uncovered.
        val unlocked = lockedPackageName?.let(AppLockManager::isAppTemporarilyUnlocked) == true
        if (!unlocked) {
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
        }
    }
}
