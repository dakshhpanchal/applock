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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

/**
 * Full-screen camera face check for a locked app. On success it unlocks the app the same way
 * the fingerprint path does; "Use PIN" (or back) closes it so the normal lock screen returns.
 */
class FaceUnlockActivity : FragmentActivity() {

    private var lockedPackage: String? = null
    private var finishedHandled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        val templates = FaceTemplateStore(this).load()
        if (lockedPackage == null || templates.isEmpty() || !FaceUnlock.hasCameraPermission(this)) {
            AppLockManager.reportBiometricAuthFinished()
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
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Left via Home/Recents: give up quietly; the lock screen will show again next time.
        if (!isChangingConfigurations && !finishedHandled) usePin()
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
    var message by remember { mutableStateOf("Look at the camera, then blink") }
    var timedOut by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Face unlock", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))

        FaceCameraView(
            modifier = Modifier.size(260.dp).clip(CircleShape),
            onResult = { result, ts ->
                if (timedOut) return@FaceCameraView
                when (val s = verifier.onFrame(result, ts)) {
                    is FaceVerifier.Status.Success -> {
                        message = "Unlocked"
                        onSuccess()
                    }
                    FaceVerifier.Status.TimedOut -> {
                        timedOut = true
                        message = "Face not recognised"
                    }
                    is FaceVerifier.Status.InProgress ->
                        message = s.hint.toMessage("Look at the camera, then blink")
                }
            },
        )

        Spacer(Modifier.height(24.dp))
        Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))

        if (timedOut) {
            Button(onClick = {
                verifier.reset()
                timedOut = false
                message = "Look at the camera, then blink"
            }) { Text("Try again") }
            Spacer(Modifier.height(8.dp))
        }
        TextButton(onClick = onUsePin) { Text("Use PIN instead") }
    }
}
