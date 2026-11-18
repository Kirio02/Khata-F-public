package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.model.InvoiceThemeConfig
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InvoiceDesignSelection(
    currentTheme: String,
    storeName: String = "Ghanshyam Kids Wear",
    lang: AppLanguage = AppLanguage.HINGLISH,
    onThemeSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    val themes = remember {
        listOf(
            InvoiceThemeConfig("PREMIUM_BLUE", "Premium Blue", "#1E56A0", "#00ADB5", isPremium = true, badgeText = "POPULAR"),
            InvoiceThemeConfig("MODERN_GST", "Modern GST", "#163172", "#E2E8F0", isPremium = false, badgeText = "FREE"),
            InvoiceThemeConfig("ROYAL_GOLD", "Royal Gold", "#D4AF37", "#0F172A", isPremium = true, badgeText = "VIP"),
            InvoiceThemeConfig("MINIMAL_CLEAN", "Minimal Clean", "#2D3748", "#EDF2F7", isPremium = false, badgeText = "LIGHT"),
            InvoiceThemeConfig("EMERALD_GREEN", "Emerald Green", "#2E7D32", "#A5D6A7", isPremium = true, badgeText = "NEW")
        )
    }

    var selectedIndex by remember {
        mutableStateOf(themes.indexOfFirst { it.id == currentTheme }.coerceAtLeast(0))
    }

    var fontSize by remember { mutableStateOf("Medium") }
    var showWatermark by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = LanguageStrings.get("invoice_customization_title", lang),
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = LanguageStrings.get("cancel_button", lang),
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SoftBackground)
                .verticalScroll(rememberScrollState())
        ) {
            // Central Invoice Preview Carousel Box
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(290.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    val activeTheme = themes[selectedIndex]

                    // Central Preview Representation
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Header simulated in the theme color
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(android.graphics.Color.parseColor(activeTheme.primaryColorHex)))
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = LanguageStrings.get("tax_invoice_label", lang),
                                    color = SurfaceWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = storeName.ifBlank { "Ghanshyam Kids Wear" },
                                    color = SurfaceWhite.copy(alpha = 0.9f),
                                    fontSize = 11.sp
                                )
                            }
                            Text(
                                text = LanguageStrings.get("sample_bill_number", lang),
                                color = SurfaceWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }

                        // Simulated Item Rows
                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = LanguageStrings.get("invoice_item_col", lang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = LanguageStrings.get("invoice_qty_col", lang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = LanguageStrings.get("invoice_price_col", lang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = LanguageStrings.get("invoice_total_col", lang),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            HorizontalDivider(color = CardBorder)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = LanguageStrings.get("sample_item_1", lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "12",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = formatIndianCurrency(180.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = formatIndianCurrency(2160.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = LanguageStrings.get("sample_item_2", lang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = "6",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = formatIndianCurrency(250.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextPrimary
                                )
                                Text(
                                    text = formatIndianCurrency(1500.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }

                        // Bottom simulated footer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(ChipInactiveBg)
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageStrings.get("grand_total_label", lang),
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                fontSize = 13.sp
                            )
                            Text(
                                text = formatIndianCurrency(3660.0),
                                fontWeight = FontWeight.Bold,
                                color = Color(android.graphics.Color.parseColor(activeTheme.primaryColorHex)),
                                fontSize = 15.sp
                            )
                        }
                    }

                    // Left Carousel Button
                    IconButton(
                        onClick = {
                            if (selectedIndex > 0) {
                                selectedIndex--
                            } else {
                                selectedIndex = themes.size - 1
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(4.dp)
                            .size(36.dp)
                            .background(SurfaceWhite.copy(alpha = 0.85f), CircleShape)
                            .border(1.dp, CardBorder, CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = LanguageStrings.get("btn_cancel", lang),
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Right Carousel Button
                    IconButton(
                        onClick = {
                            if (selectedIndex < themes.size - 1) {
                                selectedIndex++
                            } else {
                                selectedIndex = 0
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(4.dp)
                            .size(36.dp)
                            .background(SurfaceWhite.copy(alpha = 0.85f), CircleShape)
                            .border(1.dp, CardBorder, CircleShape)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = LanguageStrings.get("btn_continue", lang),
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Theme details action card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = TextPrimary),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(GoldAccent)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = themes[selectedIndex].badgeText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = themes[selectedIndex].name.uppercase(),
                                fontWeight = FontWeight.Bold,
                                color = SurfaceWhite,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = LanguageStrings.get("invoice_features_desc", lang),
                            style = MaterialTheme.typography.bodySmall,
                            color = SurfaceWhite.copy(alpha = 0.7f)
                        )
                    }
                    Button(
                        onClick = { onThemeSelected(themes[selectedIndex].id) },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = LanguageStrings.get("btn_apply", lang),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Horizontal Selector
            Text(
                text = LanguageStrings.get("choose_invoice_style", lang),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(themes) { index, t ->
                    val isSelected = index == selectedIndex
                    Card(
                        modifier = Modifier
                            .width(130.dp)
                            .clickable {
                                selectedIndex = index
                                onThemeSelected(t.id)
                            },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = if (isSelected) ChipActiveBg else SurfaceWhite),
                        border = if (isSelected) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(PrimaryBlue)) else CardDefaults.outlinedCardBorder()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(android.graphics.Color.parseColor(t.primaryColorHex)))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = t.name,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 12.sp,
                                color = if (isSelected) PrimaryBlue else TextPrimary
                            )
                            val badgeLabel = if (t.isPremium) LanguageStrings.get("badge_premium", lang) else LanguageStrings.get("badge_free", lang)
                            Text(
                                text = badgeLabel,
                                color = if (t.isPremium) GoldAccent else JamaGreen,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Print Settings
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                    Text(
                        text = LanguageStrings.get("print_settings_title", lang),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = LanguageStrings.get("invoice_font_size", lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "Small" to LanguageStrings.get("size_small", lang),
                                "Medium" to LanguageStrings.get("size_medium", lang),
                                "Large" to LanguageStrings.get("size_large", lang)
                            ).forEach { (sizeKey, sizeLabel) ->
                                val active = fontSize == sizeKey
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) PrimaryBlue else ChipInactiveBg)
                                        .clickable { fontSize = sizeKey }
                                        .padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = sizeLabel,
                                        color = if (active) SurfaceWhite else TextPrimary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = LanguageStrings.get("show_upi_qr_bill", lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextPrimary
                        )
                        Switch(
                            checked = showWatermark,
                            onCheckedChange = { showWatermark = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryBlue)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(30.dp))
        }
    }
}
