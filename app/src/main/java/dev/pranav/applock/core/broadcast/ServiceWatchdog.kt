package dev.pranav.applock.core.broadcast

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import dev.pranav.applock.core.utils.appLockRepository

/**
 * Checks every ~15 minutes that the lock service is still running and restarts it if not.
 * Uses an inexact repeating alarm, so Android batches the wake-up with other work (negligible
 * battery cost). It doesn't survive a Force stop; nothing can, until the app is opened again.
 */
object ServiceWatchdog {
    private const val TAG = "ServiceWatchdog"
    private const val REQUEST_CODE = 4711

    private fun pendingIntent(context: Context, flags: Int): PendingIntent? =
        PendingIntent.getBroadcast(
            context, REQUEST_CODE,
            Intent(context, WatchdogReceiver::class.java),
            flags or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Safe to call often: does nothing if the alarm is already scheduled. */
    fun schedule(context: Context) {
        try {
            if (pendingIntent(context, PendingIntent.FLAG_NO_CREATE) != null) return
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                pendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT)!!,
            )
            Log.d(TAG, "Watchdog scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't schedule watchdog", e)
        }
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val repository = context.appLockRepository()
            if (repository.isProtectEnabled()) {
                AppLockServiceStarter.startAppropriateServices(context, repository)
            }
        } catch (e: Exception) {
            Log.e("ServiceWatchdog", "Watchdog check failed", e)
        }
    }
}
