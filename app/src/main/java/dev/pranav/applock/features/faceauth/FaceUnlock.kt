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
    private const val SKIP_VALID_MS = 60_000L

    @Volatile private var lastLaunchAt = 0L

    fun hasCameraPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED

    /** Enabled in settings, enrolled, model bundled and camera permission granted. */
    fun isReady(context: Context): Boolean =
        context.appLockRepository().isFaceUnlockEnabled() &&
                hasCameraPermission(context) &&
                FaceTemplateStore(context).hasTemplates() &&
                FaceEmbedder.isModelBundled(context)

    /** Manual start (the biometric button). Returns false only if debounced. */
    fun launch(context: Context, lockedPackage: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLaunchAt < 1_500) return false // debounce double triggers
        lastLaunchAt = now

        AppLockManager.reportBiometricAuthStarted()
        context.startActivity(
            Intent(context, FaceUnlockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                putExtra(FaceUnlockActivity.EXTRA_LOCKED_PACKAGE, lockedPackage)
            }
        )
        return true
    }

    /**
     * Automatic start when a lock screen appears. Skipped once right after the user
     * dismissed face unlock with "Use PIN", so it doesn't relaunch in a loop.
     * Returns true if face unlock was launched.
     */
    fun launchAuto(context: Context, lockedPackage: String): Boolean {
        val skippedAt = skipNextAuto.remove(lockedPackage)
        if (skippedAt != null && SystemClock.elapsedRealtime() - skippedAt < SKIP_VALID_MS) return false
        return launch(context, lockedPackage)
    }

    fun skipNextAutoStart(lockedPackage: String) {
        skipNextAuto[lockedPackage] = SystemClock.elapsedRealtime()
    }
}
