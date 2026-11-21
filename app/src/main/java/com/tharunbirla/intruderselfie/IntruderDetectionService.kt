package com.tharunbirla.intruderselfie

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.ImageReader
import android.media.RingtoneManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * Foreground service for local failed-attempt photos and the optional security alarm.
 * The alarm/torch is only started after the configured consecutive-failure threshold.
 */
class IntruderDetectionService : Service() {

    companion object {
        private const val TAG = "IntruderService"
        private const val NOTIF_ID = 101
        private const val CHANNEL_ID = "INTRUDER_CHANNEL"
        const val ACTION_CAPTURE_FAILED_ATTEMPT =
            "com.tharunbirla.intruderselfie.CAPTURE_FAILED_ATTEMPT"
        const val ACTION_START_ALARM =
            "com.tharunbirla.intruderselfie.START_SECURITY_ALARM"
        const val ACTION_STOP_ALARM =
            "com.tharunbirla.intruderselfie.STOP_SECURITY_ALARM"
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var mediaPlayer: MediaPlayer? = null
    private var torchCameraId: String? = null
    private var torchIsOn = false

    private val stopAlarmRunnable = Runnable { stopSecurityAlarm() }
    private val torchBlinkRunnable = object : Runnable {
        override fun run() {
            toggleTorch()
            val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)
            val interval = prefs.getInt(AppConstants.KEY_TORCH_INTERVAL_MS, 1200).coerceIn(1000, 2000)
            mainHandler.postDelayed(this, interval.toLong())
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        ensureBackgroundThread()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CAPTURE_FAILED_ATTEMPT -> {
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
            ACTION_START_ALARM -> startSecurityAlarm()
            ACTION_STOP_ALARM -> stopSecurityAlarm()
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
            .setContentTitle("SACHIN • Intruder Selfie")
            .setContentText("Monitoring failed screen-lock credential attempts")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    private fun ensureBackgroundThread() {
        if (backgroundThread == null || !backgroundThread!!.isAlive) {
            backgroundThread = HandlerThread("CameraBg").apply { start() }
            backgroundHandler = Handler(backgroundThread!!.looper)
        }
    }

    // Alert playback is isolated from the camera queue and automatically times out.
    private fun startSecurityAlarm() {
        stopSecurityAlarm()
        val prefs = getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(AppConstants.KEY_ALARM_SOUND_ENABLED, true)) {
            val volume = prefs.getInt(AppConstants.KEY_ALARM_VOLUME, 80).coerceIn(0, 100) / 100f
            val customUri = prefs.getString(AppConstants.KEY_RINGTONE_URI, null)?.let(Uri::parse)
            val defaultUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            val started = customUri?.let { startTone(it, volume) } ?: false
            if (!started && defaultUri != null) startTone(defaultUri, volume)
        }

        if (prefs.getBoolean(AppConstants.KEY_TORCH_ENABLED, false)) {
            // Give the front-camera still capture time to finish before requesting the rear flash.
            mainHandler.postDelayed(torchBlinkRunnable, 2500L)
        }

        val duration = prefs.getInt(AppConstants.KEY_ALARM_DURATION_SECONDS, 30).coerceIn(10, 60)
        mainHandler.postDelayed(stopAlarmRunnable, duration * 1000L)
    }

    private fun startTone(uri: Uri, volume: Float): Boolean {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(this, uri)
            player.isLooping = true
            player.setVolume(volume, volume)
            player.prepare()
            player.start()
            mediaPlayer = player
            true
        } catch (e: Exception) {
            Log.w(TAG, "Could not play selected alarm tone", e)
            try { player.release() } catch (_: Exception) {}
            false
        }
    }

    private fun findFlashCameraId(): String? {
        val manager = getSystemService(CameraManager::class.java)
        return try {
            manager.cameraIdList.firstOrNull { id ->
                val characteristics = manager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_BACK &&
                    characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to find rear flash", e)
            null
        }
    }

    private fun toggleTorch() {
        val cameraId = torchCameraId ?: findFlashCameraId()?.also { torchCameraId = it } ?: run {
            Log.w(TAG, "No rear camera flash available")
            mainHandler.removeCallbacks(torchBlinkRunnable)
            return
        }
        val nextState = !torchIsOn
        try {
            getSystemService(CameraManager::class.java).setTorchMode(cameraId, nextState)
            torchIsOn = nextState
        } catch (e: Exception) {
            // A device may temporarily reserve the camera for the front-facing photo.
            Log.w(TAG, "Torch change failed; will retry on next blink", e)
            torchIsOn = false
        }
    }

    private fun stopSecurityAlarm() {
        mainHandler.removeCallbacks(stopAlarmRunnable)
        mainHandler.removeCallbacks(torchBlinkRunnable)

        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) player.stop()
                player.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping alarm playback", e)
        } finally {
            mediaPlayer = null
        }

        if (torchIsOn && torchCameraId != null) {
            try {
                getSystemService(CameraManager::class.java).setTorchMode(torchCameraId!!, false)
            } catch (e: Exception) {
                Log.w(TAG, "Could not switch off torch", e)
            }
        }
        torchIsOn = false
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

        var uri: Uri? = null
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

        if (pendingCaptureIds.isNotEmpty()) pendingCaptureIds.removeFirst()
        captureNextIfIdle()
    }

    override fun onDestroy() {
        stopSecurityAlarm()
        activeSession?.close()
        cameraDevice?.close()
        imageReader?.close()
        backgroundThread?.quitSafely()
        super.onDestroy()
    }
}
