package com.example.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.model.AppLanguage
import com.example.model.Attachment
import com.example.ui.theme.*
import com.example.ui.util.AttachmentManager
import com.example.ui.util.LanguageStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun AttachmentPickerStrip(
    attachments: List<Attachment>,
    onAttachmentsChanged: (List<Attachment>) -> Unit,
    ownerType: String,
    ownerId: String,
    currentLang: AppLanguage,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var showOptionsDialog by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    // rememberSaveable: on low-memory phones Android often kills the app while the
    // camera is open. Plain remember() would forget where the photo was saved.
    var tempCameraUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var tempCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingRemoval by remember { mutableStateOf<Attachment?>(null) }
    var viewerIndex by remember { mutableStateOf<Int?>(null) }

    fun removeAttachment(att: Attachment) {
        AttachmentManager.deleteAttachmentFiles(att)
        onAttachmentsChanged(attachments.filter { it.id != att.id })
    }

    fun clearTempCamera() {
        tempCameraPath?.let { File(it).delete() }
        tempCameraUri = null
        tempCameraPath = null
    }

    // Camera Launcher (TakePicture + FileProvider)
    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = tempCameraUri
        if (success && uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val att = AttachmentManager.processAndSaveImage(
                        context = context,
                        uri = uri,
                        ownerType = ownerType,
                        ownerId = ownerId
                    )
                    onAttachmentsChanged(attachments + att)
                } catch (e: Exception) {
                    Toast.makeText(context, LanguageStrings.get("photo_add_failed", currentLang), Toast.LENGTH_SHORT).show()
                } finally {
                    clearTempCamera()
                    isProcessing = false
                }
            }
        } else {
            clearTempCamera()
        }
    }

    // Gallery Launcher. The limit is a fixed 5: PickMultipleVisualMedia throws if
    // maxItems < 2, so it must not shrink as photos are added. Extra picks are
    // dropped below using the free slots.
    val pickMultipleVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = MAX_ATTACHMENTS)
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                isProcessing = true
                val remainingSlots = (MAX_ATTACHMENTS - attachments.size).coerceAtLeast(0)
                if (uris.size > remainingSlots) {
                    Toast.makeText(context, LanguageStrings.get("max_attachments_reached", currentLang), Toast.LENGTH_SHORT).show()
                }
                val newAttachments = mutableListOf<Attachment>()
                var failures = 0
                // Each photo on its own, so one bad file doesn't lose the others.
                for (u in uris.take(remainingSlots)) {
                    try {
                        newAttachments.add(
                            AttachmentManager.processAndSaveImage(
                                context = context,
                                uri = u,
                                ownerType = ownerType,
                                ownerId = ownerId
                            )
                        )
                    } catch (e: Exception) {
                        failures++
                    }
                }
                if (newAttachments.isNotEmpty()) onAttachmentsChanged(attachments + newAttachments)
                if (failures > 0) {
                    val key = if (newAttachments.isEmpty()) "photo_add_failed" else "some_photos_failed"
                    Toast.makeText(context, LanguageStrings.get(key, currentLang), Toast.LENGTH_SHORT).show()
                }
                isProcessing = false
            }
        }
    }

    // PDF Launcher (GetContent)
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val att = AttachmentManager.processAndSavePdf(
                        context = context,
                        uri = uri,
                        ownerType = ownerType,
                        ownerId = ownerId
                    )
                    onAttachmentsChanged(attachments + att)
                } catch (e: Exception) {
                    val msg = if (e.message?.contains("10 MB") == true) {
                        LanguageStrings.get("pdf_size_exceeded", currentLang)
                    } else {
                        LanguageStrings.get("pdf_add_failed", currentLang)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                } finally {
                    isProcessing = false
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Big "Photo" Button
            OutlinedButton(
                onClick = {
                    if (attachments.size >= MAX_ATTACHMENTS) {
                        Toast.makeText(
                            context,
                            LanguageStrings.get("max_attachments_reached", currentLang),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        showOptionsDialog = true
                    }
                },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                border = ButtonDefaults.outlinedButtonBorder()
            ) {
                Icon(
                    imageVector = Icons.Default.AddAPhoto,
                    contentDescription = LanguageStrings.get("btn_photo", currentLang),
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${LanguageStrings.get("btn_photo", currentLang)} (${attachments.size}/$MAX_ATTACHMENTS)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }

            // Thumbnail Strip
            attachments.forEachIndexed { index, att ->
                val thumbPath = att.thumbPath.ifBlank { att.filePath }
                // Decode off the main thread, downsampled, so cheap phones don't stutter
                // (and a missing thumb doesn't load a full 1600px photo).
                val bitmap by produceState<android.graphics.Bitmap?>(null, thumbPath) {
                    value = withContext(Dispatchers.IO) { decodeThumbnail(thumbPath, THUMB_DECODE_PX) }
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .background(ChipInactiveBg)
                        .clickable { viewerIndex = index },
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = att.caption.ifBlank { "${LanguageStrings.get("btn_photo", currentLang)} ${index + 1}" },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (att.mimeType == "application/pdf") {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = LanguageStrings.get("pdf_file_label", currentLang),
                            tint = UdharRed,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Remove (X): 40dp touch area, asks before deleting.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(40.dp)
                            .clickable { pendingRemoval = att },
                        contentAlignment = Alignment.TopEnd
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(3.dp)
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(SurfaceWhite.copy(alpha = 0.92f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = LanguageStrings.get("btn_delete", currentLang),
                                tint = UdharRed,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Confirm before removing a photo (a missed tap shouldn't delete it).
    pendingRemoval?.let { att ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(LanguageStrings.get("remove_photo_title", currentLang), fontWeight = FontWeight.Bold) },
            text = { Text(LanguageStrings.get("remove_photo_desc", currentLang)) },
            confirmButton = {
                TextButton(onClick = {
                    removeAttachment(att)
                    pendingRemoval = null
                }) {
                    Text(LanguageStrings.get("btn_delete", currentLang), color = UdharRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Tap a thumbnail to see it full screen (the viewer has its own delete confirm).
    viewerIndex?.let { start ->
        FullScreenAttachmentViewer(
            attachments = attachments,
            initialIndex = start.coerceIn(0, (attachments.size - 1).coerceAtLeast(0)),
            currentLang = currentLang,
            onDismiss = { viewerIndex = null },
            onDelete = { att ->
                removeAttachment(att)
                if (attachments.size <= 1) viewerIndex = null
            }
        )
    }

    // Modal Option Dialog: Camera | Gallery | PDF
    if (showOptionsDialog) {
        AlertDialog(
            onDismissRequest = { showOptionsDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("photo_options_title", currentLang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Option 1: Camera
                    ListItem(
                        headlineContent = {
                            Text(
                                text = LanguageStrings.get("option_camera", currentLang),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = PrimaryBlue)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showOptionsDialog = false
                                val (uri, file) = AttachmentManager.createTempCameraUri(context)
                                tempCameraUri = uri
                                tempCameraPath = file.absolutePath
                                takePictureLauncher.launch(uri)
                            }
                    )

                    // Option 2: Gallery
                    ListItem(
                        headlineContent = {
                            Text(
                                text = LanguageStrings.get("option_gallery", currentLang),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = JamaGreen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showOptionsDialog = false
                                pickMultipleVisualMediaLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                    )

                    // Option 3: PDF Document
                    ListItem(
                        headlineContent = {
                            Text(
                                text = LanguageStrings.get("option_pdf", currentLang),
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        leadingContent = {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = UdharRed)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                showOptionsDialog = false
                                pickPdfLauncher.launch("application/pdf")
                            }
                    )
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showOptionsDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Processing Progress Dialog
    if (isProcessing) {
        Dialog(onDismissRequest = {}) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = SurfaceWhite,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), color = PrimaryBlue)
                    Text(
                        text = LanguageStrings.get("processing_attachment", currentLang),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private const val MAX_ATTACHMENTS = 5
private const val THUMB_DECODE_PX = 200

/** Decodes an image file scaled down to roughly [reqPx] on its longer side. */
private fun decodeThumbnail(path: String, reqPx: Int): android.graphics.Bitmap? = try {
    val f = File(path)
    if (!f.exists() || f.length() == 0L) null else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= reqPx) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
} catch (e: Exception) {
    null
}
