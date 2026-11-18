package com.example.ui.screens

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.model.BankDayCheck
import com.example.model.CashEntry
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BankCheckScreen(
    repository: AppRepository,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val lang by repository.language.collectAsState()
    val cashbook by repository.cashbook.collectAsState()
    val bankOpeningBalance by repository.bankOpeningBalance.collectAsState()
    val bankDayChecks by repository.bankDayChecks.collectAsState()

    val screenWidth = LocalConfiguration.current.screenWidthDp
    val isWideScreen = screenWidth >= 600

    // Filter states
    var filterType by rememberSaveable { mutableStateOf("ALL") } // ALL, UNMATCHED, MATCHED
    var selectedPresetRange by rememberSaveable { mutableStateOf("THIS_MONTH") } // THIS_MONTH, THIS_WEEK, ALL, CUSTOM

    val calendar = Calendar.getInstance()
    var customStartDate by rememberSaveable {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        mutableStateOf(c.timeInMillis)
    }
    var customEndDate by rememberSaveable {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        c.set(Calendar.MILLISECOND, 999)
        mutableStateOf(c.timeInMillis)
    }

    // Active Bank Entries
    val bankEntries = remember(cashbook) {
        cashbook.filter { it.bookType == "BANK" && !it.isDeleted }.sortedBy { it.date }
    }

    // Calculate running balance for each bank entry across all time
    data class StatementLine(
        val entry: CashEntry,
        val debit: Double, // OUT
        val credit: Double, // IN
        val runningBalance: Double
    )

    val statementLines = remember(bankEntries, bankOpeningBalance) {
        var bal = bankOpeningBalance
        bankEntries.map { entry ->
            val debit = if (entry.type == "OUT") entry.amount else 0.0
            val credit = if (entry.type == "IN") entry.amount else 0.0
            bal += (credit - debit)
            StatementLine(
                entry = entry,
                debit = debit,
                credit = credit,
                runningBalance = bal
            )
        }
    }

    // Calculate effective start and end dates based on preset
    val (filterStart, filterEnd) = remember(selectedPresetRange, customStartDate, customEndDate) {
        val nowCal = Calendar.getInstance()
        when (selectedPresetRange) {
            "THIS_WEEK" -> {
                val start = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                Pair(start, Long.MAX_VALUE)
            }
            "THIS_MONTH" -> {
                val start = Calendar.getInstance().apply {
                    set(Calendar.DAY_OF_MONTH, 1)
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                Pair(start, Long.MAX_VALUE)
            }
            "CUSTOM" -> Pair(customStartDate, customEndDate)
            else -> Pair(0L, Long.MAX_VALUE)
        }
    }

    // Filtered statement lines
    val linesInDateRange = remember(statementLines, filterStart, filterEnd) {
        statementLines.filter { it.entry.date in filterStart..filterEnd }
    }

    val matchedCount = remember(linesInDateRange) {
        linesInDateRange.count { it.entry.bankVerified }
    }
    val totalCount = linesInDateRange.size

    val displayedLines = remember(linesInDateRange, filterType) {
        when (filterType) {
            "MATCHED" -> linesInDateRange.filter { it.entry.bankVerified }
            "UNMATCHED" -> linesInDateRange.filter { !it.entry.bankVerified }
            else -> linesInDateRange
        }
    }

    // Multi-select bulk tick state
    var isSelectionMode by rememberSaveable { mutableStateOf(false) }
    val selectedLineIds = remember { mutableStateListOf<String>() }

    // Dialog states
    var showDayCheckDialog by rememberSaveable { mutableStateOf(false) }
    var showAddMissingDialog by rememberSaveable { mutableStateOf(false) }
    var entryForUnmatchedNote by remember { mutableStateOf<CashEntry?>(null) }
    var showHistoryDialog by rememberSaveable { mutableStateOf(false) }

    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }

    Scaffold(
        containerColor = SoftBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = LanguageStrings.get("bank_check_title", lang),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        val counterText = String.format(
                            LanguageStrings.get("matched_counter_format", lang),
                            matchedCount,
                            totalCount
                        )
                        Text(
                            text = counterText,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (matchedCount == totalCount && totalCount > 0) JamaGreen else PrimaryBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = LanguageStrings.get("btn_back", lang),
                            tint = TextPrimary
                        )
                    }
                },
                actions = {
                    // Day Check button
                    FilledTonalButton(
                        onClick = { showDayCheckDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = ChipActiveBg,
                            contentColor = PrimaryBlue
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = LanguageStrings.get("day_check_btn", lang),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    // Add Missing Entry button
                    IconButton(
                        onClick = { showAddMissingDialog = true },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.AddCircle,
                            contentDescription = LanguageStrings.get("btn_add_missing_entry", lang),
                            tint = JamaGreen,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite)
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = isSelectionMode) {
                Surface(
                    color = SurfaceWhite,
                    tonalElevation = 8.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .border(1.dp, CardBorder)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val countText = String.format(LanguageStrings.get("selected_count_format", lang), selectedLineIds.size)
                        Text(
                            text = countText,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                onClick = {
                                    if (selectedLineIds.size == displayedLines.size) {
                                        selectedLineIds.clear()
                                    } else {
                                        selectedLineIds.clear()
                                        selectedLineIds.addAll(displayedLines.map { it.entry.id })
                                    }
                                }
                            ) {
                                Text(
                                    text = if (selectedLineIds.size == displayedLines.size) LanguageStrings.get("clear_selection_btn", lang) else LanguageStrings.get("select_all_btn", lang),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Button(
                                onClick = {
                                    repository.setBankEntriesVerifiedBulk(selectedLineIds.toList(), true)
                                    selectedLineIds.clear()
                                    isSelectionMode = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                                enabled = selectedLineIds.isNotEmpty()
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = LanguageStrings.get("bulk_tick_action", lang),
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            IconButton(
                                onClick = {
                                    selectedLineIds.clear()
                                    isSelectionMode = false
                                }
                            ) {
                                Icon(Icons.Default.Close, contentDescription = LanguageStrings.get("close_btn", lang))
                            }
                        }
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Filter Header Card
            Surface(
                color = SurfaceWhite,
                tonalElevation = 2.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = CardBorder)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Filter Chips: All, Not matched, Matched
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = filterType == "ALL",
                            onClick = { filterType = "ALL" },
                            label = { Text(LanguageStrings.get("filter_all", lang)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ChipActiveBg,
                                selectedLabelColor = PrimaryBlue
                            )
                        )
                        FilterChip(
                            selected = filterType == "UNMATCHED",
                            onClick = { filterType = "UNMATCHED" },
                            label = { Text(LanguageStrings.get("filter_unmatched", lang)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = UdharRed.copy(alpha = 0.12f),
                                selectedLabelColor = UdharRed
                            )
                        )
                        FilterChip(
                            selected = filterType == "MATCHED",
                            onClick = { filterType = "MATCHED" },
                            label = { Text(LanguageStrings.get("filter_matched", lang)) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = JamaGreen.copy(alpha = 0.12f),
                                selectedLabelColor = JamaGreen
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Date presets & custom picker
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            "THIS_MONTH" to LanguageStrings.get("filter_this_month", lang),
                            "THIS_WEEK" to LanguageStrings.get("filter_this_week", lang),
                            "ALL" to LanguageStrings.get("filter_all_time", lang),
                            "CUSTOM" to LanguageStrings.get("filter_custom", lang)
                        ).forEach { (key, label) ->
                            val isSelected = selectedPresetRange == key
                            SuggestionChip(
                                onClick = {
                                    selectedPresetRange = key
                                    if (key == "CUSTOM") {
                                        val calPicker = Calendar.getInstance()
                                        DatePickerDialog(
                                            context,
                                            { _, y, m, d ->
                                                val c = Calendar.getInstance().apply {
                                                    set(y, m, d, 0, 0, 0)
                                                    set(Calendar.MILLISECOND, 0)
                                                }
                                                customStartDate = c.timeInMillis
                                                DatePickerDialog(
                                                    context,
                                                    { _, ey, em, ed ->
                                                        val ec = Calendar.getInstance().apply {
                                                            set(ey, em, ed, 23, 59, 59)
                                                            set(Calendar.MILLISECOND, 999)
                                                        }
                                                        customEndDate = ec.timeInMillis
                                                    },
                                                    calPicker.get(Calendar.YEAR),
                                                    calPicker.get(Calendar.MONTH),
                                                    calPicker.get(Calendar.DAY_OF_MONTH)
                                                ).show()
                                            },
                                            calPicker.get(Calendar.YEAR),
                                            calPicker.get(Calendar.MONTH),
                                            calPicker.get(Calendar.DAY_OF_MONTH)
                                        ).show()
                                    }
                                },
                                label = { Text(label, fontSize = 11.sp) },
                                colors = SuggestionChipDefaults.suggestionChipColors(
                                    containerColor = if (isSelected) ChipActiveBg else ChipInactiveBg,
                                    labelColor = if (isSelected) PrimaryBlue else TextSecondary
                                ),
                                border = SuggestionChipDefaults.suggestionChipBorder(
                                    enabled = true,
                                    borderColor = if (isSelected) PrimaryBlue else CardBorder
                                )
                            )
                        }
                    }
                }
            }

            // Table Header Bar
            Surface(
                color = ChipInactiveBg,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                if (isWideScreen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = LanguageStrings.get("col_date", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.width(80.dp)
                        )
                        Text(
                            text = LanguageStrings.get("col_particulars", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = LanguageStrings.get("col_debit", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = UdharRed,
                            modifier = Modifier.width(85.dp)
                        )
                        Text(
                            text = LanguageStrings.get("col_credit", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = JamaGreen,
                            modifier = Modifier.width(85.dp)
                        )
                        Text(
                            text = LanguageStrings.get("col_balance", lang),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = PrimaryBlue,
                            modifier = Modifier.width(90.dp)
                        )
                        Text(
                            text = "Match",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary,
                            modifier = Modifier.width(55.dp)
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${LanguageStrings.get("col_date", lang)} & ${LanguageStrings.get("col_particulars", lang)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                        Text(
                            text = "${LanguageStrings.get("col_debit", lang)} / ${LanguageStrings.get("col_credit", lang)} • ${LanguageStrings.get("col_balance", lang)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Entries List
            if (displayedLines.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AccountBalance,
                            contentDescription = null,
                            tint = TextSecondary.copy(alpha = 0.5f),
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = LanguageStrings.get("no_entries_found", lang),
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayedLines, key = { it.entry.id }) { item ->
                        val entry = item.entry
                        val isMatched = entry.bankVerified
                        val isSelected = selectedLineIds.contains(entry.id)
                        val hasUnmatchedNote = entry.unmatchedNote.isNotBlank()

                        val rowBg = when {
                            isSelected -> ChipActiveBg
                            isMatched -> JamaGreen.copy(alpha = 0.08f)
                            hasUnmatchedNote -> UdharRed.copy(alpha = 0.06f)
                            else -> SurfaceWhite
                        }

                        val rowBorder = when {
                            isSelected -> PrimaryBlue
                            isMatched -> JamaGreen.copy(alpha = 0.35f)
                            hasUnmatchedNote -> UdharRed.copy(alpha = 0.3f)
                            else -> CardBorder
                        }

                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isSelectionMode) {
                                            if (isSelected) selectedLineIds.remove(entry.id) else selectedLineIds.add(entry.id)
                                        } else {
                                            // Toggle verified
                                            repository.setBankEntryVerified(entry.id, !isMatched)
                                        }
                                    },
                                    onLongClick = {
                                        isSelectionMode = true
                                        if (!selectedLineIds.contains(entry.id)) {
                                            selectedLineIds.add(entry.id)
                                        }
                                    }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = rowBg),
                            border = BorderStroke(1.dp, rowBorder)
                        ) {
                            if (isWideScreen) {
                                // Wide Screen / Landscape Row
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateFormat.format(Date(entry.date)),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary,
                                        modifier = Modifier.width(80.dp)
                                    )

                                    Column(modifier = Modifier.weight(1f)) {
                                        val partyTitle = if (entry.partyName.isNotBlank()) entry.partyName else (if (entry.remark.isNotBlank()) entry.remark else entry.category)
                                        Text(
                                            text = partyTitle,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val subRef = listOfNotNull(
                                            entry.reference.takeIf { it.isNotBlank() },
                                            entry.remark.takeIf { it.isNotBlank() && it != partyTitle }
                                        ).joinToString(" • ")
                                        if (subRef.isNotBlank()) {
                                            Text(
                                                text = subRef,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (hasUnmatchedNote) {
                                            Text(
                                                text = "${LanguageStrings.get("not_on_sheet_label", lang)}: ${entry.unmatchedNote}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = UdharRed,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Text(
                                        text = if (item.debit > 0) formatIndianCurrency(item.debit) else "-",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (item.debit > 0) UdharRed else TextSecondary,
                                        modifier = Modifier.width(85.dp)
                                    )

                                    Text(
                                        text = if (item.credit > 0) formatIndianCurrency(item.credit) else "-",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (item.credit > 0) JamaGreen else TextSecondary,
                                        modifier = Modifier.width(85.dp)
                                    )

                                    Text(
                                        text = formatIndianCurrency(item.runningBalance),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryBlue,
                                        modifier = Modifier.width(90.dp)
                                    )

                                    Checkbox(
                                        checked = isMatched,
                                        onCheckedChange = { repository.setBankEntryVerified(entry.id, it) },
                                        colors = CheckboxDefaults.colors(checkedColor = JamaGreen),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            } else {
                                // Narrow Screen Two-Line Row
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    // Row 1: Particulars & Amount
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            val partyTitle = if (entry.partyName.isNotBlank()) entry.partyName else (if (entry.remark.isNotBlank()) entry.remark else entry.category)
                                            Text(
                                                text = partyTitle,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = dateFormat.format(Date(entry.date)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                                if (entry.reference.isNotBlank()) {
                                                    Text(
                                                        text = "• ${entry.reference}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = TextSecondary,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }

                                        // Amount with Dr / Cr indicator
                                        Column(horizontalAlignment = Alignment.End) {
                                            val isCredit = entry.type == "IN"
                                            val amtColor = if (isCredit) JamaGreen else UdharRed
                                            val tag = if (isCredit) "CR" else "DR"
                                            Text(
                                                text = "${formatIndianCurrency(entry.amount)} $tag",
                                                style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
                                                fontWeight = FontWeight.Bold,
                                                color = amtColor
                                            )
                                            Text(
                                                text = "Bal: ${formatIndianCurrency(item.runningBalance)}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                color = PrimaryBlue,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(4.dp))

                                    // Row 2: Note / Explanation + Big Matched Checkbox & Explain button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (hasUnmatchedNote) {
                                            Surface(
                                                color = UdharRed.copy(alpha = 0.12f),
                                                shape = RoundedCornerShape(4.dp),
                                                modifier = Modifier.clickable { entryForUnmatchedNote = entry }
                                            ) {
                                                Text(
                                                    text = "${LanguageStrings.get("not_on_sheet_label", lang)}: ${entry.unmatchedNote}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = UdharRed,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        } else {
                                            TextButton(
                                                onClick = { entryForUnmatchedNote = entry },
                                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 36.dp)
                                            ) {
                                                Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(16.dp), tint = TextSecondary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = LanguageStrings.get("mark_not_on_sheet", lang),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }

                                        // Big Checkbox with Label
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clickable { repository.setBankEntryVerified(entry.id, !isMatched) }
                                                .padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = LanguageStrings.get("matched_with_sheet_checkbox", lang),
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = if (isMatched) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isMatched) JamaGreen else TextSecondary
                                            )
                                            Checkbox(
                                                checked = isMatched,
                                                onCheckedChange = { repository.setBankEntryVerified(entry.id, it) },
                                                colors = CheckboxDefaults.colors(checkedColor = JamaGreen),
                                                modifier = Modifier.size(48.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Day Check Dialog
    if (showDayCheckDialog) {
        var sheetBalanceInput by rememberSaveable { mutableStateOf("") }
        var dayCheckNote by rememberSaveable { mutableStateOf("") }
        var chosenDayMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

        // Compute bank closing balance up to chosen day
        val chosenDayEnd = remember(chosenDayMillis) {
            val c = Calendar.getInstance().apply {
                timeInMillis = chosenDayMillis
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            c.timeInMillis
        }

        val bookClosingForDay = remember(bankEntries, bankOpeningBalance, chosenDayEnd) {
            val entriesUntilDay = bankEntries.filter { it.date <= chosenDayEnd }
            val inAmt = entriesUntilDay.filter { it.type == "IN" }.sumOf { it.amount }
            val outAmt = entriesUntilDay.filter { it.type == "OUT" }.sumOf { it.amount }
            bankOpeningBalance + inAmt - outAmt
        }

        val parsedSheetBalance = sheetBalanceInput.toDoubleOrNull() ?: 0.0
        val difference = parsedSheetBalance - bookClosingForDay
        val isZeroDiff = Math.abs(difference) < 0.01 && sheetBalanceInput.isNotBlank()

        AlertDialog(
            onDismissRequest = { showDayCheckDialog = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = LanguageStrings.get("day_check_title", lang),
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = { showHistoryDialog = true }) {
                        Icon(Icons.Default.History, contentDescription = LanguageStrings.get("day_check_history_title", lang), tint = PrimaryBlue)
                    }
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Date button
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = chosenDayMillis }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val newC = Calendar.getInstance().apply { set(y, m, d) }
                                    chosenDayMillis = newC.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${LanguageStrings.get("col_date", lang)}: ${dateFormat.format(Date(chosenDayMillis))}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Book Balance info
                    Surface(
                        color = ChipInactiveBg,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "App Bank Closing",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            Text(
                                text = formatIndianCurrency(bookClosingForDay),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue
                            )
                        }
                    }

                    // Input: Sheet Closing Balance
                    val rupeeSign = "₹"
                    OutlinedTextField(
                        value = sheetBalanceInput,
                        onValueChange = { sheetBalanceInput = it },
                        label = { Text(LanguageStrings.get("sheet_balance_label", lang)) },
                        prefix = { Text(rupeeSign, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Difference feedback
                    if (sheetBalanceInput.isNotBlank()) {
                        Surface(
                            color = if (isZeroDiff) JamaGreen.copy(alpha = 0.12f) else UdharRed.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isZeroDiff) Icons.Default.CheckCircle else Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = if (isZeroDiff) JamaGreen else UdharRed,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isZeroDiff) {
                                        LanguageStrings.get("day_check_diff_zero", lang)
                                    } else {
                                        String.format(LanguageStrings.get("day_check_diff_format", lang), String.format(Locale.US, "%,.2f", difference))
                                    },
                                    fontWeight = FontWeight.Bold,
                                    color = if (isZeroDiff) JamaGreen else UdharRed,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }

                    // Optional Note
                    OutlinedTextField(
                        value = dayCheckNote,
                        onValueChange = { dayCheckNote = it },
                        label = { Text(LanguageStrings.get("bill_note_hint", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.addBankDayCheck(
                            date = chosenDayMillis,
                            sheetBalance = parsedSheetBalance,
                            bookBalance = bookClosingForDay,
                            note = dayCheckNote.trim()
                        )
                        showDayCheckDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isZeroDiff) JamaGreen else PrimaryBlue),
                    enabled = sheetBalanceInput.isNotBlank()
                ) {
                    Text(
                        text = LanguageStrings.get("btn_save_day_check", lang),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDayCheckDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Day Check History Dialog
    if (showHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showHistoryDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("day_check_history_title", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                if (bankDayChecks.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(LanguageStrings.get("no_counts_yet", lang), color = TextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bankDayChecks, key = { it.id }) { check ->
                            val isZero = Math.abs(check.difference) < 0.01
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = ChipInactiveBg)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = dateFormat.format(Date(check.date)),
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = if (isZero) LanguageStrings.get("day_check_diff_zero", lang) else String.format(LanguageStrings.get("day_check_diff_format", lang), String.format(Locale.US, "%,.2f", check.difference)),
                                            color = if (isZero) JamaGreen else UdharRed,
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                    Text(
                                        text = "Sheet: ${formatIndianCurrency(check.sheetBalance)} • Book: ${formatIndianCurrency(check.bookBalance)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                    if (check.note.isNotBlank()) {
                                        Text(
                                            text = check.note,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showHistoryDialog = false }) {
                    Text(LanguageStrings.get("close_btn", lang))
                }
            }
        )
    }

    // Add Missing Entry Dialog
    if (showAddMissingDialog) {
        var missingAmount by rememberSaveable { mutableStateOf("") }
        var isCredit by rememberSaveable { mutableStateOf(true) } // Credit (In) vs Debit (Out)
        var missingParty by rememberSaveable { mutableStateOf("") }
        var missingReference by rememberSaveable { mutableStateOf("") }
        var missingDateMillis by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }

        AlertDialog(
            onDismissRequest = { showAddMissingDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("missing_entry_title", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = LanguageStrings.get("add_missing_entry_desc", lang),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    // Credit / Debit selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { isCredit = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCredit) JamaGreen else ChipInactiveBg,
                                contentColor = if (isCredit) SurfaceWhite else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(LanguageStrings.get("credit_in", lang), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { isCredit = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isCredit) UdharRed else ChipInactiveBg,
                                contentColor = if (!isCredit) SurfaceWhite else TextPrimary
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(LanguageStrings.get("debit_out", lang), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Amount
                    val rupeePrefix = "₹"
                    OutlinedTextField(
                        value = missingAmount,
                        onValueChange = { missingAmount = it },
                        label = { Text(LanguageStrings.get("amount_label", lang)) },
                        prefix = { Text(rupeePrefix, fontWeight = FontWeight.Bold) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Date Button
                    OutlinedButton(
                        onClick = {
                            val c = Calendar.getInstance().apply { timeInMillis = missingDateMillis }
                            DatePickerDialog(
                                context,
                                { _, y, m, d ->
                                    val newC = Calendar.getInstance().apply { set(y, m, d) }
                                    missingDateMillis = newC.timeInMillis
                                },
                                c.get(Calendar.YEAR),
                                c.get(Calendar.MONTH),
                                c.get(Calendar.DAY_OF_MONTH)
                            ).show()
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${LanguageStrings.get("col_date", lang)}: ${dateFormat.format(Date(missingDateMillis))}",
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // Party Name (Optional)
                    OutlinedTextField(
                        value = missingParty,
                        onValueChange = { missingParty = it },
                        label = { Text(LanguageStrings.get("optional_party", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Reference (UTR / Cheque)
                    OutlinedTextField(
                        value = missingReference,
                        onValueChange = { missingReference = it },
                        label = { Text(LanguageStrings.get("reference_utr_cheque", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = missingAmount.toDoubleOrNull() ?: 0.0
                        if (parsed > 0) {
                            repository.addMissingBankEntry(
                                date = missingDateMillis,
                                amount = parsed,
                                isCredit = isCredit,
                                partyName = missingParty.trim(),
                                reference = missingReference.trim()
                            )
                            showAddMissingDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (isCredit) JamaGreen else UdharRed),
                    enabled = (missingAmount.toDoubleOrNull() ?: 0.0) > 0.0
                ) {
                    Text(LanguageStrings.get("btn_save_entry", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddMissingDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Explain / Unmatched Note Dialog
    entryForUnmatchedNote?.let { entry ->
        var noteInput by remember(entry.id) { mutableStateOf(entry.unmatchedNote) }

        AlertDialog(
            onDismissRequest = { entryForUnmatchedNote = null },
            title = {
                Text(
                    text = LanguageStrings.get("mark_not_on_sheet", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${dateFormat.format(Date(entry.date))} • ${formatIndianCurrency(entry.amount)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue
                    )
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text(LanguageStrings.get("btn_explain", lang)) },
                        placeholder = { Text(LanguageStrings.get("explanation_hint", lang)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.setBankEntryUnmatchedNote(entry.id, noteInput.trim())
                        entryForUnmatchedNote = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_save", lang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { entryForUnmatchedNote = null }) {
                    Text(LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }
}
