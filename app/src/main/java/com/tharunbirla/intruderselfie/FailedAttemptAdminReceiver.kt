package com.tharunbirla.intruderselfie

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receives failed PIN/password/pattern callbacks after the owner explicitly
 * activates this app as a device administrator.
 */
class FailedAttemptAdminReceiver : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true)) return

        // Android invokes this callback for failed device credentials. The
        // requirement is one saved image per callback, with no unlock callback.
        val eventId = SystemClock.elapsedRealtimeNanos()
        val captureIntent = Intent(context, IntruderDetectionService::class.java).apply {
            action = IntruderDetectionService.ACTION_CAPTURE_FAILED_ATTEMPT
            putExtra("failed_attempt_event_id", eventId)
        }
        try {
            ContextCompat.startForegroundService(context, captureIntent)
            Log.i("FailedAttemptAdmin", "Queued failed attempt $eventId")
        } catch (e: Exception) {
            Log.e("FailedAttemptAdmin", "Unable to start capture service", e)
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
