package com.tharunbirla.intruderselfie

import android.net.Uri

/**
 * Data class representing a captured photo with its URI and timestamp.
 *
 * @param uri The content URI of the photo in MediaStore.
 * @param timestamp The Unix timestamp (in milliseconds) when the photo was captured or added.
 */
data class CapturedPhoto(
    val uri: Uri,
    val timestamp: Long // Unix timestamp in milliseconds
) {
    // Override equals and hashCode for proper comparison in Sets/Lists,
    // especially useful for selection based on Uri.
    // This ensures that two CapturedPhoto objects with the same URI are considered equal,
    // regardless of their timestamp, which simplifies selection logic.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CapturedPhoto

        if (uri != other.uri) return false

        return true
    }

    override fun hashCode(): Int {
        return uri.hashCode()
    }
}