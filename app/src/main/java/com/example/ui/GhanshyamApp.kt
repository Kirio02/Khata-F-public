package com.example.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.asImageBitmap
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.model.AppLanguage
import com.example.model.Customer
import com.example.ui.components.AppLoadingScreen
import com.example.ui.screens.*
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhanshyamApp(
    repository: AppRepository,
    appLockManager: com.example.ui.security.AppLockManager? = null,
    dailyReport: String? = null,
    onDailyReportConsumed: (() -> Unit)? = null
) {
    val isInitialLoading by repository.isInitialLoading.collectAsState()
    val isMigrationBlocked by repository.isMigrationBlocked.collectAsState()
    val migrationError by repository.migrationError.collectAsState()
    val migrationNotice by repository.migrationNotice.collectAsState()
    val hasCompletedOnboarding by repository.hasCompletedOnboarding.collectAsState()
    val currentLanguage by repository.language.collectAsState()
    val storeProfile by repository.storeProfile.collectAsState()

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showStartFreshDialog by rememberSaveable { mutableStateOf(false) }
    var startFreshInputText by rememberSaveable { mutableStateOf("") }

    val exportDamagedLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val rawJson = repository.getDamagedLegacyJson()
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(rawJson.toByteArray(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    android.util.Log.e("GhanshyamApp", "Export damaged data error: ${e.message}", e)
                }
            }
        }
    }

    // App Startup Loading Screen: prevents flashing OnboardingScreen for existing users
    if (isInitialLoading) {
        AppLoadingScreen(
            message = LanguageStrings.get("progress_loading", currentLanguage),
            storeProfile = storeProfile
        )
        return
    }

    var showLanguageMenu by rememberSaveable { mutableStateOf(false) }

    var currentTab by rememberSaveable { mutableStateOf(0) } // 0: Khata, 1: Billing, 2: Cashbook, 3: Shop
    var selectedCustomerId by rememberSaveable { mutableStateOf<String?>(null) }
    var showGuideModal by rememberSaveable { mutableStateOf(false) }
    val hasCompletedBatteryGuide by repository.hasCompletedBatteryGuide.collectAsState()
    var batteryPromptDismissed by rememberSaveable { mutableStateOf(false) }

    val customers by repository.customers.collectAsState()
    val selectedCustomer = customers.find { it.id == selectedCustomerId }

    val simpleMode by repository.simpleMode.collectAsState()
    var firstRunLanguageChosen by rememberSaveable { mutableStateOf(false) }

    // If onboarding is not completed yet, show FirstRunLanguageScreen -> BatteryAutoStartGuideScreen!
    if (!isMigrationBlocked && !hasCompletedOnboarding) {
        if (!firstRunLanguageChosen) {
            FirstRunLanguageScreen(
                onLanguageSelected = { lang ->
                    repository.setLanguage(lang)
                    firstRunLanguageChosen = true
                }
            )
            return
        } else {
            BatteryAutoStartGuideScreen(
                currentLang = currentLanguage,
                onDone = {
                    repository.completeBatteryGuide()
                    repository.completeOnboarding()
                }
            )
            return
        }
    }

    if (!isMigrationBlocked && showGuideModal) {
        BatteryAutoStartGuideScreen(
            currentLang = currentLanguage,
            onDone = { showGuideModal = false }
        )
        return
    }

    // Existing users on phones that kill background apps (Samsung, Xiaomi, Vivo, Oppo...) see the
    // setup guide once. Done = never again; Later = hidden until the app is opened again.
    if (!isMigrationBlocked &&
        !hasCompletedBatteryGuide &&
        !batteryPromptDismissed &&
        com.example.ui.util.BatteryOptimizationHelper.needsBackgroundSetup()
    ) {
        BatteryAutoStartGuideScreen(
            currentLang = currentLanguage,
            onSkip = { batteryPromptDismissed = true },
            onDone = { repository.completeBatteryGuide() }
        )
        return
    }

    var currentSubScreen by rememberSaveable { mutableStateOf<String?>(null) }

    // Sub-Screen BackHandlers
    if (currentSubScreen == "ACCOUNTANT_REPORTS") {
        BackHandler { currentSubScreen = null }
        AccountantReportsScreen(
            repository = repository,
            onBack = { currentSubScreen = null }
        )
        return
    }

    if (currentSubScreen == "BATTERY_GUIDE") {
        BackHandler { currentSubScreen = null }
        BatteryAutoStartGuideScreen(
            currentLang = currentLanguage,
            onDone = { currentSubScreen = null }
        )
        return
    }

    // Detail Screen BackHandler
    if (selectedCustomer != null) {
        BackHandler { selectedCustomerId = null }
        CustomerDetailScreen(
            customer = selectedCustomer,
            repository = repository,
            onBack = { selectedCustomerId = null }
        )
        return
    }

    val logoBitmap = remember(storeProfile.logoUri) {
        if (storeProfile.logoUri.isNotBlank()) {
            try {
                val uri = android.net.Uri.parse(storeProfile.logoUri)
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        } else null
    }

    // In landscape the screen is only ~340dp tall: give that room to the content.
    val showHeader = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp >= 480
    StatusBarIcons(light = showHeader)

    Scaffold(
        containerColor = SoftBackground,
        topBar = {
            if (showHeader) Surface(
                color = BrandCharcoal,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
                shadowElevation = 3.dp
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                        titleContentColor = OnBrand,
                        actionIconContentColor = OnBrand
                    ),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (logoBitmap != null) {
                                Image(
                                    bitmap = logoBitmap,
                                    contentDescription = "Logo",
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(BrandMaroon),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Store, contentDescription = null, tint = OnBrand, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = LanguageStrings.get("shree_ganeshay_namah", currentLanguage),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = GoldAccent,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    lineHeight = 11.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Text(
                                    text = storeProfile.storeName.ifBlank { "Ghanshyam Kids Wear" },
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = BrandSerifFontFamily),
                                    fontWeight = FontWeight.Bold,
                                    color = OnBrand,
                                    // The 64dp bar has no room for a second line, so the name follows the
                                    // phone font only until it would stop fitting on one.
                                    fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                                        minOf(15.sp.toPx(), 15.dp.toPx()).toSp()
                                    },
                                    letterSpacing = 0.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                    },
                actions = {
                    // Universal Language Selection Button (Hinglish default, Hindi, Gujarati, English)
                    Box {
                        FilledTonalButton(
                            onClick = { showLanguageMenu = true },
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = ChipInactiveBg,
                                contentColor = TextPrimary
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.widthIn(min = 44.dp)
                        ) {
                            Icon(Icons.Default.Translate, contentDescription = "Language: " + currentLanguage.displayName, modifier = Modifier.size(18.dp), tint = PrimaryBlue)
                        }

                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false },
                            modifier = Modifier.background(SurfaceWhite)
                        ) {
                            AppLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = "${lang.displayName} (${lang.nativeName})",
                                                fontWeight = if (lang == currentLanguage) FontWeight.Bold else FontWeight.Normal,
                                                color = if (lang == currentLanguage) PrimaryBlue else TextPrimary
                                            )
                                            if (lang == AppLanguage.HINGLISH) {
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Box(
                                                    modifier = Modifier
                                                        .clip(RoundedCornerShape(4.dp))
                                                        .background(ChipActiveBg)
                                                        .padding(horizontal = 4.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = LanguageStrings.get("default_label", currentLanguage),
                                                        fontSize = 9.sp,
                                                        color = PrimaryBlue,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    },
                                    onClick = {
                                        repository.setLanguage(lang)
                                        showLanguageMenu = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                }
            )
        }
    },
        bottomBar = {
            val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
            val showLabelsAlways = true

            // Adaptive bottom navigation respecting gesture navigation insets
            NavigationBar(
                containerColor = SurfaceWhite,
                tonalElevation = 8.dp,
                windowInsets = WindowInsets.navigationBars,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(width = 1.dp, color = CardBorder)
            ) {
                val navItems = listOf(
                    Triple(0, LanguageStrings.get("tab_khata", currentLanguage), Icons.Default.AccountBalanceWallet),
                    Triple(1, LanguageStrings.get("tab_billing", currentLanguage), Icons.Default.ReceiptLong),
                    Triple(2, LanguageStrings.get("tab_cashbook", currentLanguage), Icons.Default.Payments),
                    Triple(3, if (simpleMode) LanguageStrings.get("tab_more", currentLanguage) else LanguageStrings.get("tab_profile", currentLanguage), Icons.Default.Storefront)
                )

                navItems.forEach { (index, label, icon) ->
                    val isSelected = currentTab == index
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { currentTab = index },
                        icon = {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                modifier = Modifier.size(24.dp)
                            )
                        },
                        label = {
                            Text(
                                text = label,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                // Four tabs share ~340dp, so this label follows the phone font only up to 14dp.
                                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                                    minOf(12.sp.toPx(), 14.dp.toPx()).toSp()
                                },
                                letterSpacing = 0.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = if (isSelected) PrimaryBlue else TextSecondary
                            )
                        },
                        alwaysShowLabel = showLabelsAlways,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryBlue,
                            selectedTextColor = PrimaryBlue,
                            indicatorColor = ChipActiveBg,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary
                        )
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Each tab has its own Scaffold: without this they add the system bars a second time.
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            // Persistent Error Banner when migration failed or writes are blocked (cannot be simply dismissed)
            if (isMigrationBlocked || migrationError != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    colors = CardDefaults.cardColors(containerColor = UdharRed.copy(alpha = 0.12f)),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = migrationError ?: LanguageStrings.get("corrupt_data_banner", currentLanguage),
                            color = UdharRed,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { repository.retryMigration() },
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(LanguageStrings.get("btn_retry", currentLanguage), fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    exportDamagedLauncher.launch("ghanshyam_damaged_data_${System.currentTimeMillis()}.json")
                                },
                                modifier = Modifier.weight(1.3f)
                            ) {
                                Text(LanguageStrings.get("btn_export_damaged", currentLanguage), fontSize = 11.sp, maxLines = 1)
                            }
                            Button(
                                onClick = {
                                    startFreshInputText = ""
                                    showStartFreshDialog = true
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = UdharRed),
                                modifier = Modifier.weight(1.1f)
                            ) {
                                Text(LanguageStrings.get("btn_start_fresh", currentLanguage), fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }

            // Informational Notice Banner (e.g. orphan transactions recovered)
            if (migrationNotice != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.12f)),
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = migrationNotice ?: "",
                            color = PrimaryBlue,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { repository.dismissMigrationNotice() }) {
                            Text(LanguageStrings.get("btn_dismiss", currentLanguage), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (currentTab) {
                    0 -> KhataScreen(
                        repository = repository,
                        onCustomerClick = { selectedCustomerId = it.id },
                        onNavigateToBilling = { currentTab = 1 }
                    )
                    1 -> BillingScreen(repository = repository)
                    2 -> CashbookScreen(repository = repository)
                    3 -> ShopProfileScreen(
                        repository = repository,
                        appLockManager = appLockManager,
                        onOpenGuide = { showGuideModal = true },
                        onNavigateToAccountantReports = { currentSubScreen = "ACCOUNTANT_REPORTS" },
                        onOpenBatteryGuide = { currentSubScreen = "BATTERY_GUIDE" }
                    )
                }
            }
        }
    }

    if (!dailyReport.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = { onDailyReportConsumed?.invoke() },
            title = {
                Text(
                    text = LanguageStrings.get("daily_report_title", currentLanguage),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(text = dailyReport, fontSize = 15.sp)
                }
            },
            confirmButton = {
                Button(onClick = { onDailyReportConsumed?.invoke() }) {
                    Text(LanguageStrings.get("btn_dismiss", currentLanguage))
                }
            }
        )
    }

    if (showStartFreshDialog) {
        AlertDialog(
            onDismissRequest = {
                showStartFreshDialog = false
                startFreshInputText = ""
            },
            title = {
                Text(
                    text = LanguageStrings.get("start_fresh_dialog_title", currentLanguage),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = LanguageStrings.get("start_fresh_dialog_desc", currentLanguage),
                        color = TextSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = startFreshInputText,
                        onValueChange = { startFreshInputText = it },
                        label = { Text(LanguageStrings.get("type_delete_hint", currentLanguage)) },
                        placeholder = { Text(text = LanguageStrings.get("delete_confirmation_word", currentLanguage)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        repository.startFreshFromDamagedData()
                        showStartFreshDialog = false
                        startFreshInputText = ""
                    },
                    enabled = startFreshInputText.trim() == "DELETE",
                    colors = ButtonDefaults.buttonColors(containerColor = UdharRed)
                ) {
                    Text(LanguageStrings.get("btn_start_fresh", currentLanguage))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showStartFreshDialog = false
                        startFreshInputText = ""
                    }
                ) {
                    Text(LanguageStrings.get("btn_cancel", currentLanguage))
                }
            }
        )
    }
}

/** White clock and battery icons over the dark header; dark again on the white screens. */
@Composable
private fun StatusBarIcons(light: Boolean) {
    val view = androidx.compose.ui.platform.LocalView.current
    val window = (view.context as? android.app.Activity)?.window ?: return
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
    val dark = AppTheme.palette.isDark
    SideEffect { controller.isAppearanceLightStatusBars = !light && !dark }
    DisposableEffect(controller) {
        onDispose { controller.isAppearanceLightStatusBars = !AppTheme.palette.isDark }
    }
}
