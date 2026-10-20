package dev.pranav.applock.features.faceauth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import dev.pranav.applock.core.utils.appLockRepository
import dev.pranav.applock.services.AppLockManager
import java.util.concurrent.ConcurrentHashMap

/** Entry points the lock screens use to start camera face unlock. */
object FaceUnlock {

    /** Packages whose next automatic face attempt should be skipped (user chose PIN). */
    private val skipNextAuto = ConcurrentHashMap<String, Long>()
    private const val SKIP_VALID_MS = 5_000L // just long enough to avoid an instant relaunch loop

    @Volatile private var lastLaunchAt = 0L

    /** True while a face unlock screen exists, so an automatic start can't stack a second one. */
    @Volatile var screenOpen = false

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    /** Enabled in settings, enrolled, model bundled and camera permission granted. */
    fun isReady(context: Context): Boolean =
        context.appLockRepository().isFaceUnlockEnabled() &&
                hasCameraPermission(context) &&
                FaceTemplateStore(context).hasTemplates() &&
                FaceEmbedder.isModelBundled(context)

    /** Manual start (the biometric button). Returns false if debounced or it couldn't start. */
    fun launch(context: Context, lockedPackage: String, auto: Boolean = false): Boolean {
        val now = SystemClock.elapsedRealtime()
        // Only automatic starts are guarded (a tap on the button must always work), and only
        // against a double trigger or a screen that's already up, so a new lock screen
        // right after an unlock still starts face unlock.
        if (auto && (screenOpen || now - lastLaunchAt < 400)) return false
        lastLaunchAt = now
        AppLockManager.lastLockLaunchAt = now

        AppLockManager.reportBiometricAuthStarted()
        return try {
            context.startActivity(
                Intent(context, FaceUnlockActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra(FaceUnlockActivity.EXTRA_LOCKED_PACKAGE, lockedPackage)
                },
                // Explicit fade, so the system doesn't pick its own sideways slide for the new task.
                android.app.ActivityOptions.makeCustomAnimation(
                    context, dev.pranav.applock.R.anim.lock_fade_in, 0
                ).toBundle()
            )
            true
        } catch (e: Exception) {
            // Couldn't open it: don't leave the app thinking a biometric prompt is up.
            AppLockManager.reportBiometricAuthFinished()
            false
        }
    }

    /**
     * Automatic start when a lock screen appears. Skipped once right after the user
     * dismissed face unlock with "Use PIN", so it doesn't relaunch in a loop.
     * Returns true if face unlock was launched.
     */
    fun launchAuto(context: Context, lockedPackage: String): Boolean {
        val skippedAt = skipNextAuto.remove(lockedPackage)
        if (skippedAt != null && SystemClock.elapsedRealtime() - skippedAt < SKIP_VALID_MS) return false
        return launch(context, lockedPackage, auto = true)
    }

    fun skipNextAutoStart(lockedPackage: String) {
        skipNextAuto[lockedPackage] = SystemClock.elapsedRealtime()
    }
}
