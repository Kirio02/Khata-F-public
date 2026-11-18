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
    var tempCameraUri by remember { mutableStateOf<Uri?>(null) }
    var tempCameraFile by remember { mutableStateOf<File?>(null) }

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
                    Toast.makeText(context, e.message ?: "Failed to process photo", Toast.LENGTH_SHORT).show()
                } finally {
                    tempCameraFile?.delete()
                    tempCameraUri = null
                    tempCameraFile = null
                    isProcessing = false
                }
            }
        } else {
            tempCameraFile?.delete()
            tempCameraUri = null
            tempCameraFile = null
        }
    }

    // Gallery Launcher (PickMultipleVisualMedia max 5)
    val pickMultipleVisualMediaLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(
            maxItems = (5 - attachments.size).coerceAtLeast(1)
        )
    ) { uris ->
        if (uris.isNotEmpty()) {
            coroutineScope.launch {
                isProcessing = true
                try {
                    val remainingSlots = 5 - attachments.size
                    val toProcess = uris.take(remainingSlots)
                    val newAttachments = mutableListOf<Attachment>()
                    for (u in toProcess) {
                        val att = AttachmentManager.processAndSaveImage(
                            context = context,
                            uri = u,
                            ownerType = ownerType,
                            ownerId = ownerId
                        )
                        newAttachments.add(att)
                    }
                    onAttachmentsChanged(attachments + newAttachments)
                } catch (e: Exception) {
                    Toast.makeText(context, e.message ?: "Failed to process photo", Toast.LENGTH_SHORT).show()
                } finally {
                    isProcessing = false
                }
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
                        e.message ?: "Failed to process PDF"
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
                    if (attachments.size >= 5) {
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryBlue),
                border = ButtonDefaults.outlinedButtonBorder()
            ) {
                Icon(
                    imageVector = Icons.Default.AddAPhoto,
                    contentDescription = LanguageStrings.get("btn_photo", currentLang),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${LanguageStrings.get("btn_photo", currentLang)} (${attachments.size}/5)",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Thumbnail Strip
            attachments.forEachIndexed { index, att ->
                val thumbFile = if (att.thumbPath.isNotBlank()) File(att.thumbPath) else File(att.filePath)
                val bitmap = remember(thumbFile.absolutePath) {
                    if (thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                        .background(ChipInactiveBg),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = att.caption.ifBlank { "Attachment #${index + 1}" },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else if (att.mimeType == "application/pdf") {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = LanguageStrings.get("pdf_file_label", currentLang),
                            tint = UdharRed,
                            modifier = Modifier.size(24.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Remove (X) button at top-right
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(2.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(SurfaceWhite.copy(alpha = 0.9f))
                            .clickable {
                                AttachmentManager.deleteAttachmentFiles(att)
                                val updated = attachments.toMutableList()
                                updated.removeAt(index)
                                onAttachmentsChanged(updated)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = LanguageStrings.get("btn_delete", currentLang),
                            tint = UdharRed,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
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
                                tempCameraFile = file
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
