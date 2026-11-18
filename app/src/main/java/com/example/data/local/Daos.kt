package com.example.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomerDao {
    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    fun getAll(): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers ORDER BY createdAt DESC")
    suspend fun getAllList(): List<CustomerEntity>

    @Query("SELECT * FROM customers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CustomerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(customer: CustomerEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(customers: List<CustomerEntity>)

    @Update
    suspend fun update(customer: CustomerEntity)

    @Query("DELETE FROM customers WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM customers")
    suspend fun deleteAll()
}

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    suspend fun getAllList(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE customerId = :customerId ORDER BY date DESC")
    suspend fun getByCustomerId(customerId: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE billNumber = :billNumber")
    suspend fun getByBillNumber(billNumber: String): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE linkedToTxId = :linkedToTxId")
    suspend fun getByLinkedToTxId(linkedToTxId: String): List<TransactionEntity>

    @Query("DELETE FROM transactions WHERE linkedToTxId = :linkedToTxId")
    suspend fun deleteByLinkedToTxId(linkedToTxId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Update
    suspend fun update(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM transactions WHERE customerId = :customerId")
    suspend fun deleteByCustomerId(customerId: String)

    @Query("DELETE FROM transactions WHERE billNumber = :billNumber")
    suspend fun deleteByBillNumber(billNumber: String)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    fun getAll(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_items ORDER BY name ASC")
    suspend fun getAllList(): List<InventoryItemEntity>

    @Query("SELECT * FROM inventory_items WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): InventoryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<InventoryItemEntity>)

    @Update
    suspend fun update(item: InventoryItemEntity)

    @Query("DELETE FROM inventory_items WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM inventory_items")
    suspend fun deleteAll()
}

@Dao
interface InvoiceDao {
    @Transaction
    @Query("SELECT * FROM invoices ORDER BY date DESC")
    fun getAllWithItems(): Flow<List<InvoiceWithItems>>

    @Transaction
    @Query("SELECT * FROM invoices ORDER BY date DESC")
    suspend fun getAllWithItemsList(): List<InvoiceWithItems>

    @Transaction
    @Query("SELECT * FROM invoices WHERE id = :id LIMIT 1")
    suspend fun getWithItemsById(id: String): InvoiceWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoices(invoices: List<InvoiceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<InvoiceItemEntity>)

    @Update
    suspend fun update(invoice: InvoiceEntity)

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteItemsByInvoiceId(invoiceId: String)

    @Query("DELETE FROM invoices")
    suspend fun deleteAll()
}

@Dao
interface CashbookDao {
    @Query("SELECT * FROM cash_entries WHERE isDeleted = 0 ORDER BY date DESC")
    fun getAll(): Flow<List<CashEntryEntity>>

    @Query("SELECT * FROM cash_entries WHERE isDeleted = 0 ORDER BY date DESC")
    suspend fun getAllList(): List<CashEntryEntity>

    @Query("SELECT * FROM cash_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun getBinEntries(): Flow<List<CashEntryEntity>>

    @Query("SELECT * FROM cash_entries WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    suspend fun getBinEntriesList(): List<CashEntryEntity>

    @Query("SELECT * FROM cash_entries WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CashEntryEntity?

    @Query("SELECT * FROM cash_entries WHERE linkedId = :linkedId LIMIT 1")
    suspend fun getByLinkedId(linkedId: String): CashEntryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CashEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<CashEntryEntity>)

    @Update
    suspend fun update(entry: CashEntryEntity)

    @Query("DELETE FROM cash_entries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM cash_entries WHERE linkedId = :linkedId")
    suspend fun deleteByLinkedId(linkedId: String)

    @Query("UPDATE cash_entries SET bankVerified = :verified, verifiedAt = :verifiedAt WHERE id = :id")
    suspend fun updateBankVerification(id: String, verified: Boolean, verifiedAt: Long)

    @Query("UPDATE cash_entries SET bankVerified = :verified, verifiedAt = :verifiedAt WHERE id IN (:ids)")
    suspend fun updateBankVerificationBulk(ids: List<String>, verified: Boolean, verifiedAt: Long)

    @Query("UPDATE cash_entries SET unmatchedNote = :note WHERE id = :id")
    suspend fun updateUnmatchedNote(id: String, note: String)

    @Query("DELETE FROM cash_entries WHERE linkedId IN (:linkedIds)")
    suspend fun deleteByLinkedIds(linkedIds: List<String>)

    @Query("DELETE FROM cash_entries WHERE isDeleted = 1 AND deletedAt < :cutoffTime")
    suspend fun deleteExpiredBinEntries(cutoffTime: Long): Int

    @Query("DELETE FROM cash_entries")
    suspend fun deleteAll()
}

@Dao
interface DrawerCountDao {
    @Query("SELECT * FROM drawer_counts ORDER BY date DESC, createdAt DESC")
    fun getAll(): Flow<List<DrawerCountEntity>>

    @Query("SELECT * FROM drawer_counts ORDER BY date DESC, createdAt DESC")
    suspend fun getAllList(): List<DrawerCountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(count: DrawerCountEntity)

    @Query("DELETE FROM drawer_counts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM drawer_counts")
    suspend fun deleteAll()
}

@Dao
interface BankDayCheckDao {
    @Query("SELECT * FROM bank_day_checks ORDER BY date DESC, createdAt DESC")
    fun getAll(): Flow<List<BankDayCheckEntity>>

    @Query("SELECT * FROM bank_day_checks ORDER BY date DESC, createdAt DESC")
    suspend fun getAllList(): List<BankDayCheckEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(check: BankDayCheckEntity)

    @Query("DELETE FROM bank_day_checks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bank_day_checks")
    suspend fun deleteAll()
}

@Dao
interface DeliveryTaskDao {
    @Query("SELECT * FROM delivery_tasks ORDER BY isCompleted ASC, createdAt DESC")
    fun getAll(): Flow<List<DeliveryTaskEntity>>

    @Query("SELECT * FROM delivery_tasks ORDER BY isCompleted ASC, createdAt DESC")
    suspend fun getAllList(): List<DeliveryTaskEntity>

    @Query("SELECT * FROM delivery_tasks WHERE (date >= :startOfDay AND date <= :endOfDay) OR (isCompleted = 0) ORDER BY isCompleted ASC, createdAt DESC")
    fun getTodayTasks(startOfDay: Long, endOfDay: Long): Flow<List<DeliveryTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: DeliveryTaskEntity)

    @Query("UPDATE delivery_tasks SET isCompleted = :isCompleted, completedAt = :completedAt WHERE id = :id")
    suspend fun setCompleted(id: String, isCompleted: Boolean, completedAt: Long)

    @Query("DELETE FROM delivery_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM delivery_tasks")
    suspend fun deleteAll()
}


@Dao
interface StoreProfileDao {
    @Query("SELECT * FROM store_profile WHERE id = 1 LIMIT 1")
    fun getProfile(): Flow<StoreProfileEntity?>

    @Query("SELECT * FROM store_profile WHERE id = 1 LIMIT 1")
    suspend fun getProfileDirect(): StoreProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: StoreProfileEntity)

    @Query("DELETE FROM store_profile")
    suspend fun delete()
}

@Dao
interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    fun getSettings(): Flow<AppSettingsEntity?>

    @Query("SELECT * FROM app_settings WHERE id = 1 LIMIT 1")
    suspend fun getSettingsDirect(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(settings: AppSettingsEntity)

    @Query("DELETE FROM app_settings")
    suspend fun delete()
}

@Dao
interface InvoiceSequenceDao {
    @Query("SELECT * FROM invoice_sequences WHERE financialYear = :fy LIMIT 1")
    suspend fun getByFy(fy: String): InvoiceSequenceEntity?

    @Query("SELECT * FROM invoice_sequences")
    suspend fun getAllList(): List<InvoiceSequenceEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(seq: InvoiceSequenceEntity)

    @Query("DELETE FROM invoice_sequences")
    suspend fun deleteAll()
}

@Dao
interface ChatMessageDao {
    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
    suspend fun getAllMessagesList(): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessageEntity)

    @Query("DELETE FROM chat_messages WHERE id NOT IN (SELECT id FROM chat_messages ORDER BY timestamp DESC LIMIT 50)")
    suspend fun trimToLast50()

    @Query("DELETE FROM chat_messages")
    suspend fun deleteAll()
}

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt ASC")
    fun getByOwner(ownerType: String, ownerId: String): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId ORDER BY createdAt ASC")
    suspend fun getByOwnerList(ownerType: String, ownerId: String): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE ownerId IN (:ownerIds) ORDER BY createdAt ASC")
    suspend fun getByOwnerIdsList(ownerIds: List<String>): List<AttachmentEntity>

    @Query("SELECT * FROM attachments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AttachmentEntity?

    @Query("SELECT * FROM attachments ORDER BY createdAt DESC")
    fun getAll(): Flow<List<AttachmentEntity>>

    @Query("SELECT * FROM attachments ORDER BY createdAt DESC")
    suspend fun getAllList(): List<AttachmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("UPDATE attachments SET caption = :caption WHERE id = :id")
    suspend fun updateCaption(id: String, caption: String)

    @Query("DELETE FROM attachments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM attachments WHERE ownerType = :ownerType AND ownerId = :ownerId")
    suspend fun deleteByOwner(ownerType: String, ownerId: String)

    @Query("DELETE FROM attachments WHERE ownerType = :ownerType AND ownerId IN (:ownerIds)")
    suspend fun deleteByOwnerIds(ownerType: String, ownerIds: List<String>)

    @Query("DELETE FROM attachments")
    suspend fun deleteAll()
}


