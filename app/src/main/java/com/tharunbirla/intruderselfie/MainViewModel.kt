package com.tharunbirla.intruderselfie

import android.Manifest
import android.app.Application
import android.content.Context
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context
        get() = getApplication<Application>().applicationContext

    private val prefs = context.getSharedPreferences(AppConstants.PREFS_NAME, Context.MODE_PRIVATE)

    // StateFlow to observe the app's enabled/disabled status
    private val _appEnabled = MutableStateFlow(prefs.getBoolean(AppConstants.KEY_APP_ENABLED, true))
    val appEnabled: StateFlow<Boolean> = _appEnabled.asStateFlow()

    // StateFlow to track if setup is completed
    private val _isSetupCompleted = MutableStateFlow(prefs.getBoolean("setup_completed", false))
    val isSetupCompleted: StateFlow<Boolean> = _isSetupCompleted.asStateFlow()

    // StateFlow to hold the list of captured photos
    private val _capturedPhotos = MutableStateFlow<List<CapturedPhoto>>(emptyList())
    val capturedPhotos: StateFlow<List<CapturedPhoto>> = _capturedPhotos.asStateFlow()

    // StateFlow to hold the URIs of currently selected photos for deletion
    private val _selectedPhotos = MutableStateFlow<Set<Uri>>(emptySet())
    val selectedPhotos: StateFlow<Set<Uri>> = _selectedPhotos.asStateFlow()

    // StateFlow to hold the URI of the photo currently being previewed in full screen
    private val _previewPhotoUri = MutableStateFlow<Uri?>(null)
    val previewPhotoUri: StateFlow<Uri?> = _previewPhotoUri.asStateFlow()

    // ContentObserver to listen for changes in MediaStore
    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            // Simplified logic: Reload on ANY change to external content URI.
            // This ensures we catch the 'IS_PENDING' flip or insertion even if path matching is flaky.
            // We rely on loadCapturedPhotos to filter by directory anyway.
            Log.d("MainViewModel", "ContentObserver onChange: $uri")
            loadCapturedPhotos()
        }
    }

    init {
        // Register the content observer when the ViewModel is created
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants
            contentObserver
        )

        // Load photos immediately
        loadCapturedPhotos()

        // Check service state
        checkServiceState()
    }

    fun completeSetup() {
        _isSetupCompleted.value = true
        prefs.edit().putBoolean("setup_completed", true).apply()
        // Try starting service now that setup is done (likely permissions granted)
        checkServiceState()
    }

    /**
     * Checks if the service should be running based on 'enabled' state AND permissions.
     */
    fun checkServiceState() {
        // Only start if setup is completed OR if we just want to enforce logic
        // But strictly, we need permissions.
        if (_appEnabled.value) {
            val cameraPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            if (cameraPermission == PackageManager.PERMISSION_GRANTED) {
                Log.d("MainViewModel", "App enabled and permissions granted. Starting service.")
                IntruderDetection.start(context)
            } else {
                Log.d("MainViewModel", "App enabled but Camera permission missing. Not starting service.")
                IntruderDetection.stop(context)
            }
        } else {
            Log.d("MainViewModel", "App disabled. Stopping service.")
            IntruderDetection.stop(context)
        }
    }

    fun toggleAppEnabled(enabled: Boolean) {
        _appEnabled.value = enabled
        prefs.edit().putBoolean(AppConstants.KEY_APP_ENABLED, enabled).apply()
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

                // For Android 10+, use RELATIVE_PATH
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                } else {
                    "${MediaStore.Images.Media.DATA} LIKE ?"
                }

                // Important: Ensure trailing slash for RELATIVE_PATH matching to be safe
                val targetDir = Environment.DIRECTORY_PICTURES + File.separator + "IntruderSelfie"
                val selectionArgs = arrayOf("$targetDir%")

                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                try {
                    context.contentResolver.query(
                        collection,
                        projection,
                        selection,
                        selectionArgs,
                        sortOrder
                    )?.use { cursor ->
                        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                        val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                        val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(idColumn)
                            val contentUri = Uri.withAppendedPath(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                id.toString()
                            )
                            val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                            val dateTaken = cursor.getLong(dateTakenColumn)
                            val finalTimestamp = if (dateTaken > 0) dateTaken else dateAdded

                            images.add(CapturedPhoto(uri = contentUri, timestamp = finalTimestamp))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainViewModel", "Error loading photos", e)
                }
                images
            }
            _capturedPhotos.value = photoList
            _selectedPhotos.value = _selectedPhotos.value.filter { uri ->
                photoList.any { it.uri == uri }
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
            // If the deleted photo was the one being previewed, close the preview
            if (_previewPhotoUri.value == uri) {
                _previewPhotoUri.value = null
            }
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