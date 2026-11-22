package com.example.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.example.data.local.*
import com.example.model.*
import com.example.ui.util.PhoneUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.withContext

class AppRepository(
    val context: Context,
    val database: AppDatabase = AppDatabase.getInstance(context),
    val asyncInit: Boolean = true
) {
    private val prefs = context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE)
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Serialises settings read-modify-write + state assignment against the settings collector.
    private val settingsMutex = Mutex()

    private fun <T> settingsBlocking(block: suspend () -> T): T =
        runBlocking(Dispatchers.IO) { settingsMutex.withLock { block() } }

    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading: StateFlow<Boolean> = _isInitialLoading.asStateFlow()

    private val _migrationError = MutableStateFlow<String?>(null)
    val migrationError: StateFlow<String?> = _migrationError.asStateFlow()

    private val _isMigrationBlocked = MutableStateFlow(false)
    val isMigrationBlocked: StateFlow<Boolean> = _isMigrationBlocked.asStateFlow()

    private val _migrationNotice = MutableStateFlow<String?>(null)
    val migrationNotice: StateFlow<String?> = _migrationNotice.asStateFlow()

    // Backwards compatibility alias for corruptDataBanner
    val corruptDataBanner: StateFlow<String?> = _migrationError.asStateFlow()

    private val _customers = MutableStateFlow<List<Customer>>(emptyList())
    val customers: StateFlow<List<Customer>> = _customers.asStateFlow()

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _inventory = MutableStateFlow<List<InventoryItem>>(emptyList())
    val inventory: StateFlow<List<InventoryItem>> = _inventory.asStateFlow()

    private val _invoices = MutableStateFlow<List<Invoice>>(emptyList())
    val invoices: StateFlow<List<Invoice>> = _invoices.asStateFlow()

    private val _cashbook = MutableStateFlow<List<CashEntry>>(emptyList())
    val cashbook: StateFlow<List<CashEntry>> = _cashbook.asStateFlow()

    private val _storeProfile = MutableStateFlow<StoreProfile>(StoreProfile())
    val storeProfile: StateFlow<StoreProfile> = _storeProfile.asStateFlow()

    private val _language = MutableStateFlow<AppLanguage>(AppLanguage.HINGLISH)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    private val _selectedInvoiceTheme = MutableStateFlow<String>("PREMIUM_BLUE")
    val selectedInvoiceTheme: StateFlow<String> = _selectedInvoiceTheme.asStateFlow()

    private val _hasCompletedOnboarding = MutableStateFlow<Boolean>(false)
    val hasCompletedOnboarding: StateFlow<Boolean> = _hasCompletedOnboarding.asStateFlow()

    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()

    private val _drawerCounts = MutableStateFlow<List<DrawerCount>>(emptyList())
    val drawerCounts: StateFlow<List<DrawerCount>> = _drawerCounts.asStateFlow()

    private val _binEntries = MutableStateFlow<List<CashEntry>>(emptyList())
    val binEntries: StateFlow<List<CashEntry>> = _binEntries.asStateFlow()

    private val _cashOpeningBalance = MutableStateFlow(0.0)
    val cashOpeningBalance: StateFlow<Double> = _cashOpeningBalance.asStateFlow()

    private val _cashOpeningDate = MutableStateFlow(0L)
    val cashOpeningDate: StateFlow<Long> = _cashOpeningDate.asStateFlow()

    private val _bankOpeningBalance = MutableStateFlow(0.0)
    val bankOpeningBalance: StateFlow<Double> = _bankOpeningBalance.asStateFlow()

    private val _bankOpeningDate = MutableStateFlow(0L)
    val bankOpeningDate: StateFlow<Long> = _bankOpeningDate.asStateFlow()

    private val _bankDayChecks = MutableStateFlow<List<BankDayCheck>>(emptyList())
    val bankDayChecks: StateFlow<List<BankDayCheck>> = _bankDayChecks.asStateFlow()

    private val _deliveryTasks = MutableStateFlow<List<DeliveryTask>>(emptyList())
    val deliveryTasks: StateFlow<List<DeliveryTask>> = _deliveryTasks.asStateFlow()

    private val _simpleMode = MutableStateFlow<Boolean>(true)
    val simpleMode: StateFlow<Boolean> = _simpleMode.asStateFlow()

    private val _hasCompletedBatteryGuide = MutableStateFlow<Boolean>(false)
    val hasCompletedBatteryGuide: StateFlow<Boolean> = _hasCompletedBatteryGuide.asStateFlow()

    private val _lastBackupTimestamp = MutableStateFlow<Long>(0L)
    val lastBackupTimestamp: StateFlow<Long> = _lastBackupTimestamp.asStateFlow()

    init {
        val persistentError = prefs.getString("legacy_migration_error", null)
        if (!persistentError.isNullOrBlank()) {
            _migrationError.value = persistentError
            _isMigrationBlocked.value = true
        }

        if (asyncInit) {
            repositoryScope.launch(Dispatchers.IO) {
                try {
                    if (!_isMigrationBlocked.value) {
                        performOneTimeMigrationIfNeededInternal()
                    }
                    cleanExpiredBinEntries()
                    refreshAllInMemoryCachesInternal()
                } catch (e: Exception) {
                    Log.e("AppRepository", "Async startup initialization error: ${e.message}", e)
                    _migrationError.value = e.localizedMessage ?: "Startup error"
                    _isMigrationBlocked.value = true
                } finally {
                    _isInitialLoading.value = false
                }
                startRoomFlowCollectors()
            }
        } else {
            runBlocking(Dispatchers.IO) {
                if (!_isMigrationBlocked.value) {
                    performOneTimeMigrationIfNeededInternal()
                }
                cleanExpiredBinEntries()
                refreshAllInMemoryCachesInternal()
                _isInitialLoading.value = false
                startRoomFlowCollectors()
            }
        }
    }

    suspend fun awaitInitialization() {
        isInitialLoading.first { !it }
    }

    private fun checkWritesAllowed() {
        if (_isMigrationBlocked.value) {
            throw IllegalStateException("Database operations are blocked due to an unhandled migration error: ${_migrationError.value}. Please resolve the error first.")
        }
    }

    private suspend fun refreshAllInMemoryCachesInternal() {
        _customers.value = database.customerDao().getAllList().map { it.toDomain() }
        _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
        _inventory.value = database.inventoryDao().getAllList().map { it.toDomain() }
        _invoices.value = database.invoiceDao().getAllWithItemsList().map { it.toDomain() }
        _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
        _drawerCounts.value = database.drawerCountDao().getAllList().map { it.toDomain() }
        _bankDayChecks.value = database.bankDayCheckDao().getAllList().map { it.toDomain() }
        _deliveryTasks.value = database.deliveryTaskDao().getAllList().map { it.toDomain() }
        _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        _storeProfile.value = database.storeProfileDao().getProfileDirect()?.toDomain() ?: StoreProfile()
        val settings = database.appSettingsDao().getSettingsDirect()
        _language.value = when (settings?.language?.lowercase()) {
            "hi", "hindi" -> AppLanguage.HINDI
            "gu", "gujarati" -> AppLanguage.GUJARATI
            "en", "english" -> AppLanguage.ENGLISH
            else -> AppLanguage.HINGLISH
        }
        _selectedInvoiceTheme.value = settings?.theme ?: "PREMIUM_BLUE"
        _hasCompletedOnboarding.value = settings?.hasCompletedOnboarding ?: false
        _cashOpeningBalance.value = settings?.cashOpeningBalance ?: 0.0
        _cashOpeningDate.value = settings?.cashOpeningDate ?: 0L
        _bankOpeningBalance.value = settings?.bankOpeningBalance ?: 0.0
        _bankOpeningDate.value = settings?.bankOpeningDate ?: 0L
        _simpleMode.value = settings?.simpleMode ?: true
        _hasCompletedBatteryGuide.value = settings?.hasCompletedBatteryGuide ?: false
        _lastBackupTimestamp.value = settings?.lastBackupTimestamp ?: 0L
    }

    fun refreshAllInMemoryCaches() = runBlocking(Dispatchers.IO) {
        refreshAllInMemoryCachesInternal()
    }

    /**
     * A flow emission can be older than what a write has already put in the cache, so it is only
     * a signal to read again. If the cache changes during that read, read once more: the money
     * lists therefore never step back to an older state.
     */
    private suspend fun <T> MutableStateFlow<T>.replaceWithLatest(read: suspend () -> T) {
        while (true) {
            val before = value
            val latest = read()
            if (compareAndSet(before, latest)) return
        }
    }

    private fun startRoomFlowCollectors() {
        database.customerDao().getAll()
            .onEach { _customers.replaceWithLatest { database.customerDao().getAllList().map { it.toDomain() } } }
            .launchIn(repositoryScope)

        database.transactionDao().getAll()
            .onEach { _transactions.replaceWithLatest { database.transactionDao().getAllList().map { it.toDomain() } } }
            .launchIn(repositoryScope)

        database.inventoryDao().getAll()
            .map { list -> list.map { it.toDomain() } }
            .onEach { _inventory.value = it }
            .launchIn(repositoryScope)

        database.invoiceDao().getAllWithItems()
            .onEach { _invoices.replaceWithLatest { database.invoiceDao().getAllWithItemsList().map { it.toDomain() } } }
            .launchIn(repositoryScope)

        database.cashbookDao().getAll()
            .onEach { _cashbook.replaceWithLatest { database.cashbookDao().getAllList().map { it.toDomain() } } }
            .launchIn(repositoryScope)

        database.cashbookDao().getBinEntries()
            .onEach { _binEntries.replaceWithLatest { database.cashbookDao().getBinEntriesList().map { it.toDomain() } } }
            .launchIn(repositoryScope)

        database.drawerCountDao().getAll()
            .map { list -> list.map { it.toDomain() } }
            .onEach { _drawerCounts.value = it }
            .launchIn(repositoryScope)

        database.bankDayCheckDao().getAll()
            .map { list -> list.map { it.toDomain() } }
            .onEach { _bankDayChecks.value = it }
            .launchIn(repositoryScope)

        database.deliveryTaskDao().getAll()
            .map { list -> list.map { it.toDomain() } }
            .onEach { _deliveryTasks.value = it }
            .launchIn(repositoryScope)

        database.attachmentDao().getAll()
            .map { list -> list.map { it.toDomain() } }
            .onEach { _attachments.value = it }
            .launchIn(repositoryScope)

        database.storeProfileDao().getProfile()
            .map { it?.toDomain() ?: StoreProfile() }
            .onEach { _storeProfile.value = it }
            .launchIn(repositoryScope)

        database.appSettingsDao().getSettings()
            .onEach { emitted ->
                // Re-read the current row instead of trusting the emitted snapshot. Room delivers
                // emissions asynchronously, so a stale snapshot from an earlier write could otherwise
                // overwrite a newer value that a setter (e.g. setSimpleMode) has just applied.
                // Hold the settings lock across read + assign so a setter can't write in between
                // and then have its fresh value overwritten by this stale read.
                settingsMutex.withLock {
                val settings = if (emitted != null) {
                    database.appSettingsDao().getSettingsDirect() ?: emitted
                } else {
                    null
                }
                if (settings != null) {
                    _language.value = when (settings.language.lowercase()) {
                        "hi", "hindi" -> AppLanguage.HINDI
                        "gu", "gujarati" -> AppLanguage.GUJARATI
                        "en", "english" -> AppLanguage.ENGLISH
                        else -> AppLanguage.HINGLISH
                    }
                    _selectedInvoiceTheme.value = settings.theme
                    _hasCompletedOnboarding.value = settings.hasCompletedOnboarding
                    _cashOpeningBalance.value = settings.cashOpeningBalance
                    _cashOpeningDate.value = settings.cashOpeningDate
                    _bankOpeningBalance.value = settings.bankOpeningBalance
                    _bankOpeningDate.value = settings.bankOpeningDate
                    _simpleMode.value = settings.simpleMode
                    _hasCompletedBatteryGuide.value = settings.hasCompletedBatteryGuide
                    _lastBackupTimestamp.value = settings.lastBackupTimestamp
                }
                }
            }
            .launchIn(repositoryScope)
    }

    // ==========================================
    // ONE-TIME MIGRATION (SharedPreferences -> Room)
    // ==========================================

    private suspend fun performOneTimeMigrationIfNeededInternal() {
        if (!prefs.contains("app_saved_data")) {
            ensureDefaultProfileAndSettings()
            return
        }

        val savedJson = prefs.getString("app_saved_data", null)
        if (savedJson.isNullOrBlank()) {
            prefs.edit().putString("app_saved_data_migrated", "").remove("app_saved_data").commit()
            ensureDefaultProfileAndSettings()
            return
        }

        try {
            val json = JSONObject(savedJson)
            val tempCustomers = mutableListOf<CustomerEntity>()
            val rawTransactions = mutableListOf<TransactionEntity>()
            val tempInventory = mutableListOf<InventoryItemEntity>()
            val tempInvoices = mutableListOf<InvoiceEntity>()
            val tempInvoiceItems = mutableListOf<InvoiceItemEntity>()
            val tempCashbook = mutableListOf<CashEntryEntity>()

            if (json.has("customers")) {
                val arr = json.getJSONArray("customers")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tempCustomers.add(CustomerEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "").trim().ifBlank { "Party" },
                        phone = PhoneUtil.normalize(o.optString("phone", "").trim()),
                        address = o.optString("address", "").trim(),
                        gstin = o.optString("gstin", "").trim(),
                        type = o.optString("type", "CUSTOMER").uppercase(),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            if (json.has("transactions")) {
                val arr = json.getJSONArray("transactions")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val txId = o.optString("id", UUID.randomUUID().toString())
                    val custId = o.optString("customerId", "").trim()
                    val amt = o.optDouble("amount", 0.0)
                    val txType = o.optString("type", "GAVE")
                    val dt = o.optLong("date", System.currentTimeMillis())
                    val oldIsCancelled = o.optBoolean("isCancelled", false)

                    rawTransactions.add(TransactionEntity(
                        id = txId,
                        customerId = custId,
                        amount = amt,
                        type = txType,
                        note = o.optString("note", ""),
                        date = dt,
                        paymentMode = o.optString("paymentMode", "CASH"),
                        linkedToTxId = if (o.has("linkedToTxId") && !o.isNull("linkedToTxId")) o.getString("linkedToTxId") else null,
                        isCompleted = o.optBoolean("isCompleted", oldIsCancelled),
                        billNumber = o.optString("billNumber", ""),
                        dueDate = o.optLong("dueDate", 0L)
                    ))

                    // Map old isCancelled = true on credit bills into linked payments
                    if (oldIsCancelled && txType == "GAVE") {
                        rawTransactions.add(TransactionEntity(
                            id = UUID.randomUUID().toString(),
                            customerId = custId,
                            amount = amt,
                            type = "GOT",
                            note = "Settled (migrated)",
                            date = dt,
                            paymentMode = "CASH",
                            linkedToTxId = txId,
                            isCompleted = true,
                            billNumber = ""
                        ))
                    }
                }
            }

            // Tolerant migration: attach transactions whose customerId is missing or unknown to "Recovered entries"
            val validCustIds = tempCustomers.map { it.id }.toSet()
            val repairedTransactions = mutableListOf<TransactionEntity>()
            var orphanCount = 0
            var recoveredCustomer: CustomerEntity? = tempCustomers.find { it.name.equals("Recovered entries", ignoreCase = true) }

            for (tx in rawTransactions) {
                if (tx.customerId.isBlank() || !validCustIds.contains(tx.customerId)) {
                    orphanCount++
                    if (recoveredCustomer == null) {
                        recoveredCustomer = CustomerEntity(
                            id = UUID.randomUUID().toString(),
                            name = "Recovered entries",
                            phone = "",
                            address = "Auto-created for unassigned legacy transactions"
                        )
                        tempCustomers.add(recoveredCustomer)
                    }
                    repairedTransactions.add(tx.copy(customerId = recoveredCustomer.id))
                } else {
                    repairedTransactions.add(tx)
                }
            }

            if (json.has("inventory")) {
                val arr = json.getJSONArray("inventory")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sizesArr = o.optJSONArray("sizes")
                    val sizes = mutableListOf<String>()
                    if (sizesArr != null) {
                        for (j in 0 until sizesArr.length()) {
                            sizes.add(sizesArr.getString(j))
                        }
                    }
                    tempInventory.add(InventoryItemEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "").trim().ifBlank { "Item" },
                        itemCode = o.optString("itemCode", "").trim(),
                        salePrice = o.optDouble("salePrice", 0.0),
                        purchasePrice = o.optDouble("purchasePrice", 0.0),
                        stock = o.optInt("stock", 0),
                        category = o.optString("category", "General").trim().ifBlank { "General" },
                        sizes = sizes,
                        gstPercent = o.optDouble("gstPercent", 0.0)
                    ))
                }
            }

            if (json.has("invoices")) {
                val arr = json.getJSONArray("invoices")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val invId = o.optString("id", UUID.randomUUID().toString())
                    val themeName = prefs.getString("invoice_theme", null)
                        ?: o.optString("themeName", "PREMIUM_BLUE")
                    tempInvoices.add(InvoiceEntity(
                        id = invId,
                        billNumber = o.optString("billNumber", "INV-${System.currentTimeMillis() % 100000}"),
                        date = o.optLong("date", System.currentTimeMillis()),
                        customerName = o.optString("customerName", ""),
                        customerPhone = o.optString("customerPhone", ""),
                        subtotal = o.optDouble("subtotal", 0.0),
                        gstAmount = o.optDouble("gstAmount", 0.0),
                        discount = o.optDouble("discount", 0.0),
                        additionalCharges = o.optDouble("additionalCharges", 0.0),
                        totalAmount = o.optDouble("totalAmount", 0.0),
                        paymentMode = o.optString("paymentMode", "CASH"),
                        stateOfSupply = o.optString("stateOfSupply", "Gujarat"),
                        gstin = o.optString("gstin", ""),
                        notes = o.optString("notes", ""),
                        themeName = themeName
                    ))

                    val itemsArr = o.optJSONArray("items")
                    if (itemsArr != null) {
                        for (j in 0 until itemsArr.length()) {
                            val io = itemsArr.getJSONObject(j)
                            tempInvoiceItems.add(InvoiceItemEntity(
                                id = io.optString("id", UUID.randomUUID().toString()),
                                invoiceId = invId,
                                name = io.optString("name", "Item"),
                                quantity = io.optInt("quantity", 1),
                                unitPrice = io.optDouble("unitPrice", 0.0),
                                gstPercent = io.optDouble("gstPercent", 0.0),
                                total = io.optDouble("total", 0.0)
                            ))
                        }
                    }
                }
            }

            if (json.has("cashbook")) {
                val arr = json.getJSONArray("cashbook")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    tempCashbook.add(CashEntryEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        type = o.optString("type", "IN"),
                        amount = o.optDouble("amount", 0.0),
                        category = o.optString("category", ""),
                        remark = o.optString("remark", ""),
                        date = o.optLong("date", System.currentTimeMillis()),
                        paymentMode = o.optString("paymentMode", "CASH"),
                        linkedId = if (o.has("linkedId") && !o.isNull("linkedId")) o.getString("linkedId") else null,
                        source = o.optString("source", "manual")
                    ))
                }
            }

            val storeProfile = StoreProfileEntity(
                id = 1,
                storeName = prefs.getString("store_name", "") ?: "",
                address = prefs.getString("store_address", "") ?: "",
                phone = prefs.getString("store_phone", "") ?: "",
                upiId = prefs.getString("store_upi", "") ?: "",
                gstin = prefs.getString("store_gstin", "") ?: "",
                ownerName = prefs.getString("owner_name", "") ?: "",
                state = prefs.getString("store_state", "Gujarat") ?: "Gujarat"
            )

            val invoiceThemePref = prefs.getString("invoice_theme", null)
                ?: prefs.getString("selected_invoice_theme", "PREMIUM_BLUE")
                ?: "PREMIUM_BLUE"

            val appSettings = AppSettingsEntity(
                id = 1,
                language = prefs.getString("selected_language", "hinglish") ?: "hinglish",
                theme = invoiceThemePref,
                hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false)
            )

            // Insert everything inside ONE atomic transaction
            database.withTransaction {
                database.customerDao().insertAll(tempCustomers)
                database.transactionDao().insertAll(repairedTransactions)
                database.inventoryDao().insertAll(tempInventory)
                database.invoiceDao().insertInvoices(tempInvoices)
                database.invoiceDao().insertItems(tempInvoiceItems)
                database.cashbookDao().insertAll(tempCashbook)
                database.storeProfileDao().insertOrUpdate(storeProfile)
                database.appSettingsDao().insertOrUpdate(appSettings)
            }

            // Only after success rename the old key to app_saved_data_migrated (do not delete it)
            prefs.edit()
                .putString("app_saved_data_migrated", savedJson)
                .remove("app_saved_data")
                .remove("legacy_migration_error")
                .commit()

            _migrationError.value = null
            _isMigrationBlocked.value = false

            if (orphanCount > 0) {
                _migrationNotice.value = "Migration recovered $orphanCount unlinked transaction(s) and attached them to 'Recovered entries'."
            }

            Log.i("AppRepository", "Migration to Room succeeded.")
        } catch (e: Exception) {
            Log.e("AppRepository", "Error during one-time migration: ${e.message}", e)
            val timestamp = System.currentTimeMillis()
            prefs.edit()
                .putString("legacy_migration_error", e.localizedMessage ?: "Migration error")
                .putString("app_saved_data_corrupt_$timestamp", savedJson)
                .commit()
            // CRITICAL: keep app_saved_data UNTOUCHED
            _migrationError.value = e.localizedMessage ?: "Data migration error detected"
            _isMigrationBlocked.value = true
        }
    }

    fun performOneTimeMigrationIfNeeded() = runBlocking(Dispatchers.IO) {
        performOneTimeMigrationIfNeededInternal()
    }

    fun retryMigration() {
        repositoryScope.launch(Dispatchers.IO) {
            _migrationError.value = null
            _isMigrationBlocked.value = false
            prefs.edit().remove("legacy_migration_error").commit()
            performOneTimeMigrationIfNeededInternal()
            refreshAllInMemoryCachesInternal()
        }
    }

    fun getDamagedLegacyJson(): String {
        val currentSaved = prefs.getString("app_saved_data", null)
        if (!currentSaved.isNullOrBlank()) return currentSaved
        val corruptKey = prefs.all.keys.filter { it.startsWith("app_saved_data_corrupt_") }.maxOrNull()
        if (corruptKey != null) {
            return prefs.getString(corruptKey, "") ?: ""
        }
        return ""
    }

    fun startFreshFromDamagedData() {
        val raw = getDamagedLegacyJson()
        val timestamp = System.currentTimeMillis()
        prefs.edit()
            .putString("app_saved_data_discarded_$timestamp", raw)
            .remove("app_saved_data")
            .remove("legacy_migration_error")
            .commit()
        _migrationError.value = null
        _isMigrationBlocked.value = false
        clearAllData()
    }

    fun dismissMigrationNotice() {
        _migrationNotice.value = null
    }

    private suspend fun ensureDefaultProfileAndSettings() {
        val existingProfile = database.storeProfileDao().getProfileDirect()
        if (existingProfile == null) {
            database.storeProfileDao().insertOrUpdate(
                StoreProfileEntity(
                    id = 1,
                    storeName = prefs.getString("store_name", "") ?: "",
                    address = prefs.getString("store_address", "") ?: "",
                    phone = prefs.getString("store_phone", "") ?: "",
                    upiId = prefs.getString("store_upi", "") ?: "",
                    gstin = prefs.getString("store_gstin", "") ?: "",
                    ownerName = prefs.getString("owner_name", "") ?: "",
                    state = prefs.getString("store_state", "Gujarat") ?: "Gujarat"
                )
            )
        }

        val existingSettings = database.appSettingsDao().getSettingsDirect()
        if (existingSettings == null) {
            val invoiceThemePref = prefs.getString("invoice_theme", null)
                ?: prefs.getString("selected_invoice_theme", "PREMIUM_BLUE")
                ?: "PREMIUM_BLUE"

            database.appSettingsDao().insertOrUpdate(
                AppSettingsEntity(
                    id = 1,
                    language = prefs.getString("selected_language", "hinglish") ?: "hinglish",
                    theme = invoiceThemePref,
                    hasCompletedOnboarding = prefs.getBoolean("has_completed_onboarding", false)
                )
            )
        }
    }

    fun dismissCorruptDataBanner() {
        _migrationNotice.value = null
    }

    fun startFreshAfterCorruption() {
        startFreshFromDamagedData()
    }

    // ==========================================
    // STORE PROFILE & SETTINGS
    // ==========================================

    fun updateStoreProfile(
        storeName: String,
        address: String,
        phone: String,
        upiId: String,
        gstin: String,
        ownerName: String,
        state: String = "Gujarat",
        logoUri: String = "",
        email: String = ""
    ) {
        checkWritesAllowed()
        val current = _storeProfile.value
        val profile = StoreProfileEntity(
            id = 1,
            storeName = storeName,
            address = address,
            phone = phone,
            email = if (email.isNotBlank()) email else current.email,
            upiId = upiId,
            gstin = gstin,
            ownerName = ownerName,
            state = state,
            logoUri = if (logoUri.isNotBlank()) logoUri else current.logoUri
        )
        runBlocking(Dispatchers.IO) {
            database.storeProfileDao().insertOrUpdate(profile)
            _storeProfile.value = profile.toDomain()
        }
    }

    fun setLanguage(newLanguage: AppLanguage) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(language = newLanguage.code))
            _language.value = newLanguage
        }
    }

    fun setInvoiceTheme(theme: String) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(theme = theme))
            _selectedInvoiceTheme.value = theme
        }
    }

    fun completeOnboarding() {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(hasCompletedOnboarding = true))
            _hasCompletedOnboarding.value = true
        }
    }

    fun setSimpleMode(enabled: Boolean) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(simpleMode = enabled))
            _simpleMode.value = enabled
        }
    }

    fun completeBatteryGuide() {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(hasCompletedBatteryGuide = true))
            _hasCompletedBatteryGuide.value = true
        }
    }

    fun recordBackupCompleted(timestamp: Long = System.currentTimeMillis()) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity(id = 1)
            database.appSettingsDao().insertOrUpdate(current.copy(lastBackupTimestamp = timestamp))
            _lastBackupTimestamp.value = timestamp
        }
    }

    fun addDeliveryTask(
        partyName: String,
        partyId: String? = null,
        note: String = "",
        date: Long = System.currentTimeMillis()
    ): DeliveryTask {
        checkWritesAllowed()
        val entity = DeliveryTaskEntity(
            partyName = partyName.trim(),
            partyId = partyId,
            note = note.trim(),
            date = date,
            isCompleted = false,
            completedAt = 0L,
            createdAt = System.currentTimeMillis()
        )
        runBlocking(Dispatchers.IO) {
            database.deliveryTaskDao().insert(entity)
            val all = database.deliveryTaskDao().getAllList().map { it.toDomain() }
            _deliveryTasks.value = all
        }
        return entity.toDomain()
    }

    fun toggleDeliveryTask(id: String, isCompleted: Boolean) {
        checkWritesAllowed()
        val compAt = if (isCompleted) System.currentTimeMillis() else 0L
        runBlocking(Dispatchers.IO) {
            database.deliveryTaskDao().setCompleted(id, isCompleted, compAt)
            val all = database.deliveryTaskDao().getAllList().map { it.toDomain() }
            _deliveryTasks.value = all
        }
    }

    fun deleteDeliveryTask(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.deliveryTaskDao().deleteById(id)
            val all = database.deliveryTaskDao().getAllList().map { it.toDomain() }
            _deliveryTasks.value = all
        }
    }

    fun getCashBalance(): Double {
        val entries = cashbook.value.filter { it.bookType == "CASH" && !it.isDeleted }
        val ins = entries.filter { it.type == "IN" }.sumOf { it.amount }
        val outs = entries.filter { it.type == "OUT" }.sumOf { it.amount }
        return cashOpeningBalance.value + ins - outs
    }

    fun getBankBalance(): Double {
        val entries = cashbook.value.filter { it.bookType == "BANK" && !it.isDeleted }
        val ins = entries.filter { it.type == "IN" }.sumOf { it.amount }
        val outs = entries.filter { it.type == "OUT" }.sumOf { it.amount }
        return bankOpeningBalance.value + ins - outs
    }

    fun getTodaySummary(): Triple<Double, Double, Int> {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 23)
        cal.set(java.util.Calendar.MINUTE, 59)
        cal.set(java.util.Calendar.SECOND, 59)
        cal.set(java.util.Calendar.MILLISECOND, 999)
        val endOfDay = cal.timeInMillis

        // Opening balances are old dues carried over, not today's business.
        val todayTxs = transactions.value.filter { it.date in startOfDay..endOfDay && !OpeningBalance.isOpeningNote(it.note) }
        val todayGot = todayTxs.filter { it.type == "GOT" }.sumOf { it.amount }
        val todayGave = todayTxs.filter { it.type == "GAVE" }.sumOf { it.amount }

        // Overdue count: parties with a bill that is still unpaid AND past its due date (or older than 15 days).
        val txsByCustomer = transactions.value.groupBy { it.customerId }
        val dueOrOverdueCount = customers.value.count { cust ->
            cust.type != "SUPPLIER" && OverdueLogic.isOverdue(txsByCustomer[cust.id] ?: emptyList())
        }

        return Triple(todayGot, todayGave, dueOrOverdueCount)
    }


    // ==========================================
    // CUSTOMER OPERATIONS
    // ==========================================

    fun addCustomer(
        name: String,
        phone: String,
        address: String = "",
        gstin: String = "",
        type: String = "CUSTOMER"
    ): Customer {
        checkWritesAllowed()
        val normalizedPhone = PhoneUtil.normalize(phone)
        val newCustomer = CustomerEntity(
            name = name.trim(),
            phone = normalizedPhone,
            address = address.trim(),
            gstin = gstin.trim(),
            type = type.uppercase()
        )
        runBlocking(Dispatchers.IO) {
            database.customerDao().insert(newCustomer)
            _customers.value = database.customerDao().getAllList().map { it.toDomain() }
        }
        return newCustomer.toDomain()
    }

    /**
     * Adds many parties in one go (contact import) with a single background write, so picking
     * hundreds of contacts does not freeze the screen. Blank names and phone numbers that are
     * already in the khata are skipped. Returns how many parties were added.
     */
    suspend fun addCustomersBulk(
        contacts: List<Pair<String, String>>,
        type: String = "CUSTOMER"
    ): Int {
        checkWritesAllowed()
        // NonCancellable: once the write starts, the in-memory list must be refreshed even if the screen closes.
        return withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val knownPhones = database.customerDao().getAllList()
                .map { PhoneUtil.normalize(it.phone) }
                .filter { it.isNotBlank() }
                .toMutableSet()
            val toInsert = mutableListOf<CustomerEntity>()
            for ((rawName, rawPhone) in contacts) {
                val cleanName = rawName.trim()
                if (cleanName.isBlank()) continue
                val normalizedPhone = PhoneUtil.normalize(rawPhone)
                if (normalizedPhone.isNotBlank() && !knownPhones.add(normalizedPhone)) continue
                toInsert.add(
                    CustomerEntity(
                        name = cleanName,
                        phone = normalizedPhone,
                        type = type.uppercase()
                    )
                )
            }
            if (toInsert.isNotEmpty()) {
                database.customerDao().insertAll(toInsert)
            }
            _customers.value = database.customerDao().getAllList().map { it.toDomain() }
            toInsert.size
        }
    }

    fun updateCustomer(
        id: String,
        name: String,
        phone: String,
        address: String = "",
        gstin: String = "",
        type: String = "CUSTOMER"
    ) {
        checkWritesAllowed()
        val normalizedPhone = PhoneUtil.normalize(phone)
        runBlocking(Dispatchers.IO) {
            val current = database.customerDao().getById(id)
            if (current != null) {
                database.customerDao().update(
                    current.copy(
                        name = name.trim(),
                        phone = normalizedPhone,
                        address = address.trim(),
                        gstin = gstin.trim(),
                        type = type.uppercase()
                    )
                )
                _customers.value = database.customerDao().getAllList().map { it.toDomain() }
            }
        }
    }

    fun deleteCustomer(customerId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val txs = database.transactionDao().getByCustomerId(customerId)
            val txIds = txs.map { it.id }
            val partyAtts = database.attachmentDao().getByOwnerList("PARTY", customerId).map { it.toDomain() }
            val txAtts = if (txIds.isNotEmpty()) database.attachmentDao().getByOwnerIdsList(txIds).map { it.toDomain() } else emptyList()
            (partyAtts + txAtts).forEach { att -> com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att) }

            database.withTransaction {
                database.attachmentDao().deleteByOwner("PARTY", customerId)
                if (txIds.isNotEmpty()) {
                    database.attachmentDao().deleteByOwnerIds("ENTRY", txIds)
                    database.cashbookDao().deleteByLinkedIds(txIds)
                }
                database.transactionDao().deleteByCustomerId(customerId)
                database.customerDao().deleteById(customerId)
            }
            _customers.value = database.customerDao().getAllList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }


    fun getCustomerBalance(customerId: String): Double {
        val txs = transactions.value.filter { it.customerId == customerId }
        val gave = txs.filter { it.type == "GAVE" }.sumOf { it.amount }
        val got = txs.filter { it.type == "GOT" }.sumOf { it.amount }
        return gave - got
    }

    fun getTotalReceivable(): Double {
        return customers.value.sumOf { getCustomerBalance(it.id).coerceAtLeast(0.0) }
    }

    fun getTotalPayable(): Double {
        return customers.value.sumOf { (-getCustomerBalance(it.id)).coerceAtLeast(0.0) }
    }

    /**
     * How much of each credit bill is paid, derived from ALL payments of the party:
     * payments linked to a bill count for that bill first; every other payment is applied
     * to the oldest unpaid bills first. Nothing is stored; it is recalculated from the entries.
     */
    fun computeBillAllocations(customerId: String): Map<String, Double> = allocatePayments(
        partyTxs = transactions.value.filter { it.customerId == customerId },
        isSupplier = customers.value.firstOrNull { it.id == customerId }?.type == "SUPPLIER"
    )

    /**
     * For writing payments. The in-memory lists can briefly lag the database, so sizing a payment
     * from them skipped bills or took a payment twice; this reads inside the caller's transaction.
     */
    private suspend fun outstandingInDb(bill: TransactionEntity): Double {
        val partyTxs = database.transactionDao().getAllList()
            .filter { it.customerId == bill.customerId }
            .map { it.toDomain() }
        val isSupplier = database.customerDao().getById(bill.customerId)?.type == "SUPPLIER"
        val received = allocatePayments(partyTxs, isSupplier)[bill.id] ?: 0.0
        return (bill.amount - received).coerceAtLeast(0.0)
    }

    private fun allocatePayments(partyTxs: List<Transaction>, isSupplier: Boolean): Map<String, Double> {
        val billType = if (isSupplier) "GOT" else "GAVE"
        val paymentType = if (isSupplier) "GAVE" else "GOT"
        val bills = partyTxs.filter { it.type == billType && it.linkedToTxId == null }.sortedBy { it.date }
        val billIds = bills.map { it.id }.toHashSet()
        val payments = partyTxs.filter { it.type == paymentType }

        val result = HashMap<String, Double>()
        var pool = 0.0
        val linkedByBill = payments
            .filter { it.linkedToTxId != null && it.linkedToTxId in billIds }
            .groupBy { it.linkedToTxId!! }
        for (bill in bills) {
            val linked = linkedByBill[bill.id]?.sumOf { it.amount } ?: 0.0
            val applied = minOf(linked, bill.amount)
            result[bill.id] = applied
            pool += (linked - applied)
        }
        pool += payments
            .filter { it.linkedToTxId == null || it.linkedToTxId !in billIds }
            .sumOf { it.amount }
        for (bill in bills) {
            if (pool <= 0.0) break
            val room = bill.amount - (result[bill.id] ?: 0.0)
            if (room > 0.0) {
                val take = minOf(room, pool)
                result[bill.id] = (result[bill.id] ?: 0.0) + take
                pool -= take
            }
        }
        return result
    }

    fun getBillReceivedAmount(billId: String): Double {
        val bill = transactions.value.firstOrNull { it.id == billId } ?: return 0.0
        return computeBillAllocations(bill.customerId)[billId] ?: 0.0
    }

    fun getBillOutstandingAmount(bill: Transaction): Double {
        val received = getBillReceivedAmount(bill.id)
        return (bill.amount - received).coerceAtLeast(0.0)
    }

    enum class BillStatus {
        PENDING,
        PARTLY_RECEIVED,
        RECEIVED
    }

    fun getBillStatus(bill: Transaction): BillStatus {
        val received = getBillReceivedAmount(bill.id)
        return when {
            received >= bill.amount - 0.001 -> BillStatus.RECEIVED
            received > 0.001 -> BillStatus.PARTLY_RECEIVED
            else -> BillStatus.PENDING
        }
    }

    // ==========================================
    // TRANSACTION OPERATIONS
    // ==========================================

    fun addTransaction(
        customerId: String,
        amount: Double,
        type: String, // "GAVE" or "GOT"
        note: String = "",
        billNumber: String = "",
        paymentMode: String = "CASH",
        linkedToTxId: String? = null,
        isCompleted: Boolean = false,
        date: Long = System.currentTimeMillis(),
        onlyInKhata: Boolean = false,
        dueDate: Long = 0L,
        cashGivenOut: Boolean = false
    ): Transaction {
        checkWritesAllowed()
        val newTx = TransactionEntity(
            customerId = customerId,
            amount = amount,
            type = type,
            note = note,
            date = date,
            paymentMode = paymentMode,
            linkedToTxId = linkedToTxId,
            isCompleted = isCompleted,
            billNumber = billNumber,
            dueDate = dueDate
        )
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.transactionDao().insert(newTx)
                if (!onlyInKhata) {
                    val customer = database.customerDao().getById(customerId)
                    val isSupplier = customer?.type == "SUPPLIER"
                    val isPayment = if (isSupplier) type == "GAVE" else type == "GOT"
                    if (isPayment) {
                        val cashType = if (isSupplier) "OUT" else "IN"
                        val modeUpper = paymentMode.uppercase()
                        val bookType = if (modeUpper == "CASH") "CASH" else "BANK"
                        val partyLabel = customer?.name ?: "Party"
                        val defaultRemark = if (cashType == "IN") "Received from $partyLabel" else "Paid to $partyLabel"
                        val cashEntry = CashEntryEntity(
                            type = cashType,
                            amount = amount,
                            category = "Khata Payment",
                            remark = if (note.isNotBlank()) note else defaultRemark,
                            date = date,
                            paymentMode = paymentMode,
                            linkedId = newTx.id,
                            source = "khata",
                            bookType = bookType,
                            partyName = partyLabel,
                            partyId = customerId
                        )
                        database.cashbookDao().insert(cashEntry)
                    } else if (cashGivenOut && type == "GAVE" && !isSupplier) {
                        // Customer was handed real cash: record it as money going out.
                        val modeUpper = paymentMode.uppercase()
                        val partyLabel = customer?.name ?: "Party"
                        database.cashbookDao().insert(
                            CashEntryEntity(
                                type = "OUT",
                                amount = amount,
                                category = "Khata Payment",
                                remark = if (note.isNotBlank()) note else "Cash given to $partyLabel",
                                date = date,
                                paymentMode = paymentMode,
                                linkedId = newTx.id,
                                source = "khata",
                                bookType = if (modeUpper == "CASH") "CASH" else "BANK",
                                partyName = partyLabel,
                                partyId = customerId
                            )
                        )
                    }
                }
            }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
        return newTx.toDomain()
    }

    fun markBillReceived(
        billId: String,
        amount: Double,
        paymentMode: String = "CASH",
        date: Long = System.currentTimeMillis(),
        addToCashbook: Boolean = true,
        onlyInKhata: Boolean = false
    ): Transaction? {
        checkWritesAllowed()
        return runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val bill = database.transactionDao().getById(billId) ?: return@withTransaction null
                val customer = database.customerDao().getById(bill.customerId)
                val maxAllowed = outstandingInDb(bill)
                if (maxAllowed <= 0.001) return@withTransaction null
                val cleanAmount = amount.coerceAtLeast(0.01).coerceAtMost(maxAllowed)

                // For CUSTOMER credit bill (GAVE), payment received is GOT
                // For SUPPLIER credit bill (GOT), payment to supplier is GAVE
                val paymentType = if (customer?.type == "SUPPLIER") "GAVE" else "GOT"
                val paymentTxId = UUID.randomUUID().toString()

                val paymentTx = TransactionEntity(
                    id = paymentTxId,
                    customerId = bill.customerId,
                    amount = cleanAmount,
                    type = paymentType,
                    note = if (bill.billNumber.isNotBlank()) "Received for Bill ${bill.billNumber}" else "Received for bill",
                    date = date,
                    paymentMode = paymentMode,
                    linkedToTxId = billId,
                    isCompleted = true,
                    billNumber = ""
                )
                database.transactionDao().insert(paymentTx)

                // Check if bill is now fully settled
                val alreadyReceived = database.transactionDao().getByLinkedToTxId(billId).sumOf { it.amount }
                if (alreadyReceived >= bill.amount - 0.001) {
                    database.transactionDao().update(bill.copy(isCompleted = true))
                }

                // Add to Cashbook/Bankbook if requested
                if (addToCashbook && !onlyInKhata) {
                    val cashType = if (paymentType == "GOT") "IN" else "OUT"
                    val partyLabel = customer?.name ?: "Party"
                    val remark = if (cashType == "IN") "Received from $partyLabel" else "Paid to $partyLabel"
                    val modeUpper = paymentMode.uppercase()
                    val bookType = if (modeUpper == "CASH") "CASH" else "BANK"
                    database.cashbookDao().insert(
                        CashEntryEntity(
                            type = cashType,
                            amount = cleanAmount,
                            category = "Khata Settlement",
                            remark = remark,
                            date = date,
                            paymentMode = paymentMode,
                            linkedId = paymentTxId,
                            source = "khata",
                            bookType = bookType,
                            partyName = partyLabel,
                            partyId = bill.customerId
                        )
                    )
                }

                paymentTx.toDomain()
            }.also {
                _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
                _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            }
        }
    }

    fun markBillsAllReceived(
        billIds: List<String>,
        paymentMode: String = "CASH",
        addToCashbook: Boolean = true,
        onlyInKhata: Boolean = false,
        date: Long = System.currentTimeMillis()
    ) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                // Outstanding amounts are taken once, before any new payment is added.
                val outstandingSnapshot: Map<String, Double> = billIds.associateWith { id ->
                    database.transactionDao().getById(id)?.let { outstandingInDb(it) } ?: 0.0
                }
                for (billId in billIds.distinct()) {
                    val bill = database.transactionDao().getById(billId) ?: continue
                    val outstanding = outstandingSnapshot[billId] ?: 0.0
                    if (outstanding > 0.01) {
                        val customer = database.customerDao().getById(bill.customerId)
                        val paymentType = if (customer?.type == "SUPPLIER") "GAVE" else "GOT"
                        val paymentTxId = UUID.randomUUID().toString()

                        val paymentTx = TransactionEntity(
                            id = paymentTxId,
                            customerId = bill.customerId,
                            amount = outstanding,
                            type = paymentType,
                            note = if (bill.billNumber.isNotBlank()) "Received for Bill ${bill.billNumber}" else "Received for bill",
                            date = date,
                            paymentMode = paymentMode,
                            linkedToTxId = billId,
                            isCompleted = true,
                            billNumber = ""
                        )
                        database.transactionDao().insert(paymentTx)
                        database.transactionDao().update(bill.copy(isCompleted = true))

                        if (addToCashbook && !onlyInKhata) {
                            val cashType = if (paymentType == "GOT") "IN" else "OUT"
                            val partyLabel = customer?.name ?: "Party"
                            val remark = if (cashType == "IN") "Received from $partyLabel" else "Paid to $partyLabel"
                            val modeUpper = paymentMode.uppercase()
                            val bookType = if (modeUpper == "CASH") "CASH" else "BANK"
                            database.cashbookDao().insert(
                                CashEntryEntity(
                                    type = cashType,
                                    amount = outstanding,
                                    category = "Khata Settlement",
                                    remark = remark,
                                    date = date,
                                    paymentMode = paymentMode,
                                    linkedId = paymentTxId,
                                    source = "khata",
                                    bookType = bookType,
                                    partyName = partyLabel,
                                    partyId = bill.customerId
                                )
                            )
                        }
                    }
                }
            }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
    }

    fun undoPayment(paymentTxId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val payment = database.transactionDao().getById(paymentTxId) ?: return@withTransaction
                val billId = payment.linkedToTxId

                val linkedCash = database.cashbookDao().getByLinkedId(paymentTxId)
                if (linkedCash != null) {
                    database.cashbookDao().update(
                        linkedCash.copy(
                            isDeleted = true,
                            deletedAt = System.currentTimeMillis()
                        )
                    )
                }
                database.transactionDao().deleteById(paymentTxId)

                if (!billId.isNullOrBlank()) {
                    val bill = database.transactionDao().getById(billId)
                    if (bill != null) {
                        val remainingReceived = database.transactionDao().getByLinkedToTxId(billId).sumOf { it.amount }
                        if (remainingReceived < bill.amount - 0.001) {
                            database.transactionDao().update(bill.copy(isCompleted = false))
                        }
                    }
                }
            }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
        }
    }

    fun toggleBillCompleted(billId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val bill = database.transactionDao().getById(billId) ?: return@runBlocking
            database.transactionDao().update(bill.copy(isCompleted = !bill.isCompleted))
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
        }
    }

    fun editTransaction(
        txId: String,
        amount: Double,
        note: String,
        date: Long,
        paymentMode: String
    ) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val current = database.transactionDao().getById(txId) ?: return@withTransaction
                val updated = current.copy(
                    amount = amount.coerceAtLeast(0.01),
                    note = note.trim(),
                    date = date,
                    paymentMode = paymentMode
                )
                database.transactionDao().update(updated)

                // A Naya Bill is saved as bill + "received now" payment + cash line + invoice, all with
                // one timestamp. Re-dating the bill moves those along; later payments keep their own date.
                if (date != current.date && current.billNumber.isNotBlank() && current.linkedToTxId == null) {
                    database.transactionDao().getByLinkedToTxId(txId)
                        .filter { it.date == current.date }
                        .forEach { database.transactionDao().update(it.copy(date = date)) }
                    val invoice = database.invoiceDao().getAllWithItemsList().map { it.invoice }
                        .firstOrNull { it.billNumber == current.billNumber && it.date == current.date }
                    if (invoice != null) {
                        database.invoiceDao().update(invoice.copy(date = date))
                        database.cashbookDao().getByLinkedId(invoice.id)
                            ?.let { database.cashbookDao().update(it.copy(date = date)) }
                    }
                }

                val linkedCash = database.cashbookDao().getByLinkedId(txId)
                if (linkedCash != null) {
                    val orig = linkedCash.originalAmount ?: if (linkedCash.amount != updated.amount) linkedCash.amount else null
                    val modeUpper = paymentMode.uppercase()
                    val bookType = if (modeUpper == "CASH") "CASH" else "BANK"
                    database.cashbookDao().update(
                        linkedCash.copy(
                            amount = updated.amount,
                            originalAmount = orig,
                            remark = if (updated.note.isNotBlank()) updated.note else linkedCash.remark,
                            date = updated.date,
                            paymentMode = updated.paymentMode,
                            bookType = bookType
                        )
                    )
                }
            }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _invoices.value = database.invoiceDao().getAllWithItemsList().map { it.toDomain() }
        }
    }

    fun deleteTransaction(transactionId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val targetTx = database.transactionDao().getById(transactionId) ?: return@withTransaction

                // 1. Before deleting a credit entry, handle payments linked to it (linkedToTxId == that id):
                // delete them and move their cash/bank entries to the Bin so no orphan payment keeps reducing the balance.
                val linkedPayments = database.transactionDao().getAllList().filter { it.linkedToTxId == transactionId }
                for (p in linkedPayments) {
                    val pCash = database.cashbookDao().getByLinkedId(p.id)
                    if (pCash != null) {
                        val refWithLink = if (p.linkedToTxId != null && p.linkedToTxId.isNotBlank()) {
                            if (pCash.reference.isBlank() || pCash.reference == p.linkedToTxId) {
                                p.linkedToTxId
                            } else if (!pCash.reference.contains("|linked:")) {
                                "${pCash.reference}|linked:${p.linkedToTxId}"
                            } else {
                                pCash.reference
                            }
                        } else pCash.reference

                        database.cashbookDao().update(
                            pCash.copy(
                                isDeleted = true,
                                deletedAt = System.currentTimeMillis(),
                                reference = refWithLink
                            )
                        )
                        // Do not delete photos of an entry that is only moved to the Bin;
                        // keep attachment rows while it is in the Bin.
                    } else {
                        val pAtts = database.attachmentDao().getByOwnerList("ENTRY", p.id).map { it.toDomain() }
                        pAtts.forEach { att -> com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att) }
                        database.attachmentDao().deleteByOwner("ENTRY", p.id)
                    }
                    database.transactionDao().deleteById(p.id)
                }

                // 2. Handle the target transaction itself:
                val linkedCash = database.cashbookDao().getByLinkedId(transactionId)
                if (linkedCash != null) {
                    val refWithLink = if (targetTx.linkedToTxId != null && targetTx.linkedToTxId.isNotBlank()) {
                        if (linkedCash.reference.isBlank() || linkedCash.reference == targetTx.linkedToTxId) {
                            targetTx.linkedToTxId
                        } else if (!linkedCash.reference.contains("|linked:")) {
                            "${linkedCash.reference}|linked:${targetTx.linkedToTxId}"
                        } else {
                            linkedCash.reference
                        }
                    } else linkedCash.reference

                    database.cashbookDao().update(
                        linkedCash.copy(
                            isDeleted = true,
                            deletedAt = System.currentTimeMillis(),
                            reference = refWithLink
                        )
                    )
                    // Do not delete photos of an entry that is only moved to the Bin;
                    // keep attachment rows while it is in the Bin.
                } else {
                    val atts = database.attachmentDao().getByOwnerList("ENTRY", transactionId).map { it.toDomain() }
                    atts.forEach { att -> com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att) }
                    database.attachmentDao().deleteByOwner("ENTRY", transactionId)
                }

                database.transactionDao().deleteById(transactionId)
            }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }


    // ==========================================
    // INVENTORY OPERATIONS
    // ==========================================

    fun addInventoryItem(
        name: String,
        itemCode: String = "",
        hsnCode: String = "",
        salePrice: Double,
        purchasePrice: Double = 0.0,
        stock: Int = 0,
        category: String = "General",
        sizes: List<String> = emptyList(),
        sizeStock: Map<String, Int> = emptyMap(),
        lowStockThreshold: Int = 5,
        packSize: Int = 1,
        packRatio: Map<String, Int> = emptyMap(),
        gstPercent: Double = 0.0
    ): InventoryItem {
        checkWritesAllowed()
        val calculatedSizeStock = if (sizeStock.isNotEmpty()) {
            sizeStock
        } else if (sizes.isNotEmpty()) {
            val perSize = stock / sizes.size
            val rem = stock % sizes.size
            sizes.mapIndexed { idx, s -> s to (perSize + if (idx == 0) rem else 0) }.toMap()
        } else {
            mapOf("Free" to stock)
        }
        val totalStock = calculatedSizeStock.values.sum()

        val newItem = InventoryItemEntity(
            name = name.trim(),
            itemCode = itemCode.trim(),
            hsnCode = hsnCode.trim(),
            salePrice = salePrice,
            purchasePrice = purchasePrice,
            stock = totalStock,
            category = category.trim(),
            sizes = calculatedSizeStock.keys.toList(),
            sizeStock = calculatedSizeStock,
            lowStockThreshold = lowStockThreshold,
            packSize = packSize,
            packRatio = packRatio,
            gstPercent = gstPercent
        )
        runBlocking(Dispatchers.IO) {
            database.inventoryDao().insert(newItem)
            _inventory.value = database.inventoryDao().getAllList().map { it.toDomain() }
        }
        return newItem.toDomain()
    }

    fun updateInventoryStock(itemId: String, stockDelta: Int, size: String = "Free") {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val current = database.inventoryDao().getById(itemId)
            if (current != null) {
                val currentMap = current.sizeStock.toMutableMap()
                val targetKey = if (currentMap.containsKey(size)) size else (currentMap.keys.firstOrNull() ?: "Free")
                val oldQty = currentMap[targetKey] ?: 0
                val newQty = (oldQty + stockDelta).coerceAtLeast(0)
                currentMap[targetKey] = newQty
                val newTotal = currentMap.values.sum()
                database.inventoryDao().update(
                    current.copy(
                        sizeStock = currentMap,
                        stock = newTotal,
                        sizes = currentMap.keys.toList()
                    )
                )
                _inventory.value = database.inventoryDao().getAllList().map { it.toDomain() }
            }
        }
    }

    fun deleteInventoryItem(itemId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.inventoryDao().deleteById(itemId)
            _inventory.value = database.inventoryDao().getAllList().map { it.toDomain() }
        }
    }

    // ==========================================
    // INVOICE OPERATIONS (All-or-Nothing Transaction)
    // ==========================================

    fun getFinancialYear(dateMillis: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) // 0-indexed: April is 3
        val startYear = if (month >= Calendar.APRIL) year else year - 1
        val endYear = startYear + 1
        return String.format(Locale.US, "%02d-%02d", startYear % 100, endYear % 100)
    }

    enum class InvoicePaymentStatus {
        PAID,
        PARTLY_PAID,
        UNPAID,
        CANCELLED
    }

    fun peekNextBillNumber(dateMillis: Long = System.currentTimeMillis()): String {
        val fy = getFinancialYear(dateMillis)
        val seq = runBlocking(Dispatchers.IO) {
            database.invoiceSequenceDao().getByFy(fy)?.lastNumber ?: 0
        }
        return (seq + 1).toString()
    }

    fun getInvoiceReceivedAmount(invoice: Invoice): Double {
        if (invoice.isCancelled) return 0.0
        if (invoice.customerId.isNotBlank() && invoice.billNumber.isNotBlank()) {
            val invoiceFy = getFinancialYear(invoice.date)
            val billTxs = transactions.value.filter {
                it.customerId == invoice.customerId &&
                it.billNumber == invoice.billNumber &&
                it.type == "GAVE"
            }
            val billTx = billTxs.find { it.date == invoice.date }
                ?: billTxs.find { getFinancialYear(it.date) == invoiceFy }
                ?: billTxs.firstOrNull()
            if (billTx != null) {
                return getBillReceivedAmount(billTx.id)
            }
        }
        return cashbook.value
            .filter { it.linkedId == invoice.id && it.type == "IN" && !it.isDeleted }
            .sumOf { it.amount }
    }

    fun getInvoicePaymentStatus(invoice: Invoice): InvoicePaymentStatus {
        if (invoice.isCancelled) return InvoicePaymentStatus.CANCELLED
        val total = invoice.totalAmount
        val received = getInvoiceReceivedAmount(invoice)
        return when {
            received >= total - 0.001 -> InvoicePaymentStatus.PAID
            received > 0.001 -> InvoicePaymentStatus.PARTLY_PAID
            else -> InvoicePaymentStatus.UNPAID
        }
    }

    /** A walk-in sale must never open a khata party, whichever language the button was shown in. */
    private fun isWalkInCustomer(name: String): Boolean =
        name == "Cash Customer" || com.example.model.AppLanguage.entries.any {
            name == com.example.ui.util.LanguageStrings.get("quick_cash_customer", it)
        }

    fun createInvoice(
        invoice: Invoice,
        receivedNow: Double = if (invoice.paymentMode in listOf("CASH", "UPI", "BANK")) invoice.totalAmount else 0.0,
        receivedPaymentMode: String = if (invoice.paymentMode in listOf("CASH", "UPI", "BANK")) invoice.paymentMode else "CASH"
    ): Invoice {
        checkWritesAllowed()
        return runBlocking(Dispatchers.IO) {
            val savedInvoice = database.withTransaction {
                // 1. Allocate next consecutive bill number for the financial year
                val fy = getFinancialYear(invoice.date)
                val currentSeq = database.invoiceSequenceDao().getByFy(fy)?.lastNumber ?: 0
                val nextNum = currentSeq + 1
                database.invoiceSequenceDao().insert(InvoiceSequenceEntity(fy, nextNum))
                val allocatedBillNumber = if (invoice.billNumber.isNotBlank() && invoice.billNumber != "PREVIEW") {
                    invoice.billNumber
                } else {
                    nextNum.toString()
                }

                // 2. Round off calculation: round to nearest rupee
                val unrounded = (invoice.subtotal + invoice.gstAmount + invoice.additionalCharges - invoice.discount).coerceAtLeast(0.0)
                val roundedTotal = if (invoice.totalAmount > 0.0) invoice.totalAmount else Math.round(unrounded).toDouble()
                val roundOff = roundedTotal - unrounded

                // 3. Customer linking
                var finalCustomerId = invoice.customerId
                if (finalCustomerId.isBlank() && invoice.customerName.isNotBlank() && !isWalkInCustomer(invoice.customerName)) {
                    val existingCust = database.customerDao().getAllList().find { it.name.equals(invoice.customerName, ignoreCase = true) }
                    if (existingCust != null) {
                        finalCustomerId = existingCust.id
                    } else {
                        val newCust = CustomerEntity(
                            name = invoice.customerName.trim(),
                            phone = invoice.customerPhone.trim()
                        )
                        database.customerDao().insert(newCust)
                        finalCustomerId = newCust.id
                    }
                }

                val finalInvoice = invoice.copy(
                    billNumber = allocatedBillNumber,
                    customerId = finalCustomerId,
                    roundOff = roundOff,
                    totalAmount = roundedTotal,
                    isCancelled = false,
                    cancellationReason = ""
                )

                // 4. Insert invoice header and line items
                database.invoiceDao().insertInvoice(finalInvoice.toEntity())
                val itemsToInsert = if (finalInvoice.items.isNotEmpty()) {
                    finalInvoice.items.map { it.toEntity(finalInvoice.id) }
                } else {
                    listOf(
                        InvoiceItemEntity(
                            invoiceId = finalInvoice.id,
                            name = if (finalInvoice.notes.isNotBlank()) finalInvoice.notes else "Handwritten Bill",
                            unitPrice = finalInvoice.totalAmount,
                            quantity = 1,
                            gstPercent = 0.0,
                            total = finalInvoice.totalAmount
                        )
                    )
                }
                database.invoiceDao().insertItems(itemsToInsert)

                // 5. Payment handling & Khata linking:
                val recNow = receivedNow.coerceIn(0.0, finalInvoice.totalAmount)
                if (recNow > 0.01) {
                    val bookType = if (receivedPaymentMode.uppercase() == "CASH") "CASH" else "BANK"
                    database.cashbookDao().insert(
                        CashEntryEntity(
                            type = "IN",
                            amount = recNow,
                            category = "Sales",
                            remark = "Bill #${finalInvoice.billNumber} (${finalInvoice.customerName.ifBlank { "Cash Customer" }})",
                            date = finalInvoice.date,
                            paymentMode = receivedPaymentMode,
                            linkedId = finalInvoice.id,
                            source = "invoice",
                            bookType = bookType,
                            partyName = finalInvoice.customerName,
                            partyId = finalCustomerId
                        )
                    )
                }

                // If party is linked in Khata:
                if (finalCustomerId.isNotBlank()) {
                    val billTxId = UUID.randomUUID().toString()
                    val isFullPaid = recNow >= finalInvoice.totalAmount - 0.001
                    val billTx = TransactionEntity(
                        id = billTxId,
                        customerId = finalCustomerId,
                        amount = finalInvoice.totalAmount,
                        type = "GAVE",
                        note = if (finalInvoice.notes.isNotBlank()) "Bill #${finalInvoice.billNumber}: ${finalInvoice.notes}" else "Bill #${finalInvoice.billNumber}",
                        billNumber = finalInvoice.billNumber,
                        date = finalInvoice.date,
                        isCompleted = isFullPaid,
                        dueDate = finalInvoice.dueDate
                    )
                    database.transactionDao().insert(billTx)

                    if (recNow > 0.01) {
                        val paymentTx = TransactionEntity(
                            id = UUID.randomUUID().toString(),
                            customerId = finalCustomerId,
                            amount = recNow,
                            type = "GOT",
                            note = "Received for Bill #${finalInvoice.billNumber}",
                            date = finalInvoice.date,
                            paymentMode = receivedPaymentMode,
                            linkedToTxId = billTxId,
                            isCompleted = isFullPaid,
                            billNumber = ""
                        )
                        database.transactionDao().insert(paymentTx)
                    }
                }

                finalInvoice
            }

            _invoices.value = database.invoiceDao().getAllWithItemsList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _customers.value = database.customerDao().getAllList().map { it.toDomain() }

            savedInvoice
        }
    }

    fun cancelInvoice(invoiceId: String, reason: String = "Cancelled by user") {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val invWithItems = database.invoiceDao().getWithItemsById(invoiceId) ?: return@withTransaction
                val invoice = invWithItems.invoice
                if (invoice.isCancelled) return@withTransaction

                // 1. Mark as cancelled with reason (never delete a numbered invoice!)
                database.invoiceDao().update(
                    invoice.copy(
                        isCancelled = true,
                        cancellationReason = reason.trim().ifBlank { "Cancelled" }
                    )
                )

                // 2. Remove linked cashbook entries
                database.cashbookDao().deleteByLinkedId(invoice.id)

                // 3. Remove linked credit and payment transactions from Khata (scoped by customer & FY/date)
                if (invoice.billNumber.isNotBlank() && invoice.customerId.isNotBlank()) {
                    val invoiceFy = getFinancialYear(invoice.date)
                    val allForBillNum = database.transactionDao().getByBillNumber(invoice.billNumber)
                        .filter { it.customerId == invoice.customerId }
                    val scopedTxs = allForBillNum.filter { it.date == invoice.date }
                        .ifEmpty { allForBillNum.filter { getFinancialYear(it.date) == invoiceFy } }
                    val txIds = scopedTxs.map { it.id }
                    if (txIds.isNotEmpty()) {
                        txIds.forEach { bId ->
                            val linkedPayments = database.transactionDao().getByLinkedToTxId(bId)
                            linkedPayments.forEach { p ->
                                database.cashbookDao().deleteByLinkedId(p.id)
                                database.transactionDao().deleteById(p.id)
                            }
                            database.transactionDao().deleteById(bId)
                        }
                        database.cashbookDao().deleteByLinkedIds(txIds)
                    }
                }
            }
            _invoices.value = database.invoiceDao().getAllWithItemsList().map { it.toDomain() }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
        }
    }

    fun deleteInvoice(invoiceId: String) {
        cancelInvoice(invoiceId, "Deleted by user")
    }

    // ==========================================
    // CASHBOOK OPERATIONS
    // ==========================================

    fun addCashEntry(
        type: String, // "IN" or "OUT"
        amount: Double,
        category: String,
        remark: String = "",
        paymentMode: String = "CASH",
        linkedId: String? = null,
        source: String = "manual",
        bookType: String = if (paymentMode.uppercase() == "CASH") "CASH" else "BANK",
        reference: String = "",
        chequeStatus: String = "",
        partyName: String = "",
        partyId: String = "",
        date: Long = System.currentTimeMillis()
    ): CashEntry {
        checkWritesAllowed()
        val entry = CashEntryEntity(
            type = type,
            amount = amount,
            category = category,
            remark = remark,
            date = date,
            paymentMode = paymentMode,
            linkedId = linkedId,
            source = source,
            bookType = bookType,
            reference = reference,
            chequeStatus = chequeStatus,
            partyName = partyName,
            partyId = partyId
        )
        runBlocking(Dispatchers.IO) {
            database.cashbookDao().insert(entry)
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
        return entry.toDomain()
    }

    fun editCashEntry(
        id: String,
        amount: Double,
        remark: String,
        date: Long,
        paymentMode: String,
        bookType: String = if (paymentMode.uppercase() == "CASH") "CASH" else "BANK",
        reference: String = "",
        chequeStatus: String = "",
        partyName: String = "",
        partyId: String = ""
    ) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val current = database.cashbookDao().getById(id) ?: return@withTransaction
                val orig = current.originalAmount ?: if (current.amount != amount) current.amount else null
                val updated = current.copy(
                    amount = amount,
                    originalAmount = orig,
                    remark = remark,
                    date = date,
                    paymentMode = paymentMode,
                    bookType = bookType,
                    reference = reference,
                    chequeStatus = chequeStatus,
                    partyName = if (partyName.isNotBlank()) partyName else current.partyName,
                    partyId = if (partyId.isNotBlank()) partyId else current.partyId
                )
                database.cashbookDao().update(updated)

                // If linked to a Khata transaction, update the Khata transaction as well!
                if (current.source == "khata" && !current.linkedId.isNullOrBlank()) {
                    val tx = database.transactionDao().getById(current.linkedId)
                    if (tx != null) {
                        database.transactionDao().update(
                            tx.copy(
                                amount = amount,
                                date = date,
                                paymentMode = paymentMode,
                                note = if (remark.isNotBlank()) remark else tx.note
                            )
                        )
                    }
                }
            }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
        }
    }

    fun moveCashEntryToBin(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val entry = database.cashbookDao().getById(id) ?: return@withTransaction
                database.cashbookDao().update(
                    entry.copy(
                        isDeleted = true,
                        deletedAt = System.currentTimeMillis()
                    )
                )
                // If linked to Khata, remove the transaction from active khata transactions so balance goes back
                if (entry.source == "khata" && !entry.linkedId.isNullOrBlank()) {
                    val tx = database.transactionDao().getById(entry.linkedId)
                    if (tx != null) {
                        database.transactionDao().deleteById(tx.id)
                    }
                }
            }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
        }
    }

    fun restoreCashEntryFromBin(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                val entry = database.cashbookDao().getById(id) ?: return@withTransaction
                val restoredLinkedToTxId = if (entry.reference.contains("|linked:")) {
                    entry.reference.substringAfter("|linked:").trim().takeIf { it.isNotBlank() }
                } else if (entry.reference.isNotBlank() && !entry.reference.contains(" ")) {
                    entry.reference.trim()
                } else {
                    null
                }
                val cleanRef = if (entry.reference.contains("|linked:")) {
                    entry.reference.substringBefore("|linked:").trim()
                } else if (entry.reference == restoredLinkedToTxId) {
                    ""
                } else {
                    entry.reference
                }

                database.cashbookDao().update(
                    entry.copy(
                        isDeleted = false,
                        deletedAt = 0L,
                        reference = cleanRef
                    )
                )
                // If linked to Khata, restore the payment transaction with linkedToTxId and its attachments
                if (entry.source == "khata" && !entry.linkedId.isNullOrBlank() && entry.partyId.isNotBlank()) {
                    val existing = database.transactionDao().getById(entry.linkedId)
                    if (existing == null) {
                        val customer = database.customerDao().getById(entry.partyId)
                        val isSupplier = customer?.type == "SUPPLIER"
                        val txType = if (isSupplier) {
                            if (entry.type == "OUT") "GAVE" else "GOT"
                        } else {
                            if (entry.type == "IN") "GOT" else "GAVE"
                        }
                        val restoredTx = TransactionEntity(
                            id = entry.linkedId,
                            customerId = entry.partyId,
                            amount = entry.amount,
                            type = txType,
                            note = entry.remark,
                            date = entry.date,
                            paymentMode = entry.paymentMode,
                            linkedToTxId = restoredLinkedToTxId,
                            isCompleted = true
                        )
                        database.transactionDao().insert(restoredTx)
                    }

                    // Restore attachments: ensure any CASH_ENTRY attachments are mapped to ENTRY as well
                    val cashAtts = database.attachmentDao().getByOwnerList("CASH_ENTRY", entry.id)
                    val entryAtts = database.attachmentDao().getByOwnerList("ENTRY", entry.linkedId)
                    for (cAtt in cashAtts) {
                        if (entryAtts.none { it.filePath == cAtt.filePath }) {
                            database.attachmentDao().insert(
                                cAtt.copy(
                                    id = java.util.UUID.randomUUID().toString(),
                                    ownerType = "ENTRY",
                                    ownerId = entry.linkedId
                                )
                            )
                        }
                    }
                }
            }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun permanentlyDeleteCashEntry(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val entry = database.cashbookDao().getById(id)
            val atts = database.attachmentDao().getByOwnerList("CASH_ENTRY", id).map { it.toDomain() }.toMutableList()
            if (entry != null && !entry.linkedId.isNullOrBlank()) {
                atts.addAll(database.attachmentDao().getByOwnerList("ENTRY", entry.linkedId).map { it.toDomain() })
            }
            atts.forEach { att -> com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att) }

            database.withTransaction {
                database.attachmentDao().deleteByOwner("CASH_ENTRY", id)
                if (entry != null && !entry.linkedId.isNullOrBlank()) {
                    database.attachmentDao().deleteByOwner("ENTRY", entry.linkedId)
                }
                database.cashbookDao().deleteById(id)
            }
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun cleanExpiredBinEntries() {
        runBlocking(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 90L * 24 * 3600 * 1000L
            val expired = database.cashbookDao().getBinEntriesList().filter { it.deletedAt > 0 && it.deletedAt < cutoff }
            expired.forEach { entry ->
                val atts = database.attachmentDao().getByOwnerList("CASH_ENTRY", entry.id).map { it.toDomain() }.toMutableList()
                if (!entry.linkedId.isNullOrBlank()) {
                    atts.addAll(database.attachmentDao().getByOwnerList("ENTRY", entry.linkedId).map { it.toDomain() })
                }
                atts.forEach { att -> com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att) }
                database.attachmentDao().deleteByOwner("CASH_ENTRY", entry.id)
                if (!entry.linkedId.isNullOrBlank()) {
                    database.attachmentDao().deleteByOwner("ENTRY", entry.linkedId)
                }
            }
            database.cashbookDao().deleteExpiredBinEntries(cutoff)
            _binEntries.value = database.cashbookDao().getBinEntriesList().map { it.toDomain() }
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun deleteCashEntry(id: String) {
        permanentlyDeleteCashEntry(id)
    }

    fun addDrawerCount(
        date: Long = System.currentTimeMillis(),
        actualCash: Double,
        bookBalance: Double,
        note: String = ""
    ): DrawerCount {
        checkWritesAllowed()
        val difference = actualCash - bookBalance
        val entity = DrawerCountEntity(
            date = date,
            actualCash = actualCash,
            bookBalance = bookBalance,
            difference = difference,
            note = note.trim(),
            createdAt = System.currentTimeMillis()
        )
        runBlocking(Dispatchers.IO) {
            database.drawerCountDao().insert(entity)
            _drawerCounts.value = database.drawerCountDao().getAllList().map { it.toDomain() }
        }
        return entity.toDomain()
    }

    fun deleteDrawerCount(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.drawerCountDao().deleteById(id)
            _drawerCounts.value = database.drawerCountDao().getAllList().map { it.toDomain() }
        }
    }

    // ==========================================
    // BANK CHECK & STATEMENT RECONCILIATION
    // ==========================================

    fun setBankEntryVerified(entryId: String, verified: Boolean) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val verifiedAt = if (verified) System.currentTimeMillis() else 0L
            database.cashbookDao().updateBankVerification(entryId, verified, verifiedAt)
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
    }

    fun setBankEntriesVerifiedBulk(entryIds: List<String>, verified: Boolean) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val verifiedAt = if (verified) System.currentTimeMillis() else 0L
            database.cashbookDao().updateBankVerificationBulk(entryIds, verified, verifiedAt)
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
    }

    fun setBankEntryUnmatchedNote(entryId: String, note: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.cashbookDao().updateUnmatchedNote(entryId, note.trim())
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
    }

    fun addMissingBankEntry(
        date: Long = System.currentTimeMillis(),
        amount: Double,
        isCredit: Boolean,
        partyName: String = "",
        reference: String = ""
    ): CashEntry {
        checkWritesAllowed()
        val type = if (isCredit) "IN" else "OUT"
        val entry = CashEntryEntity(
            type = type,
            amount = amount.coerceAtLeast(0.01),
            category = "Bank Statement Entry",
            remark = if (partyName.isNotBlank()) partyName.trim() else "Statement Entry",
            date = date,
            paymentMode = "BANK",
            linkedId = null,
            source = "bank_check",
            bookType = "BANK",
            reference = reference.trim(),
            chequeStatus = "CLEARED",
            partyName = partyName.trim(),
            partyId = "",
            isDeleted = false,
            deletedAt = 0L,
            originalAmount = null,
            bankVerified = true,
            verifiedAt = System.currentTimeMillis(),
            unmatchedNote = ""
        )
        runBlocking(Dispatchers.IO) {
            database.cashbookDao().insert(entry)
            _cashbook.value = database.cashbookDao().getAllList().map { it.toDomain() }
        }
        return entry.toDomain()
    }

    fun addBankDayCheck(
        date: Long = System.currentTimeMillis(),
        sheetBalance: Double,
        bookBalance: Double,
        note: String = ""
    ): BankDayCheck {
        checkWritesAllowed()
        val difference = sheetBalance - bookBalance
        val entity = BankDayCheckEntity(
            date = date,
            sheetBalance = sheetBalance,
            bookBalance = bookBalance,
            difference = difference,
            note = note.trim(),
            createdAt = System.currentTimeMillis()
        )
        runBlocking(Dispatchers.IO) {
            database.bankDayCheckDao().insert(entity)
            _bankDayChecks.value = database.bankDayCheckDao().getAllList().map { it.toDomain() }
        }
        return entity.toDomain()
    }

    fun deleteBankDayCheck(id: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.bankDayCheckDao().deleteById(id)
            _bankDayChecks.value = database.bankDayCheckDao().getAllList().map { it.toDomain() }
        }
    }

    fun updateCashOpeningBalance(balance: Double, date: Long = System.currentTimeMillis()) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity()
            val updated = current.copy(cashOpeningBalance = balance, cashOpeningDate = date)
            database.appSettingsDao().insertOrUpdate(updated)
            _cashOpeningBalance.value = balance
            _cashOpeningDate.value = date
        }
    }

    fun updateBankOpeningBalance(balance: Double, date: Long = System.currentTimeMillis()) {
        checkWritesAllowed()
        settingsBlocking {
            val current = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity()
            val updated = current.copy(bankOpeningBalance = balance, bankOpeningDate = date)
            database.appSettingsDao().insertOrUpdate(updated)
            _bankOpeningBalance.value = balance
            _bankOpeningDate.value = date
        }
    }



    // ==========================================
    // CHAT MESSAGE PERSISTENCE (Last 50)
    // ==========================================

    fun saveChatMessage(
        sender: String,
        text: String,
        actionTitle: String? = null,
        reportContent: String? = null,
        isOutOfScope: Boolean = false
    ) {
        runBlocking(Dispatchers.IO) {
            database.chatMessageDao().insert(
                ChatMessageEntity(
                    sender = sender,
                    text = text,
                    actionTitle = actionTitle,
                    reportContent = reportContent,
                    isOutOfScope = isOutOfScope
                )
            )
            database.chatMessageDao().trimToLast50()
        }
    }

    fun loadRecentChatMessages(): List<ChatMessageEntity> {
        return runBlocking(Dispatchers.IO) {
            database.chatMessageDao().getAllMessagesList()
        }
    }

    fun clearChatHistory() {
        runBlocking(Dispatchers.IO) {
            database.chatMessageDao().deleteAll()
        }
    }

    // ==========================================
    // CSV IMPORT
    // ==========================================

    /** Same phone, or the same name when either side has no phone. */
    private fun isSameParty(nameA: String, phoneA: String, nameB: String, phoneB: String): Boolean {
        val a = PhoneUtil.normalize(phoneA)
        val b = PhoneUtil.normalize(phoneB)
        return if (a.isNotBlank() && b.isNotBlank()) a == b else nameA.trim().equals(nameB.trim(), ignoreCase = true)
    }

    fun previewCustomersFromCsv(csvText: String): List<CsvCustomerPreview> {
        val existing = customers.value
        val accepted = mutableListOf<BalanceCsv.Row>()
        return BalanceCsv.parse(csvText).map { row ->
            val isDup = row.error == null && (
                existing.any { isSameParty(row.name, row.phone, it.name, it.phone) } ||
                    accepted.any { isSameParty(row.name, row.phone, it.name, it.phone) }
                )
            if (row.error == null && !isDup) accepted.add(row)
            CsvCustomerPreview(
                name = row.name,
                phone = row.phone,
                balance = row.balance,
                isDuplicate = isDup,
                error = row.error,
                line = row.line
            )
        }
    }

    fun importCustomersFromCsv(previews: List<CsvCustomerPreview>): Int {
        checkWritesAllowed()
        val toImport = previews.filter { it.canImport }
        return runBlocking(Dispatchers.IO) {
            val count = database.withTransaction {
                // Checked again here: a second tap or an old preview must not add a balance twice.
                val saved = database.customerDao().getAllList().toMutableList()
                var importedCount = 0
                for (item in toImport) {
                    if (saved.any { isSameParty(item.name, item.phone, it.name, it.phone) }) continue
                    val newCust = CustomerEntity(
                        name = item.name.trim(),
                        phone = PhoneUtil.normalize(item.phone),
                        type = item.type.uppercase()
                    )
                    database.customerDao().insert(newCust)
                    saved.add(newCust)
                    if (Math.abs(item.balance) > 0.009) {
                        database.transactionDao().insert(
                            TransactionEntity(
                                customerId = newCust.id,
                                amount = Math.abs(item.balance),
                                type = if (item.balance > 0) "GAVE" else "GOT",
                                note = "Opening Balance (CSV Import)"
                            )
                        )
                    }
                    importedCount++
                }
                importedCount
            }
            _customers.value = database.customerDao().getAllList().map { it.toDomain() }
            _transactions.value = database.transactionDao().getAllList().map { it.toDomain() }
            count
        }
    }

    fun importCustomersFromCsv(csvText: String): Pair<Int, Int> {
        val preview = previewCustomersFromCsv(csvText)
        val imported = importCustomersFromCsv(preview)
        return Pair(imported, preview.size - imported)
    }

    // ==========================================
    // ATTACHMENT OPERATIONS (TASK 10)
    // ==========================================

    fun getAttachmentsForOwner(ownerType: String, ownerId: String): List<Attachment> {
        return attachments.value.filter { it.ownerType == ownerType && it.ownerId == ownerId }
    }

    fun getAttachmentsForParty(partyId: String): List<Attachment> {
        val custTxIds = transactions.value.filter { it.customerId == partyId }.map { it.id }.toSet()
        val custInvoiceIds = invoices.value.filter { it.customerId == partyId }.map { it.id }.toSet()
        return attachments.value.filter {
            (it.ownerType == "PARTY" && it.ownerId == partyId) ||
            (it.ownerType == "ENTRY" && custTxIds.contains(it.ownerId)) ||
            (it.ownerType == "INVOICE" && custInvoiceIds.contains(it.ownerId))
        }
    }

    fun addAttachment(attachment: Attachment) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.attachmentDao().insert(attachment.toEntity())
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun addAttachments(newAttachments: List<Attachment>) {
        if (newAttachments.isEmpty()) return
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.attachmentDao().insertAll(newAttachments.map { it.toEntity() })
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun updateAttachmentCaption(attachmentId: String, newCaption: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.attachmentDao().updateCaption(attachmentId, newCaption)
            _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
        }
    }

    fun deleteAttachment(attachmentId: String) {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            val att = database.attachmentDao().getById(attachmentId)?.toDomain()
            if (att != null) {
                com.example.ui.util.AttachmentManager.deleteAttachmentFiles(att)
                database.attachmentDao().deleteById(attachmentId)
                _attachments.value = database.attachmentDao().getAllList().map { it.toDomain() }
            }
        }
    }

    fun clearAllData() {
        checkWritesAllowed()
        runBlocking(Dispatchers.IO) {
            database.withTransaction {
                database.customerDao().deleteAll()
                database.transactionDao().deleteAll()
                database.inventoryDao().deleteAll()
                database.invoiceDao().deleteAll()
                database.cashbookDao().deleteAll()
                database.attachmentDao().deleteAll()
                database.deliveryTaskDao().deleteAll()
                database.drawerCountDao().deleteAll()
                database.bankDayCheckDao().deleteAll()
            }
            _customers.value = emptyList()
            _transactions.value = emptyList()
            _inventory.value = emptyList()
            _invoices.value = emptyList()
            _cashbook.value = emptyList()
            _attachments.value = emptyList()
            _deliveryTasks.value = emptyList()
            _drawerCounts.value = emptyList()
            _bankDayChecks.value = emptyList()
        }
    }


    // ==========================================
    // BACKUP & RESTORE (ROOM BACKED)
    // ==========================================

    fun exportDataAsJsonString(): String {
        return runBlocking(Dispatchers.IO) {
            val json = JSONObject()

            val custArr = JSONArray()
            database.customerDao().getAllList().forEach { c ->
                val o = JSONObject()
                o.put("id", c.id)
                o.put("name", c.name)
                o.put("phone", c.phone)
                o.put("address", c.address)
                o.put("gstin", c.gstin)
                o.put("type", c.type)
                o.put("createdAt", c.createdAt)
                custArr.put(o)
            }
            json.put("customers", custArr)

            val txArr = JSONArray()
            database.transactionDao().getAllList().forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("customerId", t.customerId)
                o.put("amount", t.amount)
                o.put("type", t.type)
                o.put("note", t.note)
                o.put("date", t.date)
                o.put("paymentMode", t.paymentMode)
                o.put("linkedToTxId", t.linkedToTxId)
                o.put("isCompleted", t.isCompleted)
                o.put("billNumber", t.billNumber)
                o.put("dueDate", t.dueDate)
                txArr.put(o)
            }
            json.put("transactions", txArr)

            val invArr = JSONArray()
            database.inventoryDao().getAllList().forEach { item ->
                val o = JSONObject()
                o.put("id", item.id)
                o.put("name", item.name)
                o.put("itemCode", item.itemCode)
                o.put("hsnCode", item.hsnCode)
                o.put("salePrice", item.salePrice)
                o.put("purchasePrice", item.purchasePrice)
                o.put("stock", item.stock)
                o.put("category", item.category)
                o.put("sizes", JSONArray(item.sizes))
                val sizeStockObj = JSONObject()
                item.sizeStock.forEach { (k, v) -> sizeStockObj.put(k, v) }
                o.put("sizeStock", sizeStockObj)
                o.put("lowStockThreshold", item.lowStockThreshold)
                o.put("packSize", item.packSize)
                val packRatioObj = JSONObject()
                item.packRatio.forEach { (k, v) -> packRatioObj.put(k, v) }
                o.put("packRatio", packRatioObj)
                o.put("gstPercent", item.gstPercent)
                invArr.put(o)
            }
            json.put("inventory", invArr)

            val invsArr = JSONArray()
            database.invoiceDao().getAllWithItemsList().forEach { invWithItems ->
                val inv = invWithItems.invoice
                val o = JSONObject()
                o.put("id", inv.id)
                o.put("billNumber", inv.billNumber)
                o.put("date", inv.date)
                o.put("customerId", inv.customerId)
                o.put("customerName", inv.customerName)
                o.put("customerPhone", inv.customerPhone)
                o.put("subtotal", inv.subtotal)
                o.put("gstAmount", inv.gstAmount)
                o.put("discount", inv.discount)
                o.put("additionalCharges", inv.additionalCharges)
                o.put("roundOff", inv.roundOff)
                o.put("totalAmount", inv.totalAmount)
                o.put("paymentMode", inv.paymentMode)
                o.put("stateOfSupply", inv.stateOfSupply)
                o.put("gstin", inv.gstin)
                o.put("notes", inv.notes)
                o.put("themeName", inv.themeName)
                o.put("isCancelled", inv.isCancelled)
                o.put("cancellationReason", inv.cancellationReason)
                o.put("dueDate", inv.dueDate)

                val itemsArr = JSONArray()
                invWithItems.items.forEach { line ->
                    val io = JSONObject()
                    io.put("id", line.id)
                    io.put("itemId", line.itemId)
                    io.put("name", line.name)
                    io.put("size", line.size)
                    io.put("hsnCode", line.hsnCode)
                    io.put("quantity", line.quantity)
                    io.put("unitPrice", line.unitPrice)
                    io.put("gstPercent", line.gstPercent)
                    io.put("total", line.total)
                    itemsArr.put(io)
                }
                o.put("items", itemsArr)
                invsArr.put(o)
            }
            json.put("invoices", invsArr)

            val cashArr = JSONArray()
            (database.cashbookDao().getAllList() + database.cashbookDao().getBinEntriesList())
                .distinctBy { it.id }
                .forEach { ce ->
                val o = JSONObject()
                o.put("id", ce.id)
                o.put("type", ce.type)
                o.put("amount", ce.amount)
                o.put("category", ce.category)
                o.put("remark", ce.remark)
                o.put("date", ce.date)
                o.put("paymentMode", ce.paymentMode)
                o.put("linkedId", ce.linkedId)
                o.put("source", ce.source)
                o.put("bookType", ce.bookType)
                o.put("reference", ce.reference)
                o.put("chequeStatus", ce.chequeStatus)
                o.put("partyName", ce.partyName)
                o.put("partyId", ce.partyId)
                o.put("isDeleted", ce.isDeleted)
                o.put("deletedAt", ce.deletedAt)
                ce.originalAmount?.let { o.put("originalAmount", it) }
                o.put("bankVerified", ce.bankVerified)
                o.put("verifiedAt", ce.verifiedAt)
                o.put("unmatchedNote", ce.unmatchedNote)
                cashArr.put(o)
            }
            json.put("cashbook", cashArr)

            val drawerArr = JSONArray()
            database.drawerCountDao().getAllList().forEach { dc ->
                val o = JSONObject()
                o.put("id", dc.id)
                o.put("date", dc.date)
                o.put("actualCash", dc.actualCash)
                o.put("bookBalance", dc.bookBalance)
                o.put("difference", dc.difference)
                o.put("note", dc.note)
                o.put("createdAt", dc.createdAt)
                drawerArr.put(o)
            }
            json.put("drawer_counts", drawerArr)

            val bankCheckArr = JSONArray()
            database.bankDayCheckDao().getAllList().forEach { bc ->
                val o = JSONObject()
                o.put("id", bc.id)
                o.put("date", bc.date)
                o.put("sheetBalance", bc.sheetBalance)
                o.put("bookBalance", bc.bookBalance)
                o.put("difference", bc.difference)
                o.put("note", bc.note)
                o.put("createdAt", bc.createdAt)
                bankCheckArr.put(o)
            }
            json.put("bank_day_checks", bankCheckArr)

            val attArr = JSONArray()
            database.attachmentDao().getAllList().forEach { a ->
                val o = JSONObject()
                o.put("id", a.id)
                o.put("ownerType", a.ownerType)
                o.put("ownerId", a.ownerId)
                o.put("filePath", a.filePath)
                o.put("thumbPath", a.thumbPath)
                o.put("fileName", File(a.filePath).name)
                o.put("thumbName", if (a.thumbPath.isNotBlank()) File(a.thumbPath).name else "")
                o.put("mimeType", a.mimeType)
                o.put("sizeBytes", a.sizeBytes)
                o.put("caption", a.caption)
                o.put("createdAt", a.createdAt)
                attArr.put(o)
            }
            json.put("attachments", attArr)

            val deliveryArr = JSONArray()
            database.deliveryTaskDao().getAllList().forEach { dt ->
                val o = JSONObject()
                o.put("id", dt.id)
                o.put("partyName", dt.partyName)
                o.put("partyId", dt.partyId)
                o.put("note", dt.note)
                o.put("date", dt.date)
                o.put("isCompleted", dt.isCompleted)
                o.put("completedAt", dt.completedAt)
                o.put("createdAt", dt.createdAt)
                deliveryArr.put(o)
            }
            json.put("delivery_tasks", deliveryArr)

            val seqArr = JSONArray()
            database.invoiceSequenceDao().getAllList().forEach { s ->
                val o = JSONObject()
                o.put("financialYear", s.financialYear)
                o.put("lastNumber", s.lastNumber)
                seqArr.put(o)
            }
            json.put("invoice_sequences", seqArr)

            database.storeProfileDao().getProfileDirect()?.let { p ->
                val po = JSONObject()
                po.put("storeName", p.storeName)
                po.put("address", p.address)
                po.put("phone", p.phone)
                po.put("email", p.email)
                po.put("upiId", p.upiId)
                po.put("gstin", p.gstin)
                po.put("ownerName", p.ownerName)
                po.put("state", p.state)
                po.put("logoUri", p.logoUri)
                json.put("profile", po)
            }

            database.appSettingsDao().getSettingsDirect()?.let { st ->
                val so = JSONObject()
                so.put("language", st.language)
                so.put("theme", st.theme)
                so.put("hasCompletedOnboarding", st.hasCompletedOnboarding)
                so.put("cashOpeningBalance", st.cashOpeningBalance)
                so.put("cashOpeningDate", st.cashOpeningDate)
                so.put("bankOpeningBalance", st.bankOpeningBalance)
                so.put("bankOpeningDate", st.bankOpeningDate)
                so.put("simpleMode", st.simpleMode)
                so.put("hasCompletedBatteryGuide", st.hasCompletedBatteryGuide)
                so.put("lastBackupTimestamp", st.lastBackupTimestamp)
                json.put("settings", so)
            }

            json.toString(2)
        }
    }


    fun validateBackupJson(jsonStr: String): ImportResult {
        return try {
            val json = JSONObject(jsonStr)
            val expectedKeys = listOf("customers", "transactions", "inventory", "invoices", "cashbook")
            var foundAny = false
            for (k in expectedKeys) {
                if (json.has(k)) foundAny = true
            }
            if (!foundAny) {
                return ImportResult(success = false, errorMessage = "Backup JSON missing expected data arrays")
            }

            var custCount = 0
            if (json.has("customers")) {
                val arr = json.getJSONArray("customers")
                custCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name", "").isBlank()) {
                        return ImportResult(success = false, errorMessage = "Customer at position $i missing name")
                    }
                }
            }

            var txCount = 0
            if (json.has("transactions")) {
                val arr = json.getJSONArray("transactions")
                txCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (!o.has("amount")) {
                        return ImportResult(success = false, errorMessage = "Transaction at position $i missing amount")
                    }
                }
            }

            var invCount = 0
            if (json.has("inventory")) {
                val arr = json.getJSONArray("inventory")
                invCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("name", "").isBlank()) {
                        return ImportResult(success = false, errorMessage = "Inventory item at position $i missing name")
                    }
                }
            }

            var billCount = 0
            if (json.has("invoices")) {
                val arr = json.getJSONArray("invoices")
                billCount = arr.length()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (!o.has("billNumber")) {
                        return ImportResult(success = false, errorMessage = "Invoice at position $i missing billNumber")
                    }
                }
            }

            var cashCount = 0
            if (json.has("cashbook")) {
                val arr = json.getJSONArray("cashbook")
                cashCount = arr.length()
            }

            ImportResult(
                success = true,
                customerCount = custCount,
                transactionCount = txCount,
                inventoryCount = invCount,
                invoiceCount = billCount,
                cashbookCount = cashCount
            )
        } catch (e: Exception) {
            Log.e("AppRepository", "Validation failed for backup JSON: ${e.message}", e)
            ImportResult(success = false, errorMessage = "Invalid JSON structure: ${e.localizedMessage ?: "Syntax error"}")
        }
    }

    fun importDataFromJsonString(jsonStr: String, isFromZip: Boolean = false): ImportResult {
        checkWritesAllowed()
        val validation = validateBackupJson(jsonStr)
        if (!validation.success) {
            return validation
        }

        return try {
            val currentRaw = exportDataAsJsonString()
            prefs.edit().putString("app_saved_data_prev", currentRaw).commit()

            val json = JSONObject(jsonStr)
            val newCustomers = mutableListOf<CustomerEntity>()
            val rawTransactions = mutableListOf<TransactionEntity>()
            val newInventory = mutableListOf<InventoryItemEntity>()
            val newInvoices = mutableListOf<InvoiceEntity>()
            val newInvoiceItems = mutableListOf<InvoiceItemEntity>()
            val newCashbook = mutableListOf<CashEntryEntity>()

            if (json.has("customers")) {
                val arr = json.getJSONArray("customers")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    newCustomers.add(CustomerEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", "").trim().ifBlank { "Party" },
                        phone = PhoneUtil.normalize(o.optString("phone", "").trim()),
                        address = o.optString("address", "").trim(),
                        gstin = o.optString("gstin", "").trim(),
                        type = o.optString("type", "CUSTOMER").uppercase(),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            if (json.has("transactions")) {
                val arr = json.getJSONArray("transactions")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val txId = o.optString("id", UUID.randomUUID().toString())
                    val custId = o.optString("customerId", "").trim()
                    val amt = o.optDouble("amount", 0.0)
                    val txType = o.optString("type", "GAVE")
                    val dt = o.optLong("date", System.currentTimeMillis())
                    val oldIsCancelled = o.optBoolean("isCancelled", false)

                    rawTransactions.add(TransactionEntity(
                        id = txId,
                        customerId = custId,
                        amount = amt,
                        type = txType,
                        note = o.optString("note", ""),
                        date = dt,
                        paymentMode = o.optString("paymentMode", "CASH"),
                        linkedToTxId = if (o.has("linkedToTxId") && !o.isNull("linkedToTxId")) o.getString("linkedToTxId") else null,
                        isCompleted = o.optBoolean("isCompleted", oldIsCancelled),
                        billNumber = o.optString("billNumber", ""),
                        dueDate = o.optLong("dueDate", 0L)
                    ))

                    // Map old isCancelled = true on credit bills into linked payments
                    if (oldIsCancelled && txType == "GAVE") {
                        rawTransactions.add(TransactionEntity(
                            id = UUID.randomUUID().toString(),
                            customerId = custId,
                            amount = amt,
                            type = "GOT",
                            note = "Settled (migrated)",
                            date = dt,
                            paymentMode = "CASH",
                            linkedToTxId = txId,
                            isCompleted = true,
                            billNumber = ""
                        ))
                    }
                }
            }

            // Repair orphan transactions whose customerId is missing or unknown
            val validCustIds = newCustomers.map { it.id }.toSet()
            val repairedTransactions = mutableListOf<TransactionEntity>()
            var orphanCount = 0
            var recoveredCustomer: CustomerEntity? = null

            for (tx in rawTransactions) {
                if (tx.customerId.isBlank() || !validCustIds.contains(tx.customerId)) {
                    orphanCount++
                    if (recoveredCustomer == null) {
                        recoveredCustomer = newCustomers.find { it.name.equals("Recovered entries", ignoreCase = true) }
                        if (recoveredCustomer == null) {
                            recoveredCustomer = CustomerEntity(
                                id = UUID.randomUUID().toString(),
                                name = "Recovered entries",
                                phone = "",
                                address = "Auto-created for unassigned backup transactions"
                            )
                            newCustomers.add(recoveredCustomer)
                        }
                    }
                    repairedTransactions.add(tx.copy(customerId = recoveredCustomer.id))
                } else {
                    repairedTransactions.add(tx)
                }
            }

            if (json.has("inventory")) {
                val arr = json.getJSONArray("inventory")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val sizesArr = o.optJSONArray("sizes")
                    val sizes = mutableListOf<String>()
                    if (sizesArr != null) {
                        for (j in 0 until sizesArr.length()) {
                            sizes.add(sizesArr.getString(j))
                        }
                    }
                    val sizeStockObj = o.optJSONObject("sizeStock")
                    val parsedSizeStock = LinkedHashMap<String, Int>()
                    if (sizeStockObj != null) {
                        val keys = sizeStockObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            parsedSizeStock[k] = sizeStockObj.optInt(k, 0)
                        }
                    }
                    val legacyStock = o.optInt("stock", 0)
                    val finalSizeStock = if (parsedSizeStock.isNotEmpty()) {
                        parsedSizeStock
                    } else if (sizes.isNotEmpty()) {
                        val perSize = legacyStock / sizes.size
                        val rem = legacyStock % sizes.size
                        sizes.mapIndexed { idx, s -> s to (perSize + if (idx == 0) rem else 0) }.toMap()
                    } else {
                        mapOf("Free" to legacyStock)
                    }
                    val finalSizes = if (sizes.isNotEmpty()) sizes else finalSizeStock.keys.toList()
                    val packRatioObj = o.optJSONObject("packRatio")
                    val parsedPackRatio = LinkedHashMap<String, Int>()
                    if (packRatioObj != null) {
                        val keys = packRatioObj.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            parsedPackRatio[k] = packRatioObj.optInt(k, 0)
                        }
                    }
                    newInventory.add(InventoryItemEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        name = o.optString("name", ""),
                        itemCode = o.optString("itemCode", ""),
                        hsnCode = o.optString("hsnCode", ""),
                        salePrice = o.optDouble("salePrice", 0.0),
                        purchasePrice = o.optDouble("purchasePrice", 0.0),
                        stock = finalSizeStock.values.sum(),
                        category = o.optString("category", "General"),
                        sizes = finalSizes,
                        sizeStock = finalSizeStock,
                        lowStockThreshold = o.optInt("lowStockThreshold", 5),
                        packSize = o.optInt("packSize", 1),
                        packRatio = parsedPackRatio,
                        gstPercent = o.optDouble("gstPercent", 0.0)
                    ))
                }
            }

            if (json.has("invoices")) {
                val arr = json.getJSONArray("invoices")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val invId = o.optString("id", UUID.randomUUID().toString())
                    newInvoices.add(InvoiceEntity(
                        id = invId,
                        billNumber = o.optString("billNumber", ""),
                        date = o.optLong("date", System.currentTimeMillis()),
                        customerId = o.optString("customerId", ""),
                        customerName = o.optString("customerName", ""),
                        customerPhone = o.optString("customerPhone", ""),
                        subtotal = o.optDouble("subtotal", 0.0),
                        gstAmount = o.optDouble("gstAmount", 0.0),
                        discount = o.optDouble("discount", 0.0),
                        additionalCharges = o.optDouble("additionalCharges", 0.0),
                        roundOff = o.optDouble("roundOff", 0.0),
                        totalAmount = o.optDouble("totalAmount", 0.0),
                        paymentMode = o.optString("paymentMode", "CASH"),
                        stateOfSupply = o.optString("stateOfSupply", "Gujarat"),
                        gstin = o.optString("gstin", ""),
                        notes = o.optString("notes", ""),
                        themeName = o.optString("themeName", "PREMIUM_BLUE"),
                        isCancelled = o.optBoolean("isCancelled", false),
                        cancellationReason = o.optString("cancellationReason", ""),
                        dueDate = o.optLong("dueDate", 0L)
                    ))

                    val itemsArr = o.optJSONArray("items")
                    if (itemsArr != null) {
                        for (j in 0 until itemsArr.length()) {
                            val io = itemsArr.getJSONObject(j)
                            newInvoiceItems.add(InvoiceItemEntity(
                                id = io.optString("id", UUID.randomUUID().toString()),
                                invoiceId = invId,
                                itemId = io.optString("itemId", ""),
                                name = io.optString("name", ""),
                                size = io.optString("size", "Free"),
                                hsnCode = io.optString("hsnCode", ""),
                                quantity = io.optInt("quantity", 1),
                                unitPrice = io.optDouble("unitPrice", 0.0),
                                gstPercent = io.optDouble("gstPercent", 0.0),
                                total = io.optDouble("total", 0.0)
                            ))
                        }
                    }
                }
            }

            if (json.has("cashbook")) {
                val arr = json.getJSONArray("cashbook")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val mode = o.optString("paymentMode", "CASH")
                    val defaultBook = if (mode.uppercase() in listOf("UPI", "BANK", "CHEQUE", "BANK TRANSFER", "ONLINE")) "BANK" else "CASH"
                    newCashbook.add(CashEntryEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        type = o.optString("type", "IN"),
                        amount = o.optDouble("amount", 0.0),
                        category = o.optString("category", ""),
                        remark = o.optString("remark", ""),
                        date = o.optLong("date", System.currentTimeMillis()),
                        paymentMode = mode,
                        linkedId = if (o.has("linkedId") && !o.isNull("linkedId")) o.getString("linkedId") else null,
                        source = o.optString("source", "manual"),
                        bookType = o.optString("bookType", defaultBook),
                        reference = o.optString("reference", ""),
                        chequeStatus = o.optString("chequeStatus", ""),
                        partyName = o.optString("partyName", ""),
                        partyId = o.optString("partyId", ""),
                        isDeleted = o.optBoolean("isDeleted", false),
                        deletedAt = o.optLong("deletedAt", 0L),
                        originalAmount = if (o.has("originalAmount") && !o.isNull("originalAmount")) o.getDouble("originalAmount") else null,
                        bankVerified = o.optBoolean("bankVerified", false),
                        verifiedAt = o.optLong("verifiedAt", 0L),
                        unmatchedNote = o.optString("unmatchedNote", "")
                    ))
                }
            }

            val newDrawerCounts = mutableListOf<DrawerCountEntity>()
            if (json.has("drawer_counts")) {
                val arr = json.getJSONArray("drawer_counts")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    newDrawerCounts.add(DrawerCountEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        date = o.optLong("date", System.currentTimeMillis()),
                        actualCash = o.optDouble("actualCash", 0.0),
                        bookBalance = o.optDouble("bookBalance", 0.0),
                        difference = o.optDouble("difference", 0.0),
                        note = o.optString("note", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            val newBankDayChecks = mutableListOf<BankDayCheckEntity>()
            if (json.has("bank_day_checks")) {
                val arr = json.getJSONArray("bank_day_checks")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    newBankDayChecks.add(BankDayCheckEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        date = o.optLong("date", System.currentTimeMillis()),
                        sheetBalance = o.optDouble("sheetBalance", 0.0),
                        bookBalance = o.optDouble("bookBalance", 0.0),
                        difference = o.optDouble("difference", 0.0),
                        note = o.optString("note", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            val newAttachments = mutableListOf<AttachmentEntity>()
            if (json.has("attachments")) {
                val arr = json.getJSONArray("attachments")
                val attDir = com.example.ui.util.AttachmentManager.getAttachmentsDir(context)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val fileName = o.optString("fileName", "")
                    val thumbName = o.optString("thumbName", "")
                    val resolvedFilePath = if (fileName.isNotBlank()) File(attDir, fileName).absolutePath else o.optString("filePath", "")
                    val resolvedThumbPath = if (thumbName.isNotBlank()) File(attDir, thumbName).absolutePath else o.optString("thumbPath", "")
                    newAttachments.add(AttachmentEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        ownerType = o.optString("ownerType", "ENTRY"),
                        ownerId = o.optString("ownerId", ""),
                        filePath = resolvedFilePath,
                        thumbPath = resolvedThumbPath,
                        mimeType = o.optString("mimeType", "image/jpeg"),
                        sizeBytes = o.optLong("sizeBytes", 0L),
                        caption = o.optString("caption", ""),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            val newDeliveryTasks = mutableListOf<DeliveryTaskEntity>()
            if (json.has("delivery_tasks")) {
                val arr = json.getJSONArray("delivery_tasks")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    newDeliveryTasks.add(DeliveryTaskEntity(
                        id = o.optString("id", UUID.randomUUID().toString()),
                        partyName = o.optString("partyName", ""),
                        partyId = if (o.has("partyId") && !o.isNull("partyId")) o.getString("partyId") else null,
                        note = o.optString("note", ""),
                        date = o.optLong("date", System.currentTimeMillis()),
                        isCompleted = o.optBoolean("isCompleted", false),
                        completedAt = o.optLong("completedAt", 0L),
                        createdAt = o.optLong("createdAt", System.currentTimeMillis())
                    ))
                }
            }

            val newSequences = mutableListOf<InvoiceSequenceEntity>()
            if (json.has("invoice_sequences")) {
                val arr = json.getJSONArray("invoice_sequences")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val fy = o.optString("financialYear", "")
                    if (fy.isNotBlank()) {
                        newSequences.add(InvoiceSequenceEntity(fy, o.optInt("lastNumber", 0)))
                    }
                }
            } else if (newInvoices.isNotEmpty()) {
                newInvoices.groupBy { getFinancialYear(it.date) }.forEach { (fy, invs) ->
                    val maxNum = invs.mapNotNull { it.billNumber.toIntOrNull() }.maxOrNull() ?: 0
                    if (maxNum > 0) newSequences.add(InvoiceSequenceEntity(fy, maxNum))
                }
            }

            val restoredProfile = (json.optJSONObject("profile") ?: json.optJSONObject("store_profile"))?.let { po ->
                StoreProfileEntity(
                    id = 1,
                    storeName = po.optString("storeName", ""),
                    address = po.optString("address", ""),
                    phone = po.optString("phone", ""),
                    email = po.optString("email", ""),
                    upiId = po.optString("upiId", ""),
                    gstin = po.optString("gstin", ""),
                    ownerName = po.optString("ownerName", ""),
                    state = po.optString("state", "Gujarat"),
                    logoUri = po.optString("logoUri", "")
                )
            }

            val restoredSettingsObj = json.optJSONObject("settings") ?: json.optJSONObject("app_settings")

            runBlocking(Dispatchers.IO) {
                database.withTransaction {
                    database.customerDao().deleteAll()
                    database.transactionDao().deleteAll()
                    database.inventoryDao().deleteAll()
                    database.invoiceDao().deleteAll()
                    database.cashbookDao().deleteAll()
                    database.drawerCountDao().deleteAll()
                    database.bankDayCheckDao().deleteAll()
                    database.attachmentDao().deleteAll()
                    database.deliveryTaskDao().deleteAll()
                    database.invoiceSequenceDao().deleteAll()

                    database.customerDao().insertAll(newCustomers)
                    database.transactionDao().insertAll(repairedTransactions)
                    database.inventoryDao().insertAll(newInventory)
                    database.invoiceDao().insertInvoices(newInvoices)
                    database.invoiceDao().insertItems(newInvoiceItems)
                    database.cashbookDao().insertAll(newCashbook)
                    newDrawerCounts.forEach { database.drawerCountDao().insert(it) }
                    newBankDayChecks.forEach { database.bankDayCheckDao().insert(it) }
                    database.attachmentDao().insertAll(newAttachments)
                    newDeliveryTasks.forEach { database.deliveryTaskDao().insert(it) }
                    newSequences.forEach { database.invoiceSequenceDao().insert(it) }
                    if (restoredProfile != null) {
                        database.storeProfileDao().insertOrUpdate(restoredProfile)
                    }
                    if (restoredSettingsObj != null) {
                        val existing = database.appSettingsDao().getSettingsDirect() ?: AppSettingsEntity()
                        database.appSettingsDao().insertOrUpdate(
                            existing.copy(
                                language = restoredSettingsObj.optString("language", existing.language),
                                theme = restoredSettingsObj.optString("theme", existing.theme),
                                hasCompletedOnboarding = restoredSettingsObj.optBoolean("hasCompletedOnboarding", existing.hasCompletedOnboarding),
                                cashOpeningBalance = restoredSettingsObj.optDouble("cashOpeningBalance", existing.cashOpeningBalance),
                                cashOpeningDate = restoredSettingsObj.optLong("cashOpeningDate", existing.cashOpeningDate),
                                bankOpeningBalance = restoredSettingsObj.optDouble("bankOpeningBalance", existing.bankOpeningBalance),
                                bankOpeningDate = restoredSettingsObj.optLong("bankOpeningDate", existing.bankOpeningDate),
                                simpleMode = restoredSettingsObj.optBoolean("simpleMode", existing.simpleMode),
                                hasCompletedBatteryGuide = restoredSettingsObj.optBoolean("hasCompletedBatteryGuide", existing.hasCompletedBatteryGuide),
                                lastBackupTimestamp = restoredSettingsObj.optLong("lastBackupTimestamp", existing.lastBackupTimestamp)
                            )
                        )
                    }
                }
                refreshAllInMemoryCachesInternal()
            }
            validation.copy(
                customerCount = newCustomers.size,
                transactionCount = repairedTransactions.size,
                inventoryCount = newInventory.size,
                invoiceCount = newInvoices.size,
                cashbookCount = newCashbook.size,
                attachmentCount = newAttachments.size,
                isLegacyJsonWithoutPhotos = !isFromZip,
                repairedCount = orphanCount
            )
        } catch (e: Exception) {
            Log.e("AppRepository", "Error applying backup to Room: ${e.message}", e)
            ImportResult(success = false, errorMessage = "Error applying backup: ${e.localizedMessage}")
        }
    }

    suspend fun exportBackupZip(
        context: Context,
        onProgress: ((Float, String) -> Unit)? = null
    ): ByteArray = withContext(Dispatchers.IO) {
        val jsonData = exportDataAsJsonString()
        val file = com.example.ui.util.ZipBackupHelper.exportBackupZip(
            context = context,
            jsonData = jsonData,
            attachments = attachments.value,
            onProgress = onProgress
        )
        file.readBytes()
    }

    suspend fun restoreBackupFromStream(
        context: Context,
        inputStream: InputStream,
        onProgress: ((Float, String) -> Unit)? = null
    ): ImportResult {
        return withContext(Dispatchers.IO) {
            val extracted = com.example.ui.util.ZipBackupHelper.extractBackup(context, inputStream, onProgress)
            importDataFromJsonString(extracted.jsonContent, isFromZip = extracted.isZip)
        }
    }

    fun exportBackupFile(context: Context): File {
        val backupDir = File(context.cacheDir, "backups").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        // If attachments exist, export as zip; otherwise json
        return runBlocking(Dispatchers.IO) {
            com.example.ui.util.ZipBackupHelper.exportBackupZip(
                context = context,
                jsonData = exportDataAsJsonString(),
                attachments = attachments.value
            )
        }
    }

    fun shareBackupFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val isZip = file.name.endsWith(".zip", ignoreCase = true)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isZip) "application/zip" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(
                Intent.EXTRA_SUBJECT,
                if (isZip) "Ghanshyam Kids Wear ZIP Data & Photo Backup" else "Ghanshyam Kids Wear Plain JSON Data Backup"
            )
            putExtra(
                Intent.EXTRA_TEXT,
                if (isZip) "Attached is the full ZIP database and photo backup file for Ghanshyam Kids Wear." else "Attached is the plain JSON database backup file for Ghanshyam Kids Wear."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, if (isZip) "Share ZIP Backup File" else "Share Plain JSON Backup File"))
    }

    // ==========================================
    // EXCEL / CSV SPREADSHEET EXPORTS
    // ==========================================

    private fun escapeCsv(value: Any?): String {
        val str = value?.toString() ?: ""
        return if (str.contains(",") || str.contains("\"") || str.contains("\n")) {
            "\"" + str.replace("\"", "\"\"") + "\""
        } else {
            str
        }
    }

    fun exportKhataLedgerCsv(context: Context): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(reportsDir, "Khata_Ledger_$dateStr.csv")

        val sb = StringBuilder()
        sb.append("Customer Name,Phone,Net Balance,Last Transaction Date\n")

        customers.value.forEach { customer ->
            val custTxns = transactions.value.filter { it.customerId == customer.id }
            val totalGave = custTxns.filter { it.type == "GAVE" }.sumOf { it.amount }
            val totalGot = custTxns.filter { it.type == "GOT" }.sumOf { it.amount }
            val netBalance = totalGave - totalGot
            val lastTxnDateMillis = custTxns.maxOfOrNull { it.date } ?: customer.createdAt
            val lastTxnDateStr = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault()).format(Date(lastTxnDateMillis))

            sb.append(escapeCsv(customer.name)).append(",")
            sb.append(escapeCsv(customer.phone)).append(",")
            sb.append(escapeCsv("%.2f".format(netBalance))).append(",")
            sb.append(escapeCsv(lastTxnDateStr)).append("\n")
        }

        file.writeText(sb.toString())
        return file
    }

    fun exportSalesReportCsv(context: Context): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(reportsDir, "Sales_Report_$dateStr.csv")

        val sb = StringBuilder()
        sb.append("Bill No,Date,Customer Name,Subtotal,GST,Discount,Total Amount,Payment Mode\n")

        invoices.value.forEach { invoice ->
            val dateFormatted = SimpleDateFormat("dd-MM-yyyy HH:mm", Locale.getDefault()).format(Date(invoice.date))
            sb.append(escapeCsv(invoice.billNumber)).append(",")
            sb.append(escapeCsv(dateFormatted)).append(",")
            sb.append(escapeCsv(invoice.customerName)).append(",")
            sb.append(escapeCsv("%.2f".format(invoice.subtotal))).append(",")
            sb.append(escapeCsv("%.2f".format(invoice.gstAmount))).append(",")
            sb.append(escapeCsv("%.2f".format(invoice.discount))).append(",")
            sb.append(escapeCsv("%.2f".format(invoice.totalAmount))).append(",")
            sb.append(escapeCsv(invoice.paymentMode)).append("\n")
        }

        file.writeText(sb.toString())
        return file
    }

    fun exportInventoryCsv(context: Context): File {
        val reportsDir = File(context.cacheDir, "reports").apply { mkdirs() }
        val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(reportsDir, "Inventory_Stock_$dateStr.csv")

        val sb = StringBuilder()
        sb.append("Item Code,Item Name,Category,Stock Quantity,Purchase Price,Sale Price\n")

        inventory.value.forEach { item ->
            sb.append(escapeCsv(item.itemCode)).append(",")
            sb.append(escapeCsv(item.name)).append(",")
            sb.append(escapeCsv(item.category)).append(",")
            sb.append(escapeCsv(item.stock)).append(",")
            sb.append(escapeCsv("%.2f".format(item.purchasePrice))).append(",")
            sb.append(escapeCsv("%.2f".format(item.salePrice))).append("\n")
        }

        file.writeText(sb.toString())
        return file
    }

    fun shareCsvFile(context: Context, file: File, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            putExtra(Intent.EXTRA_TEXT, "Spreadsheet report from Ghanshyam Kids Wear & Traders.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }
}
