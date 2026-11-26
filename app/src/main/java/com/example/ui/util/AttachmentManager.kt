package com.example.ui.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.FileProvider
import com.example.model.Attachment
import com.example.model.AttachmentOwnerType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID

object AttachmentManager {

    private const val TAG = "AttachmentManager"
    const val MAX_IMAGE_SIDE_PX = 1600
    const val THUMB_SIDE_PX = 256
    const val JPEG_QUALITY = 80
    const val MAX_PDF_SIZE_BYTES = 10 * 1024 * 1024L // 10 MB

    fun getAttachmentsDir(context: Context): File {
        val dir = File(context.filesDir, "attachments")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createTempCameraUri(context: Context): Pair<Uri, File> {
        val dir = getAttachmentsDir(context)
        val tempFile = File(dir, "temp_camera_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            tempFile
        )
        return Pair(uri, tempFile)
    }

    suspend fun processAndSaveImage(
        context: Context,
        uri: Uri,
        ownerType: String = AttachmentOwnerType.ENTRY.raw,
        ownerId: String,
        caption: String = ""
    ): Attachment = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = getAttachmentsDir(context)
        val targetImageFile = File(dir, "$id.jpg")
        val targetThumbFile = File(dir, "${id}_thumb.jpg")

        // 1. Determine rotation from EXIF
        var rotationDegrees = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                val exifInterface = ExifInterface(inputStream)
                val orientation = exifInterface.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                rotationDegrees = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not determine EXIF orientation: ${e.message}")
        }

