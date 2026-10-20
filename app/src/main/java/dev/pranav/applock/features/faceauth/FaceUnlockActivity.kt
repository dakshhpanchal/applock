package dev.pranav.applock.features.faceauth

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.services.AppLockManager
import kotlinx.coroutines.delay
import dev.pranav.applock.ui.theme.AppLockTheme

/**
 * Full-screen camera face check for a locked app. On success it unlocks the app the same way
 * the fingerprint path does; "Use PIN" (or back) closes it so the normal lock screen returns.
 */
class FaceUnlockActivity : FragmentActivity() {

    private var lockedPackage: String? = null
    private var finishedHandled = false
    private var unlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        val templates = FaceTemplateStore(this).load()
        if (lockedPackage == null || templates.isEmpty() || !FaceUnlock.hasCameraPermission(this)) {
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
            finish()
            return
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLockManager.reportBiometricAuthStarted()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = usePin()
        })

        setContent {
            AppLockTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    FaceUnlockScreen(templates, onSuccess = ::onSuccess, onUsePin = ::usePin)
                }
            }
        }
    }

    private fun onSuccess() {
        if (finishedHandled) return
        finishedHandled = true
        unlocked = true
        lockedPackage?.let { AppLockManager.temporarilyUnlockAppWithBiometrics(it) }
        AppLockManager.isLockScreenShown.set(false)
        AppLockManager.reportBiometricAuthFinished()
        finish()
    }

    private fun usePin() {
        if (finishedHandled) return
        finishedHandled = true
        lockedPackage?.let(FaceUnlock::skipNextAutoStart)
        AppLockManager.reportBiometricAuthFinished()
        // The overlay was removed when face unlock started; clearing this lets the service
        // show the PIN screen again when the locked app comes back to the front.
        AppLockManager.isLockScreenShown.set(false)
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Left via Home/Recents: give up quietly; the lock screen will show again next time.
        if (!isChangingConfigurations && !finishedHandled) usePin()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Safety net: any exit without an unlock must let the PIN screen come back.
        if (!unlocked) {
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
        }
    }

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
    }
}

@Composable
private fun FaceUnlockScreen(
    templates: List<FloatArray>,
    onSuccess: () -> Unit,
    onUsePin: () -> Unit,
) {
    val verifier = remember { FaceVerifier(templates) }
    var message by remember { mutableStateOf("") }
    var timedOut by remember { mutableStateOf(false) }
    var scanState by remember { mutableStateOf(FaceScanState.Scanning) }
    var attempt by remember { mutableStateOf(0) }

    // If the camera never produces a verdict (no frames), give up instead of waiting forever.
    LaunchedEffect(attempt) {
        delay(12_000)
        if (scanState == FaceScanState.Scanning) {
            timedOut = true
            scanState = FaceScanState.Failed
            message = "Face not recognised"
        }
    }

    // Let the success animation play before closing.
    LaunchedEffect(scanState) {
        if (scanState == FaceScanState.Success) {
            delay(550)
            onSuccess()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Face unlock", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        FaceScanIndicator(scanState)
        Spacer(Modifier.height(24.dp))

        // No preview here: the camera only verifies in the background.
        FaceCameraView(
            showPreview = false,
            onError = onUsePin, // camera or model unavailable: fall back to the PIN screen
            onResult = { result, ts ->
                if (timedOut || scanState == FaceScanState.Success) return@FaceCameraView
                when (val s = verifier.onFrame(result, ts)) {
                    is FaceVerifier.Status.Success -> scanState = FaceScanState.Success
                    FaceVerifier.Status.TimedOut -> {
                        timedOut = true
                        scanState = FaceScanState.Failed
                        message = "Face not recognised"
                    }
                    is FaceVerifier.Status.InProgress -> Unit // no on-screen hints
                }
            },
        )

        Spacer(Modifier.height(24.dp))
        if (message.isNotEmpty()) {
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(32.dp))

        if (timedOut) {
            Button(onClick = {
                verifier.reset()
                timedOut = false
                scanState = FaceScanState.Scanning
                attempt++
                message = ""
            }) { Text("Try again") }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onUsePin) { Text("Use PIN instead") }
    }
}
