package com.tharunbirla.intruderselfie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
        val isAppEnabled = prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true)
        if (!isAppEnabled) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ applies while-in-use restrictions to camera foreground
            // services launched from the background. Android 15+ explicitly
            // blocks camera FGS launches from BOOT_COMPLETED receivers.
            Log.i("BootCompletedReceiver", "Open Intruder Selfie after reboot to resume camera monitoring.")
            return
        }

        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                IntruderDetection.start(context)
            } catch (e: Exception) {
                Log.e("BootCompletedReceiver", "Unable to restart camera service after boot", e)
            }
        }
    }
}
