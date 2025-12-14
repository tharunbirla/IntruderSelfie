package com.tharunbirla.intruderselfie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    private val TAG = "BootCompletedReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d(TAG, "Received action: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val isAppEnabled = prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true) // Default to true

            if (isAppEnabled) {
                // Check permissions before starting
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Device booted, app enabled, and permissions granted. Starting service.")
                    IntruderDetection.start(context)
                } else {
                    Log.w(TAG, "Device booted and app enabled, but CAMERA permission missing. Service not started.")
                }
            } else {
                Log.d(TAG, "Device booted but app is disabled. Not starting service.")
            }
        }
    }
}