package dev.pixelagent.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

data class Snapshot(val jpeg: ByteArray, val width: Int, val height: Int) {
    fun dataUrl(): String = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
}

object SnapshotEncoder {
    /** Decode sampled pixels, apply EXIF orientation, strip metadata, bound upload. */
    fun fromFile(file: File): Snapshot {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid capture" }
        val options = BitmapFactory.Options().apply { inSampleSize = 1 }
        while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
        var bitmap = BitmapFactory.decodeFile(file.path, options) ?: error("Invalid capture")
        try {
            val exif = ExifInterface(file)
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) { bitmap.recycle(); bitmap = oriented }
            val scale = minOf(1f, 1024f / maxOf(bitmap.width, bitmap.height))
            val resized = Bitmap.createScaledBitmap(bitmap, maxOf(1, (bitmap.width * scale).roundToInt()), maxOf(1, (bitmap.height * scale).roundToInt()), true)
            if (resized !== bitmap) { bitmap.recycle(); bitmap = resized }
            while (true) {
                val bytes = ByteArrayOutputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out); out.toByteArray() }
                if (bytes.size <= 180_000) return Snapshot(bytes, bitmap.width, bitmap.height)
                val smaller = Bitmap.createScaledBitmap(bitmap, maxOf(1, bitmap.width * 3 / 4), maxOf(1, bitmap.height * 3 / 4), true)
                bitmap.recycle(); bitmap = smaller
            }
        } finally { bitmap.recycle() }
    }
    fun clearTemporaryFiles(cacheDir: File) {
        File(cacheDir, "snapshots").listFiles()?.filter { it.isFile }?.forEach { it.delete() }
    }
}
