package com.tharunbirla.intruderselfie

import android.app.*
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import android.Manifest

/**
 * Foreground service that:
 * 1. Shows a sticky notification
 * 2. Dynamically registers a receiver for ACTION_USER_PRESENT.
 * 3. On unlock, opens the front camera, captures one still picture.
 * 4. Saves it to Pictures/IntruderSelfie (using MediaStore for modern Android).
 */
class IntruderDetectionService : Service() {

    companion object {
        private const val TAG = "IntruderService"
        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "INTRUDER_CHANNEL"
    }

    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var imageReader: ImageReader? = null

    // Dynamic BroadcastReceiver for ACTION_USER_PRESENT
    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                Log.d(TAG, "Device unlocked! Starting camera capture.")
                // Prevent multiple captures if multiple unlocks happen fast
                if (cameraDevice == null) { // Only capture if camera is not already open
                    openFrontCamera()
                } else {
                    Log.d(TAG, "Camera already open, skipping capture.")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created.")
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification()) // required for fg-service

        // Register the dynamic receiver
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        registerReceiver(unlockReceiver, filter)
        Log.d(TAG, "Dynamically registered UnlockBroadcastReceiver.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started or restarted.")
        // The service is now running. It will wait for the unlockReceiver to trigger the camera.
        // If it was started by MainActivity, it's already running.
        // If it was restarted by system (due to START_STICKY), it will re-register the receiver.
        return START_STICKY // Change to START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /* -------------------------------------------------- */
    /* Foreground notification helpers                    */
    /* -------------------------------------------------- */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Intruder Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Intruder Selfie Running")
            .setContentText("Monitoring for screen unlocks...")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /* -------------------------------------------------- */
    /* Camera plumbing                                    */
    /* -------------------------------------------------- */
    private fun openFrontCamera() {
        // Ensure only one background thread
        if (backgroundThread == null || !backgroundThread!!.isAlive) {
            backgroundThread = HandlerThread("CameraBg").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }

        val manager = getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        } ?: run {
            Log.e(TAG, "No front camera found!")
            stopCameraResources() // Only stop camera resources, not the service itself
            return
        }

        try {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d(TAG, "Opening camera: $cameraId")
                manager.openCamera(cameraId, stateCallback, backgroundHandler)
            } else {
                Log.e(TAG, "Camera permission missing for opening camera!")
                stopCameraResources()
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot open camera: ${e.message}", e)
            stopCameraResources()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            Log.d(TAG, "Camera opened: ${camera.id}")
            cameraDevice = camera
            createImageSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            Log.w(TAG, "Camera disconnected: ${camera.id}")
            camera.close()
            cameraDevice = null
            // Do NOT stopSelf() here immediately. Service might restart or await next unlock.
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error for camera ${camera.id}")
            camera.close()
            cameraDevice = null
            stopCameraResources() // Stop camera resources if there's an error
        }
    }

    private fun createImageSession() {
        val camera = cameraDevice ?: run {
            Log.e(TAG, "Camera device is null in createImageSession")
            stopCameraResources()
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(camera.id)
        val largest = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width * it.height } ?: run {
            Log.e(TAG, "No supported JPEG output sizes.")
            stopCameraResources()
            return
        }

        imageReader = ImageReader.newInstance(
            largest.width,
            largest.height,
            ImageFormat.JPEG, // Use ImageFormat from android.graphics
            1
        ).apply {
            setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { img ->
                    val buffer = img.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    savePhoto(bytes)
                } ?: Log.e(TAG, "ImageReader acquired null image.")
            }, backgroundHandler)
        }

        val outputs = listOf(imageReader!!.surface)
        try {
            camera.createCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    // In IntruderDetectionService.kt, inside createImageSession() -> onConfigured()
                    override fun onConfigured(session: CameraCaptureSession) {
                        Log.d(TAG, "CameraCaptureSession configured.")
                        val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                            addTarget(imageReader!!.surface)
                            // REMOVED: set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                            // The camera's default auto-exposure for TEMPLATE_STILL_CAPTURE will now apply.
                            set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(characteristics))
                        }.build()
                        session.capture(request, captureCallback, backgroundHandler)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "CameraCaptureSession failed!")
                        stopCameraResources()
                    }
                },
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera session error: ${e.message}", e)
            stopCameraResources()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession,
                                        request: CaptureRequest,
                                        result: TotalCaptureResult) {
            Log.d(TAG, "Capture completed.")
            session.close() // Close the session after capture
            // Resources are closed in savePhoto's finally block
        }

        override fun onCaptureFailed(session: CameraCaptureSession,
                                     request: CaptureRequest,
                                     failure: CaptureFailure) {
            Log.e(TAG, "Capture failed: ${failure.reason}")
            session.close()
            stopCameraResources()
        }
    }

    // Helper to get JPEG orientation based on device orientation and camera sensor orientation
    private fun getJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val deviceRotation = (applicationContext.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.rotation
        val surfaceRotation = when(deviceRotation) {
            android.view.Surface.ROTATION_0 -> 0
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val frontCamera = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        return if (frontCamera) {
            (sensorOrientation + surfaceRotation + 270) % 360
        } else {
            (sensorOrientation - surfaceRotation + 360) % 360
        }
    }

    /* -------------------------------------------------- */
    /* Save JPEG to MediaStore                            */
    /* -------------------------------------------------- */
    private fun savePhoto(bytes: ByteArray) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "IntruderSelfie")
                put(MediaStore.MediaColumns.IS_PENDING, 1) // Mark as pending while writing
            }
        }

        val resolver = contentResolver
        var uri: android.net.Uri? = null
        var outputStream: OutputStream? = null

        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                throw RuntimeException("Failed to create new MediaStore record.")
            }

            outputStream = resolver.openOutputStream(uri)
            if (outputStream == null) {
                throw RuntimeException("Failed to open output stream for $uri")
            }

            outputStream.write(bytes)
            Log.d(TAG, "Photo saved via MediaStore: $uri")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0) // Mark as not pending
                resolver.update(uri, contentValues, null, null)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error saving photo: ${e.message}", e)
            if (uri != null) {
                // If error, delete the pending record
                resolver.delete(uri, null, null)
            }
        } finally {
            outputStream?.close()
            // Important: Close camera and quit thread only after saving is fully done
            stopCameraResources() // Only close camera resources
        }
    }

    private fun stopCameraResources() {
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        backgroundThread?.quitSafely()
        backgroundThread = null // Set to null to allow re-creation
        backgroundHandler = null // Set to null
        Log.d(TAG, "Camera resources closed.")
        // The service does NOT stop itself here after a single capture.
        // It remains running in the foreground, waiting for the next unlock event.
    }


    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed.")
        // Unregister the receiver when the service is destroyed
        try {
            unregisterReceiver(unlockReceiver)
            Log.d(TAG, "UnlockBroadcastReceiver unregistered.")
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Receiver not registered, or already unregistered: ${e.message}")
        }
        stopCameraResources() // Close camera resources on service destruction
    }
}