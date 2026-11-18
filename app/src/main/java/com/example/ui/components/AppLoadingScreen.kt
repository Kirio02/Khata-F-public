package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

@Composable
fun AppLoadingScreen(
    message: String = "Loading store data...",
    storeProfile: com.example.model.StoreProfile? = null
) {
    val shopName = storeProfile?.storeName?.ifBlank { null } ?: "Ghanshyam Kids Wear"
    val logoUri = storeProfile?.logoUri?.ifBlank { null }

    val context = androidx.compose.ui.platform.LocalContext.current
    val logoBitmap = remember(logoUri) {
        if (logoUri != null) {
            try {
                val uri = android.net.Uri.parse(logoUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceWhite),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            // "Shree Ganeshay Namah" small on top
            Text(
                text = com.example.ui.util.LanguageStrings.get("shree_ganeshay_namah"),
                style = MaterialTheme.typography.labelSmall,
                color = GoldAccent,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (logoBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = logoBitmap,
                    contentDescription = "Store Logo",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(BrandMaroon),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Store,
                        contentDescription = null,
                        tint = SurfaceWhite,
                        modifier = Modifier.size(38.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Shop name from profile in serif font
            Text(
                text = shopName,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = BrandSerifFontFamily),
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                fontSize = 24.sp
            )

            Spacer(modifier = Modifier.height(36.dp))

            CircularProgressIndicator(
                color = BrandCharcoal,
                strokeWidth = 3.dp,
                modifier = Modifier.size(36.dp)
            )

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                fontSize = 12.sp
            )
        }
    }
}
