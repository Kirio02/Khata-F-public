package com.example.ui.components

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.TextPrimary
import com.example.ui.util.EntryDate
import com.example.ui.util.LanguageStrings
import com.example.ui.util.RelativeDate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun shortDate(millis: Long) = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(millis))

/** The phone's own calendar: familiar, and it already handles big fonts and landscape. */
private fun showCalendar(
    context: Context,
    initial: Long,
    minDate: Long? = null,
    maxDate: Long? = null,
    onPicked: (Long) -> Unit
) {
    val cal = Calendar.getInstance().apply { timeInMillis = initial }
    DatePickerDialog(
        context,
        { _, year, month, day -> onPicked(EntryDate.onDay(year, month, day, initial)) },
        cal.get(Calendar.YEAR),
        cal.get(Calendar.MONTH),
        cal.get(Calendar.DAY_OF_MONTH)
    ).apply {
        maxDate?.let { datePicker.maxDate = it }
        minDate?.let { datePicker.minDate = it }
    }.show()
}

/**
 * "Tareekh: Aaj" button that opens the calendar. Future days are blocked.
 * [value] may be [EntryDate.NOW]; picking today on such a field keeps it as "now".
 */
@Composable
fun EntryDateButton(
    value: Long,
    lang: AppLanguage,
    onChange: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val shown = EntryDate.resolve(value, now)
    val dayText = when (RelativeDate.daysBetween(shown, now)) {
        0 -> LanguageStrings.get("date_today_label", lang)
        1 -> LanguageStrings.get("date_yesterday_label", lang)
        else -> shortDate(shown)
    }
    OutlinedButton(
        onClick = {
            showCalendar(context, initial = shown, maxDate = now) { picked ->
                val isToday = RelativeDate.daysBetween(picked, now) == 0
                onChange(if (value == EntryDate.NOW && isToday) EntryDate.NOW else minOf(picked, now))
            }
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${LanguageStrings.get("entry_date_label", lang)}: $dayText",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
    }
}

/** The extra chip next to 7 / 15 / 30 din that opens a calendar for any due date. */
@Composable
fun DueDateChip(
    value: Long,
    entryDate: Long,
    lang: AppLanguage,
    onChange: (Long) -> Unit
) {
    val context = LocalContext.current
    FilterChip(
        selected = value != EntryDate.NOW,
        onClick = {
            showCalendar(
                context,
                initial = if (value != EntryDate.NOW) maxOf(value, entryDate) else entryDate,
                minDate = entryDate,
                onPicked = onChange
            )
        },
        leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = {
            Text(
                text = if (value != EntryDate.NOW) shortDate(value) else LanguageStrings.get("pick_date", lang),
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    )
}