        // 2. Decode bitmap with downsampling
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, options)
        }

        val origWidth = options.outWidth
        val origHeight = options.outHeight

        var sampleSize = 1
        val maxOrigDim = maxOf(origWidth, origHeight)
        while (maxOrigDim / (sampleSize * 2) >= MAX_IMAGE_SIDE_PX) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val decodedBitmap: Bitmap = context.contentResolver.openInputStream(uri)?.use { inputStream ->
            BitmapFactory.decodeStream(inputStream, null, decodeOptions)
        } ?: throw IllegalArgumentException("Failed to decode image from uri: $uri")

        // 3. Fix rotation & scale longest side to max 1600px
        val finalBitmap = scaleAndRotateBitmap(decodedBitmap, MAX_IMAGE_SIDE_PX, rotationDegrees)

        // 4. Save full image with JPEG quality 80
        FileOutputStream(targetImageFile).use { out ->
            finalBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }

        // 5. Generate and save 256px thumbnail
        val thumbBitmap = scaleBitmap(finalBitmap, THUMB_SIDE_PX)
        FileOutputStream(targetThumbFile).use { out ->
            thumbBitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        }

        if (finalBitmap !== decodedBitmap && !decodedBitmap.isRecycled) {
            decodedBitmap.recycle()
        }
        if (!finalBitmap.isRecycled) {
            finalBitmap.recycle()
        }
        if (!thumbBitmap.isRecycled) {
            thumbBitmap.recycle()
        }

        Attachment(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            filePath = targetImageFile.absolutePath,
            thumbPath = targetThumbFile.absolutePath,
            mimeType = "image/jpeg",
            sizeBytes = targetImageFile.length(),
            caption = caption,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * Saves an edited (cropped / rotated / written-on) photo over [attachment]'s own files,
     * so every entry, bill and backup that points at them sees the new picture. Writes to a
     * temp file first, so a failure leaves the old photo intact.
     */
    suspend fun saveEditedImage(attachment: Attachment, edited: Bitmap): Attachment = withContext(Dispatchers.IO) {
        val image = File(attachment.filePath)
        val tmp = File(image.parentFile, "${image.name}.edit.tmp")
        val scaled = scaleBitmap(edited, MAX_IMAGE_SIDE_PX)
        try {
            FileOutputStream(tmp).use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            if (!tmp.renameTo(image)) {
                tmp.copyTo(image, overwrite = true)
                tmp.delete()
            }
            val thumbPath = attachment.thumbPath.ifBlank { File(image.parentFile, "${attachment.id}_thumb.jpg").absolutePath }
            val thumb = scaleBitmap(scaled, THUMB_SIDE_PX)
            FileOutputStream(thumbPath).use { out -> thumb.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
            if (thumb !== scaled) thumb.recycle()
            attachment.copy(thumbPath = thumbPath, sizeBytes = image.length())
        } finally {
            tmp.delete()
            if (scaled !== edited) scaled.recycle()
        }
    }

    suspend fun processAndSavePdf(
        context: Context,
        uri: Uri,
        ownerType: String = AttachmentOwnerType.ENTRY.raw,
        ownerId: String,
        caption: String = ""
    ): Attachment = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val dir = getAttachmentsDir(context)
        val targetPdfFile = File(dir, "$id.pdf")
        val targetThumbFile = File(dir, "${id}_thumb.jpg")

        // 1. Copy PDF stream and check max 10MB limit
        var bytesCopied = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetPdfFile).use { output ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    bytesCopied += read
                    if (bytesCopied > MAX_PDF_SIZE_BYTES) {
                        targetPdfFile.delete()
                        throw IllegalStateException("PDF file size exceeds maximum limit of 10 MB")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("Could not open input stream for PDF: $uri")

        // 2. Render first page thumbnail using PdfRenderer if possible
        var thumbPath = ""
        try {
            val pfd = ParcelFileDescriptor.open(targetPdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            if (renderer.pageCount > 0) {
                val page = renderer.openPage(0)
                val width = page.width
                val height = page.height
                val scale = THUMB_SIDE_PX.toFloat() / maxOf(width, height)
                val thumbW = (width * scale).toInt().coerceAtLeast(1)
                val thumbH = (height * scale).toInt().coerceAtLeast(1)
                val thumbBmp = Bitmap.createBitmap(thumbW, thumbH, Bitmap.Config.ARGB_8888)
                thumbBmp.eraseColor(android.graphics.Color.WHITE)
                page.render(thumbBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                FileOutputStream(targetThumbFile).use { out ->
                    thumbBmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                thumbBmp.recycle()
                page.close()
                thumbPath = targetThumbFile.absolutePath
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            Log.w(TAG, "Could not render PDF thumbnail: ${e.message}")
        }

        Attachment(
            id = id,
            ownerType = ownerType,
            ownerId = ownerId,
            filePath = targetPdfFile.absolutePath,
            thumbPath = thumbPath,
            mimeType = "application/pdf",
            sizeBytes = targetPdfFile.length(),
            caption = caption,
            createdAt = System.currentTimeMillis()
        )
    }

    private fun scaleAndRotateBitmap(source: Bitmap, maxDimension: Int, rotationDegrees: Int): Bitmap {
        val width = source.width
        val height = source.height
        val scale = if (maxOf(width, height) > maxDimension) {
            maxDimension.toFloat() / maxOf(width, height)
        } else {
            1.0f
        }

        val matrix = Matrix()
        if (scale < 1.0f) {
            matrix.postScale(scale, scale)
        }
        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        return if (scale < 1.0f || rotationDegrees != 0) {
            Bitmap.createBitmap(source, 0, 0, width, height, matrix, true)
        } else {
            source
        }
    }

    private fun scaleBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val width = source.width
        val height = source.height
        val scale = if (maxOf(width, height) > maxDimension) {
            maxDimension.toFloat() / maxOf(width, height)
        } else {
            1.0f
        }
        if (scale >= 1.0f) return source

        val targetW = (width * scale).toInt().coerceAtLeast(1)
        val targetH = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, targetW, targetH, true)
    }

    fun deleteAttachmentFiles(attachment: Attachment) {
        try {
            if (attachment.filePath.isNotBlank()) {
                val f = File(attachment.filePath)
                if (f.exists()) f.delete()
            }
            if (attachment.thumbPath.isNotBlank()) {
                val t = File(attachment.thumbPath)
                if (t.exists()) t.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting attachment files: ${e.message}", e)
        }
    }

    fun cleanupOrphanFiles(context: Context, validFilePaths: Set<String>): Int {
        val dir = getAttachmentsDir(context)
        val files = dir.listFiles() ?: return 0
        var deletedCount = 0
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000L

        for (file in files) {
            val absPath = file.absolutePath
            // If file is not in valid paths and was modified > 1 hour ago
            if (!validFilePaths.contains(absPath) && file.lastModified() < oneHourAgo) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    fun loadBitmap(filePath: String): Bitmap? {
        return try {
            val f = File(filePath)
            if (f.exists() && f.length() > 0) {
                BitmapFactory.decodeFile(filePath)
            } else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Lets a not-yet-saved attachment list survive Android killing the app while the
 * camera or gallery is open (common on low-RAM phones). The files are already on
 * disk; only these few fields need to be kept.
 */
val AttachmentListSaver: androidx.compose.runtime.saveable.Saver<List<com.example.model.Attachment>, Any> =
    androidx.compose.runtime.saveable.listSaver(
        save = { list ->
            list.map {
                arrayListOf<Any>(
                    it.id, it.ownerType, it.ownerId, it.filePath, it.thumbPath,
                    it.mimeType, it.sizeBytes, it.caption, it.createdAt
                )
            }
        },
        restore = { saved ->
            saved.map {
                @Suppress("UNCHECKED_CAST")
                val f = it as List<Any>
                com.example.model.Attachment(
                    id = f[0] as String,
                    ownerType = f[1] as String,
                    ownerId = f[2] as String,
                    filePath = f[3] as String,
                    thumbPath = f[4] as String,
                    mimeType = f[5] as String,
                    sizeBytes = f[6] as Long,
                    caption = f[7] as String,
                    createdAt = f[8] as Long
                )
            }
        }
    )
