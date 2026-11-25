package com.tharunbirla.intruderselfie

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AlertSettings(
    val threshold: Int = 3,
    val soundEnabled: Boolean = true,
    val volume: Int = 80,
    val ringtoneUri: String? = null,
    val ringtoneTitle: String = "System alarm",
    val torchEnabled: Boolean = false,
    val blinkIntervalMs: Int = 1200,
    val durationSeconds: Int = 30
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    private val _appEnabled = MutableStateFlow(prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true))
    val appEnabled: StateFlow<Boolean> = _appEnabled.asStateFlow()

    private val _isSetupCompleted = MutableStateFlow(prefs.getBoolean("setup_completed", false))
    val isSetupCompleted: StateFlow<Boolean> = _isSetupCompleted.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<CapturedPhoto>>(emptyList())
    val capturedPhotos: StateFlow<List<CapturedPhoto>> = _capturedPhotos.asStateFlow()

    private val _selectedPhotos = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedPhotos: StateFlow<Set<Uri>> = _selectedPhotos.asStateFlow()

    private val _previewPhotoUri = MutableStateFlow<Uri?>(null)
    val previewPhotoUri: StateFlow<Uri?> = _previewPhotoUri.asStateFlow()

    private val _alertSettings = MutableStateFlow(readAlertSettings())
    val alertSettings: StateFlow<AlertSettings> = _alertSettings.asStateFlow()

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            loadCapturedPhotos()
        }
    }

    init {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
        loadCapturedPhotos()
        checkServiceState()
    }

    private fun readAlertSettings(): AlertSettings = AlertSettings(
        threshold = prefs.getInt(AppConstants.KEY_ALERT_THRESHOLD, 3).coerceIn(2, 5),
        soundEnabled = prefs.getBoolean(AppConstants.KEY_ALARM_SOUND_ENABLED, true),
        volume = prefs.getInt(AppConstants.KEY_ALARM_VOLUME, 80).coerceIn(0, 100),
        ringtoneUri = prefs.getString(AppConstants.KEY_RINGTONE_URI, null),
        ringtoneTitle = prefs.getString(AppConstants.KEY_RINGTONE_TITLE, "System alarm") ?: "System alarm",
        torchEnabled = prefs.getBoolean(AppConstants.KEY_TORCH_ENABLED, false),
        blinkIntervalMs = prefs.getInt(AppConstants.KEY_TORCH_INTERVAL_MS, 1200).coerceIn(1000, 2000),
        durationSeconds = prefs.getInt(AppConstants.KEY_ALARM_DURATION_SECONDS, 30).coerceIn(10, 60)
    )

    private fun saveAlertSettings(settings: AlertSettings) {
        _alertSettings.value = settings
        prefs.edit()
            .putInt(AppConstants.KEY_ALERT_THRESHOLD, settings.threshold)
            .putBoolean(AppConstants.KEY_ALARM_SOUND_ENABLED, settings.soundEnabled)
            .putInt(AppConstants.KEY_ALARM_VOLUME, settings.volume)
            .putString(AppConstants.KEY_RINGTONE_URI, settings.ringtoneUri)
            .putString(AppConstants.KEY_RINGTONE_TITLE, settings.ringtoneTitle)
            .putBoolean(AppConstants.KEY_TORCH_ENABLED, settings.torchEnabled)
            .putInt(AppConstants.KEY_TORCH_INTERVAL_MS, settings.blinkIntervalMs)
            .putInt(AppConstants.KEY_ALARM_DURATION_SECONDS, settings.durationSeconds)
            .apply()
    }

    fun setAlertThreshold(value: Int) =
        saveAlertSettings(_alertSettings.value.copy(threshold = value.coerceIn(2, 5)))

    fun setAlarmSoundEnabled(enabled: Boolean) =
        saveAlertSettings(_alertSettings.value.copy(soundEnabled = enabled))

    fun setAlarmVolume(value: Int) =
        saveAlertSettings(_alertSettings.value.copy(volume = value.coerceIn(0, 100)))

    fun setRingtone(uri: String?, title: String) =
        saveAlertSettings(_alertSettings.value.copy(ringtoneUri = uri, ringtoneTitle = title))

    fun setTorchEnabled(enabled: Boolean) =
        saveAlertSettings(_alertSettings.value.copy(torchEnabled = enabled))

    fun setBlinkInterval(value: Int) =
        saveAlertSettings(_alertSettings.value.copy(blinkIntervalMs = value.coerceIn(1000, 2000)))

    fun setAlarmDuration(value: Int) =
        saveAlertSettings(_alertSettings.value.copy(durationSeconds = value.coerceIn(10, 60)))

    fun testAlert() {
        val intent = Intent(context, IntruderDetectionService::class.java).apply {
            action = IntruderDetectionService.ACTION_START_ALARM
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Unable to test security alert", e)
        }
    }

    fun completeSetup() {
        _isSetupCompleted.value = true
        prefs.edit().putBoolean("setup_completed", true).apply()
        checkServiceState()
    }

    fun checkServiceState() {
        if (_appEnabled.value) {
            val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
                IntruderDetection.start(context)
            } else {
                IntruderDetection.stop(context)
            }
        } else {
            IntruderDetection.stop(context)
        }
    }

    fun toggleAppEnabled(enabled: Boolean) {
        _appEnabled.value = enabled
        prefs.edit().putBoolean(AppConstants.KEY_APP_ENABLED, enabled).apply()
        if (!enabled) {
            prefs.edit()
                .putInt(AppConstants.KEY_FAILED_STREAK, 0)
                .putBoolean(AppConstants.KEY_ALARM_TRIGGERED, false)
                .apply()
        }
        checkServiceState()
    }

    fun loadCapturedPhotos() {
        viewModelScope.launch {
            val photoList = withContext(Dispatchers.IO) {
                val images = mutableListOf<CapturedPhoto>()
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Images.Media.DATE_TAKEN
                )
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                } else {
                    "${MediaStore.Images.Media.DATA} LIKE ?"
                }
                val targetDir = Environment.DIRECTORY_PICTURES + File.separator + "IntruderSelfie"
                val selectionArgs = arrayOf("$targetDir%")
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                try {
                    context.contentResolver.query(
                        collection, projection, selection, selectionArgs, sortOrder
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val contentUri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()
                            )
                            val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                            val dateTaken = cursor.getLong(dateTakenColumn)
                            images.add(
                                CapturedPhoto(
                                    uri = contentUri,
                                    timestamp = if (dateTaken > 0) dateTaken else dateAdded
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error loading photos", e)
                }
                images
            }
            _capturedPhotos.value = photoList
            _selectedPhotos.value = _selectedPhotos.value.filter { selected ->
                photoList.any { it.uri == selected }
            }.toSet()
        }
    }

    fun togglePhotoSelection(uri: Uri) {
        _selectedPhotos.value = if (_selectedPhotos.value.contains(uri)) {
            _selectedPhotos.value - uri
        } else {
            _selectedPhotos.value + uri
        }
    }

    fun clearSelection() {
        _selectedPhotos.value = emptySet()
    }

    fun deleteSelectedPhotos() {
        viewModelScope.launch {
            if (_selectedPhotos.value.isEmpty()) return@launch
            deletePhotos(_selectedPhotos.value.toList())
            _selectedPhotos.value = emptySet()
        }
    }

    fun deleteSinglePhoto(uri: Uri) {
        viewModelScope.launch {
            deletePhotos(listOf(uri))
            if (_previewPhotoUri.value == uri) _previewPhotoUri.value = null
        }
    }

    private suspend fun deletePhotos(uris: List<Uri>) {
        withContext(Dispatchers.IO) {
            uris.forEach { uri ->
                try {
                    context.contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error deleting photo: $uri", e)
                }
            }
        }
        loadCapturedPhotos()
    }

    fun setPreviewPhoto(uri: Uri?) {
        _previewPhotoUri.value = uri
    }

    override fun onCleared() {
        super.onCleared()
        context.contentResolver.unregisterContentObserver(contentObserver)
    }
}
