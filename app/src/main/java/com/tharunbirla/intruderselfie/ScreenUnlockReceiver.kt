package com.tharunbirla.intruderselfie

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens for:
 * - ACTION_USER_PRESENT  (user unlocked the device)
 * - ACTION_BOOT_COMPLETED (device just finished booting)
 *
 * When either happens it launches IntruderDetectionService as a foreground service.
 */
class ServiceUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("ServiceUnlockReceiver", "Received action: $action")

        when (action) {
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_BOOT_COMPLETED -> {
                val serviceIntent = Intent(context, IntruderDetectionService::class.java)
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}