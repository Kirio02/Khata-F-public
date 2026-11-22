package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings

/** White / Cream / Dark / System, each drawn in its own colours so no reading is needed. */
@Composable
fun ThemePickerCard(lang: AppLanguage, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
        border = BorderStroke(1.dp, CardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = LanguageStrings.get("theme_title", lang),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = TextPrimary
            )
            Column(modifier = Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        pair.forEach { option ->
                            ThemeOption(
                                option = option,
                                label = LanguageStrings.get("theme_${option.name.lowercase()}", lang),
                                selected = AppTheme.choice == option,
                                onClick = { AppTheme.select(context, option) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
            Text(
                text = LanguageStrings.get("theme_system_hint", lang),
                fontSize = 11.sp,
                lineHeight = 14.sp,
                color = TextSecondary
            )
        }
    }
}

@Composable
private fun ThemeOption(
    option: ThemeChoice,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .border(if (selected) 3.dp else 1.dp, if (selected) PrimaryBlue else CardBorder, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(6.dp))) {
            // System is drawn half white, half dark
            val shown = if (option == ThemeChoice.SYSTEM) listOf(WhitePalette, DarkPalette) else listOf(paletteFor(option, false))
            shown.forEach { MiniScreen(it, Modifier.weight(1f)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MiniScreen(p: Palette, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxHeight().background(p.page).padding(5.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Box(Modifier.fillMaxWidth(0.8f).height(5.dp).background(p.text))
        Row(
            modifier = Modifier.fillMaxWidth().weight(1f).background(p.card).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f).height(8.dp).background(p.red))
            Box(Modifier.weight(1f).height(8.dp).background(p.green))
        }
    }
}
