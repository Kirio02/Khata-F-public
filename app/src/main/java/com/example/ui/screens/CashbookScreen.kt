@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui.screens

import androidx.compose.foundation.verticalScroll
import com.example.ui.util.EntryDate
import com.example.ui.components.EntryDateButton
import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.model.AppLanguage
import com.example.model.Attachment
import com.example.model.CashEntry
import com.example.model.DrawerCount
import com.example.ui.components.AttachmentPickerStrip
import com.example.ui.components.EmptyStateView
import com.example.ui.components.FullScreenAttachmentViewer
import com.example.ui.components.VoiceNoteButton
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.AttachmentListSaver
import java.text.SimpleDateFormat
import java.util.*

private data class DayCashSection(
    val dayMillis: Long,
    val opening: Double,
    val totalIn: Double,
    val totalOut: Double,
    val closing: Double,
    val entries: List<CashEntry>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashbookScreen(
    repository: AppRepository
) {
    val context = LocalContext.current
    val allEntries by repository.cashbook.collectAsState()
    val binEntries by repository.binEntries.collectAsState()
    val drawerCounts by repository.drawerCounts.collectAsState()
    val cashOpeningBalance by repository.cashOpeningBalance.collectAsState()
    val bankOpeningBalance by repository.bankOpeningBalance.collectAsState()
    val currentLang by repository.language.collectAsState()
    val lang = currentLang

    // Book switcher: "CASH" or "BANK"
    var selectedBook by rememberSaveable { mutableStateOf("CASH") }

    // Date filters: "TODAY", "WEEK", "MONTH", "ALL", "CUSTOM"
    var selectedDateFilter by remember { mutableStateOf("TODAY") }
    var customPickedDayMillis by remember { mutableStateOf<Long?>(null) }

    // Dialog & sheet states
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var addDialogType by rememberSaveable { mutableStateOf("IN") }
    var entryToEdit by remember { mutableStateOf<CashEntry?>(null) }
    var viewingEditedInfo by remember { mutableStateOf<CashEntry?>(null) }
    var showBinSheet by remember { mutableStateOf(false) }
    var showDrawerCountDialog by remember { mutableStateOf(false) }

    // Full screen attachment viewer
    var viewingAttachments by remember { mutableStateOf<List<Attachment>?>(null) }
    var viewingInitialIndex by remember { mutableIntStateOf(0) }

    // Filter active entries by book type
    val activeBookEntries = remember(allEntries, selectedBook) {
        allEntries.filter { it.bookType == selectedBook && !it.isDeleted }
    }

    // Helper: normalize timestamp to midnight 00:00:00.000
    fun getMidnight(timeMillis: Long): Long {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMillis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    val todayMidnight = getMidnight(System.currentTimeMillis())

    // Compute cumulative day-wise balances in chronological order
    val calculatedDaySections = remember(activeBookEntries, selectedBook, cashOpeningBalance, bankOpeningBalance) {
        val baseOpening = if (selectedBook == "CASH") cashOpeningBalance else bankOpeningBalance
        val entriesByDay = activeBookEntries.groupBy { getMidnight(it.date) }
        val distinctDays = entriesByDay.keys.sorted()

        var runningBalance = baseOpening
        val sections = mutableListOf<DayCashSection>()

        for (day in distinctDays) {
            val dayEntries = (entriesByDay[day] ?: emptyList()).sortedByDescending { it.date }
            val dayIn = dayEntries.filter { it.type == "IN" }.sumOf { it.amount }
            val dayOut = dayEntries.filter { it.type == "OUT" }.sumOf { it.amount }
            val dayOpening = runningBalance
            val dayClosing = dayOpening + dayIn - dayOut
            runningBalance = dayClosing

            sections.add(
                DayCashSection(
                    dayMillis = day,
                    opening = dayOpening,
                    totalIn = dayIn,
                    totalOut = dayOut,
                    closing = dayClosing,
                    entries = dayEntries
                )
            )
        }
        sections
    }

    // Filter sections by selected date filter chip (and reverse so newest day is first)
    val displayDaySections = remember(calculatedDaySections, selectedDateFilter, customPickedDayMillis) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.timeInMillis = now

        val filtered = when (selectedDateFilter) {
            "TODAY" -> {
                val todayMid = getMidnight(now)
                calculatedDaySections.filter { it.dayMillis == todayMid }
            }
            "WEEK" -> {
                val weekStart = getMidnight(now - 7L * 24 * 3600 * 1000L)
                calculatedDaySections.filter { it.dayMillis >= weekStart }
            }
            "MONTH" -> {
                val monthCal = Calendar.getInstance()
                monthCal.timeInMillis = now
                monthCal.set(Calendar.DAY_OF_MONTH, 1)
                val monthStart = getMidnight(monthCal.timeInMillis)
                calculatedDaySections.filter { it.dayMillis >= monthStart }
            }
            "CUSTOM" -> {
                if (customPickedDayMillis != null) {
                    val targetMid = getMidnight(customPickedDayMillis!!)
                    calculatedDaySections.filter { it.dayMillis == targetMid }
                } else {
                    calculatedDaySections
                }
            }
            else -> calculatedDaySections
        }
        filtered.sortedByDescending { it.dayMillis }
    }

    // Overall book balance totals
    val currentBookOpening = if (selectedBook == "CASH") cashOpeningBalance else bankOpeningBalance
    val totalBookIn = activeBookEntries.filter { it.type == "IN" }.sumOf { it.amount }
    val totalBookOut = activeBookEntries.filter { it.type == "OUT" }.sumOf { it.amount }
    val currentBookBalance = currentBookOpening + totalBookIn - totalBookOut

    // Date picker dialog launcher for "Jump to date"
    fun openDatePicker() {
        val cal = Calendar.getInstance()
        customPickedDayMillis?.let { cal.timeInMillis = it }
        val dialog = DatePickerDialog(
            context,
            AppTheme.calendarStyle,
            { _, year, month, dayOfMonth ->
                val pickedCal = Calendar.getInstance()
                pickedCal.set(year, month, dayOfMonth, 12, 0, 0)
                customPickedDayMillis = pickedCal.timeInMillis
                selectedDateFilter = "CUSTOM"
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        )
        dialog.show()
    }

    Scaffold(
        containerColor = SoftBackground,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceWhite)
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            ) {
                // Top Row: Book Switcher & Action Icons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Two Books Segmented Switch
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = SoftBackground,
                        modifier = Modifier.weight(1f)
                    ) {
                        Row(modifier = Modifier.padding(3.dp)) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (selectedBook == "CASH") PrimaryBlue else SoftBackground,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { selectedBook = "CASH" }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = LanguageStrings.get("book_cash", lang),
                                        letterSpacing = 0.sp,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedBook == "CASH") SurfaceWhite else TextSecondary
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (selectedBook == "BANK") PrimaryBlue else SoftBackground,
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { selectedBook = "BANK" }
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = LanguageStrings.get("book_bank", lang),
                                        letterSpacing = 0.sp,
                                        maxLines = 1,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = if (selectedBook == "BANK") SurfaceWhite else TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    // Action Icons: Drawer Count, Jump to Date, Recycle Bin
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (selectedBook == "CASH") {
                            IconButton(
                                onClick = { showDrawerCountDialog = true },
                                modifier = Modifier.size(38.dp)
                            ) {
                                Icon(
                                    Icons.Default.Calculate,
                                    contentDescription = LanguageStrings.get("count_drawer_btn", lang),
                                    tint = PrimaryBlue,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = { showBinSheet = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            BadgedBox(
                                badge = {
                                    if (binEntries.isNotEmpty()) {
                                        Badge(containerColor = UdharRed) {
                                            Text(text = "${binEntries.size}")
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.DeleteOutline,
                                    contentDescription = LanguageStrings.get("btn_bin", lang),
                                    tint = if (binEntries.isNotEmpty()) UdharRed else TextSecondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(AppSpacing.xs))

                // Date Filter Chips Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // First in the row, so the calendar is always on screen
                    AssistChip(
                        onClick = { openDatePicker() },
                        leadingIcon = { Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        label = { Text(text = LanguageStrings.get("jump_to_date_btn", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = selectedDateFilter == "ALL",
                        onClick = {
                            selectedDateFilter = "ALL"
                            customPickedDayMillis = null
                        },
                        label = { Text(text = LanguageStrings.get("filter_all", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = selectedDateFilter == "TODAY",
                        onClick = {
                            selectedDateFilter = "TODAY"
                            customPickedDayMillis = null
                        },
                        label = { Text(text = LanguageStrings.get("filter_today", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = selectedDateFilter == "WEEK",
                        onClick = {
                            selectedDateFilter = "WEEK"
                            customPickedDayMillis = null
                        },
                        label = { Text(text = LanguageStrings.get("filter_this_week", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    )
                    FilterChip(
                        selected = selectedDateFilter == "MONTH",
                        onClick = {
                            selectedDateFilter = "MONTH"
                            customPickedDayMillis = null
                        },
                        label = { Text(text = LanguageStrings.get("filter_this_month", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                    )
                    if (selectedDateFilter == "CUSTOM" && customPickedDayMillis != null) {
                        val customDateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(Date(customPickedDayMillis!!))
                        FilterChip(
                            selected = true,
                            onClick = { openDatePicker() },
                            label = { Text(text = customDateStr, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = LanguageStrings.get("filter_all", lang),
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clickable {
                                            selectedDateFilter = "ALL"
                                            customPickedDayMillis = null
                                        }
                                )
                            }
                        )
                    }
                }
            }
        },
        bottomBar = {
            // A real bar (not floating buttons) so the list is laid out ABOVE it and nothing hides
            // behind the buttons, even with large phone fonts. Labels may wrap to 2 lines.
            Surface(color = SoftBackground, shadowElevation = 6.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Button(
                        onClick = {
                            addDialogType = "IN"
                            showAddDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JamaGreen, contentColor = SurfaceWhite),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageStrings.get(if (selectedBook == "CASH") "cash_in_btn" else "bank_in_btn", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Button(
                        onClick = {
                            addDialogType = "OUT"
                            showAddDialog = true
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = UdharRed, contentColor = SurfaceWhite),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = LanguageStrings.get(if (selectedBook == "CASH") "cash_out_btn" else "bank_out_btn", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = AppSpacing.md,
                end = AppSpacing.md,
                top = AppSpacing.sm,
                bottom = AppSpacing.md
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            // BIG "cash in the shop right now" card: one number the owner reads at a glance.
            // It always shows the whole book balance (not the date filter) plus what came / went TODAY.
            item(key = "cash_hero") {
                val dayEnd = todayMidnight + 86_400_000L
                val todayEntries = activeBookEntries.filter { it.date in todayMidnight until dayEnd }
                val todayIn = todayEntries.filter { it.type == "IN" }.sumOf { it.amount }
                val todayOut = todayEntries.filter { it.type == "OUT" }.sumOf { it.amount }
                val heroLabel = if (selectedBook == "CASH") {
                    LanguageStrings.get("cash_in_shop_now", lang)
                } else {
                    LanguageStrings.get("bank_balance_now", lang)
                }
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = AppSpacing.xs)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = heroLabel,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = formatIndianCurrency(currentBookBalance),
                            fontSize = 44.sp,
                            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.ExtraBold,
                            color = if (currentBookBalance >= 0) PrimaryBlue else UdharRed,
                            maxLines = 1,
                            softWrap = false
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = LanguageStrings.get("today_in_label", lang),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextSecondary
                                )
                                Text(
                                    text = formatIndianCurrency(todayIn),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = JamaGreen,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = LanguageStrings.get("today_out_label", lang),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = TextSecondary
                                )
                                Text(
                                    text = formatIndianCurrency(todayOut),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = UdharRed,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }

            if (displayDaySections.isEmpty()) {
                item {
                    val emptyTitle = if (selectedBook == "CASH") {
                        LanguageStrings.get("empty_cashbook_title", lang)
                    } else {
                        LanguageStrings.get("empty_bankbook_title", lang)
                    }
                    val emptyDesc = if (selectedBook == "CASH") {
                        LanguageStrings.get("empty_cashbook_desc", lang)
                    } else {
                        LanguageStrings.get("empty_bankbook_desc", lang)
                    }
                    EmptyStateView(
                        icon = if (selectedBook == "CASH") Icons.Default.AccountBalanceWallet else Icons.Default.AccountBalance,
                        title = emptyTitle,
                        description = emptyDesc
                    )
                }
            } else {
                displayDaySections.forEach { daySection ->
                    // Day Section Header
                    item(key = "header_${daySection.dayMillis}") {
                        val dayLabel = when {
                            daySection.dayMillis == todayMidnight -> LanguageStrings.get("date_today_label", lang)
                            daySection.dayMillis == todayMidnight - 24L * 3600 * 1000L -> LanguageStrings.get("date_yesterday_label", lang)
                            else -> SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()).format(Date(daySection.dayMillis))
                        }
                        val formattedDateNum = SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(daySection.dayMillis))

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ChipActiveBg.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                // Day Title Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dayLabel,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = formattedDateNum,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                // Opening, Total In, Total Out, Closing (two per line: four amounts never fit)
                                FlowRow(
                                    modifier = Modifier.fillMaxWidth(),
                                    maxItemsInEachRow = 2,
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = LanguageStrings.get("day_opening_short", lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = formatIndianCurrency(daySection.opening),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = LanguageStrings.get("day_in_short", lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "+${formatIndianCurrency(daySection.totalIn)}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.SemiBold,
                                            color = JamaGreen
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = LanguageStrings.get("day_out_short", lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = "-${formatIndianCurrency(daySection.totalOut)}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.SemiBold,
                                            color = UdharRed
                                        )
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = LanguageStrings.get("day_closing_short", lang),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontSize = 10.sp,
                                            color = TextSecondary
                                        )
                                        Text(
                                            text = formatIndianCurrency(daySection.closing),
                                            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryBlue
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Entries for this day
                    items(daySection.entries, key = { it.id }) { entry ->
                        val isIn = entry.type == "IN"
                        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(entry.date))
                        val isEdited = entry.originalAmount != null && entry.originalAmount != entry.amount
                        val hasParty = entry.partyName.isNotBlank()

                        AppCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { entryToEdit = entry }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Details column
                                Column(modifier = Modifier.weight(1f)) {
                                    // Row 1: Party name (if present) or Remark/Category
                                    if (hasParty) {
                                        Text(
                                            text = entry.partyName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = PrimaryBlue,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    val mainText = if (entry.remark.isNotBlank()) entry.remark else entry.category
                                    Text(
                                        text = mainText,
                                        style = if (hasParty) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (hasParty) FontWeight.Normal else FontWeight.SemiBold,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    // Row 2: Badges (Mode, Reference, Cheque Status, Edited Badge, Photos, Time)
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                                    ) {
                                        // Mode Badge
                                        Surface(
                                            color = if (entry.paymentMode == "CASH") SoftBackground else ChipActiveBg,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = entry.paymentMode,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (entry.paymentMode == "CASH") TextSecondary else PrimaryBlue,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }

                                        // Reference badge (UTR / Cheque)
                                        if (entry.reference.isNotBlank()) {
                                            Surface(
                                                color = SoftBackground,
                                                shape = RoundedCornerShape(4.dp)
                                            ) {
                                                Text(
                                                    text = entry.reference,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                                    maxLines = 1
                                                )
                                            }
                                        }

                                        // Cheque status badge
                                        if (entry.paymentMode == "CHEQUE" && entry.chequeStatus.isNotBlank()) {
                                            val isCleared = entry.chequeStatus == "CLEARED"
                                            Surface(
                                                color = if (isCleared) JamaGreen.copy(alpha = 0.15f) else UdharRed.copy(alpha = 0.15f),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable {
                                                    val newStatus = if (isCleared) "PENDING" else "CLEARED"
                                                    repository.editCashEntry(
                                                        id = entry.id,
                                                        amount = entry.amount,
                                                        remark = entry.remark,
                                                        date = entry.date,
                                                        paymentMode = entry.paymentMode,
                                                        bookType = entry.bookType,
                                                        reference = entry.reference,
                                                        chequeStatus = newStatus,
                                                        partyName = entry.partyName,
                                                        partyId = entry.partyId
                                                    )
                                                }
                                            ) {
                                                val statusText = if (isCleared) {
                                                    LanguageStrings.get("cheque_status_cleared", lang)
                                                } else {
                                                    LanguageStrings.get("cheque_status_pending", lang)
                                                }
                                                Text(
                                                    text = statusText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = if (isCleared) JamaGreen else UdharRed,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        // Edited badge with tap to view original
                                        if (isEdited) {
                                            Surface(
                                                color = UdharRed.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable { viewingEditedInfo = entry }
                                            ) {
                                                Text(
                                                    text = LanguageStrings.get("badge_edited", lang),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = UdharRed,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                )
                                            }
                                        }

                                        // Photos badge
                                        val cashAtts = repository.getAttachmentsForOwner("CASH_ENTRY", entry.id)
                                        if (cashAtts.isNotEmpty()) {
                                            Surface(
                                                color = ChipActiveBg,
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable {
                                                    viewingAttachments = cashAtts
                                                    viewingInitialIndex = 0
                                                }
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.PhotoLibrary,
                                                        contentDescription = LanguageStrings.get("tab_photos", lang),
                                                        tint = PrimaryBlue,
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(2.dp))
                                                    Text(
                                                        text = "${cashAtts.size}",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = PrimaryBlue
                                                    )
                                                }
                                            }
                                        }

                                        // Time
                                        Text(
                                            text = timeStr,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary,
                                            maxLines = 1
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(AppSpacing.sm))

                                // Right: Amount
                                val amountPrefix = if (isIn) "+" else "-"
                                Text(
                                    text = amountPrefix + formatIndianCurrency(entry.amount),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    fontWeight = FontWeight.Bold,
                                    color = if (isIn) JamaGreen else UdharRed,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Cash / Bank Entry Dialog
    if (showAddDialog) {
        // rememberSaveable: survive Android killing the app while the camera is open.
        var amountText by rememberSaveable { mutableStateOf("") }
        var categoryText by rememberSaveable { mutableStateOf("") }
        var remarkText by rememberSaveable { mutableStateOf("") }
        var selectedMode by rememberSaveable(selectedBook) {
            mutableStateOf(if (selectedBook == "CASH") "CASH" else "UPI")
        }
        var referenceText by rememberSaveable { mutableStateOf("") }
        var chequeStatus by rememberSaveable { mutableStateOf("PENDING") }
        var partyNameText by rememberSaveable { mutableStateOf("") }
        var pendingAttachments by rememberSaveable(stateSaver = AttachmentListSaver) { mutableStateOf<List<Attachment>>(emptyList()) }
        val tempOwnerId = rememberSaveable { UUID.randomUUID().toString() }

        var entryDate by rememberSaveable { mutableStateOf(EntryDate.NOW) }
        val availableModes = if (selectedBook == "CASH") {
            listOf("CASH")
        } else {
            listOf("UPI", "BANK TRANSFER", "CHEQUE")
        }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = {
                Text(
                    text = if (addDialogType == "IN") LanguageStrings.get(if (selectedBook == "CASH") "cash_in_btn" else "bank_in_btn", lang) else LanguageStrings.get(if (selectedBook == "CASH") "cash_out_btn" else "bank_out_btn", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(text = LanguageStrings.get("amount_label", lang)) },
                        placeholder = { Text(text = LanguageStrings.get("amount_zero_placeholder", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    EntryDateButton(value = entryDate, lang = lang, onChange = { entryDate = it })

                    // Payment Mode selector
                    Text(
                        text = LanguageStrings.get("payment_mode_label", lang),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        availableModes.forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode },
                                label = { Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    // Bank book fields: Reference & Cheque Status
                    if (selectedBook == "BANK") {
                        OutlinedTextField(
                            value = referenceText,
                            onValueChange = { referenceText = it },
                            label = { Text(text = LanguageStrings.get("ref_utr_cheque", lang)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (selectedMode == "CHEQUE") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = chequeStatus == "PENDING",
                                    onClick = { chequeStatus = "PENDING" },
                                    label = { Text(text = LanguageStrings.get("cheque_status_pending", lang), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = chequeStatus == "CLEARED",
                                    onClick = { chequeStatus = "CLEARED" },
                                    label = { Text(text = LanguageStrings.get("cheque_status_cleared", lang), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // Party Name (optional for bank/cash)
                    OutlinedTextField(
                        value = partyNameText,
                        onValueChange = { partyNameText = it },
                        label = { Text(text = LanguageStrings.get("party_name_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Category
                    OutlinedTextField(
                        value = categoryText,
                        onValueChange = { categoryText = it },
                        label = { Text(text = LanguageStrings.get("category_label", lang)) },
                        placeholder = { Text(text = LanguageStrings.get("remark_placeholder", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Remark with Voice Note
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = remarkText,
                            onValueChange = { remarkText = it },
                            label = { Text(text = LanguageStrings.get("remark_label", lang)) },
                            placeholder = { Text(text = LanguageStrings.get("address_placeholder", lang)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        VoiceNoteButton(
                            currentLang = lang,
                            onSpeechResult = { spoken ->
                                remarkText = if (remarkText.isBlank()) spoken else "$remarkText $spoken"
                            }
                        )
                    }

                    // Attachments strip
                    AttachmentPickerStrip(
                        attachments = pendingAttachments,
                        onAttachmentsChanged = { pendingAttachments = it },
                        ownerType = "CASH_ENTRY",
                        ownerId = tempOwnerId,
                        currentLang = lang
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0) {
                            val defaultCat = if (addDialogType == "IN") "Counter Cash" else "Shop Expense"
                            val newEntry = repository.addCashEntry(
                                type = addDialogType,
                                amount = amt,
                                category = if (categoryText.isNotBlank()) categoryText else defaultCat,
                                remark = remarkText,
                                paymentMode = selectedMode,
                                source = "manual",
                                bookType = selectedBook,
                                reference = referenceText.trim(),
                                chequeStatus = if (selectedMode == "CHEQUE") chequeStatus else "",
                                partyName = partyNameText.trim(),
                                date = EntryDate.resolve(entryDate)
                            )
                            if (pendingAttachments.isNotEmpty()) {
                                repository.addAttachments(pendingAttachments.map { it.copy(ownerId = newEntry.id) })
                            }
                            if (entryDate != EntryDate.NOW) {
                                // Show that day, otherwise a back-dated entry seems to vanish under "Aaj"
                                customPickedDayMillis = entryDate
                                selectedDateFilter = "CUSTOM"
                            }
                            showAddDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (addDialogType == "IN") JamaGreen else UdharRed)
                ) {
                    Text(text = LanguageStrings.get("btn_save_entry", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Edit Cash / Bank Entry Dialog
    entryToEdit?.let { entry ->
        var editAmount by remember(entry.id) { mutableStateOf(entry.amount.toString()) }
        var editRemark by remember(entry.id) { mutableStateOf(entry.remark) }
        var editMode by remember(entry.id) { mutableStateOf(entry.paymentMode) }
        var editReference by remember(entry.id) { mutableStateOf(entry.reference) }
        var editChequeStatus by remember(entry.id) { mutableStateOf(if (entry.chequeStatus.isNotBlank()) entry.chequeStatus else "PENDING") }
        var editPartyName by remember(entry.id) { mutableStateOf(entry.partyName) }
        var editDate by remember(entry.id) { mutableStateOf(entry.date) }

        val editAvailableModes = if (entry.bookType == "CASH") {
            listOf("CASH")
        } else {
            listOf("UPI", "BANK TRANSFER", "CHEQUE")
        }

        AlertDialog(
            onDismissRequest = { entryToEdit = null },
            title = {
                Text(
                    text = LanguageStrings.get("edit_entry_title", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { editAmount = it },
                        label = { Text(text = LanguageStrings.get("amount_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // A bill's cash line takes its date from the bill, so it isn't changed here
                    if (entry.source != "invoice") {
                        EntryDateButton(value = editDate, lang = lang, onChange = { editDate = it })
                    }

                    Text(
                        text = LanguageStrings.get("payment_mode_label", lang),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        editAvailableModes.forEach { mode ->
                            FilterChip(
                                selected = editMode == mode,
                                onClick = { editMode = mode },
                                label = { Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                            )
                        }
                    }

                    if (entry.bookType == "BANK") {
                        OutlinedTextField(
                            value = editReference,
                            onValueChange = { editReference = it },
                            label = { Text(text = LanguageStrings.get("ref_utr_cheque", lang)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (editMode == "CHEQUE") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = editChequeStatus == "PENDING",
                                    onClick = { editChequeStatus = "PENDING" },
                                    label = { Text(text = LanguageStrings.get("cheque_status_pending", lang), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = editChequeStatus == "CLEARED",
                                    onClick = { editChequeStatus = "CLEARED" },
                                    label = { Text(text = LanguageStrings.get("cheque_status_cleared", lang), fontSize = 11.sp) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = editPartyName,
                        onValueChange = { editPartyName = it },
                        label = { Text(text = LanguageStrings.get("party_name_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = editRemark,
                        onValueChange = { editRemark = it },
                        label = { Text(text = LanguageStrings.get("remark_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Delete to Bin button
                    OutlinedButton(
                        onClick = {
                            repository.moveCashEntryToBin(entry.id)
                            entryToEdit = null
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = UdharRed),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = LanguageStrings.get("btn_bin", lang), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = editAmount.toDoubleOrNull() ?: 0.0
                        if (parsed > 0) {
                            repository.editCashEntry(
                                id = entry.id,
                                amount = parsed,
                                remark = editRemark.trim(),
                                date = editDate,
                                paymentMode = editMode,
                                bookType = entry.bookType,
                                reference = editReference.trim(),
                                chequeStatus = if (editMode == "CHEQUE") editChequeStatus else "",
                                partyName = editPartyName.trim(),
                                partyId = entry.partyId
                            )
                            entryToEdit = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(text = LanguageStrings.get("btn_save", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryToEdit = null }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Edited Amount Info Dialog (Visible when tapping "Edited" badge)
    viewingEditedInfo?.let { entry ->
        AlertDialog(
            onDismissRequest = { viewingEditedInfo = null },
            title = {
                Text(
                    text = LanguageStrings.get("badge_edited", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${LanguageStrings.get("original_amount_was", lang)}: ${formatIndianCurrency(entry.originalAmount ?: entry.amount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    Text(
                        text = "${LanguageStrings.get("edited_amount_is", lang)}: ${formatIndianCurrency(entry.amount)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewingEditedInfo = null }) {
                    Text(text = LanguageStrings.get("btn_close", lang))
                }
            }
        )
    }

    // Recycle Bin Dialog / Sheet
    if (showBinSheet) {
        AlertDialog(
            onDismissRequest = { showBinSheet = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = UdharRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = LanguageStrings.get("bin_title", lang),
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = LanguageStrings.get("bin_auto_clean_notice", lang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (binEntries.isEmpty()) {
                        EmptyStateView(
                            icon = Icons.Default.DeleteOutline,
                            title = LanguageStrings.get("bin_empty_title", lang),
                            description = LanguageStrings.get("bin_empty_desc", lang)
                        )
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(binEntries, key = { it.id }) { item ->
                                val dateStr = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(item.date))
                                val title = if (item.partyName.isNotBlank()) item.partyName else (if (item.remark.isNotBlank()) item.remark else item.category)
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = SoftBackground),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = title,
                                                    fontWeight = FontWeight.Bold,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1
                                                )
                                                Text(
                                                    text = "${item.paymentMode} • $dateStr",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            Text(
                                                text = formatIndianCurrency(item.amount),
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.type == "IN") JamaGreen else UdharRed
                                            )
                                        }

                                        Spacer(modifier = Modifier.height(6.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(
                                                onClick = { repository.restoreCashEntryFromBin(item.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(14.dp), tint = PrimaryBlue)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(text = LanguageStrings.get("btn_restore", lang), fontSize = 11.sp, color = PrimaryBlue)
                                            }

                                            Spacer(modifier = Modifier.width(8.dp))

                                            TextButton(
                                                onClick = { repository.permanentlyDeleteCashEntry(item.id) },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(14.dp), tint = UdharRed)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(text = LanguageStrings.get("btn_delete_forever", lang), fontSize = 11.sp, color = UdharRed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBinSheet = false }) {
                    Text(text = LanguageStrings.get("btn_close", lang))
                }
            }
        )
    }

    // Drawer Count Dialog & History
    if (showDrawerCountDialog) {
        var actualCashText by remember { mutableStateOf("") }
        var drawerNote by remember { mutableStateOf("") }
        var showDrawerHistory by remember { mutableStateOf(false) }

        val actualDouble = actualCashText.toDoubleOrNull() ?: 0.0
        val difference = actualDouble - currentBookBalance

        AlertDialog(
            onDismissRequest = { showDrawerCountDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Calculate, contentDescription = null, tint = PrimaryBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (showDrawerHistory) LanguageStrings.get("drawer_count_history", lang) else LanguageStrings.get("count_drawer_btn", lang),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                if (showDrawerHistory) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 350.dp)
                    ) {
                        if (drawerCounts.isEmpty()) {
                            Text(
                                text = LanguageStrings.get("no_counts_yet", lang),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(drawerCounts, key = { it.id }) { dc ->
                                    val countDate = SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(dc.date))
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = SoftBackground),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = countDate,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                                val diffText = if (dc.difference == 0.0) {
                                                    LanguageStrings.get("drawer_match", lang)
                                                } else if (dc.difference > 0) {
                                                    "+${formatIndianCurrency(dc.difference)} (${LanguageStrings.get("drawer_extra", lang)})"
                                                } else {
                                                    "-${formatIndianCurrency(-dc.difference)} (${LanguageStrings.get("drawer_shortage", lang)})"
                                                }
                                                Text(
                                                    text = diffText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (dc.difference >= 0) JamaGreen else UdharRed
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    text = "${LanguageStrings.get("drawer_actual_cash", lang)}: ${formatIndianCurrency(dc.actualCash)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                                Text(
                                                    text = "${LanguageStrings.get("drawer_book_balance", lang)}: ${formatIndianCurrency(dc.bookBalance)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = TextSecondary
                                                )
                                            }
                                            if (dc.note.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = dc.note,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Current Book Balance Card
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = ChipActiveBg),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageStrings.get("drawer_book_balance", lang),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = formatIndianCurrency(currentBookBalance),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue
                                )
                            }
                        }

                        // Actual Physical Cash input
                        OutlinedTextField(
                            value = actualCashText,
                            onValueChange = { actualCashText = it },
                            label = { Text(text = LanguageStrings.get("drawer_actual_cash", lang)) },
                            placeholder = { Text(text = formatIndianNumber(currentBookBalance)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Live Difference calculation
                        if (actualCashText.isNotBlank()) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (difference == 0.0) JamaGreen.copy(alpha = 0.12f)
                                    else if (difference > 0) JamaGreen.copy(alpha = 0.12f)
                                    else UdharRed.copy(alpha = 0.12f)
                                ),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    val diffMsg = if (difference == 0.0) {
                                        LanguageStrings.get("drawer_match", lang)
                                    } else if (difference > 0) {
                                        "+${formatIndianCurrency(difference)} ${LanguageStrings.get("drawer_extra", lang)}"
                                    } else {
                                        "-${formatIndianCurrency(-difference)} ${LanguageStrings.get("drawer_shortage", lang)}"
                                    }
                                    Text(
                                        text = LanguageStrings.get("drawer_difference", lang),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    Text(
                                        text = diffMsg,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (difference >= 0) JamaGreen else UdharRed
                                    )
                                }
                            }
                        }

                        // Optional Note
                        OutlinedTextField(
                            value = drawerNote,
                            onValueChange = { drawerNote = it },
                            label = { Text(text = LanguageStrings.get("note_label", lang)) },
                            placeholder = { Text(text = LanguageStrings.get("remark_placeholder", lang)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // View Count History button
                        TextButton(
                            onClick = { showDrawerHistory = true },
                            modifier = Modifier.align(Alignment.End)
                        ) {
                            Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = LanguageStrings.get("drawer_count_history", lang), fontSize = 12.sp)
                        }
                    }
                }
            },
            confirmButton = {
                if (showDrawerHistory) {
                    Button(onClick = { showDrawerHistory = false }) {
                        Text(text = LanguageStrings.get("btn_close", lang))
                    }
                } else {
                    Button(
                        onClick = {
                            if (actualCashText.isNotBlank()) {
                                repository.addDrawerCount(
                                    actualCash = actualDouble,
                                    bookBalance = currentBookBalance,
                                    note = drawerNote.trim()
                                )
                                showDrawerCountDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text(text = LanguageStrings.get("save_count_btn", lang), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showDrawerCountDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Full-screen image/PDF viewer
    viewingAttachments?.let { attList ->
        FullScreenAttachmentViewer(
            attachments = attList,
            initialIndex = viewingInitialIndex,
            currentLang = lang,
            onDismiss = { viewingAttachments = null },
            onDelete = { attToDelete ->
                repository.deleteAttachment(attToDelete.id)
                // Drop it from the open viewer too, or it keeps showing the deleted photo.
                viewingAttachments = attList.filter { it.id != attToDelete.id }.ifEmpty { null }
            },
            onUpdateCaption = { attToUpdate, newCaption ->
                repository.updateAttachmentCaption(attToUpdate.id, newCaption)
            }
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun CashbookScreenPreview() {
    MaterialTheme {
        Surface(color = SoftBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            val previewTitle = "CASH BOOK"
                            val previewAmount = "₹12,450.00"
                            Text(text = previewTitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                            Text(text = previewAmount, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                        }
                    }
                }
            }
        }
    }
}
