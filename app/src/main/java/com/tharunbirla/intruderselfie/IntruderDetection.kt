package com.tharunbirla.intruderselfie

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object IntruderDetection {
    fun start(context: Context) {
        val intent = Intent(context, IntruderDetectionService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, IntruderDetectionService::class.java)
        context.stopService(intent)
    }
}