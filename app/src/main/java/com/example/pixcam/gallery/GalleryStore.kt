package com.example.pixcam.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One image owned by this app, as MediaStore knows it. */
data class GalleryItem(
    val uri: Uri,
    val name: String,
    val takenAtMillis: Long,
)

/** Matches what CameraController writes: RELATIVE_PATH "Pictures/Pixcam". */
private const val PIXCAM_PATH = "Pictures/Pixcam%"

/** Longest edge we ever decode to; keeps 13MP shots well clear of the heap limit. */
private const val MAX_DECODE_EDGE = 2048

/**
 * Newest-first list of the images this app created. Scoped storage limits the query to
 * our own media, which is exactly the set we want and needs no runtime permission on API 29+.
 */
suspend fun queryPixcamImages(context: Context): List<GalleryItem> = withContext(Dispatchers.IO) {
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
    )
    val cursor = context.contentResolver.query(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        projection,
        "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?",
        arrayOf(PIXCAM_PATH),
        "${MediaStore.Images.Media.DATE_ADDED} DESC, ${MediaStore.Images.Media._ID} DESC",
    ) ?: return@withContext emptyList()

    cursor.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val out = ArrayList<GalleryItem>(c.count)
        while (c.moveToNext()) {
            val id = c.getLong(idCol)
            val taken = if (c.isNull(takenCol)) c.getLong(addedCol) * 1000L else c.getLong(takenCol)
            out += GalleryItem(
                uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI.buildUpon()
                    .appendPath(id.toString())
                    .build(),
                name = c.getString(nameCol) ?: "",
                takenAtMillis = taken,
            )
        }
        out
    }
}

/** Most recent shot, or null when the app has taken none. */
suspend fun queryLatestPixcamImage(context: Context): GalleryItem? =
    queryPixcamImages(context).firstOrNull()

/** Square-ish thumbnail from MediaStore's own cache. Null if the item vanished. */
suspend fun loadThumbnail(context: Context, uri: Uri, edgePx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.loadThumbnail(uri, Size(edgePx, edgePx), null) }
            .getOrNull()
    }

/**
 * Two-pass subsampled decode, capped at [MAX_DECODE_EDGE] on the long edge and rotated to
 * match EXIF. Falls back to a large MediaStore thumbnail for formats BitmapFactory cannot
 * read directly (DNG).
 */
suspend fun loadFullBitmap(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    runCatching {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    }

    val decoded = if (bounds.outWidth > 0 && bounds.outHeight > 0) {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DECODE_EDGE)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()
    } else {
        null
    }

    val bitmap = decoded ?: return@withContext runCatching {
        resolver.loadThumbnail(uri, Size(MAX_DECODE_EDGE, MAX_DECODE_EDGE), null)
    }.getOrNull()

    val degrees = runCatching {
        resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)

    if (degrees == 0f) bitmap else rotate(bitmap, degrees)
}

private fun rotate(source: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = runCatching {
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }.getOrDefault(source)
    if (rotated !== source) source.recycle()
    return rotated
}

/** Largest power of two that keeps both edges within [maxEdge]. */
private fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
    var sample = 1
    while (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge) {
        sample *= 2
    }
    return sample
}
