package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.CardBorder
import com.example.ui.theme.ChipActiveBg
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.SurfaceWhite
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.util.LanguageStrings
import com.example.ui.util.PdfAccent
import com.example.ui.util.PdfLang
import com.example.ui.util.PdfLayout
import com.example.ui.util.PdfText
import com.example.ui.util.PdfPaper
import com.example.ui.util.PdfStyle

/** More > "PDF ka design": layout, colour, paper and photos for the statement and bill PDFs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PdfDesignCard(lang: AppLanguage) {
    val context = LocalContext.current
    var expanded by rememberSaveable { mutableStateOf(false) }
    var style by remember { mutableStateOf(PdfStyle.load(context)) }
    fun update(s: PdfStyle) {
        style = s
        PdfStyle.save(context, s)
    }
    fun t(key: String) = LanguageStrings.get(key, lang)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = CardDefaults.outlinedCardBorder()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }
            ) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(ChipActiveBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = t("pdf_design_title"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                    Text(text = t("pdf_design_desc"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = TextSecondary,
                    modifier = Modifier.size(28.dp)
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(14.dp))
                PdfPreview(style, style.langFor(lang))

                Spacer(modifier = Modifier.height(14.dp))
                Text(text = t("pdf_lang_label"), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfLang.entries.forEach { pl ->
                        FilterChip(
                            selected = style.langFor(lang) == pl,
                            onClick = { update(style.copy(lang = pl)) },
                            label = { Text(text = pl.label, fontWeight = FontWeight.Bold) }
                        )
                    }
                }
                Text(text = t("pdf_lang_hint"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = t("pdf_layout_label"), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = style.layout == PdfLayout.PHONE,
                        onClick = { update(style.copy(layout = PdfLayout.PHONE)) },
                        label = { Text(text = t("pdf_layout_phone"), fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = style.layout == PdfLayout.A4,
                        onClick = { update(style.copy(layout = PdfLayout.A4)) },
                        label = { Text(text = t("pdf_layout_a4"), fontWeight = FontWeight.Bold) }
                    )
                }
                Text(
                    text = t(if (style.layout == PdfLayout.PHONE) "pdf_layout_phone_hint" else "pdf_layout_a4_hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = t("pdf_color_label"), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfAccent.entries.forEach { a ->
                        val selected = style.accent == a
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { update(style.copy(accent = a)) }.padding(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(a.rgb))
                                    .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) TextPrimary else CardBorder), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            }
                            Text(text = t(a.labelKey), fontSize = 12.sp, color = TextPrimary, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(text = t("pdf_paper_label"), style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfPaper.entries.forEach { paper ->
                        FilterChip(
                            selected = style.paper == paper,
                            onClick = { update(style.copy(paper = paper)) },
                            label = { Text(text = t(paper.labelKey), fontWeight = FontWeight.Bold) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { update(style.copy(photos = !style.photos)) }
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = t("pdf_photos_label"), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                        Text(text = t("pdf_photos_hint"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(checked = style.photos, onCheckedChange = { update(style.copy(photos = it)) })
                }
            }
        }
    }
}

/** A tiny drawing of the PDF's top, so the colour choice is visible before sharing. */
@Composable
private fun PdfPreview(style: PdfStyle, pdfLang: PdfLang) {
    fun w(key: String) = PdfText.get(key, pdfLang)
    val dr = w("debit").let { if (it == "Debit") "Dr" else it }
    val accent = Color(style.accent.rgb)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
            .background(Color(style.paper.rgb))
    ) {
        Box(modifier = Modifier.fillMaxWidth().background(accent).padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(text = "Ghanshyam Kids Wear", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Color(style.tint)).padding(8.dp)
            ) {
                Text(text = w("balance_due") + "  ₹6,775.00", color = Color(0xFFC81E1E), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "CH. NO. 985", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(text = "₹3,325.00 $dr", color = Color(0xFFC81E1E), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
    }
}
