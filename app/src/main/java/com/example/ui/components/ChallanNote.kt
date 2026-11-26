package com.example.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.ui.theme.TextSecondary
import com.example.ui.util.LanguageStrings

/** Which detail boxes a khata entry form shows. One choice for every form, so they never differ. */
enum class EntryDetailMode { NOTE, CHALLAN, BOTH }

object EntryDetail {
    private const val PREFS = "ghanshyam_billing_prefs"
    private const val KEY = "entry_detail_mode"
    private var loaded = false

    var mode by mutableStateOf(EntryDetailMode.NOTE)
        private set

    fun load(context: Context) {
        if (loaded) return
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = EntryDetailMode.entries.firstOrNull { it.name == saved } ?: EntryDetailMode.NOTE
        loaded = true
    }

    /** Only what the form shows is saved: a box the owner switched away from is ignored. */
    fun noteToSave(note: String): String = if (mode == EntryDetailMode.CHALLAN) "" else note.trim()
    fun challanToSave(challan: String): String = if (mode == EntryDetailMode.NOTE) "" else ChallanNo.clean(challan)

    fun select(context: Context, newMode: EntryDetailMode) {
        mode = newMode
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, newMode.name).apply()
    }
}

/** Challan numbers are stored bare ("985") and always shown as "CH. NO. 985". */
object ChallanNo {
    const val PREFIX = "CH. NO. "
    private val TYPED_PREFIX = Regex("""^(ch(allan)?\.?\s*(no|number)?\.?)\s*[:#.\-]?\s*""", RegexOption.IGNORE_CASE)

    /** Drops a "CH. NO." the owner typed himself, so it is never shown twice. */
    fun clean(raw: String): String {
        val t = raw.trim()
        val m = TYPED_PREFIX.find(t) ?: return t
        val rest = t.substring(m.range.last + 1).trim()
        return if (rest.isEmpty()) t else rest
    }

    fun label(challanNo: String): String = if (challanNo.isBlank()) "" else PREFIX + challanNo.trim()
}

/**
 * Challan / Note / Both switch plus the boxes it selects. A hidden box keeps what was typed
 * while the form is open, but only the boxes on screen are saved (see [EntryDetail.noteToSave]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChallanNoteFields(
    lang: AppLanguage,
    challan: String,
    onChallanChange: (String) -> Unit,
    noteField: @Composable () -> Unit
) {
    val context = LocalContext.current
    EntryDetail.load(context)
    val mode = EntryDetail.mode

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = LanguageStrings.get("entry_detail_write", lang),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary,
                modifier = Modifier.align(Alignment.CenterVertically)
            )
            listOf(
                EntryDetailMode.CHALLAN to "entry_detail_challan",
                EntryDetailMode.NOTE to "entry_detail_note",
                EntryDetailMode.BOTH to "entry_detail_both"
            ).forEach { (m, key) ->
                FilterChip(
                    selected = mode == m,
                    onClick = { EntryDetail.select(context, m) },
                    label = { Text(text = LanguageStrings.get(key, lang), fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                )
            }
        }
        if (mode != EntryDetailMode.NOTE) {
            OutlinedTextField(
                value = challan,
                onValueChange = onChallanChange,
                label = { Text(text = LanguageStrings.get("challan_no_label", lang)) },
                prefix = { Text(text = ChallanNo.PREFIX, fontWeight = FontWeight.Bold) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (mode != EntryDetailMode.CHALLAN) {
            noteField()
        }
    }
}
