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
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.data.AppRepository
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.model.DeliveryTask
import com.example.model.PartyType
import com.example.model.Transaction
import com.example.ui.components.ContactImportModal
import com.example.ui.components.EmptyStateView
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.QuickEntry
import com.example.ui.util.PhoneUtil
import com.example.data.OverdueLogic
import com.example.ui.util.VoiceEntryParser
import com.example.ui.util.VoiceParsedEntry
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KhataScreen(
    repository: AppRepository,
    onCustomerClick: (Customer) -> Unit,
    onNavigateToBilling: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val customers by repository.customers.collectAsState()
    val transactions by repository.transactions.collectAsState()
    val cashbook by repository.cashbook.collectAsState()
    val deliveryTasks by repository.deliveryTasks.collectAsState()
    val currentLang by repository.language.collectAsState()
    val storeProfile by repository.storeProfile.collectAsState()
    val simpleMode by repository.simpleMode.collectAsState()
    val lastBackupTimestamp by repository.lastBackupTimestamp.collectAsState()
    val lang = currentLang

    val totalReceivable = remember(transactions, customers) { repository.getTotalReceivable() }
    val totalPayable = remember(transactions, customers) { repository.getTotalPayable() }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var showAddPartyDialog by remember { mutableStateOf(false) }

    // Contact Import States
    var showContactRationaleDialog by remember { mutableStateOf(false) }
    var showContactImportModal by remember { mutableStateOf(false) }
    var showPermissionDeniedDialog by remember { mutableStateOf(false) }
    var showPermanentlyDeniedDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            showAddPartyDialog = false
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
            showAddPartyDialog = false
            showContactImportModal = true
        } else {
            showContactRationaleDialog = true
        }
    }

    // Quick Action & Voice Entry States
    var quickActionPartyPickerMode by remember { mutableStateOf<String?>(null) } // "GAVE" or "GOT"
    // Which home button was tapped. Kept alive through party picker + entry dialog; cleared only when the entry dialog closes.
    var quickActionType by remember { mutableStateOf<String?>(null) } // "GAVE" or "GOT"
    var quickActionSelectedCustomer by remember { mutableStateOf<Customer?>(null) }
    var quickActionAmount by remember { mutableStateOf("") }
    var quickActionNote by remember { mutableStateOf("") }
    var quickActionPaymentMode by remember { mutableStateOf("CASH") }
    var quickActionCashGiven by remember { mutableStateOf(false) }
    var quickActionDate by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Voice confirmation dialog state
    var voiceConfirmationEntry by remember { mutableStateOf<VoiceParsedEntry?>(null) }
    var voiceInputText by remember { mutableStateOf("") }
    var showVoiceInputDialog by remember { mutableStateOf(false) }

    // Undo snackbar state
    var lastSavedTx by remember { mutableStateOf<Transaction?>(null) }
    var showUndoSnackbar by remember { mutableStateOf(false) }
    // Hide the Undo bar after a few seconds so an old entry can't be undone by a stray tap later
    LaunchedEffect(showUndoSnackbar, lastSavedTx) {
        if (showUndoSnackbar) {
            delay(6000)
            showUndoSnackbar = false
            lastSavedTx = null
        }
    }

    // Delivery Task Dialog
    var showAddDeliveryDialog by remember { mutableStateOf(false) }
    var newDeliveryPartyName by remember { mutableStateOf("") }
    var newDeliveryNote by remember { mutableStateOf("") }
    var showDeliveryHistory by remember { mutableStateOf(false) }

    // Speech Recognizer Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenTexts = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val recognizedText = spokenTexts?.firstOrNull() ?: ""
            if (recognizedText.isNotBlank()) {
                val parsed = VoiceEntryParser.parse(recognizedText, customers)
                voiceConfirmationEntry = parsed
            }
        }
    }

    fun startVoiceListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            val langTag = when (lang) {
                AppLanguage.GUJARATI -> "gu-IN"
                AppLanguage.HINDI -> "hi-IN"
                else -> "hi-IN"
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langTag)
            putExtra(RecognizerIntent.EXTRA_PROMPT, LanguageStrings.get("voice_entry_mic", lang))
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            // If no voice recognition engine present, open manual voice input dialog
            showVoiceInputDialog = true
        }
    }

    // Backup age computation
    val daysSinceBackup = remember(lastBackupTimestamp) {
        if (lastBackupTimestamp == 0L) 999L else (System.currentTimeMillis() - lastBackupTimestamp) / (24L * 3600L * 1000L)
    }
    // Nothing to lose on a fresh app, so only warn once the app actually holds data.
    val hasAnyData = customers.isNotEmpty() || transactions.isNotEmpty() || cashbook.isNotEmpty()
    val isBackupOld = hasAnyData && daysSinceBackup >= 7L

    fun sendBackupZip() {
        coroutineScope.launch {
            try {
                val zipFile = repository.exportBackupFile(context)
                repository.recordBackupCompleted()
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", zipFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Ghanshyam Kids Wear Backup")
                    putExtra(Intent.EXTRA_TEXT, "Ghanshyam Kids Wear data backup")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, LanguageStrings.get("backup_habits_label", lang)))
            } catch (e: Exception) {
                Toast.makeText(context, "Backup failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun launchWhatsApp(phone: String, partyName: String, amount: Double) {
        try {
            val cleanPhone = phone.replace(Regex("""[^0-9]"""), "")
            val formattedPhone = if (cleanPhone.length == 10) "91$cleanPhone" else cleanPhone
            val shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" }
            val amountFormatted = formatIndianCurrency(amount)
            val reminderMsg = "Namaste $partyName ji, $shopName se aapka hisaab $amountFormatted baki hai. Kripya bhugtan karein. Dhanyawad."
            val uri = Uri.parse("https://api.whatsapp.com/send?phone=$formattedPhone&text=${URLEncoder.encode(reminderMsg, "UTF-8")}")
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "WhatsApp not available", Toast.LENGTH_SHORT).show()
        }
    }

    fun launchCall(phone: String) {
        try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Cannot dial", Toast.LENGTH_SHORT).show()
        }
    }

    // The phone keeps this screen alive all day: refresh "today" every minute so midnight rolls over by itself.
    var nowTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L)
            nowTick = System.currentTimeMillis()
        }
    }
    val startOfToday = remember(nowTick / 3_600_000L) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val endOfToday = startOfToday + 86400000L

    // Today's summary calculations
    val (todayGot, todayGave, dueOrOverdueCount) = remember(transactions, customers, startOfToday) {
        repository.getTodaySummary()
    }
    val cashOpening by repository.cashOpeningBalance.collectAsState()
    val bankOpening by repository.bankOpeningBalance.collectAsState()
    val cashBalance = remember(cashbook, cashOpening) { repository.getCashBalance() }
    val bankBalance = remember(cashbook, bankOpening) { repository.getBankBalance() }

    // Vasuli List: parties who owe money (> 0.01), sorted overdue first.
    // Overdue = a bill still unpaid and past due date / older than 15 days (shared rule, see OverdueLogic).
    val vasuliList = remember(customers, transactions, startOfToday) {
        val txByCustomer = transactions.groupBy { it.customerId }
        customers.mapNotNull { cust ->
            val bal = repository.getCustomerBalance(cust.id)
            if (bal > 0.01) {
                val isOverdue = OverdueLogic.isOverdue(txByCustomer[cust.id] ?: emptyList())
                Triple(cust, bal, isOverdue)
            } else null
        }.sortedWith(compareByDescending<Triple<Customer, Double, Boolean>> { it.third }.thenByDescending { it.second })
    }

    // Aaj ki delivery tasks
    val todayDeliveries = remember(deliveryTasks, showDeliveryHistory, startOfToday) {
        if (showDeliveryHistory) {
            deliveryTasks
        } else {
            deliveryTasks.filter { (it.date in startOfToday..endOfToday) || (!it.isCompleted) }
        }
    }

    val filteredCustomers = remember(customers, transactions, searchQuery, selectedFilter) {
        customers.filter { cust ->
            val matchesSearch = cust.name.contains(searchQuery, ignoreCase = true) ||
                    cust.phone.contains(searchQuery)
            val balance = repository.getCustomerBalance(cust.id)
            val matchesFilter = when (selectedFilter) {
                "CUSTOMERS" -> cust.type == PartyType.CUSTOMER.raw
                "SUPPLIERS" -> cust.type == PartyType.SUPPLIER.raw
                "PENDING" -> Math.abs(balance) > 0.001
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Scaffold(
        containerColor = SoftBackground,
        snackbarHost = {
            if (showUndoSnackbar && lastSavedTx != null) {
                Snackbar(
                    modifier = Modifier.padding(16.dp),
                    action = {
                        TextButton(
                            onClick = {
                                val txToDelete = lastSavedTx
                                if (txToDelete != null) {
                                    repository.deleteTransaction(txToDelete.id)
                                    lastSavedTx = null
                                    showUndoSnackbar = false
                                }
                            }
                        ) {
                            Text(text = LanguageStrings.get("btn_undo", lang), fontWeight = FontWeight.Bold, color = SurfaceWhite)
                        }
                    },
                    containerColor = PrimaryBlue
                ) {
                    Text(text = LanguageStrings.get("entry_saved_msg", lang), color = SurfaceWhite)
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = AppSpacing.lg),
                // Extra room at the end so the floating "Add Party" button never sits on the last row
                // (button height + its 16dp margin + breathing space; taller in Simple Mode).
                contentPadding = PaddingValues(top = AppSpacing.sm, bottom = if (simpleMode) 136.dp else 120.dp),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
            ) {
                // 1. THREE GIANT BUTTONS AT THE TOP
                item(key = "giant_top_buttons") {
                    val btnHeight = if (simpleMode) 76.dp else 64.dp
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        // "Maine diye" (Udhar Red)
                        Button(
                            onClick = {
                                quickActionPartyPickerMode = "GAVE"
                                quickActionType = "GAVE"
                                quickActionAmount = ""
                                quickActionNote = ""
                                quickActionPaymentMode = "CASH"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = UdharRed),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(btnHeight)
                                .testTag("giant_btn_maine_diye"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = SurfaceWhite, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = LanguageStrings.get("maine_diye", lang),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (simpleMode) 13.sp else 12.sp,
                                    color = SurfaceWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // "Mujhe mile" (Jama Green)
                        Button(
                            onClick = {
                                quickActionPartyPickerMode = "GOT"
                                quickActionType = "GOT"
                                quickActionAmount = ""
                                quickActionNote = ""
                                quickActionPaymentMode = "CASH"
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(btnHeight)
                                .testTag("giant_btn_mujhe_mile"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = null, tint = SurfaceWhite, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = LanguageStrings.get("mujhe_mile", lang),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (simpleMode) 13.sp else 12.sp,
                                    color = SurfaceWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // "Naya bill" (Primary Blue)
                        Button(
                            onClick = onNavigateToBilling,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(btnHeight)
                                .testTag("giant_btn_naya_bill"),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = null, tint = SurfaceWhite, modifier = Modifier.size(22.dp))
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = LanguageStrings.get("naya_bill_giant", lang),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = if (simpleMode) 13.sp else 12.sp,
                                    color = SurfaceWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // 2. "AAJ" CARD
                item(key = "aaj_summary_card") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("aaj_summary_card"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Today, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = LanguageStrings.get("aaj_card_title", lang),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = TextPrimary
                                    )
                                }
                                if (dueOrOverdueCount > 0) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = UdharRed.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = "$dueOrOverdueCount " + LanguageStrings.get("parties_due_today", lang),
                                            color = UdharRed,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            // Received Today & Given Today
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = JamaGreen.copy(alpha = 0.08f)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(text = LanguageStrings.get("today_received", lang), fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = formatIndianCurrency(todayGot), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = JamaGreen)
                                    }
                                }

                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = UdharRed.copy(alpha = 0.08f)
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(text = LanguageStrings.get("today_given", lang), fontSize = 11.sp, color = TextSecondary, fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(text = formatIndianCurrency(todayGave), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = UdharRed)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            HorizontalDivider(color = CardBorder.copy(alpha = 0.6f))
                            Spacer(modifier = Modifier.height(10.dp))

                            // Cash & Bank Balance
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column {
                                    Text(text = LanguageStrings.get("cash_balance_label", lang), fontSize = 11.sp, color = TextSecondary)
                                    Text(text = formatIndianCurrency(cashBalance), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(text = LanguageStrings.get("bank_balance_label", lang), fontSize = 11.sp, color = TextSecondary)
                                    Text(text = formatIndianCurrency(bankBalance), fontWeight = FontWeight.Bold, fontSize = 14.sp, color = PrimaryBlue)
                                }
                            }
                        }
                    }
                }

                // 3. BACKUP HABIT CARD (Turns red after 7 days)
                item(key = "backup_habit_card") {
                    val backupBg = if (isBackupOld) UdharRed.copy(alpha = 0.08f) else SurfaceWhite
                    val backupBorder = if (isBackupOld) UdharRed.copy(alpha = 0.4f) else CardBorder
                    val backupDaysStr = when {
                        lastBackupTimestamp == 0L -> LanguageStrings.get("backup_never", lang)
                        daysSinceBackup == 0L -> LanguageStrings.get("backup_today", lang)
                        else -> LanguageStrings.format("backup_days_ago", lang, daysSinceBackup.toInt())
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("backup_habit_card"),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = backupBg),
                        border = BorderStroke(1.dp, backupBorder)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(
                                    imageVector = if (isBackupOld) Icons.Default.Warning else Icons.Default.CloudSync,
                                    contentDescription = null,
                                    tint = if (isBackupOld) UdharRed else PrimaryBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "${LanguageStrings.get("last_backup_prefix", lang)} $backupDaysStr",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = if (isBackupOld) UdharRed else TextPrimary
                                    )
                                    if (isBackupOld) {
                                        Text(
                                            text = LanguageStrings.get(
                                                if (lastBackupTimestamp == 0L) "backup_warning_never" else "backup_warning_old",
                                                lang
                                            ),
                                            fontSize = 10.sp,
                                            color = UdharRed
                                        )
                                    }
                                }
                            }

                            Button(
                                onClick = { sendBackupZip() },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (isBackupOld) UdharRed else PrimaryBlue),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.height(36.dp)
                            ) {
                                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(text = LanguageStrings.get("backup_habits_label", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                // 4. "VASULI LIST" (Parties to collect from: Overdue first)
                if (vasuliList.isNotEmpty()) {
                    item(key = "vasuli_list_section") {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PendingActions, contentDescription = null, tint = UdharRed, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = LanguageStrings.get("vasuli_list_title", lang),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = TextPrimary
                                    )
                                }
                                Text(
                                    text = "${vasuliList.size} " + LanguageStrings.get("parties", lang),
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))

                            vasuliList.take(5).forEach { (party, amount, isOverdue) ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                    border = BorderStroke(1.dp, if (isOverdue) UdharRed.copy(alpha = 0.3f) else CardBorder)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = party.name,
                                                    fontWeight = FontWeight.Bold,
                                                    fontSize = 13.sp,
                                                    color = TextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                if (isOverdue) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = UdharRed.copy(alpha = 0.12f)
                                                    ) {
                                                        Text(
                                                            text = "Overdue",
                                                            color = UdharRed,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                        )
                                                    }
                                                }
                                            }
                                            Text(
                                                text = formatIndianCurrency(amount),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = JamaGreen
                                            )
                                        }

                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            // WhatsApp Reminder Button
                                            IconButton(
                                                onClick = { launchWhatsApp(party.phone, party.name, amount) },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(JamaGreen.copy(alpha = 0.12f))
                                            ) {
                                                Icon(Icons.Default.Chat, contentDescription = LanguageStrings.get("whatsapp_remind", lang), tint = JamaGreen, modifier = Modifier.size(18.dp))
                                            }

                                            // Phone Call Button
                                            IconButton(
                                                onClick = { launchCall(party.phone) },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(PrimaryBlue.copy(alpha = 0.12f))
                                            ) {
                                                Icon(Icons.Default.Call, contentDescription = LanguageStrings.get("call_party", lang), tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. "AAJ KI DELIVERY" (Daily checklist)
                item(key = "aaj_ki_delivery_section") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocalShipping, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = LanguageStrings.get("aaj_ki_delivery", lang),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = TextPrimary
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { showDeliveryHistory = !showDeliveryHistory }) {
                                    Text(
                                        text = if (showDeliveryHistory) "Today" else LanguageStrings.get("delivery_history", lang),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Button(
                                    onClick = { showAddDeliveryDialog = true },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(text = LanguageStrings.get("delivery_task_add", lang), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        if (todayDeliveries.isEmpty()) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = SurfaceWhite,
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Box(modifier = Modifier.padding(14.dp), contentAlignment = Alignment.Center) {
                                    Text(text = LanguageStrings.get("no_deliveries_today", lang), fontSize = 12.sp, color = TextSecondary)
                                }
                            }
                        } else {
                            todayDeliveries.forEach { task ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 3.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = CardDefaults.cardColors(containerColor = if (task.isCompleted) SoftBackground else SurfaceWhite),
                                    border = CardDefaults.outlinedCardBorder()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = task.isCompleted,
                                            onCheckedChange = { isChecked ->
                                                repository.toggleDeliveryTask(task.id, isChecked)
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = task.partyName,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = if (task.isCompleted) TextSecondary else TextPrimary,
                                                textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                            )
                                            if (task.note.isNotBlank()) {
                                                Text(
                                                    text = task.note,
                                                    fontSize = 11.sp,
                                                    color = TextSecondary,
                                                    textDecoration = if (task.isCompleted) TextDecoration.LineThrough else null
                                                )
                                            }
                                        }

                                        IconButton(onClick = { repository.deleteDeliveryTask(task.id) }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Close, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. KHATA PARTY LIST (Search + Filters + Items)
                item(key = "khata_search_filter_bar") {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(text = LanguageStrings.get("search_hint", lang), fontSize = 13.sp) },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                            shape = RoundedCornerShape(24.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SurfaceWhite,
                                unfocusedContainerColor = SurfaceWhite
                            )
                        )

                        Spacer(modifier = Modifier.height(AppSpacing.xs))

                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            val filters = listOf(
                                "ALL" to "${LanguageStrings.get("parties", lang)} (${customers.size})",
                                "CUSTOMERS" to LanguageStrings.get("filter_customers", lang),
                                "SUPPLIERS" to LanguageStrings.get("filter_suppliers", lang),
                                "PENDING" to LanguageStrings.get("filter_pending", lang)
                            )
                            items(filters) { (key, label) ->
                                val isSelected = selectedFilter == key
                                Surface(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(16.dp))
                                        .clickable { selectedFilter = key },
                                    color = if (isSelected) PrimaryBlue else ChipInactiveBg,
                                    border = if (isSelected) null else CardDefaults.outlinedCardBorder()
                                ) {
                                    Text(
                                        text = label,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) SurfaceWhite else TextPrimary
                                    )
                                }
                            }
                        }

                        // Add Party sits in the list (not floating) so it never covers the filters or a party row
                        if (customers.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(AppSpacing.sm))
                            OutlinedButton(
                                onClick = { showAddPartyDialog = true },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .testTag("fab_add_party")
                            ) {
                                Icon(Icons.Default.PersonAdd, contentDescription = null, tint = PrimaryBlue)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(text = LanguageStrings.get("add_party", lang), fontWeight = FontWeight.Bold, color = PrimaryBlue)
                            }
                        }
                    }
                }

                if (filteredCustomers.isEmpty()) {
                    item(key = "khata_empty_state") {
                        EmptyStateView(
                            icon = Icons.Default.PeopleOutline,
                            title = if (customers.isEmpty()) LanguageStrings.get("empty_khata_title", lang) else LanguageStrings.get("no_matching_parties", lang),
                            description = if (customers.isEmpty()) LanguageStrings.get("empty_khata_desc", lang) else LanguageStrings.get("try_clearing_search", lang),
                            actionButtonText = if (customers.isEmpty()) "+ ${LanguageStrings.get("add_party", lang)}" else null,
                            onActionClick = { showAddPartyDialog = true }
                        )
                    }
                } else {
                    items(filteredCustomers, key = { it.id }) { cust ->
                        val balance = repository.getCustomerBalance(cust.id)
                        val isSupplier = cust.type == PartyType.SUPPLIER.raw

                        AppCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onCustomerClick(cust) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.md),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(if (isSupplier) ChipInactiveBg else ChipActiveBg),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = cust.name.firstOrNull()?.uppercase() ?: "P",
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSupplier) TextPrimary else PrimaryBlue,
                                            fontSize = 17.sp
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(AppSpacing.md))

                                    Column(modifier = Modifier.weight(1f, fill = false)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = cust.name,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false)
                                            )
                                            // Only suppliers get a tag; most parties are customers, so a tag there is just noise
                                            if (isSupplier) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(4.dp),
                                                color = if (isSupplier) ChipInactiveBg else ChipActiveBg
                                            ) {
                                                Text(
                                                    text = if (isSupplier) LanguageStrings.get("supplier", lang) else LanguageStrings.get("customer", lang),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = if (isSupplier) TextSecondary else PrimaryBlue,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                )
                                            }
                                            }
                                        }

                                        val displayPhone = PhoneUtil.toDisplay(cust.phone)
                                        Text(
                                            text = if (displayPhone.isNotBlank()) displayPhone else LanguageStrings.get("add_phone_number_hint", lang),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (displayPhone.isNotBlank()) TextSecondary else TextTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(AppSpacing.sm))

                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        text = formatIndianCurrency(Math.abs(balance)),
                                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            balance > 0.001 -> JamaGreen
                                            balance < -0.001 -> UdharRed
                                            else -> TextSecondary
                                        },
                                        softWrap = false,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = when {
                                            balance > 0.001 -> LanguageStrings.get("you_will_get", lang)
                                            balance < -0.001 -> LanguageStrings.get("you_will_give", lang)
                                            else -> LanguageStrings.get("settled", lang)
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = when {
                                            balance > 0.001 -> JamaGreen
                                            balance < -0.001 -> UdharRed
                                            else -> TextSecondary
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // QUICK ACTION: Party Picker Dialog (Step 1)
    if (quickActionPartyPickerMode != null) {
        val mode = quickActionPartyPickerMode!!
        var partySearchQuery by remember { mutableStateOf("") }
        val eligibleParties = remember(customers, partySearchQuery) {
            customers.filter { it.name.contains(partySearchQuery, ignoreCase = true) || it.phone.contains(partySearchQuery) }
        }

        AlertDialog(
            onDismissRequest = { quickActionPartyPickerMode = null },
            title = {
                Text(
                    text = if (mode == "GAVE") LanguageStrings.get("maine_diye", lang) else LanguageStrings.get("mujhe_mile", lang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)) {
                    OutlinedTextField(
                        value = partySearchQuery,
                        onValueChange = { partySearchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(text = LanguageStrings.get("search_party_hint", lang), fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(AppSpacing.sm))
                    if (eligibleParties.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(AppSpacing.md), contentAlignment = Alignment.Center) {
                            Text(text = LanguageStrings.get("no_matching_parties", lang), color = TextSecondary)
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            items(eligibleParties) { party ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            quickActionSelectedCustomer = party
                                            quickActionAmount = ""
                                            quickActionNote = ""
                                            quickActionPaymentMode = "CASH"
                                            quickActionDate = System.currentTimeMillis()
                                            quickActionPartyPickerMode = null
                                        }
                                        .padding(vertical = AppSpacing.sm, horizontal = AppSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(AppSpacing.sm))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = party.name, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                                        if (party.phone.isNotBlank()) {
                                            Text(text = PhoneUtil.toDisplay(party.phone), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                        }
                                    }
                                    Text(
                                        text = formatIndianCurrency(repository.getCustomerBalance(party.id)),
                                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryBlue,
                                        softWrap = false
                                    )
                                }
                                HorizontalDivider(color = CardBorder.copy(alpha = 0.5f))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { quickActionPartyPickerMode = null }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // QUICK ACTION: Fast Entry Screen (Number Pad + Voice Mic + Big Mode Buttons)
    if (quickActionSelectedCustomer != null) {
        val cust = quickActionSelectedCustomer!!
        val actionType = QuickEntry.resolveType(quickActionType)
        val currencySymbol = "₹"

        AlertDialog(
            onDismissRequest = {
                quickActionSelectedCustomer = null
                quickActionType = null
                quickActionCashGiven = false
            },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(text = cust.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(
                            text = if (actionType == "GAVE") LanguageStrings.get("maine_diye", lang) else LanguageStrings.get("mujhe_mile", lang),
                            fontWeight = FontWeight.Bold,
                            color = if (actionType == "GAVE") UdharRed else JamaGreen,
                            fontSize = 14.sp
                        )
                    }
                    // Mic button for Voice Entry
                    IconButton(
                        onClick = { startVoiceListening() },
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue.copy(alpha = 0.12f))
                            .testTag("btn_voice_entry_mic")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = LanguageStrings.get("voice_entry_mic", lang), tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Large Amount Display
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        color = SoftBackground,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = "$currencySymbol ", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                            Text(
                                text = if (quickActionAmount.isBlank()) "0" else quickActionAmount,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (actionType == "GAVE") UdharRed else JamaGreen
                            )
                        }
                    }

                    // Payment Mode Buttons: CASH, UPI, BANK (Big buttons)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("CASH" to "Cash", "UPI" to "UPI", "BANK" to "Bank").forEach { (modeKey, modeTitle) ->
                            val isSelected = quickActionPaymentMode == modeKey
                            Button(
                                onClick = { quickActionPaymentMode = modeKey },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isSelected) PrimaryBlue else ChipInactiveBg,
                                    contentColor = if (isSelected) SurfaceWhite else TextPrimary
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) {
                                Text(text = modeTitle, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, softWrap = false)
                            }
                        }
                    }

                    // Customer "I Gave": optionally record real cash handed over
                    if (actionType == "GAVE" && cust.type != "SUPPLIER") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(LanguageStrings.get("cash_given_switch", lang), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text(LanguageStrings.get("cash_given_hint", lang), fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = quickActionCashGiven,
                                onCheckedChange = { quickActionCashGiven = it },
                                modifier = Modifier.testTag("switch_cash_given")
                            )
                        }
                    }

                    // Large Number Pad (1-9, ., 0, ⌫)
                    val keypadRows = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf(".", "0", "⌫")
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        keypadRows.forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                row.forEach { key ->
                                    Button(
                                        onClick = {
                                            when (key) {
                                                "⌫" -> if (quickActionAmount.isNotEmpty()) quickActionAmount = quickActionAmount.dropLast(1)
                                                "." -> if (!quickActionAmount.contains(".")) quickActionAmount += "."
                                                else -> {
                                                    if (quickActionAmount.length < 9) {
                                                        quickActionAmount += key
                                                    }
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (key == "⌫") ChipInactiveBg else SurfaceWhite,
                                            contentColor = TextPrimary
                                        ),
                                        border = BorderStroke(1.dp, CardBorder),
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Text(text = key, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsedAmount = quickActionAmount.toDoubleOrNull() ?: 0.0
                        if (parsedAmount > 0.0) {
                            val tx = repository.addTransaction(
                                customerId = cust.id,
                                type = actionType,
                                amount = parsedAmount,
                                note = quickActionNote.trim(),
                                paymentMode = quickActionPaymentMode,
                                date = quickActionDate,
                                cashGivenOut = quickActionCashGiven
                            )
                            quickActionCashGiven = false
                            lastSavedTx = tx
                            showUndoSnackbar = true
                            quickActionSelectedCustomer = null
                            quickActionType = null
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("quick_entry_save_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (actionType == "GAVE") UdharRed else JamaGreen)
                ) {
                    Text(text = LanguageStrings.get("btn_save", lang), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    quickActionSelectedCustomer = null
                    quickActionType = null
                }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // VOICE ENTRY: Big Confirmation Card (Party, Amount, Gave/Got with Yes/No buttons)
    if (voiceConfirmationEntry != null) {
        val entry = voiceConfirmationEntry!!
        val isGave = entry.type == "GAVE"
        val currencySymbol = "₹"

        AlertDialog(
            onDismissRequest = { voiceConfirmationEntry = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageStrings.get("confirm_voice_title", lang),
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                }
            },
            text = {
                Card(
                    modifier = Modifier.fillMaxWidth().testTag("voice_confirm_card"),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = if (isGave) UdharRed.copy(alpha = 0.08f) else JamaGreen.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, if (isGave) UdharRed.copy(alpha = 0.4f) else JamaGreen.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = entry.partyName,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "$currencySymbol ${formatIndianCurrency(entry.amount)}",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isGave) UdharRed else JamaGreen
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isGave) UdharRed else JamaGreen
                        ) {
                            Text(
                                text = if (isGave) LanguageStrings.get("maine_diye", lang) else LanguageStrings.get("mujhe_mile", lang),
                                color = SurfaceWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }

                        if (entry.rawText.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "\"${entry.rawText}\"",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }

                        // Several customers share the spoken name: the owner must pick, we never guess.
                        if (entry.candidates.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = LanguageStrings.get("voice_pick_party", lang),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = UdharRed
                            )
                            entry.candidates.forEach { c ->
                                OutlinedButton(
                                    onClick = {
                                        voiceConfirmationEntry = entry.copy(
                                            matchedCustomer = c,
                                            partyName = c.name,
                                            candidates = emptyList()
                                        )
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = if (c.phone.length >= 4) "${c.name} (...${c.phone.takeLast(4)})" else c.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        if (entry.typeUncertain) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = LanguageStrings.get("voice_check_direction", lang),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = UdharRed
                            )
                        }

                        // One tap to flip the direction when speech was misheard.
                        TextButton(
                            onClick = {
                                voiceConfirmationEntry = entry.copy(
                                    type = if (isGave) "GOT" else "GAVE",
                                    typeUncertain = false
                                )
                            }
                        ) {
                            Text(
                                text = LanguageStrings.get("voice_flip_direction", lang),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                // Giant Yes Button
                Button(
                    enabled = entry.partyName.isNotBlank() && entry.amount > 0.0 && entry.candidates.isEmpty(),
                    onClick = {
                        val party = entry.matchedCustomer ?: repository.addCustomer(entry.partyName, "")
                        val tx = repository.addTransaction(
                            customerId = party.id,
                            type = entry.type,
                            amount = entry.amount,
                            note = entry.rawText,
                            paymentMode = "CASH"
                        )
                        lastSavedTx = tx
                        showUndoSnackbar = true
                        voiceConfirmationEntry = null
                        quickActionSelectedCustomer = null
                        quickActionType = null
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("confirm_voice_yes"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (isGave) UdharRed else JamaGreen)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageStrings.get("btn_yes_save", lang),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                // Giant No Button
                OutlinedButton(
                    onClick = { voiceConfirmationEntry = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("confirm_voice_no"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = LanguageStrings.get("btn_no_cancel", lang),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                }
            }
        )
    }

    // Voice Input Text Dialog (Fallback or direct test typing)
    if (showVoiceInputDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceInputDialog = false },
            title = { Text(text = LanguageStrings.get("voice_entry_mic", lang), fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    OutlinedTextField(
                        value = voiceInputText,
                        onValueChange = { voiceInputText = it },
                        modifier = Modifier.fillMaxWidth().testTag("voice_input_textfield"),
                        placeholder = { Text(text = "Jaise: Ramesh ne paanch hazaar diye", fontSize = 13.sp) },
                        maxLines = 2
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (voiceInputText.isNotBlank()) {
                            val parsed = VoiceEntryParser.parse(voiceInputText, customers)
                            voiceConfirmationEntry = parsed
                            showVoiceInputDialog = false
                            voiceInputText = ""
                        }
                    },
                    modifier = Modifier.testTag("voice_input_submit_btn")
                ) {
                    Text(text = LanguageStrings.get("btn_apply", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showVoiceInputDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Add Delivery Task Dialog
    if (showAddDeliveryDialog) {
        AlertDialog(
            onDismissRequest = { showAddDeliveryDialog = false },
            title = { Text(text = LanguageStrings.get("delivery_task_add", lang), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newDeliveryPartyName,
                        onValueChange = { newDeliveryPartyName = it },
                        label = { Text(text = LanguageStrings.get("party_name_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newDeliveryNote,
                        onValueChange = { newDeliveryNote = it },
                        label = { Text(text = LanguageStrings.get("party_delivery_hint", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newDeliveryPartyName.isNotBlank()) {
                            repository.addDeliveryTask(
                                partyName = newDeliveryPartyName.trim(),
                                note = newDeliveryNote.trim()
                            )
                            newDeliveryPartyName = ""
                            newDeliveryNote = ""
                            showAddDeliveryDialog = false
                        }
                    }
                ) {
                    Text(text = LanguageStrings.get("btn_save", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDeliveryDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    // Add Party Dialog
    if (showAddPartyDialog) {
        var partyType by remember { mutableStateOf(PartyType.CUSTOMER) }
        var partyName by remember { mutableStateOf("") }
        var partyPhone by remember { mutableStateOf("") }
        var partyAddress by remember { mutableStateOf("") }
        var openingBalance by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showAddPartyDialog = false },
            title = { Text(text = LanguageStrings.get("add_party", lang), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = LanguageStrings.get("party_type_label", lang),
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = partyType == PartyType.CUSTOMER,
                            onClick = { partyType = PartyType.CUSTOMER },
                            label = { Text(text = LanguageStrings.get("customer", lang)) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = partyType == PartyType.SUPPLIER,
                            onClick = { partyType = PartyType.SUPPLIER },
                            label = { Text(text = LanguageStrings.get("supplier", lang)) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    OutlinedButton(
                        onClick = { requestContactsAction() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Contacts, contentDescription = null, modifier = Modifier.size(18.dp), tint = PrimaryBlue)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = LanguageStrings.get("import_contacts", lang), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PrimaryBlue)
                    }

                    HorizontalDivider(color = CardBorder)

                    OutlinedTextField(
                        value = partyName,
                        onValueChange = { partyName = it },
                        label = { Text(text = LanguageStrings.get("party_name_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = partyPhone,
                        onValueChange = { partyPhone = it },
                        label = { Text(text = LanguageStrings.get("phone_number_label", lang)) },
                        isError = partyPhone.isNotBlank() && !PhoneUtil.isValid(partyPhone),
                        supportingText = {
                            if (partyPhone.isNotBlank() && !PhoneUtil.isValid(partyPhone)) {
                                Text(text = LanguageStrings.get("invalid_phone_fix_hint", lang), color = UdharRed)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = partyAddress,
                        onValueChange = { partyAddress = it },
                        label = { Text(text = LanguageStrings.get("address_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = openingBalance,
                        onValueChange = { openingBalance = it },
                        label = { Text(text = LanguageStrings.get("opening_balance_label", lang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (partyName.isNotBlank()) {
                            val newCust = repository.addCustomer(
                                name = partyName.trim(),
                                phone = partyPhone.trim(),
                                address = partyAddress.trim(),
                                type = partyType.raw
                            )
                            val openBal = openingBalance.toDoubleOrNull() ?: 0.0
                            if (openBal > 0.0) {
                                repository.addTransaction(
                                    customerId = newCust.id,
                                    type = if (partyType == PartyType.CUSTOMER) "GAVE" else "GOT",
                                    amount = openBal,
                                    note = LanguageStrings.get("opening_balance_note", lang)
                                )
                            }
                            showAddPartyDialog = false
                        }
                    }
                ) {
                    Text(text = LanguageStrings.get("btn_save", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPartyDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    if (showContactImportModal) {
        val existingPhoneNumbers = remember(customers) {
            customers.mapNotNull { it.phone?.filter { ch -> ch.isDigit() } }.toSet()
        }
        ContactImportModal(
            lang = lang,
            existingPhoneNumbers = existingPhoneNumbers,
            onDismiss = { showContactImportModal = false },
            onContactsImported = { importedList ->
                showContactImportModal = false
                coroutineScope.launch {
                    try {
                        // One background write for the whole selection. Numbers already in the khata are skipped.
                        val added = repository.addCustomersBulk(importedList.map { it.name to it.rawNumber })
                        Toast.makeText(
                            context,
                            LanguageStrings.format("contacts_import_done", lang, added, importedList.size - added),
                            Toast.LENGTH_LONG
                        ).show()
                    } catch (t: Throwable) {
                        android.util.Log.e("KhataScreen", "Contact import failed", t)
                    }
                }
            }
        )
    }

    if (showContactRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showContactRationaleDialog = false },
            title = { Text(text = LanguageStrings.get("contacts_permission_title", lang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_reason", lang)) },
            confirmButton = {
                Button(onClick = {
                    showContactRationaleDialog = false
                    try {
                        permissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                    } catch (t: Throwable) {
                        // Never crash over a permission prompt: offer the Settings route instead.
                        android.util.Log.e("KhataScreen", "Contacts permission request failed", t)
                        showPermanentlyDeniedDialog = true
                    }
                }) {
                    Text(text = LanguageStrings.get("btn_continue", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showContactRationaleDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }

    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text(text = LanguageStrings.get("permission_denied_title", lang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_denied", lang)) },
            confirmButton = {
                Button(onClick = { showPermissionDeniedDialog = false }) {
                    Text(text = LanguageStrings.get("btn_ok", lang))
                }
            }
        )
    }

    if (showPermanentlyDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermanentlyDeniedDialog = false },
            title = { Text(text = LanguageStrings.get("permission_required_title", lang), fontWeight = FontWeight.Bold) },
            text = { Text(text = LanguageStrings.get("contacts_permission_permanently_denied", lang)) },
            confirmButton = {
                Button(onClick = {
                    showPermanentlyDeniedDialog = false
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) {
                    Text(text = LanguageStrings.get("btn_open_settings", lang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermanentlyDeniedDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", lang))
                }
            }
        )
    }
}
