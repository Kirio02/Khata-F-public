package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.model.AppLanguage
import com.example.model.Attachment
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.UdharRed
import com.example.ui.util.LanguageStrings
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenAttachmentViewer(
    attachments: List<Attachment>,
    initialIndex: Int = 0,
    currentLang: AppLanguage,
    onDismiss: () -> Unit,
    onDelete: ((Attachment) -> Unit)? = null,
    onUpdateCaption: ((Attachment, String) -> Unit)? = null
) {
    if (attachments.isEmpty()) {
        onDismiss()
        return
    }

    val context = LocalContext.current
    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, attachments.size - 1),
        pageCount = { attachments.size }
    )

    var showDeleteDialog by remember { mutableStateOf(false) }
    var isEditingCaption by remember { mutableStateOf(false) }

    val currentAttachment = attachments.getOrNull(pagerState.currentPage) ?: attachments.first()
    var captionText by remember(currentAttachment.id) { mutableStateOf(currentAttachment.caption) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            // Pager for attachments
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val att = attachments[page]
                val isPdf = att.mimeType == "application/pdf"

                if (isPdf) {
                    PdfViewerView(
                        attachment = att,
                        context = context,
                        currentLang = currentLang
                    )
                } else {
                    ZoomableImageView(attachment = att)
                }
            }

            // Top Bar: Back, Page Count, Share, Delete
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = LanguageStrings.get("btn_cancel", currentLang),
                        tint = SurfaceWhite
                    )
                }

                Text(
                    text = "${pagerState.currentPage + 1} / ${attachments.size}",
                    color = SurfaceWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )

                Row {
                    // Share Button
                    IconButton(
                        onClick = {
                            shareAttachment(context, currentAttachment)
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = LanguageStrings.get("btn_share", currentLang),
                            tint = SurfaceWhite
                        )
                    }

                    // Delete Button
                    if (onDelete != null) {
                        IconButton(onClick = { showDeleteDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = LanguageStrings.get("btn_delete", currentLang),
                                tint = UdharRed
                            )
                        }
                    }
                }
            }

            // Bottom Bar: Caption and Date
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding(),
                color = Color.Black.copy(alpha = 0.75f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(currentAttachment.createdAt))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateStr,
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                        if (onUpdateCaption != null && !isEditingCaption) {
                            TextButton(onClick = { isEditingCaption = true }) {
                                Icon(Icons.Default.Edit, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = LanguageStrings.get("btn_edit", currentLang),
                                    color = PrimaryBlue,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (isEditingCaption && onUpdateCaption != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = captionText,
                                onValueChange = { captionText = it },
                                placeholder = {
                                    Text(
                                        text = LanguageStrings.get("viewer_caption_placeholder", currentLang),
                                        color = Color.Gray
                                    )
                                },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White,
                                    focusedBorderColor = PrimaryBlue,
                                    unfocusedBorderColor = Color.Gray
                                )
                            )
                            Button(
                                onClick = {
                                    onUpdateCaption(currentAttachment, captionText.trim())
                                    isEditingCaption = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                            ) {
                                Text(
                                    text = LanguageStrings.get("btn_save", currentLang),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else if (currentAttachment.caption.isNotBlank()) {
                        Text(
                            text = currentAttachment.caption,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Confirm Delete Dialog
            if (showDeleteDialog) {
                AlertDialog(
                    onDismissRequest = { showDeleteDialog = false },
                    title = {
                        Text(
                            text = LanguageStrings.get("confirm_delete_attachment", currentLang),
                            fontWeight = FontWeight.Bold
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                onDelete?.invoke(currentAttachment)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = UdharRed)
                        ) {
                            Text(
                                text = LanguageStrings.get("btn_delete", currentLang),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(text = LanguageStrings.get("btn_cancel", currentLang))
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ZoomableImageView(attachment: Attachment) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val file = remember(attachment.filePath) { File(attachment.filePath) }
    val bitmap = remember(attachment.filePath) {
        if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(attachment.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    if (scale > 1f) {
                        val maxOffsetX = (size.width * (scale - 1f)) / 2f
                        val maxOffsetY = (size.height * (scale - 1f)) / 2f
                        offsetX = (offsetX + pan.x * scale).coerceIn(-maxOffsetX, maxOffsetX)
                        offsetY = (offsetY + pan.y * scale).coerceIn(-maxOffsetY, maxOffsetY)
                    } else {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = attachment.caption.ifBlank { "Photo" },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
        } else {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(64.dp)
            )
        }
    }
}

@Composable
private fun PdfViewerView(
    attachment: Attachment,
    context: Context,
    currentLang: AppLanguage
) {
    val thumbFile = remember(attachment.thumbPath) {
        if (attachment.thumbPath.isNotBlank()) File(attachment.thumbPath) else null
    }
    val thumbBmp = remember(thumbFile?.absolutePath) {
        if (thumbFile != null && thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (thumbBmp != null) {
            Surface(
                modifier = Modifier
                    .size(220.dp, 300.dp)
                    .clip(RoundedCornerShape(8.dp)),
                shadowElevation = 8.dp
            ) {
                Image(
                    bitmap = thumbBmp.asImageBitmap(),
                    contentDescription = LanguageStrings.get("pdf_file_label", currentLang),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        } else {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = LanguageStrings.get("pdf_file_label", currentLang),
                tint = UdharRed,
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = File(attachment.filePath).name,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        val sizeKb = attachment.sizeBytes / 1024
        Text(
            text = "$sizeKb KB",
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                openPdfExternal(context, attachment)
            },
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = LanguageStrings.get("open_pdf_btn", currentLang),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun openPdfExternal(context: Context, attachment: Attachment) {
    try {
        val file = File(attachment.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "No PDF viewer app found", Toast.LENGTH_SHORT).show()
    }
}

private fun shareAttachment(context: Context, attachment: Attachment) {
    try {
        val file = File(attachment.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = attachment.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            if (attachment.caption.isNotBlank()) {
                putExtra(Intent.EXTRA_TEXT, attachment.caption)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Attachment"))
    } catch (e: Exception) {
        Toast.makeText(context, "Failed to share attachment", Toast.LENGTH_SHORT).show()
    }
}
