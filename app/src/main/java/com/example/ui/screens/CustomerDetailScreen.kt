package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.data.AppRepository
import com.example.model.Customer
import com.example.model.PartyType
import com.example.model.Transaction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.RelativeDate
import com.example.ui.util.AttachmentListSaver
import com.example.ui.util.PhoneUtil
import com.example.ui.util.StatementDateRange
import com.example.ui.util.StatementFilter
import com.example.ui.util.StatementGenerator
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*
import com.example.model.Attachment
import com.example.ui.components.AttachmentPickerStrip
import com.example.ui.components.FullScreenAttachmentViewer
import com.example.ui.components.VoiceNoteButton
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CustomerDetailScreen(
    customer: Customer,
    repository: AppRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentLang by repository.language.collectAsState()
    val storeProfile by repository.storeProfile.collectAsState()
    val transactions by repository.transactions.collectAsState()
    val customers by repository.customers.collectAsState()

    val currentCustomer = customers.find { it.id == customer.id } ?: customer
    val isSupplier = currentCustomer.type == PartyType.SUPPLIER.raw
    val hasPhone = PhoneUtil.isValid(currentCustomer.phone)
    val phoneIsBad = currentCustomer.phone.isNotBlank() && !hasPhone
    val customerTx = transactions.filter { it.customerId == currentCustomer.id }
        .sortedByDescending { it.date }
    val balance = repository.getCustomerBalance(currentCustomer.id)
    // Balance right after each entry (oldest first), shown as "Bal." on every row.
    val runningBalance = remember(customerTx) {
        var acc = 0.0
        customerTx.sortedWith(compareBy({ it.date }, { it.id })).associate { tx ->
            acc += if (tx.type == "GAVE") tx.amount else -tx.amount
            tx.id to acc
        }
    }

    // Multi-select state
    var isMultiSelectMode by remember { mutableStateOf(false) }
    val selectedBillIds = remember { mutableStateListOf<String>() }

    // Dialogs state
    var showAddTxDialog by rememberSaveable { mutableStateOf(false) }
    var txTypeToAdd by rememberSaveable { mutableStateOf("GAVE") } // GAVE or GOT

    var billToMarkReceived by remember { mutableStateOf<Transaction?>(null) }
    var showMarkAllReceivedDialog by remember { mutableStateOf(false) }
    var showStatementDialog by remember { mutableStateOf(false) }
    var txToEdit by remember { mutableStateOf<Transaction?>(null) }
    var showEditPhoneDialog by remember { mutableStateOf(false) }
    var phoneInputText by remember { mutableStateOf(currentCustomer.phone) }

    val allAttachments by repository.attachments.collectAsState()
    var selectedDetailTab by remember { mutableIntStateOf(0) } // 0 = Entries, 1 = Photos
    var viewingAttachments by remember { mutableStateOf<List<Attachment>?>(null) }
    var viewingInitialIndex by remember { mutableIntStateOf(0) }

    // Back handling for multi-select
    BackHandler(enabled = isMultiSelectMode) {

        isMultiSelectMode = false
        selectedBillIds.clear()
    }

    Scaffold(
        topBar = {
            if (isMultiSelectMode) {
                TopAppBar(
                    title = {
                        Text(
                            text = "${selectedBillIds.size} ${LanguageStrings.get("selected_bills", currentLang)}",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            isMultiSelectMode = false
                            selectedBillIds.clear()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Close Selection")
                        }
                    },
                    actions = {
                        if (selectedBillIds.isNotEmpty()) {
                            IconButton(onClick = { showMarkAllReceivedDialog = true }) {
                                Icon(
                                    Icons.Default.DoneAll,
                                    contentDescription = LanguageStrings.get("mark_all_received", currentLang),
                                    tint = JamaGreen
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = ChipActiveBg)
                )
            } else {
                Column {
                TopAppBar(
                    title = {
                        Column(
                            modifier = Modifier.clickable {
                                if (!hasPhone) {
                                    phoneInputText = ""
                                    showEditPhoneDialog = true
                                }
                            }
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = currentCustomer.name,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary,
                                    fontSize = 17.sp,
                                    maxLines = 2,
                                    lineHeight = 20.sp,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isSupplier) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSupplier) ChipInactiveBg else ChipActiveBg
                                ) {
                                    Text(
                                        text = if (isSupplier) LanguageStrings.get("supplier", currentLang) else LanguageStrings.get("customer", currentLang),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSupplier) TextSecondary else PrimaryBlue,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                    )
                                }
                                }
                            }
                            val displayPhone = PhoneUtil.toDisplay(currentCustomer.phone)
                            val phoneOk = displayPhone.isNotBlank() && !phoneIsBad
                            Text(
                                text = when {
                                    phoneIsBad -> LanguageStrings.get("invalid_phone_hint", currentLang) + ": " + displayPhone
                                    displayPhone.isNotBlank() -> displayPhone
                                    else -> LanguageStrings.get("add_phone_number_hint", currentLang)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (phoneOk) TextSecondary else UdharRed,
                                fontWeight = if (phoneOk) FontWeight.Normal else FontWeight.SemiBold
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceWhite)
                )
                // Labelled actions (icons alone were hard to tell apart).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWhite)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    LedgerHeaderAction(
                        icon = Icons.Default.Call,
                        label = LanguageStrings.get("btn_call", currentLang),
                        tint = PrimaryBlue,
                        enabled = hasPhone
                    ) {
                        val dialNumber = PhoneUtil.toWhatsAppNumber(currentCustomer.phone)
                        context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialNumber")))
                    }
                    LedgerHeaderAction(
                        icon = Icons.Default.Chat,
                        label = LanguageStrings.get("btn_sms", currentLang),
                        tint = PrimaryBlue,
                        enabled = hasPhone
                    ) {
                        sendSmsReminder(
                            context = context,
                            party = currentCustomer,
                            shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" },
                            balance = balance,
                            upiId = storeProfile.upiId,
                            lang = currentLang
                        )
                    }
                    LedgerHeaderAction(
                        icon = Icons.Default.Share,
                        label = LanguageStrings.get("btn_whatsapp", currentLang),
                        tint = JamaGreen,
                        enabled = hasPhone
                    ) {
                        sendWhatsAppReminder(
                            context = context,
                            party = currentCustomer,
                            shopName = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" },
                            balance = balance,
                            upiId = storeProfile.upiId,
                            lang = currentLang
                        )
                    }
                    LedgerHeaderAction(
                        icon = Icons.Default.Description,
                        label = LanguageStrings.get("statement", currentLang),
                        tint = TextPrimary,
                        enabled = true
                    ) { showStatementDialog = true }
                }
                HorizontalDivider(color = CardBorder)
                }
            }
        },
        bottomBar = {
            if (isMultiSelectMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceWhite,
                    shadowElevation = 8.dp,
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { showMarkAllReceivedDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).height(48.dp),
                            enabled = selectedBillIds.isNotEmpty()
                        ) {
                            Icon(Icons.Default.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${LanguageStrings.get("mark_all_received", currentLang)} (${selectedBillIds.size})",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = SurfaceWhite,
                    shadowElevation = 8.dp,
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Left Button
                        val leftLabel = if (isSupplier) LanguageStrings.get("you_gave_supplier", currentLang) else LanguageStrings.get("you_gave", currentLang)
                        val leftColor = if (isSupplier) PrimaryBlue else UdharRed
                        Button(
                            onClick = {
                                txTypeToAdd = "GAVE"
                                showAddTxDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = leftColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(leftLabel, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
                        }

                        // Right Button
                        val rightLabel = if (isSupplier) LanguageStrings.get("you_got_supplier", currentLang) else LanguageStrings.get("you_got", currentLang)
                        val rightColor = if (isSupplier) UdharRed else JamaGreen
                        Button(
                            onClick = {
                                txTypeToAdd = "GOT"
                                showAddTxDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = rightColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(rightLabel, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SoftBackground)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Missing phone prompt banner (scrolls with list)
            if (!hasPhone) {
                item(key = "missing_phone_banner") {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable { phoneInputText = ""; showEditPhoneDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = ChipInactiveBg),
                        border = CardDefaults.outlinedCardBorder()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PhoneIphone, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = LanguageStrings.get("add_phone_number_hint", currentLang),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            }
                            Text(
                                text = "+",
                                fontWeight = FontWeight.Bold,
                                color = PrimaryBlue,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            // Current Net Balance Card (scrolls with list)
            item(key = "net_balance_card") {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = LanguageStrings.get("closing_balance", currentLang).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (Math.abs(balance) > 0.001) {
                                Icon(
                                    imageVector = if (balance > 0) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    tint = if (balance > 0) UdharRed else JamaGreen,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = formatIndianCurrency(Math.abs(balance)),
                                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    balance > 0.001 -> UdharRed
                                    balance < -0.001 -> JamaGreen
                                    else -> TextPrimary
                                },
                                softWrap = false
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = when {
                                balance > 0.001 -> LanguageStrings.get("filter_receive", currentLang)
                                balance < -0.001 && PartyType.fromString(currentCustomer.type) == PartyType.CUSTOMER ->
                                    LanguageStrings.get("advance_from_party", currentLang)
                                        .replace("{name}", currentCustomer.name)
                                        .replace("{amount}", formatIndianCurrency(Math.abs(balance)))
                                balance < -0.001 -> LanguageStrings.get("filter_give", currentLang)
                                else -> LanguageStrings.get("status_received", currentLang)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                balance > 0.001 -> UdharRed
                                balance < -0.001 -> JamaGreen
                                else -> TextSecondary
                            },
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(ChipInactiveBg)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(14.dp), tint = PrimaryBlue)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = LanguageStrings.get("hold_to_select", currentLang),
                                fontSize = 11.sp,
                                color = TextPrimary
                            )
                        }
                    }
                }
            }

            // Tab Row: Entries or Photos
            item(key = "party_tabs") {
                val partyAllAtts = repository.getAttachmentsForParty(currentCustomer.id)
                TabRow(
                    selectedTabIndex = selectedDetailTab,
                    containerColor = SurfaceWhite,
                    contentColor = PrimaryBlue
                ) {
                    Tab(
                        selected = selectedDetailTab == 0,
                        onClick = { selectedDetailTab = 0 },
                        text = {
                            Text(
                                text = "${LanguageStrings.get("tab_entries", currentLang)} (${customerTx.size})",
                                fontWeight = if (selectedDetailTab == 0) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                    Tab(
                        selected = selectedDetailTab == 1,
                        onClick = { selectedDetailTab = 1 },
                        text = {
                            Text(
                                text = "${LanguageStrings.get("tab_photos", currentLang)} (${partyAllAtts.size})",
                                fontWeight = if (selectedDetailTab == 1) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            if (selectedDetailTab == 0) {
                if (customerTx.isEmpty()) {
                item(key = "empty_tx_state") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Receipt, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(54.dp))
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(text = LanguageStrings.get("no_entries_party", currentLang), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                            Text(text = LanguageStrings.get("add_credit_payments_hint", currentLang), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        }
                    }
                }
            } else {
                items(customerTx, key = { it.id }) { tx ->
                        val isCreditBill = (currentCustomer.type == PartyType.CUSTOMER.raw && tx.type == "GAVE" && tx.linkedToTxId == null) ||
                                (currentCustomer.type == PartyType.SUPPLIER.raw && tx.type == "GOT" && tx.linkedToTxId == null)
                        val isLinkedPayment = tx.linkedToTxId != null
                        val dateStr = RelativeDate.format(tx.date, currentLang)
                        val balAfter = runningBalance[tx.id] ?: 0.0

                        if (isCreditBill) {
                            val receivedAmt = repository.getBillReceivedAmount(tx.id)
                            val outstandingAmt = repository.getBillOutstandingAmount(tx)
                            val status = repository.getBillStatus(tx)
                            val isSelected = selectedBillIds.contains(tx.id)

                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (isMultiSelectMode) {
                                                if (isSelected) selectedBillIds.remove(tx.id) else selectedBillIds.add(tx.id)
                                            } else {
                                                txToEdit = tx
                                            }
                                        },
                                        onLongClick = {
                                            if (!isMultiSelectMode) {
                                                isMultiSelectMode = true
                                                selectedBillIds.clear()
                                                selectedBillIds.add(tx.id)
                                            }
                                        }
                                    ),
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isSelected) ChipActiveBg else SurfaceWhite
                                ),
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                            if (isMultiSelectMode) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { checked ->
                                                        if (checked == true) selectedBillIds.add(tx.id) else selectedBillIds.remove(tx.id)
                                                    }
                                                )
                                                Spacer(modifier = Modifier.width(4.dp))
                                            }

                                            Column {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Text(
                                                        text = if (isSupplier) LanguageStrings.get("you_got_supplier", currentLang) else LanguageStrings.get("you_gave", currentLang),
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextPrimary
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    // Status Chip
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = when (status) {
                                                            AppRepository.BillStatus.RECEIVED -> JamaGreen.copy(alpha = 0.15f)
                                                            AppRepository.BillStatus.PARTLY_RECEIVED -> WarningAmber.copy(alpha = 0.15f)
                                                            AppRepository.BillStatus.PENDING -> UdharRed.copy(alpha = 0.15f)
                                                        }
                                                    ) {
                                                        val statusLabel = when (status) {
                                                            AppRepository.BillStatus.RECEIVED -> LanguageStrings.get("status_received", currentLang)
                                                            AppRepository.BillStatus.PARTLY_RECEIVED -> "${LanguageStrings.get("status_partly_received", currentLang)} (₹${"%.0f".format(receivedAmt)})"
                                                            AppRepository.BillStatus.PENDING -> LanguageStrings.get("status_pending", currentLang)
                                                        }
                                                        Text(
                                                            text = statusLabel,
                                                            color = when (status) {
                                                                AppRepository.BillStatus.RECEIVED -> JamaGreen
                                                                AppRepository.BillStatus.PARTLY_RECEIVED -> WarningAmber
                                                                AppRepository.BillStatus.PENDING -> UdharRed
                                                            },
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                    val txAtts = repository.getAttachmentsForOwner("ENTRY", tx.id)
                                                    if (txAtts.isNotEmpty()) {
                                                        Spacer(modifier = Modifier.width(6.dp))
                                                        Surface(
                                                            shape = RoundedCornerShape(4.dp),
                                                            color = ChipActiveBg,
                                                            modifier = Modifier.clickable {
                                                                viewingAttachments = txAtts
                                                                viewingInitialIndex = 0
                                                            }
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                            ) {
                                                                Icon(Icons.Default.PhotoLibrary, contentDescription = LanguageStrings.get("tab_photos", currentLang), tint = PrimaryBlue, modifier = Modifier.size(12.dp))
                                                                Spacer(modifier = Modifier.width(2.dp))
                                                                Text(text = "${txAtts.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                                                            }
                                                        }
                                                    }
                                                }
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = if (tx.note.isNotBlank()) "${tx.note} • $dateStr" else dateStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = TextSecondary
                                                )
                                            }
                                        }

                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                text = formatIndianCurrency(tx.amount),
                                                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSupplier) JamaGreen else UdharRed,
                                                softWrap = false
                                            )
                                            if (outstandingAmt > 0.001 && status != AppRepository.BillStatus.PENDING) {
                                                Text(
                                                    text = "${LanguageStrings.get("status_pending", currentLang)}: ${formatIndianCurrency(outstandingAmt)}",
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                                    color = UdharRed,
                                                    fontWeight = FontWeight.SemiBold,
                                                    softWrap = false
                                                )
                                            }
                                            RunningBalanceText(balAfter, currentLang)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(color = CardBorder.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(6.dp))

                                    // Action row: Delivered/Packed checkbox + Mark Received Button
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Plain Completed checkbox (e.g. goods delivered / packed) - NO MONEY EFFECT
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = tx.isCompleted,
                                                onCheckedChange = { repository.toggleBillCompleted(tx.id) },
                                                colors = CheckboxDefaults.colors(checkedColor = PrimaryBlue)
                                            )
                                            Text(
                                                text = LanguageStrings.get("completed_checkbox", currentLang),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = if (tx.isCompleted) PrimaryBlue else TextSecondary,
                                                fontWeight = if (tx.isCompleted) FontWeight.Bold else FontWeight.Normal
                                            )
                                        }

                                        // Mark Received Action
                                        if (outstandingAmt > 0.001) {
                                            FilledTonalButton(
                                                onClick = { billToMarkReceived = tx },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = LanguageStrings.get("mark_received", currentLang),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            // Payment entry or other transaction
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { txToEdit = tx },
                                shape = RoundedCornerShape(10.dp),
                                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                border = CardDefaults.outlinedCardBorder()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(if (tx.type == "GOT") JamaGreen.copy(alpha = 0.15f) else UdharRed.copy(alpha = 0.15f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (tx.type == "GOT") Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                                                contentDescription = null,
                                                tint = if (tx.type == "GOT") JamaGreen else UdharRed,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(10.dp))

                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = if (tx.type == "GOT") {
                                                        if (isSupplier) LanguageStrings.get("you_got_supplier", currentLang) else LanguageStrings.get("you_got", currentLang)
                                                    } else {
                                                        if (isSupplier) LanguageStrings.get("you_gave_supplier", currentLang) else LanguageStrings.get("you_gave", currentLang)
                                                    },
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextPrimary
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = ChipInactiveBg
                                                ) {
                                                    Text(
                                                        text = tx.paymentMode,
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = TextSecondary,
                                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                    )
                                                }
                                                val txAtts = repository.getAttachmentsForOwner("ENTRY", tx.id)
                                                if (txAtts.isNotEmpty()) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(4.dp),
                                                        color = ChipActiveBg,
                                                        modifier = Modifier.clickable {
                                                            viewingAttachments = txAtts
                                                            viewingInitialIndex = 0
                                                        }
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                                        ) {
                                                            Icon(Icons.Default.PhotoLibrary, contentDescription = LanguageStrings.get("tab_photos", currentLang), tint = PrimaryBlue, modifier = Modifier.size(12.dp))
                                                            Spacer(modifier = Modifier.width(2.dp))
                                                            Text(text = "${txAtts.size}", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PrimaryBlue)
                                                        }
                                                    }
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = if (tx.note.isNotBlank()) "${tx.note} • $dateStr" else dateStr,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary
                                            )
                                        }
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${if (tx.type == "GOT") "+" else "-"}${formatIndianCurrency(tx.amount)}",
                                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                            fontWeight = FontWeight.Bold,
                                            color = if (tx.type == "GOT") JamaGreen else UdharRed,
                                            softWrap = false
                                        )
                                        RunningBalanceText(balAfter, currentLang)

                                        if (isLinkedPayment) {
                                            TextButton(
                                                onClick = { repository.undoPayment(tx.id) },
                                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                            ) {
                                                Text(
                                                    text = LanguageStrings.get("undo_received", currentLang),
                                                    fontSize = 11.sp,
                                                    color = UdharRed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val partyAllAtts = repository.getAttachmentsForParty(currentCustomer.id)
                item(key = "party_add_photo") {
                    val partyDirectAtts = repository.getAttachmentsForOwner("PARTY", currentCustomer.id)
                    AppCard(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = LanguageStrings.get("btn_add_photo", currentLang),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            AttachmentPickerStrip(
                                attachments = partyDirectAtts,
                                onAttachmentsChanged = { updated ->
                                    val added = updated.filter { !partyDirectAtts.any { p -> p.id == it.id } }
                                    val removed = partyDirectAtts.filter { !updated.any { u -> u.id == it.id } }
                                    if (added.isNotEmpty()) {
                                        repository.addAttachments(added.map { it.copy(ownerType = "PARTY", ownerId = currentCustomer.id) })
                                    }
                                    removed.forEach { repository.deleteAttachment(it.id) }
                                },
                                ownerType = "PARTY",
                                ownerId = currentCustomer.id,
                                currentLang = currentLang
                            )
                        }
                    }
                }

                if (partyAllAtts.isEmpty()) {
                    item(key = "empty_photos_state") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = TextTertiary, modifier = Modifier.size(54.dp))
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(text = LanguageStrings.get("no_photos_party", currentLang), style = MaterialTheme.typography.titleMedium, color = TextSecondary)
                                Text(text = LanguageStrings.get("empty_photos_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                            }
                        }
                    }
                } else {
                    items(partyAllAtts.chunked(3), key = { row -> row.first().id }) { rowAtts ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            rowAtts.forEach { att ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(8.dp))
                                        .border(1.dp, CardBorder, RoundedCornerShape(8.dp))
                                        .clickable {
                                            viewingAttachments = partyAllAtts
                                            viewingInitialIndex = partyAllAtts.indexOf(att)
                                        }
                                ) {
                                    val thumbFile = remember(att.thumbPath) { File(att.thumbPath) }
                                    val bm = remember(att.thumbPath) {
                                        if (thumbFile.exists()) BitmapFactory.decodeFile(thumbFile.absolutePath) else null
                                    }
                                    if (bm != null) {
                                        Image(
                                            bitmap = bm.asImageBitmap(),
                                            contentDescription = att.caption,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        Box(
                                            modifier = Modifier.fillMaxSize().background(ChipInactiveBg),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = if (att.mimeType == "application/pdf") Icons.Default.PictureAsPdf else Icons.Default.Image,
                                                contentDescription = null,
                                                tint = TextSecondary
                                            )
                                        }
                                    }
                                    if (att.mimeType == "application/pdf") {
                                        Surface(
                                            color = SurfaceWhite.copy(alpha = 0.9f),
                                            shape = RoundedCornerShape(topStart = 4.dp),
                                            modifier = Modifier.align(Alignment.BottomEnd)
                                        ) {
                                            Text(
                                                text = LanguageStrings.get("pdf_file_label", currentLang),
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = UdharRed,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            repeat(3 - rowAtts.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Transaction Dialog (Udhar / Jama)
    if (showAddTxDialog) {
        // rememberSaveable: the form must survive Android killing the app while the
        // camera is open, or the photo and typed amount are lost.
        var amountText by rememberSaveable { mutableStateOf("") }
        var noteText by rememberSaveable { mutableStateOf("") }
        var onlyInKhata by rememberSaveable { mutableStateOf(false) }
        var showMoreOptions by rememberSaveable { mutableStateOf(false) }
        var selectedPaymentMode by rememberSaveable { mutableStateOf("CASH") }
        var pendingAttachments by rememberSaveable(stateSaver = AttachmentListSaver) { mutableStateOf<List<Attachment>>(emptyList()) }
        val tempOwnerId = rememberSaveable { UUID.randomUUID().toString() }

        var selectedDueDateDays by rememberSaveable { mutableStateOf(0) }

        val isGave = txTypeToAdd == "GAVE"
        val isCreditEntry = if (isSupplier) txTypeToAdd == "GOT" else txTypeToAdd == "GAVE"
        val dialogTitle = if (isGave) {
            if (isSupplier) LanguageStrings.get("you_gave_supplier", currentLang) else LanguageStrings.get("you_gave", currentLang)
        } else {
            if (isSupplier) LanguageStrings.get("you_got_supplier", currentLang) else LanguageStrings.get("you_got", currentLang)
        }

        AlertDialog(
            onDismissRequest = { showAddTxDialog = false },
            title = { Text(dialogTitle, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${LanguageStrings.get("party", currentLang)}: ${currentCustomer.name}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it },
                        label = { Text(text = LanguageStrings.get("amount_label", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("amount_sample_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = noteText,
                            onValueChange = { noteText = it },
                            label = { Text(text = LanguageStrings.get("note_label", currentLang)) },
                            placeholder = { Text(text = LanguageStrings.get("note_sample_placeholder", currentLang)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        VoiceNoteButton(
                            currentLang = currentLang,
                            onSpeechResult = { spoken ->
                                noteText = if (noteText.isBlank()) spoken else "$noteText $spoken"
                            }
                        )
                    }

                    // Optional Due Date Chips for Credit (Udhar) entries
                    if (isCreditEntry) {
                        Text(
                            text = LanguageStrings.get("due_date_label", currentLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        // Scrolls sideways instead of squeezing "30 days" onto 3 lines.
                        Row(
                            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(0 to LanguageStrings.get("none_label", currentLang), 7 to LanguageStrings.get("chip_7_days", currentLang), 15 to LanguageStrings.get("chip_15_days", currentLang), 30 to LanguageStrings.get("chip_30_days", currentLang)).forEach { (days, label) ->
                                FilterChip(
                                    selected = selectedDueDateDays == days,
                                    onClick = { selectedDueDateDays = days },
                                    label = { Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false) }
                                )
                            }
                        }
                    }

                    // Attachments picker strip
                    AttachmentPickerStrip(
                        attachments = pendingAttachments,
                        onAttachmentsChanged = { pendingAttachments = it },
                        ownerType = "ENTRY",
                        ownerId = tempOwnerId,
                        currentLang = currentLang
                    )

                    val isPayment = if (isSupplier) txTypeToAdd == "GAVE" else txTypeToAdd == "GOT"
                    if (isPayment) {
                        Text(
                            text = LanguageStrings.get("payment_mode_label", currentLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("CASH", "UPI", "BANK", "CHEQUE").forEach { mode ->
                                FilterChip(
                                    selected = selectedPaymentMode == mode,
                                    onClick = { selectedPaymentMode = mode },
                                    label = { Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                                )
                            }
                        }

                        // More options: Only in khata switch
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showMoreOptions = !showMoreOptions }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = LanguageStrings.get("more_options_label", currentLang),
                                style = MaterialTheme.typography.labelMedium,
                                color = PrimaryBlue,
                                fontWeight = FontWeight.SemiBold
                            )
                            Icon(
                                if (showMoreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = LanguageStrings.get("more_options_label", currentLang),
                                tint = PrimaryBlue,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        if (showMoreOptions) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = LanguageStrings.get("only_in_khata_label", currentLang),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondary
                                )
                                Switch(
                                    checked = onlyInKhata,
                                    onCheckedChange = { onlyInKhata = it }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = amountText.toDoubleOrNull() ?: 0.0
                        if (amt > 0.001) {
                            val computedDueDate = if (selectedDueDateDays > 0) {
                                val cal = java.util.Calendar.getInstance().apply {
                                    add(java.util.Calendar.DAY_OF_YEAR, selectedDueDateDays)
                                }
                                cal.timeInMillis
                            } else 0L

                            val newTx = repository.addTransaction(
                                customerId = currentCustomer.id,
                                amount = amt,
                                type = txTypeToAdd,
                                note = noteText.trim(),
                                paymentMode = selectedPaymentMode,
                                onlyInKhata = onlyInKhata,
                                dueDate = computedDueDate
                            )
                            if (pendingAttachments.isNotEmpty()) {
                                repository.addAttachments(
                                    pendingAttachments.map { it.copy(ownerId = newTx.id) }
                                )
                            }
                            showAddTxDialog = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isGave) (if (isSupplier) PrimaryBlue else UdharRed) else (if (isSupplier) UdharRed else JamaGreen)
                    )
                ) {
                    Text(text = LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddTxDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }


    // Mark Bill Received Dialog
    if (billToMarkReceived != null) {
        val bill = billToMarkReceived!!
        val outstanding = repository.getBillOutstandingAmount(bill)
        var receivedInput by remember { mutableStateOf("%.2f".format(outstanding)) }
        var selectedMode by remember { mutableStateOf("CASH") }
        var onlyInKhata by remember { mutableStateOf(false) }
        var showMoreOptions by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { billToMarkReceived = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = JamaGreen)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = LanguageStrings.get("mark_received_sheet_title", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${LanguageStrings.get("party", currentLang)}: ${currentCustomer.name}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${LanguageStrings.get("total_bill_amount", currentLang)}: ₹${"%.2f".format(bill.amount)} • ${LanguageStrings.get("outstanding_amount", currentLang)}: ₹${"%.2f".format(outstanding)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )

                    OutlinedTextField(
                        value = receivedInput,
                        onValueChange = { receivedInput = it },
                        label = { Text(text = LanguageStrings.get("amount_label", currentLang)) },
                        placeholder = { Text(text = formatIndianNumber(outstanding)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(
                            onClick = { receivedInput = "%.2f".format(outstanding) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(text = "${LanguageStrings.get("full_payment_prefix", currentLang)}: ${formatIndianCurrency(outstanding)}", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        if (outstanding > 100) {
                            FilledTonalButton(
                                onClick = { receivedInput = "%.2f".format(outstanding / 2) },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(text = "${LanguageStrings.get("half_payment_prefix", currentLang)}: ${formatIndianCurrency(outstanding / 2)}", fontSize = 11.sp)
                            }
                        }
                    }

                    Text(
                        text = LanguageStrings.get("payment_mode_label", currentLang),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CASH", "UPI", "BANK", "CHEQUE").forEach { mode ->
                            FilterChip(
                                selected = selectedMode == mode,
                                onClick = { selectedMode = mode },
                                label = { Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }

                    // More options: Only in khata switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showMoreOptions = !showMoreOptions }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = LanguageStrings.get("more_options_label", currentLang),
                            style = MaterialTheme.typography.labelMedium,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            if (showMoreOptions) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = LanguageStrings.get("more_options_label", currentLang),
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (showMoreOptions) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageStrings.get("only_in_khata_label", currentLang),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Switch(
                                checked = onlyInKhata,
                                onCheckedChange = { onlyInKhata = it }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amt = receivedInput.toDoubleOrNull() ?: 0.0
                        if (amt > 0.001) {
                            repository.markBillReceived(
                                billId = bill.id,
                                amount = amt,
                                paymentMode = selectedMode,
                                onlyInKhata = onlyInKhata
                            )
                            billToMarkReceived = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen)
                ) {
                    Text(text = LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { billToMarkReceived = null }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Mark All Received Dialog (Multi-select)
    if (showMarkAllReceivedDialog) {
        var bulkPaymentMode by remember { mutableStateOf("CASH") }
        var bulkOnlyInKhata by remember { mutableStateOf(false) }
        var bulkShowMore by remember { mutableStateOf(false) }

        val totalSelectedOutstanding = remember(selectedBillIds.toList(), customerTx) {
            customerTx.filter { selectedBillIds.contains(it.id) }
                .sumOf { repository.getBillOutstandingAmount(it) }
        }

        AlertDialog(
            onDismissRequest = { showMarkAllReceivedDialog = false },
            title = { Text(text = LanguageStrings.get("mark_all_received", currentLang), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "${selectedBillIds.size} ${LanguageStrings.get("selected_bills", currentLang)}",
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${LanguageStrings.get("outstanding_amount", currentLang)}: ₹${"%.2f".format(totalSelectedOutstanding)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = JamaGreen,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = LanguageStrings.get("payment_mode_label", currentLang),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CASH", "UPI", "BANK", "CHEQUE").forEach { mode ->
                            FilterChip(
                                selected = bulkPaymentMode == mode,
                                onClick = { bulkPaymentMode = mode },
                                label = { Text(text = mode, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }

                    // More options: Only in khata switch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { bulkShowMore = !bulkShowMore }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = LanguageStrings.get("more_options_label", currentLang),
                            style = MaterialTheme.typography.labelMedium,
                            color = PrimaryBlue,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            if (bulkShowMore) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = LanguageStrings.get("more_options_label", currentLang),
                            tint = PrimaryBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    if (bulkShowMore) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = LanguageStrings.get("only_in_khata_label", currentLang),
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                            Switch(
                                checked = bulkOnlyInKhata,
                                onCheckedChange = { bulkOnlyInKhata = it }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.markBillsAllReceived(
                            billIds = selectedBillIds.toList(),
                            paymentMode = bulkPaymentMode,
                            onlyInKhata = bulkOnlyInKhata
                        )
                        showMarkAllReceivedDialog = false
                        isMultiSelectMode = false
                        selectedBillIds.clear()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen)
                ) {
                    Text(text = LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMarkAllReceivedDialog = false }) {
                    Text(text = LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Statement Dialog (PDF & WhatsApp Text Export)
    if (showStatementDialog) {
        var filterType by remember { mutableStateOf(StatementFilter.ALL_ENTRIES) }
        var dateRange by remember { mutableStateOf(StatementDateRange.ALL) }

        AlertDialog(
            onDismissRequest = { showStatementDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Assessment, contentDescription = null, tint = PrimaryBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(LanguageStrings.get("statement", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "${LanguageStrings.get("party", currentLang)}: ${currentCustomer.name}",
                        fontWeight = FontWeight.SemiBold
                    )

                    // Statement Type Filter Chips
                    Text(
                        text = "Statement Type",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = filterType == StatementFilter.ALL_ENTRIES,
                            onClick = { filterType = StatementFilter.ALL_ENTRIES },
                            label = { Text(LanguageStrings.get("full_statement", currentLang), fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = filterType == StatementFilter.PENDING_BILLS_ONLY,
                            onClick = { filterType = StatementFilter.PENDING_BILLS_ONLY },
                            label = { Text(LanguageStrings.get("pending_bills_only", currentLang), fontSize = 11.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Date Range Filter Chips
                    Text(
                        text = "Date Range",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = dateRange == StatementDateRange.THIS_MONTH,
                            onClick = { dateRange = StatementDateRange.THIS_MONTH },
                            label = { Text(LanguageStrings.get("date_this_month", currentLang), fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = dateRange == StatementDateRange.THREE_MONTHS,
                            onClick = { dateRange = StatementDateRange.THREE_MONTHS },
                            label = { Text(LanguageStrings.get("date_3_months", currentLang), fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = dateRange == StatementDateRange.ALL,
                            onClick = { dateRange = StatementDateRange.ALL },
                            label = { Text(LanguageStrings.get("date_all", currentLang), fontSize = 10.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = CardBorder)

                    // Share PDF Button
                    Button(
                        onClick = {
                            try {
                                val statementData = StatementGenerator.computeStatement(
                                    customer = currentCustomer,
                                    allTransactions = customerTx,
                                    dateRange = dateRange,
                                    filter = filterType
                                )
                                val pdfFile = StatementGenerator.generateStatementPdf(
                                    context = context,
                                    statement = statementData,
                                    storeProfile = storeProfile
                                )
                                val uri = FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    pdfFile
                                )
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Statement PDF"))
                                showStatementDialog = false
                            } catch (e: Exception) {
                                android.util.Log.e("CustomerDetailScreen", "Failed to generate statement PDF: ${e.message}", e)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(LanguageStrings.get("share_pdf", currentLang), fontWeight = FontWeight.Bold)
                    }

                    // Share WhatsApp Text Button
                    OutlinedButton(
                        onClick = {
                            try {
                                val statementData = StatementGenerator.computeStatement(
                                    customer = currentCustomer,
                                    allTransactions = customerTx,
                                    dateRange = dateRange,
                                    filter = filterType
                                )
                                val textContent = StatementGenerator.generateStatementText(
                                    statement = statementData,
                                    storeProfile = storeProfile,
                                    language = currentLang
                                )
                                val waNumber = PhoneUtil.toWhatsAppNumber(currentCustomer.phone)
                                if (waNumber.isNotBlank()) {
                                    val enc = URLEncoder.encode(textContent, "UTF-8")
                                    val waUri = Uri.parse("https://wa.me/$waNumber?text=$enc")
                                    val waIntent = Intent(Intent.ACTION_VIEW, waUri)
                                    context.startActivity(waIntent)
                                } else {
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, textContent)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share Statement Text"))
                                }
                                showStatementDialog = false
                            } catch (e: Exception) {
                                android.util.Log.e("CustomerDetailScreen", "Failed to share statement text: ${e.message}", e)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp), tint = JamaGreen)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(LanguageStrings.get("share_whatsapp_text", currentLang), fontWeight = FontWeight.Bold, color = JamaGreen)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showStatementDialog = false }) {
                    Text(LanguageStrings.get("btn_dismiss", currentLang))
                }
            }
        )
    }

    // Edit Transaction Dialog
    if (txToEdit != null) {
        val editingTx = txToEdit!!
        var editAmount by remember { mutableStateOf("%.2f".format(editingTx.amount)) }
        var editNote by remember { mutableStateOf(editingTx.note) }
        var editMode by remember { mutableStateOf(editingTx.paymentMode) }
        val currentTxAttachments = remember(editingTx.id, allAttachments) {
            repository.getAttachmentsForOwner("ENTRY", editingTx.id)
        }
        var editAttachments by remember(editingTx.id) { mutableStateOf(currentTxAttachments) }

        AlertDialog(
            onDismissRequest = { txToEdit = null },
            title = { Text(LanguageStrings.get("edit_entry", currentLang), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editAmount,
                        onValueChange = { editAmount = it },
                        label = { Text(LanguageStrings.get("amount_label", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = editNote,
                            onValueChange = { editNote = it },
                            label = { Text(LanguageStrings.get("note_label", currentLang)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        VoiceNoteButton(
                            currentLang = currentLang,
                            onSpeechResult = { spoken ->
                                editNote = if (editNote.isBlank()) spoken else "$editNote $spoken"
                            }
                        )
                    }

                    // Attachments picker strip
                    AttachmentPickerStrip(
                        attachments = editAttachments,
                        onAttachmentsChanged = { updated ->
                            val added = updated.filter { !currentTxAttachments.any { c -> c.id == it.id } }
                            val removed = currentTxAttachments.filter { !updated.any { u -> u.id == it.id } }
                            if (added.isNotEmpty()) {
                                repository.addAttachments(added.map { it.copy(ownerId = editingTx.id) })
                            }
                            removed.forEach { repository.deleteAttachment(it.id) }
                            editAttachments = updated
                        },
                        ownerType = "ENTRY",
                        ownerId = editingTx.id,
                        currentLang = currentLang
                    )


                    Text(
                        text = LanguageStrings.get("payment_mode_label", currentLang),
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("CASH", "UPI", "BANK", "CHEQUE").forEach { mode ->
                            FilterChip(
                                selected = editMode == mode,
                                onClick = { editMode = mode },
                                label = { Text(mode, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                            )
                        }
                    }

                    // Delete button inside edit dialog
                    TextButton(
                        onClick = {
                            repository.deleteTransaction(editingTx.id)
                            txToEdit = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = UdharRed)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(LanguageStrings.get("btn_delete", currentLang), fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = editAmount.toDoubleOrNull() ?: 0.0
                        if (parsed > 0.001) {
                            repository.editTransaction(
                                txId = editingTx.id,
                                amount = parsed,
                                note = editNote.trim(),
                                date = editingTx.date,
                                paymentMode = editMode
                            )
                            txToEdit = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { txToEdit = null }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Edit Phone Number Dialog
    if (showEditPhoneDialog) {
        AlertDialog(
            onDismissRequest = { showEditPhoneDialog = false },
            title = { Text(text = LanguageStrings.get("update_phone_title", currentLang), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "${LanguageStrings.get("update_phone_desc", currentLang)} ${currentCustomer.name}:",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    OutlinedTextField(
                        value = phoneInputText,
                        onValueChange = { phoneInputText = it },
                        label = { Text(text = LanguageStrings.get("customer_phone_label", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("phone_placeholder", currentLang)) },
                        isError = phoneInputText.isNotBlank() && !PhoneUtil.isValid(phoneInputText),
                        supportingText = {
                            if (phoneInputText.isNotBlank() && !PhoneUtil.isValid(phoneInputText)) {
                                Text(text = LanguageStrings.get("invalid_phone_fix_hint", currentLang), color = UdharRed)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleaned = PhoneUtil.normalize(phoneInputText)
                        repository.updateCustomer(
                            id = currentCustomer.id,
                            name = currentCustomer.name,
                            phone = cleaned,
                            address = currentCustomer.address,
                            gstin = currentCustomer.gstin,
                            type = currentCustomer.type
                        )
                        showEditPhoneDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_save", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditPhoneDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    viewingAttachments?.let { attList ->
        FullScreenAttachmentViewer(
            attachments = attList,
            initialIndex = viewingInitialIndex,
            currentLang = currentLang,
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

private fun sendWhatsAppReminder(
    context: Context,
    party: Customer,
    shopName: String,
    balance: Double,
    upiId: String,
    lang: com.example.model.AppLanguage
) {
    val waNumber = PhoneUtil.toWhatsAppNumber(party.phone)
    if (waNumber.isBlank()) return

    val upiPart = if (upiId.isNotBlank()) " UPI ID: $upiId" else ""
    val balStr = "%.2f".format(Math.abs(balance))
    val msg = LanguageStrings.get("reminder_message", lang).format(party.name, shopName, balStr, upiPart)
    try {
        val enc = URLEncoder.encode(msg, "UTF-8")
        val uri = Uri.parse("https://wa.me/$waNumber?text=$enc")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback to SMS
        try {
            val smsUri = Uri.parse("smsto:$waNumber")
            val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                putExtra("sms_body", msg)
            }
            context.startActivity(smsIntent)
        } catch (se: Exception) {
            android.util.Log.e("CustomerDetailScreen", "Cannot open WhatsApp or SMS: ${se.message}", se)
        }
    }
}

private fun sendSmsReminder(
    context: Context,
    party: Customer,
    shopName: String,
    balance: Double,
    upiId: String,
    lang: com.example.model.AppLanguage
) {
    val waNumber = PhoneUtil.toWhatsAppNumber(party.phone)
    if (waNumber.isBlank()) return
    val upiPart = if (upiId.isNotBlank()) " UPI ID: $upiId" else ""
    val balStr = "%.2f".format(Math.abs(balance))
    val msg = LanguageStrings.get("reminder_message", lang).format(party.name, shopName, balStr, upiPart)
    try {
        val smsUri = Uri.parse("smsto:$waNumber")
        val smsIntent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
            putExtra("sms_body", msg)
        }
        context.startActivity(smsIntent)
    } catch (e: Exception) {
        android.util.Log.e("CustomerDetailScreen", "Cannot send SMS: ${e.message}", e)
    }
}

@Preview(name = "Customer Detail Small Phone 130%", widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun CustomerDetailScreenSmallPhonePreview() {
    MyApplicationTheme {
        Surface(color = SoftBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = LanguageStrings.get("closing_balance", com.example.model.AppLanguage.HINGLISH).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formatIndianCurrency(12500.0),
                            style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"),
                            fontWeight = FontWeight.Bold,
                            color = JamaGreen,
                            softWrap = false
                        )
                    }
                }
            }
        }
    }
}

/** One labelled button in the ledger header row (icon with its name below). */
@Composable
private fun LedgerHeaderAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(min = 64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else TextTertiary, modifier = Modifier.size(22.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (enabled) TextPrimary else TextTertiary,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** "Bal. ₹1,855" under an entry: red while the party owes us, green when we owe them. */
@Composable
private fun RunningBalanceText(bal: Double, lang: com.example.model.AppLanguage) {
    Text(
        text = "${LanguageStrings.get("running_bal", lang)} ${formatIndianCurrency(kotlin.math.abs(bal))}",
        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
        color = when {
            bal > 0.001 -> UdharRed
            bal < -0.001 -> JamaGreen
            else -> TextSecondary
        },
        softWrap = false
    )
}
