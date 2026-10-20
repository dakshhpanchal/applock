package dev.pranav.applock.features.faceauth

import android.content.Intent
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
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.BackendImplementation
import dev.pranav.applock.features.lockscreen.ui.PasswordOverlayActivity
import dev.pranav.applock.services.AppLockManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import dev.pranav.applock.ui.theme.AppLockTheme

/**
 * Full-screen camera face check for a locked app. On success it unlocks the app the same way
 * the fingerprint path does; "Use PIN" (or back) closes it so the normal lock screen returns.
 */
class FaceUnlockActivity : FragmentActivity() {

    private var lockedPackage: String? = null
    private var finishedHandled = false
    private var unlocked = false
    private val hideOverlay = Runnable { AppLockManager.hideLockRequester?.invoke() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockedPackage = intent.getStringExtra(EXTRA_LOCKED_PACKAGE)
        // Only check that templates exist here; decrypting them (Keystore) happens off the main
        // thread once the screen is up, so it doesn't hold back the first frame.
        if (lockedPackage == null || !FaceTemplateStore(this).hasTemplates() || !FaceUnlock.hasCameraPermission(this)) {
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
            finish()
            return
        }

        FaceUnlock.screenOpen = true
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        AppLockManager.reportBiometricAuthStarted()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = usePin()
        })

        // Take the PIN overlay down only once this screen has had time to draw.
        window.decorView.postDelayed(hideOverlay, 300)

        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(dev.pranav.applock.ui.theme.lockBackgroundColor(this)))
        setContent {
            AppLockTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    FaceUnlockScreen(onSuccess = ::onSuccess, onUsePin = ::usePin)
                }
            }
        }
    }

    private fun onSuccess() {
        if (finishedHandled) return
        finishedHandled = true
        unlocked = true
        window.decorView.removeCallbacks(hideOverlay)
        AppLockManager.hideLockRequester?.invoke()
        lockedPackage?.let { AppLockManager.temporarilyUnlockAppWithBiometrics(it) }
        AppLockManager.isLockScreenShown.set(false)
        AppLockManager.reportBiometricAuthFinished()
        finish()
    }

    private fun usePin(showLock: Boolean = true) {
        if (finishedHandled) return
        finishedHandled = true
        window.decorView.removeCallbacks(hideOverlay) // the PIN overlay must stay up
        lockedPackage?.let(FaceUnlock::skipNextAutoStart)
        AppLockManager.reportBiometricAuthFinished()
        // The overlay was removed when face unlock started; clearing this lets the service
        // show the PIN screen again when the locked app comes back to the front.
        AppLockManager.isLockScreenShown.set(false)
        // Choosing the PIN means the app is not unlocked: drop any stale unlock state.
        lockedPackage?.let(AppLockManager::clearAppUnlockState)

        // Put the PIN screen up first and only then close this one, so the app underneath is
        // never visible in between.
        val pkg = lockedPackage
        val requester = AppLockManager.showLockRequester
        val usesAccessibility = appLockRepository().getBackendImplementation() == BackendImplementation.ACCESSIBILITY
        if (showLock && pkg != null && usesAccessibility && requester != null) {
            requester(pkg)
            window.decorView.postDelayed({ finish() }, 250)
        } else if (showLock && pkg != null && !usesAccessibility) {
            // Usage-stats / Shizuku backends: the PIN screen is an activity that closed itself
            // when this one opened. Bring it back right now instead of waiting for the next poll.
            AppLockManager.isLockScreenShown.set(true)
            try {
                startActivity(
                    Intent(this, PasswordOverlayActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                        putExtra("locked_package", pkg)
                        putExtra("triggering_package", pkg)
                    }
                )
            } catch (e: Exception) {
                AppLockManager.isLockScreenShown.set(false)
            }
            window.decorView.postDelayed({ finish() }, 150)
        } else {
            finish()
        }
    }

    override fun onStop() {
        super.onStop()
        // Left via Home/Recents: give up quietly; the lock screen will show again next time.
        if (!isChangingConfigurations && !finishedHandled) usePin(showLock = false)
    }

    override fun onDestroy() {
        super.onDestroy()
        FaceUnlock.screenOpen = false
        // Safety net: any exit without an unlock must let the PIN screen come back.
        if (!unlocked && !finishedHandled) {
            AppLockManager.reportBiometricAuthFinished()
            AppLockManager.isLockScreenShown.set(false)
        }
    }

    companion object {
        const val EXTRA_LOCKED_PACKAGE = "locked_package"
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FaceUnlockScreen(
    onSuccess: () -> Unit,
    onUsePin: () -> Unit,
) {
    val context = LocalContext.current
    var templates by remember { mutableStateOf<List<FloatArray>?>(null) }
    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.Default) { FaceTemplateStore(context).load() }
        if (loaded.isEmpty()) onUsePin() else templates = loaded
    }
    val verifier = remember(templates) { templates?.let { FaceVerifier(it) } }
    var message by remember { mutableStateOf("") }
    var timedOut by remember { mutableStateOf(false) }
    var scanState by remember { mutableStateOf(FaceScanState.Scanning) }
    var attempt by remember { mutableStateOf(0) }
    var recognised by remember { mutableStateOf(false) } // face matched, only the blink is left

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
        Text("Face unlock", style = MaterialTheme.typography.headlineMediumEmphasized, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))

        FaceScanIndicator(scanState)
        Spacer(Modifier.height(24.dp))

        // No preview here: the camera only verifies in the background.
        FaceCameraView(
            showPreview = false,
            embedFaces = !recognised,
            onError = onUsePin, // camera or model unavailable: fall back to the PIN screen
            onResult = { result, ts ->
                if (timedOut || scanState == FaceScanState.Success) return@FaceCameraView
                val verifier = verifier ?: return@FaceCameraView // templates still loading
                when (val s = verifier.onFrame(result, ts)) {
                    is FaceVerifier.Status.Success -> scanState = FaceScanState.Success
                    FaceVerifier.Status.TimedOut -> {
                        timedOut = true
                        scanState = FaceScanState.Failed
                        message = "Face not recognised"
                    }
                    is FaceVerifier.Status.InProgress -> recognised = s.hint == Hint.BLINK // no on-screen hints
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
                verifier?.reset()
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
