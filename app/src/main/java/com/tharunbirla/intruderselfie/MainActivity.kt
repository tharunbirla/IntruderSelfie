package com.tharunbirla.intruderselfie

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.tharunbirla.intruderselfie.ui.theme.IntruderSelfieTheme
import androidx.lifecycle.viewmodel.compose.viewModel // Import for viewModel()

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    // Existing permission launchers (keep them as they are)
    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Camera permission granted.")
                checkAndRequestNotificationPermission()
            } else {
                Log.w(TAG, "Camera permission denied.")
                Toast.makeText(this, "Camera permission denied. App functionality may be limited.", Toast.LENGTH_LONG).show()
            }
        }

    private val requestNotificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d(TAG, "Notification permission granted.")
                Toast.makeText(this, "Notification permission granted!", Toast.LENGTH_SHORT).show()
                // Both permissions granted, the MainScreen will now handle starting/stopping the service
                // based on the toggle's initial state (via ViewModel init) or user interaction.
            } else {
                Log.w(TAG, "Notification permission denied.")
                Toast.makeText(this, "Notification permission denied. Cannot run background service.", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IntruderSelfieTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Inject ViewModel using the viewModel() helper function
                    val viewModel: MainViewModel = viewModel()
                    MainScreen(viewModel = viewModel)
                }
            }
        }
        // Initiate permission checks, which will then allow the UI to control the service
        checkAndRequestCameraPermission()
    }

    private fun checkAndRequestCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED -> {
                Log.d(TAG, "Camera permission already granted.")
                checkAndRequestNotificationPermission()
            }
            shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                Log.d(TAG, "Showing rationale for camera permission.")
                Toast.makeText(this, "The app needs camera access to detect intruders.", Toast.LENGTH_LONG).show()
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> {
                Log.d(TAG, "Requesting camera permission.")
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { // API 33+
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED -> {
                    Log.d(TAG, "Notification permission already granted.")
                    // Permissions granted, UI can now manage service start/stop
                }
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> {
                    Log.d(TAG, "Showing rationale for notification permission.")
                    Toast.makeText(this, "Notifications are required for the app to run in the background.", Toast.LENGTH_LONG).show()
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> {
                    Log.d(TAG, "Requesting notification permission.")
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            Log.d(TAG, "Device is pre-Android 13. Notification permission not explicitly requested at runtime.")
            // For older Android versions, POST_NOTIFICATIONS is not a runtime permission.
            // Assuming it's granted by manifest, UI can now manage service start/stop.
        }
    }
}