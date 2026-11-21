package com.tharunbirla.intruderselfie

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.os.UserHandle
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles failed and successful credential callbacks after explicit device-admin activation.
 * Each failed credential event still queues one local photo. The audible/visual alert is
 * independently armed after the user's configured number of consecutive failures.
 */
class FailedAttemptAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordFailed(context, intent, user)

        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true)) return

        val threshold = prefs.getInt(AppConstants.KEY_ALERT_THRESHOLD, 3).coerceIn(2, 5)
        val shouldStartAlarm = synchronized(prefs) {
            val nextCount = prefs.getInt(AppConstants.KEY_FAILED_STREAK, 0) + 1
            val alreadyTriggered = prefs.getBoolean(AppConstants.KEY_ALARM_TRIGGERED, false)
            val triggerNow = nextCount >= threshold && !alreadyTriggered
            prefs.edit()
                .putInt(AppConstants.KEY_FAILED_STREAK, nextCount)
                .putBoolean(AppConstants.KEY_ALARM_TRIGGERED, alreadyTriggered || triggerNow)
                .commit()
            Log.i(TAG, "Failed credential attempt count=$nextCount threshold=$threshold")
            triggerNow
        }

        // Preserve the original one-photo-per-failed-attempt behavior.
        val eventId = SystemClock.elapsedRealtimeNanos()
        val captureIntent = Intent(context, IntruderDetectionService::class.java).apply {
            action = IntruderDetectionService.ACTION_CAPTURE_FAILED_ATTEMPT
            putExtra(IntruderDetectionService.EXTRA_EVENT_ID, eventId)
        }
        try {
            ContextCompat.startForegroundService(context, captureIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to queue photo capture", e)
        }

        if (shouldStartAlarm) {
            val alarmIntent = Intent(context, IntruderDetectionService::class.java).apply {
                action = IntruderDetectionService.ACTION_START_ALARM
            }
            try {
                ContextCompat.startForegroundService(context, alarmIntent)
                Log.i(TAG, "Security alert started after $threshold consecutive failures")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to start security alert", e)
            }
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        super.onPasswordSucceeded(context, intent, user)

        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(AppConstants.KEY_FAILED_STREAK, 0)
            .putBoolean(AppConstants.KEY_ALARM_TRIGGERED, false)
            .apply()

        // The app's service is already active when an alert is running.
        try {
            context.startService(
                Intent(context, IntruderDetectionService::class.java).apply {
                    action = IntruderDetectionService.ACTION_STOP_ALARM
                }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not stop alert after successful unlock", e)
        }
    }

    companion object {
        private const val TAG = "FailedAttemptAdmin"

        fun component(context: Context) =
            android.content.ComponentName(context, FailedAttemptAdminReceiver::class.java)

        fun isActive(context: Context): Boolean {
            val manager = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
            return manager.isAdminActive(component(context))
        }
    }
}
