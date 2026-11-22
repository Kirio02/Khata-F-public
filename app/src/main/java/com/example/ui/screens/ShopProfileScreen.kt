package com.example.ui.screens

import com.example.ui.components.ThemePickerCard
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.AppRepository
import com.example.model.CsvCustomerPreview
import com.example.model.ImportResult
import com.example.ui.components.OperationProgressDialog
import com.example.ui.report.DailyReportScheduler
import com.example.ui.report.DailyReportWorker
import com.example.ui.security.AppLockManager
import com.example.ui.security.SecureSettingsManager
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.PdfInvoiceGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val INDIAN_STATES = listOf(
    "Gujarat", "Maharashtra", "Rajasthan", "Madhya Pradesh", "Delhi",
    "Uttar Pradesh", "Punjab", "Haryana", "Karnataka", "Tamil Nadu",
    "West Bengal", "Telangana", "Andhra Pradesh", "Kerala", "Bihar",
    "Odisha", "Assam", "Jharkhand", "Chhattisgarh", "Uttarakhand",
    "Goa", "Himachal Pradesh", "Jammu and Kashmir"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopProfileScreen(
    repository: AppRepository,
    appLockManager: AppLockManager? = null,
    onOpenGuide: () -> Unit,
    onNavigateToAccountantReports: () -> Unit = {},
    onOpenBatteryGuide: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentLang by repository.language.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val profile by repository.storeProfile.collectAsState()

    var storeName by remember(profile) { mutableStateOf(profile.storeName) }
    var address by remember(profile) { mutableStateOf(profile.address) }
    var phone by remember(profile) { mutableStateOf(profile.phone) }
    var email by remember(profile) { mutableStateOf(profile.email) }
    var upiId by remember(profile) { mutableStateOf(profile.upiId) }
    var gstin by remember(profile) { mutableStateOf(profile.gstin) }
    var ownerName by remember(profile) { mutableStateOf(profile.ownerName) }
    var state by remember(profile) { mutableStateOf(profile.state.ifBlank { "Gujarat" }) }
    var logoUri by remember(profile) { mutableStateOf(profile.logoUri) }
    var stateExpanded by remember { mutableStateOf(false) }

    val logoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                val internalUri = withContext(Dispatchers.IO) {
                    try {
                        val destFile = java.io.File(context.filesDir, "shop_logo.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            destFile.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        if (destFile.exists() && destFile.length() > 0L) Uri.fromFile(destFile).toString() else null
                    } catch (e: Exception) {
                        android.util.Log.e("ShopProfileScreen", "Failed to copy logo: ${e.message}", e)
                        null
                    }
                }
                if (internalUri != null) {
                    logoUri = internalUri
                    repository.updateStoreProfile(storeName, address, phone, upiId, gstin, ownerName, state, internalUri, email)
                    Toast.makeText(context, "Shop logo updated for invoices", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Could not load selected logo", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    var isSavedSnackbar by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var progressMessage by remember { mutableStateOf<String?>(null) }

    // App Lock & Security State
    var isPinActive by remember { mutableStateOf(appLockManager?.hasPin() == true) }
    var isBiometricActive by remember { mutableStateOf(appLockManager?.isBiometricEnabled() == true) }
    var isBlockScreenshotsActive by remember { mutableStateOf(appLockManager?.isBlockScreenshotsEnabled() == true) }
    var showPinDialog by remember { mutableStateOf(false) }
    var newPinText by remember { mutableStateOf("") }
    var confirmPinText by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    // Daily Evening Report State
    var isDailyReportActive by remember { mutableStateOf(SecureSettingsManager.isDailyReportEnabled(context)) }
    var isGstBillsActive by remember { mutableStateOf(SecureSettingsManager.isGstBillsEnabled(context)) }

    var showNotifRationaleDialog by remember { mutableStateOf(false) }
    var showNotifSettingsHintDialog by remember { mutableStateOf(false) }

    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            isDailyReportActive = true
            SecureSettingsManager.setDailyReportEnabled(context, true)
            DailyReportScheduler.scheduleDailyReport(context)
            Toast.makeText(
                context,
                LanguageStrings.get("daily_report_title", currentLang) + " enabled",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            isDailyReportActive = false
            SecureSettingsManager.setDailyReportEnabled(context, false)
            DailyReportScheduler.scheduleDailyReport(context)
            showNotifSettingsHintDialog = true
        }
    }

    // Opening Balances State
    val cashOpeningBalance by repository.cashOpeningBalance.collectAsState()
    val cashOpeningDate by repository.cashOpeningDate.collectAsState()
    val bankOpeningBalance by repository.bankOpeningBalance.collectAsState()
    val bankOpeningDate by repository.bankOpeningDate.collectAsState()

    var cashOpeningInput by remember(cashOpeningBalance) { mutableStateOf(if (cashOpeningBalance > 0.0) cashOpeningBalance.toString() else "") }
    var bankOpeningInput by remember(bankOpeningBalance) { mutableStateOf(if (bankOpeningBalance > 0.0) bankOpeningBalance.toString() else "") }

    // CSV Import State
    var showCsvPreviewDialog by remember { mutableStateOf(false) }
    var csvPreviews by remember { mutableStateOf<List<CsvCustomerPreview>>(emptyList()) }

    val csvPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                progressMessage = LanguageStrings.get("progress_loading", currentLang)
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    }
                    val previews = withContext(Dispatchers.IO) {
                        repository.previewCustomersFromCsv(content)
                    }
                    if (previews.isEmpty()) {
                        Toast.makeText(context, LanguageStrings.get("csv_no_rows", currentLang), Toast.LENGTH_LONG).show()
                    } else {
                        csvPreviews = previews
                        showCsvPreviewDialog = true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ShopProfileScreen", "Failed to read CSV: ${e.message}", e)
                    Toast.makeText(context, LanguageStrings.get("csv_read_failed", currentLang), Toast.LENGTH_SHORT).show()
                } finally {
                    progressMessage = null
                }
            }
        }
    }

    // Export Backup File Launcher (CreateDocument ZIP)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                progressMessage = LanguageStrings.get("progress_exporting", currentLang)
                try {
                    val zipBytes = withContext(Dispatchers.IO) { repository.exportBackupZip(context) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(zipBytes)
                        }
                    }
                    statusMessage = "✅ Backup ZIP saved successfully (data.json + attachments)!"
                } catch (e: Exception) {
                    android.util.Log.e("ShopProfileScreen", "Failed to write backup: ${e.message}", e)
                    statusMessage = "❌ Error saving backup file: ${e.localizedMessage}"
                } finally {
                    progressMessage = null
                }
            }
        }
    }

    // Backup Before Delete Launcher
    val backupBeforeDeleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                progressMessage = LanguageStrings.get("progress_exporting", currentLang)
                try {
                    val zipBytes = withContext(Dispatchers.IO) { repository.exportBackupZip(context) }
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { stream ->
                            stream.write(zipBytes)
                        }
                    }
                    withContext(Dispatchers.IO) { repository.clearAllData() }
                    statusMessage = "✅ Safety backup saved and all data cleared."
                } catch (e: Exception) {
                    android.util.Log.e("ShopProfileScreen", "Failed to save safety backup: ${e.message}", e)
                    statusMessage = "❌ Backup failed. Data was NOT cleared for safety."
                } finally {
                    progressMessage = null
                }
            }
        }
    }

    // Import Backup File Launcher (OpenDocument: ZIP or JSON)
    var pendingImportJson by remember { mutableStateOf("") }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingImportIsZip by remember { mutableStateOf(false) }
    var pendingImportValidation by remember { mutableStateOf<ImportResult?>(null) }
    var showImportSummaryDialog by remember { mutableStateOf(false) }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            coroutineScope.launch {
                progressMessage = LanguageStrings.get("progress_loading", currentLang)
                try {
                    val (jsonStr, isZip) = withContext(Dispatchers.IO) {
                        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?: throw IllegalStateException("Could not open file")
                        val looksZip = bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
                        if (looksZip) {
                            var extractedJson = ""
                            java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zis ->
                                var entry = zis.nextEntry
                                while (entry != null) {
                                    if (entry.name == "data.json") {
                                        extractedJson = zis.readBytes().toString(Charsets.UTF_8)
                                        break
                                    }
                                    zis.closeEntry()
                                    entry = zis.nextEntry
                                }
                            }
                            extractedJson to true
                        } else {
                            bytes.toString(Charsets.UTF_8) to false
                        }
                    }
                    pendingImportJson = jsonStr
                    pendingImportUri = uri
                    pendingImportIsZip = isZip
                    pendingImportValidation = repository.validateBackupJson(jsonStr)
                    showImportSummaryDialog = true
                } catch (e: Exception) {
                    android.util.Log.e("ShopProfileScreen", "Failed to read backup: ${e.message}", e)
                    statusMessage = "❌ Error reading backup file: ${e.localizedMessage}"
                } finally {
                    progressMessage = null
                }
            }
        }
    }

    // Clear Data Dialog State
    var showClearDataDialog by remember { mutableStateOf(false) }
    var deleteInputText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SoftBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
            .padding(bottom = 24.dp)
    ) {
        ThemePickerCard(lang = currentLang)
        Spacer(modifier = Modifier.height(16.dp))

        // Shop Profile Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Storefront, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(28.dp))
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column {
                        Text(text = if (storeName.isNotBlank()) storeName else LanguageStrings.get("tab_profile", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(text = LanguageStrings.get("business_gst_config", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = storeName,
                    onValueChange = { storeName = it },
                    label = { Text(text = LanguageStrings.get("store_name_label", currentLang)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text(text = LanguageStrings.get("shop_address_label", currentLang)) },
                    placeholder = { Text(text = LanguageStrings.get("shop_address_placeholder", currentLang)) },
                    maxLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(text = LanguageStrings.get("store_phone", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("phone_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = upiId,
                        onValueChange = { upiId = it },
                        label = { Text(text = LanguageStrings.get("upi_optional_label", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("upi_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text(text = LanguageStrings.get("email_label", currentLang)) },
                    placeholder = { Text(text = "shop@example.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = gstin,
                        onValueChange = { gstin = it },
                        label = { Text(text = LanguageStrings.get("gstin_optional_label", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("gstin_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = ownerName,
                        onValueChange = { ownerName = it },
                        label = { Text(text = LanguageStrings.get("owner_name_label", currentLang)) },
                        placeholder = { Text(text = LanguageStrings.get("owner_name_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // State Selector
                ExposedDropdownMenuBox(
                    expanded = stateExpanded,
                    onExpandedChange = { stateExpanded = it }
                ) {
                    OutlinedTextField(
                        value = state,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(LanguageStrings.get("state_label", currentLang)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = stateExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = stateExpanded,
                        onDismissRequest = { stateExpanded = false }
                    ) {
                        INDIAN_STATES.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item) },
                                onClick = {
                                    state = item
                                    stateExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Optional Shop Logo for PDF Invoices
                Surface(
                    color = ChipInactiveBg.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(text = LanguageStrings.get("shop_logo_pdf", currentLang), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary)
                                Text(
                                    text = if (logoUri.isNotBlank()) LanguageStrings.get("logo_attached", currentLang) else LanguageStrings.get("logo_optional_desc", currentLang),
                                    fontSize = 11.sp,
                                    color = if (logoUri.isNotBlank()) JamaGreen else TextSecondary
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                logoPickerLauncher.launch(
                                    androidx.activity.result.PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(text = if (logoUri.isNotBlank()) LanguageStrings.get("change_logo", currentLang) else LanguageStrings.get("pick_logo", currentLang), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Button(
                    onClick = {
                        repository.updateStoreProfile(storeName, address, phone, upiId, gstin, ownerName, state, logoUri, email)
                        isSavedSnackbar = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = LanguageStrings.get("btn_save_store_info", currentLang), fontWeight = FontWeight.Bold)
                }

                if (isSavedSnackbar) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = LanguageStrings.get("store_saved_success", currentLang), color = JamaGreen, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Real CSV Customer Import Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(JamaGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(LanguageStrings.get("import_csv_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(LanguageStrings.get("import_csv_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        csvPickerLauncher.launch(arrayOf("text/comma-separated-values", "text/csv", "text/plain", "*/*"))
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.FileOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(LanguageStrings.get("btn_import_csv", currentLang), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Excel / CSV Spreadsheet Export Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.TableChart, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(text = LanguageStrings.get("reports_csv_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(text = LanguageStrings.get("reports_csv_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            progressMessage = LanguageStrings.get("progress_exporting", currentLang)
                            try {
                                val file = withContext(Dispatchers.IO) { repository.exportKhataLedgerCsv(context) }
                                repository.shareCsvFile(context, file, "Share Khata Ledger CSV")
                                statusMessage = "✅ Khata Ledger CSV exported: ${file.name}"
                            } catch (e: Exception) {
                                statusMessage = "❌ Export failed: ${e.message}"
                            } finally {
                                progressMessage = null
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(18.dp), tint = PrimaryBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = LanguageStrings.get("export_khata_csv", currentLang), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        coroutineScope.launch {
                            progressMessage = LanguageStrings.get("progress_exporting", currentLang)
                            try {
                                val file = withContext(Dispatchers.IO) { repository.exportSalesReportCsv(context) }
                                repository.shareCsvFile(context, file, "Share Sales Report CSV")
                                statusMessage = "✅ Sales Report CSV exported: ${file.name}"
                            } catch (e: Exception) {
                                statusMessage = "❌ Export failed: ${e.message}"
                            } finally {
                                progressMessage = null
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(44.dp)
                ) {
                    Icon(Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(18.dp), tint = PrimaryBlue)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = LanguageStrings.get("export_sales_csv", currentLang), fontWeight = FontWeight.SemiBold, color = TextPrimary)
                }

            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Battery & Auto-Start Setup Guide Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageStrings.get("battery_guide_title", currentLang),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = LanguageStrings.get("battery_guide_subtitle", currentLang),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = onOpenBatteryGuide,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = LanguageStrings.get("battery_guide_title", currentLang), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Accountant Reports (A4 PDF & CSV) Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(JamaGreen.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Assessment, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageStrings.get("accountant_reports_title", currentLang),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = LanguageStrings.get("accountant_reports_desc", currentLang),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = { onNavigateToAccountantReports() },
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = LanguageStrings.get("accountant_reports_title", currentLang), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Opening Balances Card (Cash book & Bank book)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.AccountBalance, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageStrings.get("opening_balances_card_title", currentLang),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimary
                        )
                        Text(
                            text = LanguageStrings.get("opening_balance_label", currentLang),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = cashOpeningInput,
                    onValueChange = { cashOpeningInput = it },
                    label = { Text(text = LanguageStrings.get("cash_opening_balance", currentLang)) },
                    placeholder = { Text(text = LanguageStrings.get("amount_zero_placeholder", currentLang)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = bankOpeningInput,
                    onValueChange = { bankOpeningInput = it },
                    label = { Text(text = LanguageStrings.get("bank_opening_balance", currentLang)) },
                    placeholder = { Text(text = LanguageStrings.get("amount_zero_placeholder", currentLang)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        val cAmt = cashOpeningInput.toDoubleOrNull() ?: 0.0
                        val bAmt = bankOpeningInput.toDoubleOrNull() ?: 0.0
                        val now = System.currentTimeMillis()
                        repository.updateCashOpeningBalance(cAmt, if (cashOpeningDate > 0) cashOpeningDate else now)
                        repository.updateBankOpeningBalance(bAmt, if (bankOpeningDate > 0) bankOpeningDate else now)
                        Toast.makeText(context, LanguageStrings.get("btn_save_balances", currentLang), Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = LanguageStrings.get("btn_save_balances", currentLang), fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Honest Plain JSON Backup & Restore Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.FolderZip, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(LanguageStrings.get("backup_file_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(LanguageStrings.get("backup_file_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            exportBackupLauncher.launch("ghanshyam_backup_$timeStamp.zip")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(LanguageStrings.get("export_backup", currentLang), fontSize = 13.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                progressMessage = LanguageStrings.get("progress_exporting", currentLang)
                                try {
                                    val backupFile = withContext(Dispatchers.IO) { repository.exportBackupFile(context) }
                                    repository.shareBackupFile(context, backupFile)
                                    statusMessage = "Backup shared: ${backupFile.name}"
                                } catch (e: Exception) {
                                    statusMessage = "❌ Backup share failed: ${e.message}"
                                } finally {
                                    progressMessage = null
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(LanguageStrings.get("share_backup", currentLang), fontSize = 13.sp, textAlign = TextAlign.Center)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        importBackupLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*"))
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(42.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(LanguageStrings.get("restore_backup", currentLang), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }

                if (statusMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(statusMessage!!, color = if (statusMessage!!.startsWith("✅")) JamaGreen else UdharRed, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 1. APP LOCK & SECURITY (PIN & BIOMETRICS)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(if (isPinActive) JamaGreen.copy(alpha = 0.15f) else ChipInactiveBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                tint = if (isPinActive) JamaGreen else TextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(text = LanguageStrings.get("app_lock_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                text = if (isPinActive) LanguageStrings.get("app_lock_desc", currentLang) else LanguageStrings.get("enable_app_lock", currentLang),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isPinActive) JamaGreen else UdharRed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = LanguageStrings.get("app_lock_security_info", currentLang),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            newPinText = ""
                            confirmPinText = ""
                            pinError = null
                            showPinDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Pin, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = LanguageStrings.get("set_change_pin", currentLang), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    if (isPinActive) {
                        OutlinedButton(
                            onClick = {
                                appLockManager?.removePin()
                                isPinActive = false
                                Toast.makeText(context, "App lock PIN removed", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = UdharRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(text = LanguageStrings.get("btn_remove_pin", currentLang), fontSize = 12.sp)
                        }
                    }
                }

                if (appLockManager?.canAuthenticateWithBiometrics() == true) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = CardBorder)
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = LanguageStrings.get("biometric_unlock_label", currentLang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Switch(
                            checked = isBiometricActive,
                            onCheckedChange = { checked ->
                                isBiometricActive = checked
                                appLockManager?.setBiometricEnabled(checked)
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = LanguageStrings.get("block_screenshots_title", currentLang),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Text(
                            text = LanguageStrings.get("block_screenshots_desc", currentLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isBlockScreenshotsActive,
                        onCheckedChange = { checked ->
                            isBlockScreenshotsActive = checked
                            appLockManager?.setBlockScreenshotsEnabled(checked)
                            (context as? com.example.MainActivity)?.applyFlagSecure(checked)
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 3. DAILY BUSINESS REPORT (WORKMANAGER 8:30 PM)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(JamaGreen.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(LanguageStrings.get("daily_report_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(LanguageStrings.get("daily_report_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }

                    Switch(
                        checked = isDailyReportActive,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (!hasPermission) {
                                        showNotifRationaleDialog = true
                                    } else {
                                        isDailyReportActive = true
                                        SecureSettingsManager.setDailyReportEnabled(context, true)
                                        DailyReportScheduler.scheduleDailyReport(context)
                                        Toast.makeText(context, "Evening 8:30 PM report enabled", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    isDailyReportActive = true
                                    SecureSettingsManager.setDailyReportEnabled(context, true)
                                    DailyReportScheduler.scheduleDailyReport(context)
                                    Toast.makeText(context, "Evening 8:30 PM report enabled", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isDailyReportActive = false
                                SecureSettingsManager.setDailyReportEnabled(context, false)
                                DailyReportScheduler.scheduleDailyReport(context)
                                Toast.makeText(context, "Evening report disabled", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    LanguageStrings.get("daily_report_info", currentLang),
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val cal = java.util.Calendar.getInstance().apply {
                                    set(java.util.Calendar.HOUR_OF_DAY, 0)
                                    set(java.util.Calendar.MINUTE, 0)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }
                                val startOfDay = cal.timeInMillis
                                val todayInvoices = repository.invoices.value.filter { it.date >= startOfDay && !it.isCancelled }
                                val todaySales = todayInvoices.sumOf { it.totalAmount }
                                val todayCashIn = repository.cashbook.value.filter { it.date >= startOfDay && it.type == "IN" }.sumOf { it.amount }
                                val totalCashIn = repository.cashbook.value.filter { it.type == "IN" }.sumOf { it.amount }
                                val totalCashOut = repository.cashbook.value.filter { it.type == "OUT" }.sumOf { it.amount }
                                val cashInHand = (totalCashIn - totalCashOut).coerceAtLeast(0.0)
                                val todayCredit = repository.transactions.value.filter { it.date >= startOfDay && it.type == "GAVE" && !com.example.data.OpeningBalance.isOpeningNote(it.note) }.sumOf { it.amount }

                                val topDebtors = repository.customers.value.map {
                                    it.name to repository.getCustomerBalance(it.id)
                                }.filter { it.second > 0.01 }.sortedByDescending { it.second }.take(5)

                                val reportText = DailyReportWorker.generateReportText(
                                    todaySales = todaySales,
                                    todayInvoicesCount = todayInvoices.size,
                                    todayCollected = todayCashIn,
                                    todayCreditGiven = todayCredit,
                                    cashInHand = cashInHand,
                                    topDebtors = topDebtors
                                )

                                val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, reportText)
                                    setPackage("com.whatsapp")
                                }
                                try {
                                    context.startActivity(sendIntent)
                                } catch (e: Exception) {
                                    val chooser = Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, reportText)
                                        },
                                        "Share Business Report"
                                    )
                                    context.startActivity(chooser)
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(LanguageStrings.get("btn_share_whatsapp", currentLang), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            DailyReportScheduler.triggerImmediateReport(context)
                            Toast.makeText(context, "Triggered report test notification!", Toast.LENGTH_SHORT).show()
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(LanguageStrings.get("btn_test_notification", currentLang), fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==========================================
        // 4. ADVANCED SETTINGS (GST BILLS & LEGACY PDF)
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(LanguageStrings.get("advanced_settings_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                        Text(LanguageStrings.get("gst_bills_setting_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageStrings.get("gst_bills_setting_title", currentLang),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = LanguageStrings.get("gst_bills_setting_desc", currentLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isGstBillsActive,
                        onCheckedChange = { checked ->
                            isGstBillsActive = checked
                            SecureSettingsManager.setGstBillsEnabled(context, checked)
                            Toast.makeText(context, if (checked) "GST bills enabled" else "GST bills disabled", Toast.LENGTH_SHORT).show()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = LanguageStrings.get("old_pdf_generator_title", currentLang),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = LanguageStrings.get("old_pdf_generator_desc", currentLang),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = {
                            val latestInvoice = repository.invoices.value.firstOrNull()
                            if (latestInvoice != null) {
                                try {
                                    val pdfFile = PdfInvoiceGenerator.generateInvoicePdf(context, profile, latestInvoice)
                                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                        type = "application/pdf"
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            pdfFile
                                        )
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(shareIntent, "Share PDF Bill"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Error generating PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Create a bill first to export PDF", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(LanguageStrings.get("btn_export_sample_pdf", currentLang), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Guide & Walkthrough Card
        Card(
            modifier = Modifier.fillMaxWidth(),
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
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(ChipActiveBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.School, contentDescription = null, tint = PrimaryBlue, modifier = Modifier.size(22.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(LanguageStrings.get("app_guide_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(LanguageStrings.get("app_guide_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }

                    OutlinedButton(
                        onClick = onOpenGuide,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(LanguageStrings.get("btn_open_guide", currentLang), fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Clear All Data Card (Strictly requires typing DELETE and exports safety backup)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
            border = CardDefaults.outlinedCardBorder()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(LanguageStrings.get("clear_data_title", currentLang), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium, color = UdharRed)
                Spacer(modifier = Modifier.height(4.dp))
                Text(LanguageStrings.get("clear_data_desc", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = {
                        deleteInputText = ""
                        showClearDataDialog = true
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = UdharRed),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp), tint = UdharRed)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(LanguageStrings.get("clear_data_title", currentLang), fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // CSV Preview Dialog
    if (showCsvPreviewDialog) {
        val newCount = csvPreviews.count { it.canImport }
        val dupCount = csvPreviews.count { it.isDuplicate }
        val badCount = csvPreviews.count { it.error != null }
        val readable = csvPreviews.filter { it.error == null }
        val fileLena = readable.filter { it.balance > 0 }.sumOf { it.balance }
        val fileDena = readable.filter { it.balance < 0 }.sumOf { -it.balance }
        // problems first, so they are seen before the import button is tapped
        val shownRows = csvPreviews.sortedBy { it.error == null }

        AlertDialog(
            onDismissRequest = { showCsvPreviewDialog = false },
            title = { Text(LanguageStrings.get("csv_preview_title", currentLang), fontWeight = FontWeight.Bold) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(LanguageStrings.get("rows_found_prefix", currentLang) + " " + csvPreviews.size)
                            Text(LanguageStrings.get("new_customers_prefix", currentLang) + " $newCount", color = JamaGreen, fontWeight = FontWeight.Bold)
                            if (dupCount > 0) {
                                Text(LanguageStrings.get("existing_customers_prefix", currentLang) + " $dupCount", color = TextSecondary)
                            }
                            if (badCount > 0) {
                                Text(LanguageStrings.get("csv_bad_rows", currentLang) + " $badCount", color = UdharRed, fontWeight = FontWeight.Bold)
                            }
                            HorizontalDivider()
                            Text(LanguageStrings.get("csv_check_total", currentLang), fontSize = 12.sp, color = TextSecondary)
                            Text(text = "${LanguageStrings.get("party_lena", currentLang)}: ${formatIndianCurrency(fileLena)}", color = UdharRed, fontWeight = FontWeight.Bold)
                            Text(text = "${LanguageStrings.get("party_dena", currentLang)}: ${formatIndianCurrency(fileDena)}", color = JamaGreen, fontWeight = FontWeight.Bold)
                            HorizontalDivider()
                        }
                    }
                    items(shownRows) { item ->
                        val error = item.error
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(item.name.ifBlank { "-" }, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (item.phone.isNotBlank()) {
                                Text(item.phone, fontSize = 11.sp, color = TextSecondary)
                            }
                            if (error != null) {
                                Text(
                                    text = "${LanguageStrings.get("csv_line", currentLang)} ${item.line}: ${LanguageStrings.get(error, currentLang)}",
                                    color = UdharRed,
                                    fontSize = 12.sp
                                )
                            } else {
                                val side = if (item.balance >= 0) "party_lena" else "party_dena"
                                Text(
                                    text = "${LanguageStrings.get(side, currentLang)}: ${formatIndianCurrency(kotlin.math.abs(item.balance))}",
                                    color = if (item.balance >= 0) UdharRed else JamaGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                if (item.isDuplicate) {
                                    Text(LanguageStrings.get("csv_exists_skipped", currentLang), fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            progressMessage = LanguageStrings.get("progress_importing_csv", currentLang)
                            try {
                                val imported = withContext(Dispatchers.IO) {
                                    repository.importCustomersFromCsv(csvPreviews)
                                }
                                statusMessage = "✅ ${LanguageStrings.get("csv_imported", currentLang)} $imported"
                                showCsvPreviewDialog = false
                            } catch (e: Exception) {
                                statusMessage = "❌ Import failed: ${e.message}"
                            } finally {
                                progressMessage = null
                            }
                        }
                    },
                    enabled = newCount > 0 && progressMessage == null,
                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen)
                ) {
                    Text(LanguageStrings.get("btn_confirm_import", currentLang) + " ($newCount)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCsvPreviewDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Import Backup Summary Dialog
    if (showImportSummaryDialog && pendingImportValidation != null) {
        val validation = pendingImportValidation!!
        AlertDialog(
            onDismissRequest = { showImportSummaryDialog = false },
            title = { Text(LanguageStrings.get("restore_summary_title", currentLang), fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (validation.isValid) {
                        Text(LanguageStrings.get("restore_warning", currentLang), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(LanguageStrings.get("backup_customers_count", currentLang) + " ${validation.customerCount}", fontWeight = FontWeight.SemiBold)
                        Text(LanguageStrings.get("backup_transactions_count", currentLang) + " ${validation.transactionCount}", fontWeight = FontWeight.SemiBold)
                        Text(LanguageStrings.get("backup_inventory_count", currentLang) + " ${validation.inventoryCount}", fontWeight = FontWeight.SemiBold)
                        Text(LanguageStrings.get("backup_invoices_count", currentLang) + " ${validation.invoiceCount}", fontWeight = FontWeight.SemiBold)
                        Text(LanguageStrings.get("backup_cashbook_count", currentLang) + " ${validation.cashbookCount}", fontWeight = FontWeight.SemiBold)
                    } else {
                        Text(LanguageStrings.get("invalid_backup_file", currentLang), color = UdharRed, fontWeight = FontWeight.Bold)
                        Text(validation.errorMessage ?: "Unknown error in backup file", color = UdharRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                if (validation.isValid) {
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                progressMessage = LanguageStrings.get("progress_restoring", currentLang)
                                try {
                                    val result = withContext(Dispatchers.IO) {
                                        val uri = pendingImportUri
                                        if (pendingImportIsZip && uri != null) {
                                            context.contentResolver.openInputStream(uri)?.use { stream ->
                                                repository.restoreBackupFromStream(context, stream)
                                            } ?: ImportResult(success = false, errorMessage = "Could not open file")
                                        } else {
                                            repository.importDataFromJsonString(pendingImportJson)
                                        }
                                    }
                                    if (result.success) {
                                        val extra = if (result.repairedCount > 0) " (${result.repairedCount} recovered entries attached to 'Recovered entries')" else ""
                                        statusMessage = "✅ ${result.message}$extra"
                                        if (result.isLegacyJsonWithoutPhotos) {
                                            Toast.makeText(context, LanguageStrings.get("legacy_json_warning", currentLang), Toast.LENGTH_LONG).show()
                                        }
                                        showImportSummaryDialog = false
                                    } else {
                                        statusMessage = "❌ ${result.message}"
                                    }
                                } catch (e: Exception) {
                                    statusMessage = "❌ Restore failed: ${e.message}"
                                } finally {
                                    progressMessage = null
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text(LanguageStrings.get("btn_confirm_restore", currentLang), fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportSummaryDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Clear Data Confirmation Dialog (Requires typing DELETE and triggers safety backup)
    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(LanguageStrings.get("clear_data_title", currentLang), fontWeight = FontWeight.Bold, color = UdharRed) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        LanguageStrings.get("clear_data_desc", currentLang),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                    Text(
                        LanguageStrings.get("type_delete_hint", currentLang),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = UdharRed
                    )
                    OutlinedTextField(
                        value = deleteInputText,
                        onValueChange = { deleteInputText = it },
                        placeholder = { Text(LanguageStrings.get("delete_confirm_placeholder", currentLang)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDataDialog = false
                        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                        backupBeforeDeleteLauncher.launch("ghanshyam_backup_safety_$timeStamp.zip")
                    },
                    enabled = deleteInputText.trim().equals("DELETE", ignoreCase = false),
                    colors = ButtonDefaults.buttonColors(containerColor = UdharRed)
                ) {
                    Text(LanguageStrings.get("btn_backup_and_clear", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDataDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    // Set 4-Digit PIN Dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                pinError = null
            },
            title = {
                Text(
                    text = if (isPinActive) LanguageStrings.get("change_security_pin_title", currentLang) else LanguageStrings.get("set_security_pin_title", currentLang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        LanguageStrings.get("pin_setup_desc", currentLang),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = newPinText,
                        onValueChange = {
                            if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                                newPinText = it
                                pinError = null
                            }
                        },
                        label = { Text(LanguageStrings.get("new_pin_label", currentLang)) },
                        placeholder = { Text(LanguageStrings.get("pin_digits_placeholder", currentLang)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = confirmPinText,
                        onValueChange = {
                            if (it.length <= 4 && it.all { ch -> ch.isDigit() }) {
                                confirmPinText = it
                                pinError = null
                            }
                        },
                        label = { Text(LanguageStrings.get("confirm_pin_label", currentLang)) },
                        placeholder = { Text(LanguageStrings.get("pin_reenter_placeholder", currentLang)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (pinError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(pinError!!, color = UdharRed, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinText.length != 4) {
                            pinError = "PIN must be exactly 4 digits"
                            return@Button
                        }
                        if (newPinText != confirmPinText) {
                            pinError = "PINs do not match"
                            return@Button
                        }
                        appLockManager?.setPin(newPinText)
                        isPinActive = true
                        showPinDialog = false
                        pinError = null
                        Toast.makeText(context, "Security PIN set successfully!", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_save_pin", currentLang), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPinDialog = false
                    pinError = null
                }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    if (progressMessage != null) {
        OperationProgressDialog(message = progressMessage!!)
    }

    if (showNotifRationaleDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotifRationaleDialog = false
                isDailyReportActive = false
            },
            title = {
                Text(
                    text = LanguageStrings.get("notif_permission_rationale_title", currentLang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = LanguageStrings.get("notif_permission_rationale_msg", currentLang)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotifRationaleDialog = false
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_grant_permission", currentLang))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showNotifRationaleDialog = false
                        isDailyReportActive = false
                    }
                ) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }

    if (showNotifSettingsHintDialog) {
        AlertDialog(
            onDismissRequest = { showNotifSettingsHintDialog = false },
            title = {
                Text(
                    text = LanguageStrings.get("notif_permission_rationale_title", currentLang),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = LanguageStrings.get("notif_permission_settings_hint", currentLang)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showNotifSettingsHintDialog = false
                        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        } else {
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", context.packageName, null)
                            }
                            context.startActivity(fallback)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                ) {
                    Text(LanguageStrings.get("btn_open_settings", currentLang))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotifSettingsHintDialog = false }) {
                    Text(LanguageStrings.get("btn_cancel", currentLang))
                }
            }
        )
    }
}

@Preview(name = "Shop Profile Small Phone 130%", widthDp = 360, heightDp = 640, fontScale = 1.3f)
@Composable
private fun ShopProfileScreenSmallPhonePreview() {
    MyApplicationTheme {
        Surface(color = SoftBackground) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                AppCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = LanguageStrings.get("app_lock_title"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = LanguageStrings.get("app_lock_desc"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }
        }
    }
}
