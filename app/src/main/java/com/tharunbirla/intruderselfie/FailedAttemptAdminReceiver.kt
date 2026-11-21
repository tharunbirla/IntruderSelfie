package com.tharunbirla.intruderselfie

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives failed PIN/password/pattern callbacks after the owner explicitly
 * activates this app as a device administrator.
 */
class FailedAttemptAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)

        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true)) return

        // Monotonic ID keeps separate failures separate, even within the same second.
        val eventId = SystemClock.elapsedRealtimeNanos()
        val captureIntent = Intent(context, IntruderDetectionService::class.java).apply {
            action = IntruderDetectionService.ACTION_CAPTURE_FAILED_ATTEMPT
            putExtra(IntruderDetectionService.EXTRA_EVENT_ID, eventId)
        }

        try {
            // The camera foreground service should already be running, having been
            // started while the app was visible. Android may reject a cold camera-FGS
            // start from this background callback on modern Android versions.
            ContextCompat.startForegroundService(context, captureIntent)
            Log.i("FailedAttemptAdmin", "Queued failed attempt $eventId")
        } catch (e: Exception) {
            Log.e("FailedAttemptAdmin", "Unable to queue photo capture", e)
        }
    }

    companion object {
        fun component(context: Context) =
            ComponentName(context, FailedAttemptAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val manager = context.getSystemService(DevicePolicyManager::class.java)
            return manager.isAdminActive(component(context))
        }
    }
}
