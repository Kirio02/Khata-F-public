package com.example.ui.util

import android.content.Context
import android.util.Log
import com.example.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object ZipBackupHelper {

    private const val TAG = "ZipBackupHelper"

    suspend fun exportBackupZip(
        context: Context,
        jsonData: String,
        attachments: List<Attachment>,
        onProgress: ((Float, String) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val backupDir = File(context.cacheDir, "backups").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val zipFile = File(backupDir, "GKW_Backup_$dateStr.zip")

        val filesToZip = mutableListOf<File>()
        attachments.forEach { att ->
            if (att.filePath.isNotBlank()) {
                val f = File(att.filePath)
                if (f.exists() && f.isFile) filesToZip.add(f)
            }
            if (att.thumbPath.isNotBlank()) {
                val t = File(att.thumbPath)
                if (t.exists() && t.isFile) filesToZip.add(t)
            }
        }
        val distinctFiles = filesToZip.distinctBy { it.absolutePath }
        val totalSteps = 1 + distinctFiles.size.coerceAtLeast(1)

        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            // 1. Write data.json
            onProgress?.invoke(0.1f, "Packing database records...")
            val jsonEntry = ZipEntry("data.json")
            zos.putNextEntry(jsonEntry)
            val jsonBytes = jsonData.toByteArray(Charsets.UTF_8)
            zos.write(jsonBytes)
            zos.closeEntry()

            // 2. Write attachment files
            val buffer = ByteArray(8192)
            distinctFiles.forEachIndexed { index, file ->
                val progress = 0.1f + 0.9f * ((index + 1).toFloat() / totalSteps)
                onProgress?.invoke(progress, "Packing photo ${index + 1}/${distinctFiles.size}...")

                try {
                    val entry = ZipEntry("attachments/${file.name}")
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { fis ->
                        var len: Int
                        while (fis.read(buffer).also { len = it } > 0) {
                            zos.write(buffer, 0, len)
                        }
                    }
                    zos.closeEntry()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to zip attachment file: ${file.name}", e)
                }
            }
        }

        onProgress?.invoke(1.0f, "Backup completed")
        zipFile
    }

    suspend fun extractBackup(
        context: Context,
        inputStream: InputStream,
        onProgress: ((Float, String) -> Unit)? = null
    ): ExtractedBackupData = withContext(Dispatchers.IO) {
        // Read into buffered byte array to check magic bytes
        val bytes = inputStream.readBytes()
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()) {
            // It's a ZIP archive
            val attachmentsDir = AttachmentManager.getAttachmentsDir(context)
            var jsonContent = ""
            var extractedFilesCount = 0

            val zis = ZipInputStream(ByteArrayInputStream(bytes))
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (entryName == "data.json") {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    var read: Int
                    while (zis.read(buf).also { read = it } != -1) {
                        out.write(buf, 0, read)
                    }
                    jsonContent = out.toString(Charsets.UTF_8.name())
                } else if (entryName.startsWith("attachments/") && !entry.isDirectory) {
                    val fileName = entryName.substringAfterLast("/")
                    if (fileName.isNotBlank()) {
                        val targetFile = File(attachmentsDir, fileName)
                        FileOutputStream(targetFile).use { fos ->
                            val buf = ByteArray(8192)
                            var read: Int
                            while (zis.read(buf).also { read = it } != -1) {
                                fos.write(buf, 0, read)
                            }
                        }
                        extractedFilesCount++
                        onProgress?.invoke(0.5f, "Restoring photo $extractedFilesCount...")
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
            zis.close()

            ExtractedBackupData(
                jsonContent = jsonContent,
                isZip = true,
                extractedAttachmentsCount = extractedFilesCount
            )
        } else {
            // Plain legacy JSON backup
            val jsonContent = bytes.toString(Charsets.UTF_8)
            ExtractedBackupData(
                jsonContent = jsonContent,
                isZip = false,
                extractedAttachmentsCount = 0
            )
        }
    }

    data class ExtractedBackupData(
        val jsonContent: String,
        val isZip: Boolean,
        val extractedAttachmentsCount: Int
    )
}
