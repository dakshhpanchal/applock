package dev.pranav.applock.features.lockscreen.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.data.repository.PreferencesRepository
import dev.pranav.applock.features.faceauth.FaceUnlock
import dev.pranav.applock.services.AppLockManager
import dev.pranav.applock.ui.theme.AppLockTheme

@SuppressLint("ViewConstructor")
class LockScreenOverlayManager(private val context: Context):
    LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner, OnBackPressedDispatcherOwner {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null

    // Lifecycle setup
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()
    private var isStateRestored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    override val onBackPressedDispatcher = OnBackPressedDispatcher {
        removeOverlay()
    }

    fun showOverlay(
        lockedPackageName: String,
        triggeringPackageName: String,
        onUnlock: () -> Unit,
        onExit: () -> Unit
    ) {
        if (composeView != null) return

        if (!isStateRestored) {
            savedStateRegistryController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            isStateRestored = true
        }

        composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@LockScreenOverlayManager)
            setViewTreeSavedStateRegistryOwner(this@LockScreenOverlayManager)
            setViewTreeViewModelStoreOwner(this@LockScreenOverlayManager)

            setContent {
                CompositionLocalProvider(
                    LocalOnBackPressedDispatcherOwner provides this@LockScreenOverlayManager
                ) {
                    AppLockTheme {
                        Surface(
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.background
                        ) {
                            val appLockRepository = context.appLockRepository()
                            val appName = try {
                                val pm = context.packageManager
                                pm.getApplicationLabel(pm.getApplicationInfo(lockedPackageName, 0))
                                    .toString()
                            } catch (_: Exception) {
                                "App"
                            }

                            val onPinAttemptCallback = { pin: String ->
                                val isValid = appLockRepository.validatePassword(pin)
                                if (isValid) {
                                    onUnlock()
                                    removeOverlay()
                                }
                                isValid
                            }

                            val onPatternAttemptCallback = { pattern: String ->
                                val isValid = appLockRepository.validatePattern(pattern)
                                if (isValid) {
                                    onUnlock()
                                    removeOverlay()
                                }
                                isValid
                            }

                            BackHandler {
                                onExit()
                                removeOverlay()
                            }

                            val lockType = appLockRepository.getLockType()
                            val faceReady = remember { FaceUnlock.isReady(context) }

                            // Start face unlock automatically. The pattern screen already
                            // auto-triggers onBiometricAuth when fingerprint is on, so skip it there.
                            val patternAutoTriggers = lockType == PreferencesRepository.LOCK_TYPE_PATTERN &&
                                    appLockRepository.isBiometricAuthEnabled()
                            if (faceReady && !patternAutoTriggers) {
                                LaunchedEffect(Unit) { startBiometricOrFace(lockedPackageName) }
                            }

                            when (lockType) {
                                PreferencesRepository.LOCK_TYPE_PATTERN -> {
                                    PatternLockScreen(
                                        fromMainActivity = false,
                                        showCloseButton = false,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onPatternAttempt = onPatternAttemptCallback,
                                        onBiometricAuth = { startBiometricOrFace(lockedPackageName) }
                                    )
                                }

                                PreferencesRepository.LOCK_TYPE_PASSWORD -> {
                                    AlphanumericPasswordOverlayScreen(
                                        showBiometricButton = appLockRepository.isBiometricAuthEnabled() || faceReady,
                                        fromMainActivity = false,
                                        showCloseButton = false,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onAuthSuccess = {
                                            onUnlock()
                                            removeOverlay()
                                        },
                                        onBiometricAuth = { startBiometricOrFace(lockedPackageName) },
                                        onPasswordAttempt = onPinAttemptCallback
                                    )
                                }

                                else -> {
                                    PinPasswordOverlayScreen(
                                        showBiometricButton = appLockRepository.isBiometricAuthEnabled() || faceReady,
                                        fromMainActivity = false,
                                        showCloseButton = false,
                                        onClose = {
                                            onExit()
                                            removeOverlay()
                                        },
                                        lockedAppName = appName,
                                        triggeringPackageName = triggeringPackageName,
                                        onAuthSuccess = {
                                            onUnlock()
                                            removeOverlay()
                                        },
                                        onBiometricAuth = { startBiometricOrFace(lockedPackageName) },
                                        onPinAttempt = onPinAttemptCallback
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Window Layout Parameters
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_SECURE or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            // Respect brightness setting
            if (context.appLockRepository().shouldUseMaxBrightness()) {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            }
        }

        // Opaque from the very first frame: Compose needs a few more frames to draw its own
        // content, and until then the locked app would show through the transparent window.
        composeView?.setBackgroundColor(coverColor())

        composeView?.isFocusableInTouchMode = true
        composeView?.requestFocus()

        //// Block Back Button
        //composeView?.setOnKeyListener { _, keyCode, event ->
        //    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
        //        val intent = Intent(Intent.ACTION_MAIN).apply {
        //            addCategory(Intent.CATEGORY_HOME)
        //            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        //        }
        //        context.startActivity(intent)
        //
        //        removeOverlay()
        //        return@setOnKeyListener true
        //    }
        //    false
        //}

        try {
            windowManager.addView(composeView, params)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Plain background that matches the lock screen, so the app is hidden from the first frame. */
    private fun coverColor(): Int = dev.pranav.applock.ui.theme.lockBackgroundColor(context)

    /** Face unlock when it's set up, otherwise the system fingerprint prompt. */
    private fun startBiometricOrFace(lockedPackageName: String) {
        if (FaceUnlock.isReady(context)) {
            // The face screen takes this overlay down once it is on screen.
            if (FaceUnlock.launchAuto(context, lockedPackageName) && AppLockManager.hideLockRequester == null) {
                removeOverlay()
            }
            return
        }
        val intent = Intent(context, TransparentBiometricActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            putExtra("locked_package", lockedPackageName)
        }
        AppLockManager.reportBiometricAuthStarted()
        try {
            context.startActivity(intent)
            // The biometric screen takes this overlay down once it is on screen.
            if (AppLockManager.hideLockRequester == null) removeOverlay()
        } catch (e: Exception) {
            // Keep the PIN overlay up rather than exposing the app.
            AppLockManager.reportBiometricAuthFinished()
        }
    }

    fun removeOverlay() {
        composeView?.let {
            try {
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
                lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
                windowManager.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            composeView = null
        }
    }
}
