package com.tharunbirla.intruderselfie

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.tharunbirla.intruderselfie.ui.theme.IntruderSelfieTheme
import androidx.activity.compose.LocalActivity

import android.app.Activity // This import is crucial for shouldShowRequestPermissionRationale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupGuideScreen(onSetupComplete: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current

    // State to track if permissions are granted.
    // We'll update this whenever a permission result comes back.
    var hasCameraPermission by remember { mutableStateOf(checkPermission(context, Manifest.permission.CAMERA)) }
    var hasStoragePermission by remember {
        mutableStateOf(
            // For Android 10 (API 29) and above, READ_EXTERNAL_STORAGE might not be strictly needed for MediaStore access
            // if your app only deals with its own media files saved via MediaStore.
            // However, if you need to access *any* media on storage or older API levels, it's crucial.
            // For simplicity, we check WRITE_EXTERNAL_STORAGE for older devices and READ_EXTERNAL_STORAGE for all.
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) { // <= Android 9 (Pie)
                checkPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else { // Android 10 (Q) and above
                checkPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        )
    }
    var hasNotificationPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true // Permission not required on older Android versions
            }
        )
    }

    // Update states when the screen recomposes (e.g., after returning from settings)
    LaunchedEffect(Unit) {
        snapshotFlow {
            Triple(
                checkPermission(context, Manifest.permission.CAMERA),
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    checkPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                } else {
                    checkPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    checkPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    true
                }
            )
        }.collect { (camera, storage, notifications) ->
            hasCameraPermission = camera
            hasStoragePermission = storage
            hasNotificationPermission = notifications
        }
    }


    // Permission launchers
    val requestCameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasCameraPermission = isGranted
    }

    val requestStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasStoragePermission = isGranted
    }

    val requestNotificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        hasNotificationPermission = isGranted
    }

    // Check if all necessary permissions are granted
    val allPermissionsGranted = hasCameraPermission && hasStoragePermission && hasNotificationPermission

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Setup Guide") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // About the App Section
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = "App Info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Welcome to Intruder Selfie!",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Your personal security companion. This app helps you detect unauthorized access to your device by secretly capturing a photo of anyone who tries to unlock it unsuccessfully.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Key Features:",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(4.dp)) // Corrected Spacer usage
                    FeatureBullet("🤫 Capture secret photos on failed unlock attempts.")
                    FeatureBullet("📸 View captured photos directly in the app's gallery.")
                    FeatureBullet("🗑️ Easily delete unwanted intruder photos.")
                    FeatureBullet("✨ Modern and user-friendly interface.")
                    Spacer(modifier = Modifier.height(24.dp))
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            // Permission Request Flow
            item {
                Text(
                    text = "Let's set up your app!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            item {
                PermissionCard(
                    icon = Icons.Default.CameraAlt,
                    title = "Camera Access",
                    description = "Required to take photos of intruders. Without this, the app cannot function.",
                    isGranted = hasCameraPermission,
                    onGrantClick = {
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    activity = activity // Pass the activity to the composable
                )
            }

            // Storage permission for older Android or general media access
            item {
                val storagePermission = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    Manifest.permission.WRITE_EXTERNAL_STORAGE // For API 28 and below
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE // For API 29 and above, usually needed for querying MediaStore
                }
                PermissionCard(
                    icon = Icons.Default.PhotoLibrary,
                    title = "Photo Storage Access",
                    description = "Required to save and display captured photos on your device.",
                    isGranted = hasStoragePermission,
                    onGrantClick = {
                        requestStoragePermissionLauncher.launch(storagePermission)
                    },
                    activity = activity // Pass the activity to the composable
                )
            }

            // Notification permission for Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                item {
                    PermissionCard(
                        icon = Icons.Default.Notifications,
                        title = "Notification Permission",
                        description = "Allows the app to show important notifications, like when an intruder photo is captured.",
                        isGranted = hasNotificationPermission,
                        onGrantClick = {
                            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        },
                        activity = activity // Pass the activity to the composable
                    )
                }
            }

            // Completion / Proceed Button
            item {
                Spacer(modifier = Modifier.height(32.dp))
                AnimatedVisibility(
                    visible = allPermissionsGranted,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.DoneAll,
                            contentDescription = "All Done",
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(80.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "All set! You're ready to go.",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = onSetupComplete,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Text("Proceed to App", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !allPermissionsGranted,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Text(
                        text = "Please grant all necessary permissions to continue.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FeatureBullet(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = "Feature",
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun PermissionCard(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean,
    onGrantClick: () -> Unit,
    activity: Activity? // Added Activity parameter here
) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (isGranted) {
                    Text(
                        text = "Granted",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Button(
                        onClick = {
                            // Determine the permission string based on the card's title
                            val permissionToRequest = when (title) {
                                "Camera Access" -> Manifest.permission.CAMERA
                                "Photo Storage Access" -> if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                                "Notification Permission" -> Manifest.permission.POST_NOTIFICATIONS
                                else -> "" // Should not happen
                            }

                            // Check if permission was permanently denied
                            // Ensure 'activity' is not null before calling shouldShowRequestPermissionRationale
                            if (permissionToRequest.isNotEmpty() && activity != null &&
                                ContextCompat.checkSelfPermission(context, permissionToRequest) == PackageManager.PERMISSION_DENIED &&
                                !activity.shouldShowRequestPermissionRationale(permissionToRequest) // Now correctly using 'activity'
                            ) {
                                // Direct user to app settings if permission is permanently denied
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } else {
                                onGrantClick()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
        }
    }
}

/**
 * Helper function to check if a permission is granted.
 */
fun checkPermission(context: Context, permission: String): Boolean {
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

@Preview(showBackground = true)
@Composable
fun PreviewSetupGuideScreen() {
    IntruderSelfieTheme {
        // For preview, we pass null for activity as it's not a real activity context
        SetupGuideScreen(onSetupComplete = {})
    }
}