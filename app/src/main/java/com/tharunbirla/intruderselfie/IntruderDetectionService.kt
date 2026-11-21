package com.tharunbirla.intruderselfie

import android.app.*
import android.content.ContentValues
import android.content.Context
import android.content.Intent
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
 * Long-lived foreground service. It does not react to successful unlocks.
 * A failed credential callback explicitly requests exactly one capture.
 */
class IntruderDetectionService : Service() {

    companion object {
        private const val TAG = "IntruderService"
        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "INTRUDER_CHANNEL"
        const val ACTION_CAPTURE_FAILED_ATTEMPT =
            "com.tharunbirla.intruderselfie.CAPTURE_FAILED_ATTEMPT"
        const val EXTRA_EVENT_ID = "failed_attempt_event_id"
    }

    private var cameraDevice: CameraDevice? = null
    private var backgroundHandler: Handler? = null
    private var backgroundThread: HandlerThread? = null
    private var imageReader: ImageReader? = null
    private var isReadyToCapture = false
    private var captureInProgress = false
    private val pendingCaptureIds = ArrayDeque<Long>()
    private var lastQueuedEventId = 0L
    private var activeSession: CameraCaptureSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        ensureBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_FAILED_ATTEMPT) {
            val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1L)
            ensureBackgroundThread()
            backgroundHandler?.post {
                if (eventId > lastQueuedEventId) {
                    lastQueuedEventId = eventId
                    pendingCaptureIds.addLast(eventId)
                    Log.i(TAG, "Queued failed credential attempt id=$eventId")
                    captureNextIfIdle()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Intruder Detection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Intruder Selfie Active")
            .setContentText("Monitoring failed screen-lock credential attempts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun ensureBackgroundThread() {
        if (backgroundThread == null || !backgroundThread!!.isAlive) {
            backgroundThread = HandlerThread("CameraBg").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    // All capture-queue state is accessed only on the camera background thread.
    private fun captureNextIfIdle() {
        if (captureInProgress || pendingCaptureIds.isEmpty()) return
        captureInProgress = true
        openFrontCamera()
    }

    private fun openFrontCamera() {
        val manager = getSystemService(CameraManager::class.java)
        val cameraId = try {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            }
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Cannot enumerate cameras", e)
            finishCurrentCapture()
            return
        }

        if (cameraId == null) {
            Log.e(TAG, "No front camera found")
            finishCurrentCapture()
            return
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Camera permission missing")
            finishCurrentCapture()
            return
        }

        try {
            manager.openCamera(cameraId, stateCallback, backgroundHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open camera: ${e.message}", e)
            finishCurrentCapture()
        }
    }

    private val stateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createImageSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            finishCurrentCapture()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            Log.e(TAG, "Camera error: $error")
            camera.close()
            cameraDevice = null
            finishCurrentCapture()
        }
    }

    private fun createImageSession() {
        val camera = cameraDevice ?: run {
            finishCurrentCapture()
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(camera.id)
        val size = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height } ?: run {
            finishCurrentCapture()
            return
        }

        imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                if (isReadyToCapture) {
                    reader.acquireNextImage()?.use { image ->
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        isReadyToCapture = false
                        savePhoto(bytes)
                    } ?: run {
                        Log.e(TAG, "No JPEG received")
                        finishCurrentCapture()
                    }
                } else {
                    reader.acquireLatestImage()?.close()
                }
            }, backgroundHandler)
        }

        try {
            camera.createCaptureSession(
                listOf(imageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        activeSession = session
                        try {
                            val warmup = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                addTarget(imageReader!!.surface)
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            session.setRepeatingRequest(warmup, null, backgroundHandler)
                            backgroundHandler?.postDelayed({
                                try {
                                    session.stopRepeating()
                                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                        addTarget(imageReader!!.surface)
                                        set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation(characteristics))
                                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                    }.build()
                                    isReadyToCapture = true
                                    session.capture(request, captureCallback, backgroundHandler)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Capture request failed", e)
                                    finishCurrentCapture()
                                }
                            }, 650L)
                        } catch (e: Exception) {
                            Log.e(TAG, "Session warmup failed", e)
                            finishCurrentCapture()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Capture session configuration failed")
                        session.close()
                        finishCurrentCapture()
                    }
                },
                backgroundHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Capture session creation failed", e)
            finishCurrentCapture()
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            Log.d(TAG, "Capture completed")
            // ImageReader delivers and saves exactly one still JPEG.
        }

        override fun onCaptureFailed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            failure: CaptureFailure
        ) {
            Log.e(TAG, "Capture failed: ${failure.reason}")
            finishCurrentCapture()
        }
    }

    private fun getJpegOrientation(characteristics: CameraCharacteristics): Int {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        @Suppress("DEPRECATION")
        val rotation = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
            .defaultDisplay.rotation
        val degrees = when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val front = characteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        return if (front) (sensorOrientation + degrees + 270) % 360
        else (sensorOrientation - degrees + 360) % 360
    }

    private fun savePhoto(bytes: ByteArray) {
        val timestamp = System.currentTimeMillis()
        val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(timestamp))}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + File.separator + "IntruderSelfie"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        var uri: android.net.Uri? = null
        var stream: OutputStream? = null
        try {
            uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore insert returned null")
            stream = contentResolver.openOutputStream(uri)
                ?: throw IllegalStateException("Cannot open photo output stream")
            stream.write(bytes)
            stream.flush()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, published, null, null)
            }
            Log.i(TAG, "Saved one failed-attempt photo: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save photo", e)
            uri?.let { contentResolver.delete(it, null, null) }
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            finishCurrentCapture()
        }
    }

    private fun finishCurrentCapture() {
        val handler = backgroundHandler
        if (handler != null && Looper.myLooper() != handler.looper) {
            handler.post { finishCurrentCapture() }
            return
        }

        isReadyToCapture = false
        activeSession?.close()
        activeSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        captureInProgress = false

        if (pendingCaptureIds.isNotEmpty()) {
            pendingCaptureIds.removeFirst()
        }
        captureNextIfIdle()
    }

    override fun onDestroy() {
        activeSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
