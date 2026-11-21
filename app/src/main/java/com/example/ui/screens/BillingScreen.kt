package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.AppRepository
import com.example.model.*
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.AttachmentListSaver
import com.example.ui.util.PhoneUtil
import kotlinx.coroutines.launch
import java.io.File
import androidx.compose.ui.text.style.TextAlign
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BillingScreen(
    repository: AppRepository
) {
    val context = LocalContext.current
    val currentLang by repository.language.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    val invoices by repository.invoices.collectAsState()
    val customers by repository.customers.collectAsState()
    val transactions by repository.transactions.collectAsState()
    val cashbook by repository.cashbook.collectAsState()
    val attachments by repository.attachments.collectAsState()
    val storeProfile by repository.storeProfile.collectAsState()

    var selectedTab by rememberSaveable { mutableStateOf(0) } // 0: Naya Bill, 1: Bills List

    // ------------------------------------------
    // State for "Naya Bill" (Handwritten Chit Billing)
    // ------------------------------------------
    var selectedCustomerId by rememberSaveable { mutableStateOf("") }
    var selectedCustomerName by rememberSaveable { mutableStateOf("") }
    var selectedCustomerPhone by rememberSaveable { mutableStateOf("") }
    var partySearchQuery by rememberSaveable { mutableStateOf("") }

    var billAmountText by rememberSaveable { mutableStateOf("") }
    var billDate by rememberSaveable { mutableStateOf(System.currentTimeMillis()) }
    var billNote by rememberSaveable { mutableStateOf("") }

    // Chit photos attachments
    var pendingChitAttachments by rememberSaveable(stateSaver = AttachmentListSaver) { mutableStateOf<List<Attachment>>(emptyList()) }

    // Received now amount and payment mode
    var receivedNowText by rememberSaveable { mutableStateOf("0") }
    var selectedPaymentMode by rememberSaveable { mutableStateOf("CASH") } // CASH, UPI, BANK

    // Optional Due date in days (0: none, 7, 15, 30)
    var selectedDueDateDays by rememberSaveable { mutableStateOf(0) }

    // Dialog & UI states
    var showQuickAddPartyDialog by rememberSaveable { mutableStateOf(false) }
    var quickAddName by rememberSaveable { mutableStateOf("") }
    var quickAddPhone by rememberSaveable { mutableStateOf("") }
    var showContactImportModal by rememberSaveable { mutableStateOf(false) }
    var showContactRationaleDialog by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showPermanentlyDeniedDialog by remember { mutableStateOf(false) }

    val contactPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showContactImportModal = true
        } else {
            val activity = context as? Activity
            val canShowRationale = activity != null && ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.READ_CONTACTS)
            if (!canShowRationale) {
                showPermanentlyDeniedDialog = true
            } else {
                showPermissionDeniedDialog = true
            }
        }
    }

    val requestContactsAction = {
        val hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            showContactImportModal = true
        } else {
            showContactRationaleDialog = true
        }
    }

    var showSuccessDialog by rememberSaveable { mutableStateOf(false) }
    var lastSavedInvoice by remember { mutableStateOf<Invoice?>(null) }
    var lastSavedReceivedNow by remember { mutableStateOf(0.0) }

    var invoiceToCancel by remember { mutableStateOf<Invoice?>(null) }
    var cancelReasonText by rememberSaveable { mutableStateOf("") }

    // Full screen attachment viewer
    var viewerAttachmentList by remember { mutableStateOf<List<Attachment>?>(null) }
    var viewerInitialIndex by rememberSaveable { mutableStateOf(0) }

    // Voice recognition launcher
    val speechRecognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                billNote = if (billNote.isBlank()) spokenText else "$billNote $spokenText"
            }
        }
    }

    val totalAmount = billAmountText.toDoubleOrNull() ?: 0.0
    val receivedAmount = (receivedNowText.toDoubleOrNull() ?: 0.0).coerceIn(0.0, totalAmount)
    val remainderUdhar = (totalAmount - receivedAmount).coerceAtLeast(0.0)

    val selectedCustomer = customers.find { it.id == selectedCustomerId }

    Scaffold(
        containerColor = SoftBackground,
        topBar = {
            Column(modifier = Modifier.background(SurfaceWhite)) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = SurfaceWhite,
                    contentColor = PrimaryBlue,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = PrimaryBlue
                        )
                    }
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Text(
                                text = LanguageStrings.get("tab_new_bill", currentLang),
                                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        icon = { Icon(Icons.Default.EditNote, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            val countStr = " (${invoices.size})"
                            Text(
                                text = LanguageStrings.get("tab_past_bills", currentLang) + countStr,
                                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        },
                        icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(20.dp)) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (selectedTab == 0) {
                // ==========================================
                // TAB 0: "NAYA BILL" (HANDWRITTEN CHIT BILLING)
                // ==========================================
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. PARTY PICKER CARD
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = LanguageStrings.get("party_details_header", currentLang),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = TextPrimary
                                    )
                                }

                                if (selectedCustomerId.isNotBlank() || selectedCustomerName.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            selectedCustomerId = ""
                                            selectedCustomerName = ""
                                            selectedCustomerPhone = ""
                                        }
                                    ) {
                                        Text(
                                            text = LanguageStrings.get("change_party_btn", currentLang),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = PrimaryBlue
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            if (selectedCustomerId.isNotBlank() || selectedCustomerName.isNotBlank()) {
                                // Selected Party Badge Card
                                val balance = if (selectedCustomerId.isNotBlank()) repository.getCustomerBalance(selectedCustomerId) else 0.0
                                val balanceText = when {
                                    balance > 0.01 -> "Lena (Udhar): ₹${"%.2f".format(balance)}"
                                    balance < -0.01 -> "Dena (Advance): ₹${"%.2f".format(-balance)}"
                                    else -> "Hisaab Clear"
                                }
                                val balanceColor = when {
                                    balance > 0.01 -> UdharRed
                                    balance < -0.01 -> JamaGreen
                                    else -> TextSecondary
                                }

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(ChipActiveBg)
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = selectedCustomerName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 15.sp,
                                            color = TextPrimary
                                        )
                                        if (selectedCustomerPhone.isNotBlank()) {
                                            Text(
                                                text = PhoneUtil.toDisplay(selectedCustomerPhone),
                                                fontSize = 12.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                    if (selectedCustomerId.isNotBlank()) {
                                        Text(
                                            text = balanceText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp,
                                            color = balanceColor
                                        )
                                    }
                                }
                            } else {
                                // Search or Add New Party
                                OutlinedTextField(
                                    value = partySearchQuery,
                                    onValueChange = { partySearchQuery = it },
                                    placeholder = { Text(text = LanguageStrings.get("search_or_pick_party", currentLang)) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            quickAddName = partySearchQuery.trim()
                                            quickAddPhone = ""
                                            showQuickAddPartyDialog = true
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = LanguageStrings.get("btn_add_new_party_quick", currentLang),
                                                fontSize = 11.sp,
                                                lineHeight = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }

                                    OutlinedButton(
                                        onClick = { requestContactsAction() },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = LanguageStrings.get("import_from_contacts_quick", currentLang),
                                                fontSize = 11.sp,
                                                lineHeight = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            selectedCustomerId = ""
                                            selectedCustomerName = LanguageStrings.get("quick_cash_customer", currentLang)
                                            selectedCustomerPhone = ""
                                        },
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.buttonColors(containerColor = ChipActiveBg, contentColor = PrimaryBlue),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Icon(Icons.Default.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = LanguageStrings.get("quick_cash_customer", currentLang),
                                                fontSize = 11.sp,
                                                lineHeight = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                textAlign = TextAlign.Center,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }

                                // Autocomplete filtered customer list
                                val matchingCustomers = remember(partySearchQuery, customers) {
                                    if (partySearchQuery.isBlank()) {
                                        customers.take(5)
                                    } else {
                                        customers.filter {
                                            it.name.contains(partySearchQuery, ignoreCase = true) ||
                                                    it.phone.contains(partySearchQuery)
                                        }.take(5)
                                    }
                                }

                                if (matchingCustomers.isNotEmpty()) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        matchingCustomers.forEach { cust ->
                                            val bal = repository.getCustomerBalance(cust.id)
                                            val balLabel = if (bal > 0) "Lena: ₹${"%.0f".format(bal)}" else ""
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        selectedCustomerId = cust.id
                                                        selectedCustomerName = cust.name
                                                        selectedCustomerPhone = cust.phone
                                                        partySearchQuery = ""
                                                    }
                                                    .background(SoftBackground)
                                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column {
                                                    Text(text = cust.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                    if (cust.phone.isNotBlank()) {
                                                        Text(text = PhoneUtil.toDisplay(cust.phone), fontSize = 11.sp, color = TextSecondary)
                                                    }
                                                }
                                                if (balLabel.isNotBlank()) {
                                                    Text(text = balLabel, color = UdharRed, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. BIG BILL AMOUNT CARD
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = LanguageStrings.get("enter_bill_amount", currentLang),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary,
                                fontWeight = FontWeight.SemiBold
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            OutlinedTextField(
                                value = billAmountText,
                                onValueChange = {
                                    if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) {
                                        billAmountText = it
                                    }
                                },
                                textStyle = LocalTextStyle.current.copy(
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryBlue,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                ),
                                prefix = {
                                    Text(
                                        text = "₹ ",
                                        fontSize = 28.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryBlue
                                    )
                                },
                                placeholder = {
                                    Text(
                                        text = "0",
                                        fontSize = 32.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextTertiary,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            // Quick Amount Addition Chips
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(500, 1000, 2000, 5000, 10000).forEach { addVal ->
                                    OutlinedButton(
                                        onClick = {
                                            val cur = billAmountText.toDoubleOrNull() ?: 0.0
                                            billAmountText = (cur + addVal).toInt().toString()
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(text = "+₹$addVal", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (billAmountText.isNotBlank()) {
                                    TextButton(onClick = { billAmountText = "" }) {
                                        Text(text = LanguageStrings.get("btn_clear", currentLang), color = UdharRed, fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }

                    // 3. DATE & SHORT NOTE (WITH VOICE MIC)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            // Date row
                            val dateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(billDate))
                            val dayKeyFormat = SimpleDateFormat("yyyyMMdd", Locale.US)
                            val billDayKey = dayKeyFormat.format(Date(billDate))
                            val isBillToday = billDayKey == dayKeyFormat.format(Date())
                            val isBillYesterday = billDayKey == dayKeyFormat.format(Date(System.currentTimeMillis() - (24 * 3600 * 1000L)))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CalendarToday, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LanguageStrings.get("bill_date_label", currentLang) + ": $dateStr",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = isBillToday,
                                    onClick = { billDate = System.currentTimeMillis() },
                                    label = { Text(text = LanguageStrings.get("date_today_label", currentLang), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                )
                                FilterChip(
                                    selected = isBillYesterday,
                                    onClick = { billDate = System.currentTimeMillis() - (24 * 3600 * 1000L) },
                                    label = { Text(text = LanguageStrings.get("date_yesterday_label", currentLang), fontSize = 12.sp, maxLines = 1, softWrap = false) }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Short note with voice typing button
                            OutlinedTextField(
                                value = billNote,
                                onValueChange = { billNote = it },
                                placeholder = { Text(text = LanguageStrings.get("short_note_hint", currentLang)) },
                                singleLine = true,
                                trailingIcon = {
                                    IconButton(
                                        onClick = {
                                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                                val localeTag = when (currentLang) {
                                                    AppLanguage.GUJARATI -> "gu-IN"
                                                    AppLanguage.HINDI -> "hi-IN"
                                                    else -> "en-IN"
                                                }
                                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, localeTag)
                                                putExtra(RecognizerIntent.EXTRA_PROMPT, LanguageStrings.get("speak_note_prompt", currentLang))
                                            }
                                            try {
                                                speechRecognizerLauncher.launch(intent)
                                            } catch (e: Exception) {
                                                Toast.makeText(context, LanguageStrings.get("voice_not_supported", currentLang), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Mic, contentDescription = LanguageStrings.get("voice_note_desc", currentLang), tint = PrimaryBlue)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 4. BIG "PHOTO OF CHIT" BUTTON (TASK 10 ATTACHMENT STRIP)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PhotoCamera, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = LanguageStrings.get("photo_of_chit", currentLang),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            AttachmentPickerStrip(
                                attachments = pendingChitAttachments,
                                onAttachmentsChanged = { pendingChitAttachments = it },
                                ownerType = "INVOICE",
                                ownerId = "draft",
                                currentLang = currentLang,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 5. "RECEIVED NOW" AMOUNT & PAYMENT MODE
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Payments, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = LanguageStrings.get("received_now_label", currentLang),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = TextPrimary
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = receivedNowText,
                                onValueChange = {
                                    if (it.isEmpty() || it.matches(Regex("^\\d*(\\.\\d{0,2})?$"))) {
                                        receivedNowText = it
                                    }
                                },
                                prefix = { Text(text = "₹ ", fontWeight = FontWeight.Bold) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            // Quick settlement chips: ₹0 (Udhar), Full Paid, Half
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    selected = receivedNowText == "0" || receivedNowText.isEmpty(),
                                    onClick = { receivedNowText = "0" },
                                    label = { Text(text = LanguageStrings.get("chip_full_udhar", currentLang), fontSize = 11.sp) }
                                )
                                if (totalAmount > 0) {
                                    FilterChip(
                                        selected = (receivedNowText.toDoubleOrNull() ?: 0.0) == totalAmount,
                                        onClick = { receivedNowText = totalAmount.toInt().toString() },
                                        label = { Text(text = "${LanguageStrings.get("chip_full_paid", currentLang)} (₹${totalAmount.toInt()})", fontSize = 11.sp) }
                                    )
                                    val half = (totalAmount / 2.0).toInt()
                                    if (half > 0 && half != totalAmount.toInt()) {
                                        FilterChip(
                                            selected = (receivedNowText.toDoubleOrNull() ?: 0.0) == half.toDouble(),
                                            onClick = { receivedNowText = half.toString() },
                                            label = { Text(text = "${LanguageStrings.get("chip_half_paid", currentLang)} (₹$half)", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Mode Selector: Cash | UPI | Bank
                            Text(
                                text = LanguageStrings.get("payment_mode_label", currentLang),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(6.dp))

                            // Buttons keep their text on ONE line; on large phone fonts the row scrolls sideways
                            // instead of squeezing the words into single letters.
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("CASH", "UPI", "BANK").forEach { mode ->
                                    val isSelected = selectedPaymentMode == mode
                                    val label = when (mode) {
                                        "CASH" -> LanguageStrings.get("mode_cash", currentLang)
                                        "UPI" -> "UPI"
                                        else -> "Bank"
                                    }
                                    OutlinedButton(
                                        onClick = { selectedPaymentMode = mode },
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = if (isSelected) {
                                            ButtonDefaults.outlinedButtonColors(containerColor = ChipActiveBg, contentColor = PrimaryBlue)
                                        } else {
                                            ButtonDefaults.outlinedButtonColors()
                                        },
                                        border = if (isSelected) BorderStroke(1.5.dp, PrimaryBlue) else CardDefaults.outlinedCardBorder()
                                    ) {
                                        Text(text = label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp, maxLines = 1, softWrap = false)
                                    }
                                }
                            }

                            // Dynamic Summary Box
                            Spacer(modifier = Modifier.height(12.dp))
                            val summaryContainerColor = if (remainderUdhar > 0.01) UdharRed.copy(alpha = 0.08f) else JamaGreen.copy(alpha = 0.08f)
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = CardDefaults.cardColors(containerColor = summaryContainerColor)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    if (receivedAmount > 0.01) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = LanguageStrings.get("received_cashbook_summary", currentLang), fontSize = 12.sp, color = TextPrimary)
                                            Text(text = "+₹${"%.2f".format(receivedAmount)} ($selectedPaymentMode)", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = JamaGreen)
                                        }
                                    }
                                    if (remainderUdhar > 0.01) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(text = LanguageStrings.get("remainder_udhar_summary", currentLang), fontSize = 12.sp, color = TextPrimary)
                                            Text(text = "+₹${"%.2f".format(remainderUdhar)}", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = UdharRed)
                                        }
                                    }
                                    if (remainderUdhar <= 0.01 && totalAmount > 0) {
                                        Text(
                                            text = "✅ Full payment settled in Cashbook.",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = JamaGreen
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 6. OPTIONAL DUE DATE CHIPS
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.HourglassTop, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LanguageStrings.get("due_date_label", currentLang),
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(0, 7, 15, 30).forEach { days ->
                                    val isSelected = selectedDueDateDays == days
                                    val label = when (days) {
                                        7 -> LanguageStrings.get("chip_7_days", currentLang)
                                        15 -> LanguageStrings.get("chip_15_days", currentLang)
                                        30 -> LanguageStrings.get("chip_30_days", currentLang)
                                        else -> LanguageStrings.get("chip_no_due", currentLang)
                                    }
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { selectedDueDateDays = days },
                                        label = { Text(text = label, fontSize = 12.sp) }
                                    )
                                }
                            }

                            if (selectedDueDateDays > 0) {
                                val cal = Calendar.getInstance().apply {
                                    timeInMillis = billDate
                                    add(Calendar.DAY_OF_YEAR, selectedDueDateDays)
                                }
                                val dueDateStr = SimpleDateFormat("dd MMM yyyy", Locale.US).format(cal.time)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = LanguageStrings.get("due_date_prefix", currentLang) + " $dueDateStr",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UdharRed
                                )
                            }
                        }
                    }

                    // 7. BIG "SAVE BILL" BUTTON
                    val isFormValid = totalAmount > 0.01 && (selectedCustomerId.isNotBlank() || selectedCustomerName.isNotBlank())
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                try {
                                    val computedDueDate = if (selectedDueDateDays > 0) {
                                        val cal = Calendar.getInstance().apply {
                                            timeInMillis = billDate
                                            add(Calendar.DAY_OF_YEAR, selectedDueDateDays)
                                        }
                                        cal.timeInMillis
                                    } else 0L

                                    val dueString = if (selectedDueDateDays > 0) {
                                        " | Due: " + SimpleDateFormat("dd MMM yyyy", Locale.US).format(java.util.Date(computedDueDate))
                                    } else ""

                                    val finalNote = (billNote.trim() + dueString).trim()

                                    val draftInvoice = Invoice(
                                        billNumber = "",
                                        date = billDate,
                                        customerId = selectedCustomerId,
                                        customerName = selectedCustomerName.ifBlank { "Cash Customer" },
                                        customerPhone = selectedCustomerPhone,
                                        subtotal = totalAmount,
                                        gstAmount = 0.0,
                                        totalAmount = totalAmount,
                                        paymentMode = if (receivedAmount >= totalAmount - 0.001) selectedPaymentMode else if (receivedAmount <= 0.001) "CREDIT" else "PARTIAL",
                                        notes = finalNote,
                                        dueDate = computedDueDate,
                                        items = listOf(
                                            InvoiceItem(
                                                name = if (billNote.isNotBlank()) billNote else "Handwritten Bill",
                                                unitPrice = totalAmount,
                                                quantity = 1,
                                                gstPercent = 0.0,
                                                total = totalAmount
                                            )
                                        )
                                    )

                                    val saved = repository.createInvoice(
                                        invoice = draftInvoice,
                                        receivedNow = receivedAmount,
                                        receivedPaymentMode = selectedPaymentMode
                                    )

                                    // Save attached chit photos with ownerType = "INVOICE"
                                    if (pendingChitAttachments.isNotEmpty()) {
                                        repository.addAttachments(
                                            pendingChitAttachments.map { it.copy(ownerType = "INVOICE", ownerId = saved.id) }
                                        )
                                    }

                                    lastSavedInvoice = saved
                                    lastSavedReceivedNow = receivedAmount
                                    showSuccessDialog = true

                                    // Reset inputs
                                    billAmountText = ""
                                    billNote = ""
                                    receivedNowText = "0"
                                    pendingChitAttachments = emptyList()
                                    selectedDueDateDays = 0
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Failed to save bill: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = isFormValid,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = LanguageStrings.get("btn_create_bill", currentLang),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                }
            } else {
                // ==========================================
                // TAB 1: "BILLS" LIST
                // ==========================================
                var listSearchQuery by rememberSaveable { mutableStateOf("") }
                var statusFilter by rememberSaveable { mutableStateOf("ALL") } // ALL, PAID, PARTLY_PAID, UNPAID, CANCELLED
                var dateFilter by rememberSaveable { mutableStateOf("ALL_TIME") } // ALL_TIME, THIS_MONTH, TODAY

                val cal = Calendar.getInstance()
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                val startOfToday = cal.timeInMillis

                cal.set(Calendar.DAY_OF_MONTH, 1)
                val startOfMonth = cal.timeInMillis

                val filteredInvoices = remember(invoices, listSearchQuery, statusFilter, dateFilter, transactions, cashbook) {
                    invoices.filter { inv ->
                        val matchesSearch = listSearchQuery.isBlank() ||
                                inv.billNumber.contains(listSearchQuery, ignoreCase = true) ||
                                inv.customerName.contains(listSearchQuery, ignoreCase = true) ||
                                inv.customerPhone.contains(listSearchQuery)

                        val matchesDate = when (dateFilter) {
                            "TODAY" -> inv.date >= startOfToday
                            "THIS_MONTH" -> inv.date >= startOfMonth
                            else -> true
                        }

                        val status = repository.getInvoicePaymentStatus(inv)
                        val matchesStatus = when (statusFilter) {
                            "PAID" -> status == AppRepository.InvoicePaymentStatus.PAID
                            "PARTLY_PAID" -> status == AppRepository.InvoicePaymentStatus.PARTLY_PAID
                            "UNPAID" -> status == AppRepository.InvoicePaymentStatus.UNPAID
                            "CANCELLED" -> status == AppRepository.InvoicePaymentStatus.CANCELLED
                            else -> true
                        }

                        matchesSearch && matchesDate && matchesStatus
                    }
                }

                val totalBilled = filteredInvoices.filter { !it.isCancelled }.sumOf { it.totalAmount }
                val totalReceived = filteredInvoices.filter { !it.isCancelled }.sumOf { repository.getInvoiceReceivedAmount(it) }
                val totalPending = (totalBilled - totalReceived).coerceAtLeast(0.0)

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Search box
                        OutlinedTextField(
                            value = listSearchQuery,
                            onValueChange = { listSearchQuery = it },
                            placeholder = { Text(text = LanguageStrings.get("search_bills_hint", currentLang)) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Status Filter Chips
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val filters = listOf(
                                "ALL" to LanguageStrings.get("filter_all", currentLang),
                                "PAID" to LanguageStrings.get("filter_paid", currentLang),
                                "PARTLY_PAID" to LanguageStrings.get("filter_partly_paid", currentLang),
                                "UNPAID" to LanguageStrings.get("filter_unpaid", currentLang),
                                "CANCELLED" to LanguageStrings.get("status_cancelled", currentLang)
                            )
                            items(filters) { (key, label) ->
                                FilterChip(
                                    selected = statusFilter == key,
                                    onClick = { statusFilter = key },
                                    label = { Text(text = label, fontSize = 12.sp) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Date Range Filter Chips
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            listOf(
                                "ALL_TIME" to LanguageStrings.get("filter_all_time", currentLang),
                                "THIS_MONTH" to LanguageStrings.get("filter_this_month", currentLang),
                                "TODAY" to LanguageStrings.get("filter_today", currentLang)
                            ).forEach { (dKey, dLabel) ->
                                FilterChip(
                                    selected = dateFilter == dKey,
                                    onClick = { dateFilter = dKey },
                                    label = { Text(text = dLabel, fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Totals summary row
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                            border = CardDefaults.outlinedCardBorder()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = LanguageStrings.get("totals_billed", currentLang), fontSize = 11.sp, color = TextSecondary)
                                    Text(text = "₹${"%.0f".format(totalBilled)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = LanguageStrings.get("totals_received", currentLang), fontSize = 11.sp, color = TextSecondary)
                                    Text(text = "₹${"%.0f".format(totalReceived)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = JamaGreen)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(text = LanguageStrings.get("totals_pending", currentLang), fontSize = 11.sp, color = TextSecondary)
                                    Text(text = "₹${"%.0f".format(totalPending)}", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = UdharRed)
                                }
                            }
                        }
                    }

                    if (filteredInvoices.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = LanguageStrings.get("empty_bills_title", currentLang),
                                        fontWeight = FontWeight.Bold,
                                        color = TextSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredInvoices, key = { it.id }) { invoice ->
                            val billStatus = repository.getInvoicePaymentStatus(invoice)
                            val receivedAmt = repository.getInvoiceReceivedAmount(invoice)
                            val outstandingAmt = (invoice.totalAmount - receivedAmt).coerceAtLeast(0.0)
                            val chitAttachments = attachments.filter { it.ownerType == "INVOICE" && it.ownerId == invoice.id }

                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    // Top Row: Bill # and Date + Status Badge
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val dateFmt = SimpleDateFormat("dd MMM yyyy", Locale.US).format(Date(invoice.date))
                                        val prefix = LanguageStrings.get("bill_card_prefix", currentLang)
                                        Text(
                                            text = "$prefix${invoice.billNumber} • $dateFmt",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = TextPrimary
                                        )

                                        val (statusText, statusBg, statusColor) = when (billStatus) {
                                            AppRepository.InvoicePaymentStatus.PAID ->
                                                Triple(LanguageStrings.get("status_paid", currentLang), JamaGreen.copy(alpha = 0.15f), JamaGreen)
                                            AppRepository.InvoicePaymentStatus.PARTLY_PAID ->
                                                Triple(LanguageStrings.get("status_partly_paid", currentLang), Color(0xFFFF9800).copy(alpha = 0.15f), Color(0xFFE65100))
                                            AppRepository.InvoicePaymentStatus.UNPAID ->
                                                Triple(LanguageStrings.get("status_unpaid", currentLang), UdharRed.copy(alpha = 0.15f), UdharRed)
                                            AppRepository.InvoicePaymentStatus.CANCELLED ->
                                                Triple(LanguageStrings.get("status_cancelled", currentLang), TextSecondary.copy(alpha = 0.15f), TextSecondary)
                                        }

                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(statusBg)
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = statusText,
                                                color = statusColor,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Party name & Phone
                                    Text(
                                        text = invoice.customerName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextPrimary
                                    )
                                    if (invoice.customerPhone.isNotBlank()) {
                                        Text(
                                            text = PhoneUtil.toDisplay(invoice.customerPhone),
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }

                                    // Optional note
                                    if (invoice.notes.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = invoice.notes,
                                            fontSize = 12.sp,
                                            color = TextSecondary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Amount Row
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Text(
                                                text = "₹${"%.2f".format(invoice.totalAmount)}",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 18.sp,
                                                color = if (invoice.isCancelled) TextSecondary else PrimaryBlue
                                            )
                                            if (billStatus == AppRepository.InvoicePaymentStatus.PARTLY_PAID) {
                                                Text(
                                                    text = "Recv: ₹${"%.0f".format(receivedAmt)} • Pending: ₹${"%.0f".format(outstandingAmt)}",
                                                    fontSize = 11.sp,
                                                    color = Color(0xFFE65100),
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        // Chit photos badge
                                        if (chitAttachments.isNotEmpty()) {
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        viewerAttachmentList = chitAttachments
                                                        viewerInitialIndex = 0
                                                    }
                                                    .background(ChipActiveBg)
                                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Default.Photo, contentDescription = null, modifier = Modifier.size(16.dp), tint = PrimaryBlue)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = "${chitAttachments.size} Photos",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PrimaryBlue
                                                )
                                            }
                                        }
                                    }

                                    // Actions: WhatsApp share & Cancel
                                    Spacer(modifier = Modifier.height(10.dp))
                                    HorizontalDivider(color = CardBorder)
                                    Spacer(modifier = Modifier.height(6.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TextButton(
                                            onClick = {
                                                shareBillToWhatsApp(
                                                    context = context,
                                                    invoice = invoice,
                                                    receivedNow = receivedAmt,
                                                    remainder = outstandingAmt,
                                                    chitAttachment = chitAttachments.firstOrNull(),
                                                    shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" },
                                                    upiId = storeProfile.upiId,
                                                    currentLang = currentLang,
                                                    storeProfile = storeProfile,
                                                    partyTotalDue = invoice.customerId.takeIf { it.isNotBlank() }?.let { repository.getCustomerBalance(it) }
                                                )
                                            }
                                        ) {
                                            Icon(Icons.Default.Share, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = LanguageStrings.get("btn_whatsapp", currentLang),
                                                color = JamaGreen,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        }

                                        if (!invoice.isCancelled) {
                                            TextButton(
                                                onClick = {
                                                    invoiceToCancel = invoice
                                                    cancelReasonText = ""
                                                }
                                            ) {
                                                Text(
                                                    text = LanguageStrings.get("cancel_bill_title", currentLang),
                                                    color = UdharRed,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        } else {
                                            Text(
                                                text = invoice.cancellationReason.ifBlank { "Cancelled" },
                                                fontSize = 11.sp,
                                                color = UdharRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // Quick Add Party Dialog
    if (showQuickAddPartyDialog) {
        AlertDialog(
            onDismissRequest = { showQuickAddPartyDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("add_party", currentLang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = quickAddName,
                        onValueChange = { quickAddName = it },
                        label = { Text(text = LanguageStrings.get("customer_name_label", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = quickAddPhone,
                        onValueChange = { quickAddPhone = it },
                        label = { Text(text = LanguageStrings.get("customer_phone_label", currentLang)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (quickAddName.isNotBlank()) {
                            val newParty = repository.addCustomer(
                                name = quickAddName.trim(),
                                phone = quickAddPhone.trim(),
                                type = PartyType.CUSTOMER.raw
                            )
                            selectedCustomerId = newParty.id
                            selectedCustomerName = newParty.name
                            selectedCustomerPhone = newParty.phone
                            showQuickAddPartyDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    enabled = quickAddName.isNotBlank()
                ) {
                    Text(text = LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickAddPartyDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // In-App Contact Picker Modal (From Task 9)
    if (showContactImportModal) {
        val existingPhones = remember(customers) {
            customers.map { PhoneUtil.normalize(it.phone) }.filter { it.isNotBlank() }.toSet()
        }
        ContactImportModal(
            lang = currentLang,
            existingPhoneNumbers = existingPhones,
            onDismiss = { showContactImportModal = false },
            onContactsImported = { selectedContacts ->
                showContactImportModal = false
                if (selectedContacts.size == 1) {
                    // One contact picked for this bill: reuse the party if the number is already
                    // in the khata, so the bill does not land on a duplicate party.
                    val contact = selectedContacts.first()
                    val norm = PhoneUtil.normalize(contact.normalizedNumber.ifBlank { contact.rawNumber })
                    val existingParty = customers.firstOrNull {
                        norm.isNotBlank() &&
                                it.type.equals(PartyType.CUSTOMER.raw, ignoreCase = true) &&
                                PhoneUtil.normalize(it.phone) == norm
                    }
                    val party = existingParty ?: repository.addCustomer(
                        name = contact.name.trim(),
                        phone = norm,
                        type = PartyType.CUSTOMER.raw
                    )
                    selectedCustomerId = party.id
                    selectedCustomerName = party.name
                    selectedCustomerPhone = party.phone
                } else if (selectedContacts.size > 1) {
                    coroutineScope.launch {
                        try {
                            // One background write for the whole selection. Numbers already in the khata are skipped.
                            repository.addCustomersBulk(
                                selectedContacts.map { it.name to it.normalizedNumber.ifBlank { it.rawNumber } },
                                PartyType.CUSTOMER.raw
                            )
                        } catch (t: Throwable) {
                            android.util.Log.e("BillingScreen", "Contact import failed", t)
                        }
                    }
                }
            }
        )
    }

    if (showContactRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showContactRationaleDialog = false },
            title = { Text(text = LanguageStrings.get("contacts_permission_title", currentLang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_reason", currentLang)) },
            confirmButton = {
                Button(onClick = {
                    showContactRationaleDialog = false
                    try {
                        contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    } catch (t: Throwable) {
                        // Never crash over a permission prompt: offer the Settings route instead.
                        android.util.Log.e("BillingScreen", "Contacts permission request failed", t)
                        showPermanentlyDeniedDialog = true
                    }
                }) {
                    Text(text = LanguageStrings.get("btn_continue", currentLang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactRationaleDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text(text = LanguageStrings.get("permission_denied_title", currentLang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_denied", currentLang)) },
            confirmButton = {
                Button(onClick = { showPermissionDeniedDialog = false }) {
                    Text(text = LanguageStrings.get("btn_ok", currentLang))
                }
            }
        )
    }

    if (showPermanentlyDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermanentlyDeniedDialog = false },
            title = { Text(text = LanguageStrings.get("permission_required_title", currentLang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_permanently_denied", currentLang)) },
            confirmButton = {
                Button(onClick = {
                    showPermanentlyDeniedDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text(text = LanguageStrings.get("btn_open_settings", currentLang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentlyDeniedDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Cancel Invoice Dialog
    if (invoiceToCancel != null) {
        val targetInv = invoiceToCancel!!
        AlertDialog(
            onDismissRequest = { invoiceToCancel = null },
            title = {
                Text(
                    text = LanguageStrings.get("cancel_bill_title", currentLang),
                    fontWeight = FontWeight.Bold,
                    color = UdharRed
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = LanguageStrings.get("cancel_bill_desc", currentLang),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = cancelReasonText,
                        onValueChange = { cancelReasonText = it },
                        label = { Text(text = LanguageStrings.get("cancel_reason_label", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val reason = cancelReasonText.ifBlank { "Cancelled by user" }
                        repository.cancelInvoice(targetInv.id, reason)
                        invoiceToCancel = null
                        Toast.makeText(context, "Bill #${targetInv.billNumber} cancelled", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = UdharRed)
                ) {
                    Text(text = LanguageStrings.get("btn_confirm_cancel", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { invoiceToCancel = null }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Bill Created Successfully Dialog
    if (showSuccessDialog && lastSavedInvoice != null) {
        val inv = lastSavedInvoice!!
        val chitFirstPhoto = attachments.find { it.ownerType == "INVOICE" && it.ownerId == inv.id }
        val remUdhar = (inv.totalAmount - lastSavedReceivedNow).coerceAtLeast(0.0)

        AlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("bill_generated_title", currentLang),
                    fontWeight = FontWeight.Bold,
                    color = PrimaryBlue
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pfx = LanguageStrings.get("bill_card_prefix", currentLang)
                    Text(
                        text = "$pfx${inv.billNumber} (${inv.customerName})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${LanguageStrings.get("bill_saved_total", currentLang)}: ₹${"%.2f".format(inv.totalAmount)}",
                        fontWeight = FontWeight.Bold,
                        color = PrimaryBlue
                    )
                    if (lastSavedReceivedNow > 0) {
                        Text(
                            text = "${LanguageStrings.get("bill_saved_received", currentLang)}: ₹${"%.2f".format(lastSavedReceivedNow)}",
                            color = JamaGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (remUdhar > 0 && repository.getInvoicePaymentStatus(inv) == AppRepository.InvoicePaymentStatus.PAID) {
                        Text(
                            text = LanguageStrings.get("bill_covered_by_advance", currentLang),
                            color = JamaGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else if (remUdhar > 0) {
                        Text(
                            text = "${LanguageStrings.get("bill_saved_baaki", currentLang)}: ₹${"%.2f".format(remUdhar)}",
                            color = UdharRed,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        shareBillToWhatsApp(
                            context = context,
                            invoice = inv,
                            receivedNow = lastSavedReceivedNow,
                            remainder = remUdhar,
                            chitAttachment = chitFirstPhoto,
                            shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" },
                            upiId = storeProfile.upiId,
                            currentLang = currentLang,
                            storeProfile = storeProfile,
                            partyTotalDue = inv.customerId.takeIf { it.isNotBlank() }?.let { repository.getCustomerBalance(it) }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = LanguageStrings.get("btn_whatsapp", currentLang),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showSuccessDialog = false }) {
                    Text(text = LanguageStrings.get("btn_ok", currentLang))
                }
            }
        )
    }

    // Full Screen Attachment Viewer
    if (viewerAttachmentList != null) {
        FullScreenAttachmentViewer(
            attachments = viewerAttachmentList!!,
            initialIndex = viewerInitialIndex,
            currentLang = currentLang,
            onDismiss = { viewerAttachmentList = null },
            onDelete = { attToDelete ->
                repository.deleteAttachment(attToDelete.id)
                viewerAttachmentList = viewerAttachmentList?.filter { it.id != attToDelete.id }?.ifEmpty { null }
                if (viewerAttachmentList.isNullOrEmpty()) {
                    viewerAttachmentList = null
                }
            }
        )
    }
}

private fun shareBillToWhatsApp(
    context: Context,
    invoice: Invoice,
    receivedNow: Double,
    remainder: Double,
    chitAttachment: Attachment?,
    shopName: String,
    upiId: String,
    currentLang: AppLanguage,
    storeProfile: com.example.model.StoreProfile,
    partyTotalDue: Double?
) {
    val upiPart = if (upiId.isNotBlank()) " | UPI: $upiId" else ""
    val msg = "Namaste ${invoice.customerName} ji, aapka bill #${invoice.billNumber} ban gaya hai.\n" +
            "Total Amount: ₹${"%.2f".format(invoice.totalAmount)}\n" +
            "Received: ₹${"%.2f".format(receivedNow)}\n" +
            "Baaki Udhar: ₹${"%.2f".format(remainder)}\n" +
            "$shopName$upiPart"

    val waNumber = PhoneUtil.toWhatsAppNumber(invoice.customerPhone)
    val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")

    // Preferred: a proper PDF bill (with the chit photo inside) sent straight to the customer.
    val pdfFile = try {
        com.example.ui.util.BillPdfGenerator.generate(
            context = context,
            store = storeProfile,
            data = com.example.ui.util.BillPdfGenerator.BillData(
                invoice = invoice,
                receivedNow = receivedNow,
                partyTotalDue = partyTotalDue,
                chitPhotoPath = chitAttachment?.takeIf { it.mimeType.startsWith("image") }?.filePath
            ),
            lang = currentLang
        )
    } catch (e: Exception) {
        android.util.Log.w("BillingScreen", "PDF bill failed, sending photo/text instead: ${e.message}")
        null
    }
    if (pdfFile != null && pdfFile.exists()) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
        val pdfIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, msg)
            if (waNumber.isNotBlank()) putExtra("jid", "$waNumber@s.whatsapp.net")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        for (pkg in whatsAppPackages) {
            try {
                context.startActivity(Intent(pdfIntent).apply { setPackage(pkg) })
                return
            } catch (_: Exception) {
                // Try next WhatsApp package
            }
        }
        try {
            context.startActivity(Intent.createChooser(pdfIntent, "Share Bill"))
            return
        } catch (_: Exception) {
            // Fall back to photo / text below
        }
    }

    if (chitAttachment != null) {
        val file = File(chitAttachment.filePath)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val baseShareIntent = Intent(Intent.ACTION_SEND).apply {
                type = chitAttachment.mimeType.ifBlank { "image/jpeg" }
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, msg)
                if (waNumber.isNotBlank()) {
                    putExtra("jid", "$waNumber@s.whatsapp.net")
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            for (pkg in whatsAppPackages) {
                try {
                    val pkgIntent = Intent(baseShareIntent).apply { setPackage(pkg) }
                    context.startActivity(pkgIntent)
                    return
                } catch (_: Exception) {
                    // Try next WhatsApp package
                }
            }
            try {
                context.startActivity(Intent.createChooser(baseShareIntent, "Share Bill"))
                return
            } catch (_: Exception) {
                // Fallback to text intent below
            }
        }
    }

    // Direct WhatsApp / WhatsApp Business / chooser text link fallback
    if (waNumber.isNotBlank()) {
        val enc = java.net.URLEncoder.encode(msg, "UTF-8")
        val waUri = Uri.parse("https://wa.me/$waNumber?text=$enc")
        for (pkg in whatsAppPackages) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, waUri).apply { setPackage(pkg) })
                return
            } catch (_: Exception) {
                // Try next package
            }
        }
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, waUri))
            return
        } catch (_: Exception) {
            // Fall through to chooser
        }
    }

    val textIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, msg)
    }
    for (pkg in whatsAppPackages) {
        try {
            context.startActivity(Intent(textIntent).apply { setPackage(pkg) })
            return
        } catch (_: Exception) {
            // Try next package
        }
    }
    context.startActivity(Intent.createChooser(textIntent, "Share Bill"))
}
