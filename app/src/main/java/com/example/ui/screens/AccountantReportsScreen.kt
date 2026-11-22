package com.example.ui.screens

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.ui.theme.*
import com.example.ui.util.AccountantReportGenerator
import com.example.ui.util.LanguageStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountantReportsScreen(
    repository: AppRepository,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val lang by repository.language.collectAsState()
    val storeProfile by repository.storeProfile.collectAsState()
    val cashbook by repository.cashbook.collectAsState()
    val cashOpeningBalance by repository.cashOpeningBalance.collectAsState()
    val bankOpeningBalance by repository.bankOpeningBalance.collectAsState()

    var isGeneratingPdf by remember { mutableStateOf(false) }
    var isGeneratingCsv by remember { mutableStateOf(false) }

    // Month Selector: Calendar pointing to current selected month
    var selectedCal by remember {
        val c = Calendar.getInstance()
        c.set(Calendar.DAY_OF_MONTH, 1)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        mutableStateOf(c)
    }

    var isCustomRange by rememberSaveable { mutableStateOf(false) }
    var customStartDate by rememberSaveable { mutableStateOf(selectedCal.timeInMillis) }
    var customEndDate by rememberSaveable {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 23)
        c.set(Calendar.MINUTE, 59)
        c.set(Calendar.SECOND, 59)
        c.set(Calendar.MILLISECOND, 999)
        mutableStateOf(c.timeInMillis)
    }

    val (rangeStart, rangeEnd) = remember(isCustomRange, selectedCal, customStartDate, customEndDate) {
        if (isCustomRange) {
            Pair(customStartDate, customEndDate)
        } else {
            val start = selectedCal.timeInMillis
            val c = selectedCal.clone() as Calendar
            c.set(Calendar.DAY_OF_MONTH, c.getActualMaximum(Calendar.DAY_OF_MONTH))
            c.set(Calendar.HOUR_OF_DAY, 23)
            c.set(Calendar.MINUTE, 59)
            c.set(Calendar.SECOND, 59)
            c.set(Calendar.MILLISECOND, 999)
            Pair(start, c.timeInMillis)
        }
    }

    val monthNameFormat = remember { SimpleDateFormat("MMMM yyyy", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()) }

    // Compute Report Data
    val reportData = remember(cashbook, cashOpeningBalance, bankOpeningBalance, rangeStart, rangeEnd) {
        AccountantReportGenerator.buildReportData(
            allCashEntries = cashbook,
            cashOpeningBalance = cashOpeningBalance,
            bankOpeningBalance = bankOpeningBalance,
            startMillis = rangeStart,
            endMillis = rangeEnd
        )
    }

    Scaffold(
        containerColor = SoftBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = LanguageStrings.get("accountant_reports_title", lang),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite)
            )
        },
        bottomBar = {
            Surface(
                color = SurfaceWhite,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .border(width = 1.dp, color = CardBorder)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Share PDF Button
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                isGeneratingPdf = true
                                try {
                                    val pdfFile = withContext(Dispatchers.IO) {
                                        AccountantReportGenerator.generateAccountantReportPdf(context, reportData, storeProfile)
                                    }
                                    AccountantReportGenerator.shareFile(
                                        context,
                                        pdfFile,
                                        "application/pdf",
                                        "${storeProfile.storeName.ifBlank { "Ghanshyam" }} Accountant Report (${reportData.dateRangeLabel})"
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "PDF Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingPdf = false
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        enabled = !isGeneratingPdf && !isGeneratingCsv
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isGeneratingPdf) LanguageStrings.get("pdf_generating", lang) else LanguageStrings.get("generate_pdf_btn", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Share CSV Button
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                isGeneratingCsv = true
                                try {
                                    val csvFile = withContext(Dispatchers.IO) {
                                        AccountantReportGenerator.generateAccountantReportCsv(context, reportData, storeProfile)
                                    }
                                    AccountantReportGenerator.shareFile(
                                        context,
                                        csvFile,
                                        "text/csv",
                                        "${storeProfile.storeName.ifBlank { "Ghanshyam" }} Accountant Report CSV (${reportData.dateRangeLabel})"
                                    )
                                } catch (e: Exception) {
                                    Toast.makeText(context, "CSV Error: ${e.message}", Toast.LENGTH_SHORT).show()
                                } finally {
                                    isGeneratingCsv = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        enabled = !isGeneratingPdf && !isGeneratingCsv
                    ) {
                        Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(18.dp), tint = JamaGreen)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isGeneratingCsv) LanguageStrings.get("csv_generating", lang) else LanguageStrings.get("generate_csv_btn", lang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            maxLines = 1,
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
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Month / Range Selector Card
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageStrings.get("report_period_label", lang),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )

                            // Toggle between Month Picker & Custom Range
                            TextButton(
                                onClick = {
                                    isCustomRange = !isCustomRange
                                    if (isCustomRange) {
                                        val calPicker = Calendar.getInstance()
                                        DatePickerDialog(
                                            context,
                                            AppTheme.calendarStyle,
                                            { _, y, m, d ->
                                                val c = Calendar.getInstance().apply { set(y, m, d, 0, 0, 0) }
                                                customStartDate = c.timeInMillis
                                                DatePickerDialog(
                                                    context,
                                                    AppTheme.calendarStyle,
                                                    { _, ey, em, ed ->
                                                        val ec = Calendar.getInstance().apply { set(ey, em, ed, 23, 59, 59) }
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
                                }
                            ) {
                                Text(
                                    text = if (isCustomRange) LanguageStrings.get("filter_this_month", lang) else LanguageStrings.get("date_range_label", lang),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        if (!isCustomRange) {
                            // Month Stepper
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        val c = selectedCal.clone() as Calendar
                                        c.add(Calendar.MONTH, -1)
                                        selectedCal = c
                                    }
                                ) {
                                    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = PrimaryBlue)
                                }

                                Text(
                                    text = monthNameFormat.format(selectedCal.time),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue
                                )

                                IconButton(
                                    onClick = {
                                        val c = selectedCal.clone() as Calendar
                                        c.add(Calendar.MONTH, 1)
                                        selectedCal = c
                                    }
                                ) {
                                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = PrimaryBlue)
                                }
                            }
                        } else {
                            // Custom Range Display
                            Text(
                                text = reportData.dateRangeLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            // Section 1: Month Summary & Bank Verification
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(PrimaryBlue.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Assessment, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = LanguageStrings.get("report_month_summary", lang),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Verified with bank counter badge
                        Surface(
                            color = if (reportData.bankVerifiedEntries == reportData.bankTotalEntries && reportData.bankTotalEntries > 0) JamaGreen.copy(alpha = 0.12f) else ChipActiveBg,
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
                                    if (reportData.bankVerifiedEntries == reportData.bankTotalEntries && reportData.bankTotalEntries > 0) Icons.Default.CheckCircle else Icons.Default.AccountBalance,
                                    contentDescription = null,
                                    tint = if (reportData.bankVerifiedEntries == reportData.bankTotalEntries && reportData.bankTotalEntries > 0) JamaGreen else PrimaryBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                val vText = String.format(
                                    LanguageStrings.get("verified_with_bank_format", lang),
                                    reportData.bankVerifiedEntries,
                                    reportData.bankTotalEntries
                                )
                                Text(
                                    text = vText,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (reportData.bankVerifiedEntries == reportData.bankTotalEntries && reportData.bankTotalEntries > 0) JamaGreen else PrimaryBlue
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Totals Row (Cash & Bank)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                color = ChipInactiveBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = LanguageStrings.get("tab_cashbook", lang),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "In: ${formatIndianCurrency(reportData.totalCashIn)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JamaGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Out: ${formatIndianCurrency(reportData.totalCashOut)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UdharRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Closing: ${formatIndianCurrency(reportData.netCashClosing)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PrimaryBlue,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Surface(
                                color = ChipInactiveBg,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = LanguageStrings.get("bank_opening_balance", lang),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "In: ${formatIndianCurrency(reportData.totalBankIn)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = JamaGreen,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Out: ${formatIndianCurrency(reportData.totalBankOut)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UdharRed,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Closing: ${formatIndianCurrency(reportData.netBankClosing)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = PrimaryBlue,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Section 2: Day-Wise Cash & Bank Summary (Sample / Preview)
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "1. Day-Wise Cash & Bank Table (${reportData.dayRows.size} days)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (reportData.dayRows.isEmpty()) {
                            Text(
                                text = LanguageStrings.get("no_entries_found", lang),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            reportData.dayRows.take(5).forEach { day ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = day.dateStr,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = "Cash Cl: ${formatIndianCurrency(day.cashClosing)} • Bank Cl: ${formatIndianCurrency(day.bankClosing)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = PrimaryBlue
                                    )
                                }
                                HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                            if (reportData.dayRows.size > 5) {
                                Text(
                                    text = "+ ${reportData.dayRows.size - 5} more days in PDF / CSV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Section 3: Party-Wise Collection Summary
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = LanguageStrings.get("report_party_summary", lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (reportData.partyCollections.isEmpty()) {
                            Text(
                                text = LanguageStrings.get("no_entries_found", lang),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        } else {
                            reportData.partyCollections.take(5).forEach { party ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = party.partyName,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = formatIndianCurrency(party.totalPaid),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        color = JamaGreen
                                    )
                                }
                                HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                            if (reportData.partyCollections.size > 5) {
                                Text(
                                    text = "+ ${reportData.partyCollections.size - 5} more parties in PDF / CSV",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Section 4: Unmatched or Explained Bank Lines
            item {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = LanguageStrings.get("report_unmatched_lines", lang),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (reportData.unmatchedBankEntries.isEmpty()) {
                            Text(
                                text = LanguageStrings.get("no_unmatched_lines", lang),
                                style = MaterialTheme.typography.bodySmall,
                                color = JamaGreen,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            reportData.unmatchedBankEntries.take(5).forEach { entry ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${dateFormat.format(Date(entry.date))} • ${if (entry.partyName.isNotBlank()) entry.partyName else entry.category}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary
                                        )
                                        val isCredit = entry.type == "IN"
                                        Text(
                                            text = formatIndianCurrency(entry.amount),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCredit) JamaGreen else UdharRed
                                        )
                                    }
                                    if (entry.unmatchedNote.isNotBlank()) {
                                        Text(
                                            text = "${LanguageStrings.get("not_on_sheet_label", lang)}: ${entry.unmatchedNote}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = UdharRed
                                        )
                                    }
                                }
                                HorizontalDivider(color = CardBorder.copy(alpha = 0.5f), thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
