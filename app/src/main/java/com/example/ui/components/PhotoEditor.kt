package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AppLanguage
import com.example.model.Attachment
import com.example.ui.theme.OnBrand
import com.example.ui.theme.PrimaryBlue
import com.example.ui.util.AttachmentManager
import com.example.ui.util.LanguageStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bumped whenever a photo file is edited in place, so screens that cache decoded photos
 * (thumbnails, the full-screen viewer) decode them again.
 */
object PhotoEdits {
    var version by mutableIntStateOf(0)
}

private enum class EditMode { CROP, DRAW }

/** A pen line. Points are 0..1 fractions of the (rotated) photo, so they survive resizing. */
private class PenStroke(val color: Color, val points: List<Offset>)

private val PEN_COLORS = listOf(Color(0xFFE53935), Color(0xFF1E40AF), Color.Black, Color.White)
private const val PEN_WIDTH = 0.008f // of the photo's shorter side
private const val MIN_CROP = 0.1f

/**
 * Full-screen photo editor: crop (drag the corners or move the box), rotate, and write on
 * the photo with a finger. "Save" writes over the same photo file; "X" leaves it as it was.
 */
@Composable
fun PhotoEditorDialog(
    attachment: Attachment,
    currentLang: AppLanguage,
    onDismiss: () -> Unit,
    onSaved: (Attachment) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun t(key: String) = LanguageStrings.get(key, currentLang)

    val source by produceState<Bitmap?>(null, attachment.filePath) {
        value = withContext(Dispatchers.IO) {
            try {
                if (File(attachment.filePath).exists()) BitmapFactory.decodeFile(attachment.filePath) else null
            } catch (e: Throwable) {
                null
            }
        }
    }

    var rotation by remember { mutableIntStateOf(0) }
    var crop by remember { mutableStateOf(Rect(0f, 0f, 1f, 1f)) }
    val strokes = remember { mutableStateListOf<PenStroke>() }
    val current = remember { mutableStateListOf<Offset>() }
    var mode by remember { mutableStateOf(EditMode.CROP) }
    var penColor by remember { mutableStateOf(PEN_COLORS.first()) }
    var saving by remember { mutableStateOf(false) }
    val changed = rotation != 0 || strokes.isNotEmpty() || crop != Rect(0f, 0f, 1f, 1f)

    val rotated: Bitmap? = remember(source, rotation) {
        source?.let { src ->
            if (rotation == 0) src else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
    }

    fun rotateRight() {
        // A point (x, y) on the photo moves to (1 - y, x) when the photo turns 90° clockwise.
        fun turn(p: Offset) = Offset(1f - p.y, p.x)
        val turned = strokes.map { s -> PenStroke(s.color, s.points.map(::turn)) }
        strokes.clear(); strokes.addAll(turned)
        crop = Rect(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)
        rotation = (rotation + 90) % 360
    }

    fun save() {
        val bmp = rotated ?: return
        saving = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.Default) { render(bmp, strokes.toList(), crop) }
                val saved = AttachmentManager.saveEditedImage(attachment, result)
                if (result !== bmp) result.recycle()
                PhotoEdits.version++
                onSaved(saved)
                onDismiss()
            } catch (e: Throwable) {
                Toast.makeText(context, t("photo_edit_failed"), Toast.LENGTH_SHORT).show()
                saving = false
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        FullScreenDialogWindow(blackBackground = true)
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            // Top bar: close, title, save.
            Row(
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss, enabled = !saving) {
                    Icon(Icons.Default.Close, contentDescription = t("btn_cancel"), tint = OnBrand)
                }
                Text(
                    text = t("photo_edit_title"),
                    color = OnBrand,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { save() },
                    enabled = changed && !saving && rotated != null,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier.heightIn(min = 44.dp)
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = OnBrand, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = t("btn_save"), fontWeight = FontWeight.Bold)
                }
            }

            // The photo.
            Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                val bmp = rotated
                if (bmp == null) {
                    CircularProgressIndicator(color = OnBrand)
                } else {
                    EditorCanvas(
                        bitmap = bmp,
                        mode = mode,
                        crop = crop,
                        onCrop = { crop = it },
                        strokes = strokes,
                        current = current,
                        penColor = penColor,
                        onStrokeDone = { pts -> if (pts.size > 1) strokes.add(PenStroke(penColor, pts)) }
                    )
                }
            }

            // Bottom tools.
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = t(if (mode == EditMode.CROP) "photo_edit_crop_hint" else "photo_edit_draw_hint"),
                    color = Color.White.copy(alpha = 0.75f),
                    fontSize = 13.sp
                )
                if (mode == EditMode.DRAW) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        PEN_COLORS.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(if (penColor == c) 3.dp else 1.dp, if (penColor == c) PrimaryBlue else Color.Gray, CircleShape)
                                    .clickable { penColor = c }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        TextButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }, enabled = strokes.isNotEmpty()) {
                            Icon(Icons.Default.Undo, contentDescription = null, tint = OnBrand)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = t("photo_edit_undo"), color = OnBrand, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ToolButton(Icons.Default.Crop, t("photo_edit_crop"), mode == EditMode.CROP, Modifier.weight(1f)) { mode = EditMode.CROP }
                    ToolButton(Icons.Default.Edit, t("photo_edit_draw"), mode == EditMode.DRAW, Modifier.weight(1f)) { mode = EditMode.DRAW }
                    ToolButton(Icons.Default.RotateRight, t("photo_edit_rotate"), false, Modifier.weight(1f)) { rotateRight() }
                    ToolButton(Icons.Default.Refresh, t("photo_edit_reset"), false, Modifier.weight(1f)) {
                        rotation = 0; crop = Rect(0f, 0f, 1f, 1f); strokes.clear()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PrimaryBlue else Color.White.copy(alpha = 0.1f))
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(text = label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun EditorCanvas(
    bitmap: Bitmap,
    mode: EditMode,
    crop: Rect,
    onCrop: (Rect) -> Unit,
    strokes: List<PenStroke>,
    current: MutableList<Offset>,
    penColor: Color,
    onStrokeDone: (List<Offset>) -> Unit
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    val handlePx = with(LocalDensity.current) { 28.dp.toPx() }
    val latestCrop by rememberUpdatedState(crop)

    // Where the photo sits inside the box (fitted, centred).
    val imgRect: Rect = run {
        if (box == IntSize.Zero) return@run Rect.Zero
        val s = minOf(box.width / bitmap.width.toFloat(), box.height / bitmap.height.toFloat())
        val w = bitmap.width * s; val h = bitmap.height * s
        Rect(Offset((box.width - w) / 2f, (box.height - h) / 2f), Size(w, h))
    }
    fun toPhoto(p: Offset) = Offset(
        ((p.x - imgRect.left) / imgRect.width).coerceIn(0f, 1f),
        ((p.y - imgRect.top) / imgRect.height).coerceIn(0f, 1f)
    )
    fun toScreen(p: Offset) = Offset(imgRect.left + p.x * imgRect.width, imgRect.top + p.y * imgRect.height)

    val gestures = when (mode) {
        EditMode.DRAW -> Modifier.pointerInput(mode, imgRect) {
            detectDragGestures(
                onDragStart = { current.clear(); current.add(toPhoto(it)) },
                onDrag = { change, _ -> change.consume(); current.add(toPhoto(change.position)) },
                onDragEnd = { onStrokeDone(current.toList()); current.clear() },
                onDragCancel = { current.clear() }
            )
        }
        EditMode.CROP -> Modifier.pointerInput(mode, imgRect) {
            // 0..3 = corners (TL, TR, BR, BL), 4 = move the whole box, -1 = nothing.
            var grab = -1
            detectDragGestures(
                onDragStart = { start ->
                    val c = latestCrop
                    val corners = listOf(
                        toScreen(Offset(c.left, c.top)), toScreen(Offset(c.right, c.top)),
                        toScreen(Offset(c.right, c.bottom)), toScreen(Offset(c.left, c.bottom))
                    )
                    val nearest = corners.indices.minBy { (corners[it] - start).getDistance() }
                    grab = when {
                        (corners[nearest] - start).getDistance() <= handlePx * 1.6f -> nearest
                        Rect(corners[0], corners[2]).contains(start) -> 4
                        else -> -1
                    }
                },
                onDrag = { change, drag ->
                    change.consume()
                    if (grab < 0 || imgRect.width <= 0f) return@detectDragGestures
                    val dx = drag.x / imgRect.width; val dy = drag.y / imgRect.height
                    val c = latestCrop
                    val next = when (grab) {
                        0 -> c.copy(left = (c.left + dx).coerceIn(0f, c.right - MIN_CROP), top = (c.top + dy).coerceIn(0f, c.bottom - MIN_CROP))
                        1 -> c.copy(right = (c.right + dx).coerceIn(c.left + MIN_CROP, 1f), top = (c.top + dy).coerceIn(0f, c.bottom - MIN_CROP))
                        2 -> c.copy(right = (c.right + dx).coerceIn(c.left + MIN_CROP, 1f), bottom = (c.bottom + dy).coerceIn(c.top + MIN_CROP, 1f))
                        3 -> c.copy(left = (c.left + dx).coerceIn(0f, c.right - MIN_CROP), bottom = (c.bottom + dy).coerceIn(c.top + MIN_CROP, 1f))
                        else -> {
                            val mx = dx.coerceIn(-c.left, 1f - c.right)
                            val my = dy.coerceIn(-c.top, 1f - c.bottom)
                            c.translate(mx, my)
                        }
                    }
                    onCrop(next)
                }
            )
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .then(gestures)
    ) {
        if (imgRect == Rect.Zero) return@Canvas
        drawImage(
            image,
            dstOffset = IntOffset(imgRect.left.toInt(), imgRect.top.toInt()),
            dstSize = IntSize(imgRect.width.toInt(), imgRect.height.toInt())
        )
        val penPx = PEN_WIDTH * minOf(imgRect.width, imgRect.height)
        fun drawPen(color: Color, pts: List<Offset>) {
            if (pts.size < 2) return
            val path = Path().apply {
                val first = toScreen(pts.first()); moveTo(first.x, first.y)
                pts.drop(1).forEach { val p = toScreen(it); lineTo(p.x, p.y) }
            }
            drawPath(path, color, style = Stroke(width = penPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        strokes.forEach { drawPen(it.color, it.points) }
        drawPen(penColor, current)

        // Dim what will be cut away, then outline the kept part.
        val tl = toScreen(Offset(crop.left, crop.top)); val br = toScreen(Offset(crop.right, crop.bottom))
        val shade = Color.Black.copy(alpha = 0.55f)
        drawRect(shade, Offset(imgRect.left, imgRect.top), Size(imgRect.width, tl.y - imgRect.top))
        drawRect(shade, Offset(imgRect.left, br.y), Size(imgRect.width, imgRect.bottom - br.y))
        drawRect(shade, Offset(imgRect.left, tl.y), Size(tl.x - imgRect.left, br.y - tl.y))
        drawRect(shade, Offset(br.x, tl.y), Size(imgRect.right - br.x, br.y - tl.y))
        drawRect(Color.White, tl, Size(br.x - tl.x, br.y - tl.y), style = Stroke(width = 2.dp.toPx()))
        if (mode == EditMode.CROP) {
            listOf(tl, Offset(br.x, tl.y), br, Offset(tl.x, br.y)).forEach {
                drawCircle(Color.White, radius = 11.dp.toPx(), center = it)
                drawCircle(PrimaryBlue, radius = 7.dp.toPx(), center = it)
            }
        }
    }
}

/** Draws the pen lines onto [photo] and cuts it to [crop] (both in 0..1 photo fractions). */
private fun render(photo: Bitmap, strokes: List<PenStroke>, crop: Rect): Bitmap {
    val w = photo.width; val h = photo.height
    var out = photo
    if (strokes.isNotEmpty()) {
        out = photo.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            strokeWidth = PEN_WIDTH * minOf(w, h)
        }
        for (s in strokes) {
            paint.color = s.color.toArgb()
            val path = android.graphics.Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x * w, p.y * h) else path.lineTo(p.x * w, p.y * h) }
            canvas.drawPath(path, paint)
        }
    }
    if (crop == Rect(0f, 0f, 1f, 1f)) return out
    val l = (crop.left * w).toInt().coerceIn(0, w - 1)
    val t = (crop.top * h).toInt().coerceIn(0, h - 1)
    val cw = ((crop.right - crop.left) * w).toInt().coerceIn(1, w - l)
    val ch = ((crop.bottom - crop.top) * h).toInt().coerceIn(1, h - t)
    val cut = Bitmap.createBitmap(out, l, t, cw, ch)
    if (out !== photo && cut !== out) out.recycle()
    return cut
}
