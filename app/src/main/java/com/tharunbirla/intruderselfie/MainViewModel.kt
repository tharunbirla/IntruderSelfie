package com.tharunbirla.intruderselfie

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
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
            // Refresh photos only if the change is related to the MediaStore's external content
            // or specifically points to a path within our app's photo directory.
            // This prevents unnecessary refreshes from other apps' media changes.
            if (uri != null && (uri.path?.contains("IntruderSelfie") == true || uri == MediaStore.Images.Media.EXTERNAL_CONTENT_URI)) {
                Log.d("MainViewModel", "ContentObserver onChange: $uri, refreshing photos.")
                loadCapturedPhotos()
            }
        }
    }

    init {
        // Register the content observer when the ViewModel is created
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true, // notifyForDescendants: true to observe changes in subdirectories
            contentObserver
        )

        // Load photos immediately when the ViewModel is initialized
        loadCapturedPhotos()

        // Ensure the IntruderDetection service state matches the initial UI state
        if (_appEnabled.value) {
            IntruderDetection.start(context)
        }
    }

    /**
     * Toggles the enabled state of the intruder detection feature.
     * Updates preferences and starts/stops the detection service accordingly.
     * @param enabled The new enabled state.
     */
    fun toggleAppEnabled(enabled: Boolean) {
        _appEnabled.value = enabled
        prefs.edit().putBoolean(AppConstants.KEY_APP_ENABLED, enabled).apply()

        if (enabled) {
            IntruderDetection.start(context)
        } else {
            IntruderDetection.stop(context)
        }
    }

    /**
     * Loads captured photos from the device's MediaStore.
     * Filters photos to only include those from the "IntruderSelfie" directory.
     * Photos are sorted by date added (newest first).
     */
    fun loadCapturedPhotos() {
        viewModelScope.launch {
            val photoList = withContext(Dispatchers.IO) {
                val images = mutableListOf<CapturedPhoto>()
                // Determine the correct MediaStore collection URI based on Android version
                val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(
                        MediaStore.VOLUME_EXTERNAL_PRIMARY
                    )
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

                // Define the columns we want to retrieve from MediaStore
                val projection = arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_ADDED, // Timestamp when added to MediaStore (in seconds)
                    MediaStore.Images.Media.DATE_TAKEN // More accurate timestamp when photo was taken (in milliseconds), can be null
                )

                // Define the selection criteria to filter photos by our app's directory
                val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // For Android 10 (Q) and above, use RELATIVE_PATH
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
                } else {
                    // For older Android versions, use DATA (full file path) with LIKE
                    "${MediaStore.Images.Media.DATA} LIKE ?"
                }

                val targetDir = Environment.DIRECTORY_PICTURES + File.separator + "IntruderSelfie" + File.separator
                val selectionArgs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(targetDir)
                } else {
                    arrayOf("%$targetDir%") // Use wildcard for LIKE operator
                }

                // Define the sort order (newest photos first)
                val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

                // Query the MediaStore
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
                        val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert seconds to milliseconds
                        val dateTaken = cursor.getLong(dateTakenColumn) // Already in milliseconds

                        // Prefer DATE_TAKEN if available and valid, otherwise use DATE_ADDED
                        val finalTimestamp = if (dateTaken > 0) dateTaken else dateAdded

                        images.add(CapturedPhoto(uri = contentUri, timestamp = finalTimestamp))
                    }
                }
                images
            }
            _capturedPhotos.value = photoList
            // After loading, clear any selection that might no longer be valid (e.g., if a selected photo was manually deleted)
            _selectedPhotos.value = _selectedPhotos.value.filter { uri ->
                photoList.any { it.uri == uri }
            }.toSet()
        }
    }

    /**
     * Toggles the selection state of a specific photo.
     * @param uri The URI of the photo to toggle.
     */
    fun togglePhotoSelection(uri: Uri) {
        _selectedPhotos.value = if (_selectedPhotos.value.contains(uri)) {
            _selectedPhotos.value - uri // Deselect
        } else {
            _selectedPhotos.value + uri // Select
        }
    }

    /**
     * Clears all currently selected photos.
     */
    fun clearSelection() {
        _selectedPhotos.value = emptySet()
    }

    /**
     * Deletes all currently selected photos from the device.
     * This operation is performed on a background thread.
     * Handles potential security exceptions for Android 10+ where direct deletion
     * might require user consent (though for app-owned files in app-specific public dirs,
     * it often works without explicit prompts).
     */
    fun deleteSelectedPhotos() {
        viewModelScope.launch {
            if (_selectedPhotos.value.isEmpty()) return@launch

            val urisToDelete = _selectedPhotos.value.toList()
            _selectedPhotos.value = emptySet() // Clear selection immediately upon starting deletion

            withContext(Dispatchers.IO) {
                urisToDelete.forEach { uri ->
                    try {
                        // For Android 10 (API 29) and above, deleting public media that your
                        // app *did not* create, or that is not in your app's private storage,
                        // might require user consent via a RecoverableSecurityException or
                        // MediaStore.createDeleteRequest().
                        // However, for files *created by your own app* in its designated public directory
                        // (like Pictures/IntruderSelfie), direct deletion with ContentResolver.delete()
                        // often works without explicit permission prompts if the app is the owner
                        // or has modify access.
                        val deletedRows = context.contentResolver.delete(uri, null, null)
                        if (deletedRows > 0) {
                            Log.d("MainViewModel", "Deleted photo: $uri")
                        } else {
                            Log.w("MainViewModel", "Failed to delete photo: $uri (rows: $deletedRows)")
                            // If deletion fails, you might need more robust handling for API 29+
                            // involving MediaStore.createDeleteRequest() to prompt the user.
                        }
                    } catch (e: Exception) {
                        Log.e("MainViewModel", "Error deleting photo: $uri", e)
                        // This might catch SecurityException if deletion requires user interaction
                        // on newer Android versions. For a robust solution, you'd handle this
                        // by showing a confirmation dialog/request for deletion permission.
                    }
                }
            }
            // Refresh the list after deletion to reflect changes in the UI
            loadCapturedPhotos()
        }
    }

    /**
     * Sets the URI of the photo to be displayed in the full-screen preview.
     * Pass `null` to dismiss the preview.
     * @param uri The URI of the photo to preview, or null.
     */
    fun setPreviewPhoto(uri: Uri?) {
        _previewPhotoUri.value = uri
    }

    /**
     * Called when the ViewModel is no longer used and will be destroyed.
     * Unregisters the ContentObserver to prevent memory leaks.
     */
    override fun onCleared() {
        super.onCleared()
        context.contentResolver.unregisterContentObserver(contentObserver)
    }
}