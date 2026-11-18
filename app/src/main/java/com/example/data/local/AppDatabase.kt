package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CustomerEntity::class,
        TransactionEntity::class,
        InventoryItemEntity::class,
        InvoiceEntity::class,
        InvoiceItemEntity::class,
        CashEntryEntity::class,
        StoreProfileEntity::class,
        AppSettingsEntity::class,
        InvoiceSequenceEntity::class,
        ChatMessageEntity::class,
        AttachmentEntity::class,
        DrawerCountEntity::class,
        BankDayCheckEntity::class,
        DeliveryTaskEntity::class
    ],
    version = 9,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun customerDao(): CustomerDao
    abstract fun transactionDao(): TransactionDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun cashbookDao(): CashbookDao
    abstract fun storeProfileDao(): StoreProfileDao
    abstract fun appSettingsDao(): AppSettingsDao
    abstract fun invoiceSequenceDao(): InvoiceSequenceDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun drawerCountDao(): DrawerCountDao
    abstract fun bankDayCheckDao(): BankDayCheckDao
    abstract fun deliveryTaskDao(): DeliveryTaskDao


    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add party type to customers
                db.execSQL("ALTER TABLE customers ADD COLUMN type TEXT NOT NULL DEFAULT 'CUSTOMER'")

                // 2. Recreate transactions table to drop isCancelled and add paymentMode, linkedToTxId, isCompleted
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS transactions_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        customerId TEXT NOT NULL,
                        amount REAL NOT NULL,
                        type TEXT NOT NULL,
                        note TEXT NOT NULL,
                        date INTEGER NOT NULL,
                        paymentMode TEXT NOT NULL DEFAULT 'CASH',
                        linkedToTxId TEXT DEFAULT NULL,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        billNumber TEXT NOT NULL,
                        FOREIGN KEY(customerId) REFERENCES customers(id) ON DELETE CASCADE
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT INTO transactions_new (id, customerId, amount, type, note, date, paymentMode, linkedToTxId, isCompleted, billNumber)
                    SELECT id, customerId, amount, type, note, date, 'CASH', NULL, 0, billNumber
                    FROM transactions
                    WHERE NOT (isCancelled = 1 AND type = 'GOT')
                """.trimIndent())

                // Map old isCancelled = 1 rows into linked settled payments
                val cursor = db.query("SELECT id, customerId, amount, date FROM transactions WHERE isCancelled = 1 AND type = 'GAVE'")
                while (cursor.moveToNext()) {
                    val billId = cursor.getString(0)
                    val custId = cursor.getString(1)
                    val amt = cursor.getDouble(2)
                    val dt = cursor.getLong(3)
                    val paymentId = java.util.UUID.randomUUID().toString()
                    db.execSQL(
                        "INSERT INTO transactions_new (id, customerId, amount, type, note, date, paymentMode, linkedToTxId, isCompleted, billNumber) VALUES (?, ?, ?, 'GOT', 'Settled (migrated)', ?, 'CASH', ?, 1, '')",
                        arrayOf(paymentId, custId, amt, dt, billId)
                    )
                    db.execSQL("UPDATE transactions_new SET isCompleted = 1 WHERE id = ?", arrayOf(billId))
                }
                cursor.close()

                db.execSQL("DROP TABLE transactions")
                db.execSQL("ALTER TABLE transactions_new RENAME TO transactions")

                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_customerId ON transactions(customerId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_date ON transactions(date)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_linkedToTxId ON transactions(linkedToTxId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Invoices
                db.execSQL("ALTER TABLE invoices ADD COLUMN customerId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoices ADD COLUMN roundOff REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN isCancelled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN cancellationReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_invoices_billNumber ON invoices(billNumber)")

                // Invoice items
                db.execSQL("ALTER TABLE invoice_items ADD COLUMN itemId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE invoice_items ADD COLUMN size TEXT NOT NULL DEFAULT 'Free'")
                db.execSQL("ALTER TABLE invoice_items ADD COLUMN hsnCode TEXT NOT NULL DEFAULT ''")

                // Inventory items
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN hsnCode TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN sizeStock TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN lowStockThreshold INTEGER NOT NULL DEFAULT 5")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN packSize INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE inventory_items ADD COLUMN packRatio TEXT NOT NULL DEFAULT '{}'")

                // Split stock evenly across listed sizes (with remainder on first size); fallback to 'Free'
                val itemCursor = db.query("SELECT id, stock, sizes FROM inventory_items")
                while (itemCursor.moveToNext()) {
                    val id = itemCursor.getString(0)
                    val stock = itemCursor.getInt(1)
                    val sizesJson = itemCursor.getString(2) ?: "[]"

                    val sizesList = mutableListOf<String>()
                    try {
                        val arr = org.json.JSONArray(sizesJson)
                        for (i in 0 until arr.length()) {
                            val s = arr.optString(i, "").trim()
                            if (s.isNotEmpty()) sizesList.add(s)
                        }
                    } catch (_: Exception) {}

                    val sizeStockJson = if (sizesList.isNotEmpty()) {
                        val count = sizesList.size
                        val each = stock / count
                        val remainder = stock % count
                        val obj = org.json.JSONObject()
                        sizesList.forEachIndexed { index, size ->
                            val qty = if (index == 0) each + remainder else each
                            obj.put(size, qty)
                        }
                        obj.toString()
                    } else {
                        val obj = org.json.JSONObject()
                        obj.put("Free", stock)
                        obj.toString()
                    }

                    db.execSQL(
                        "UPDATE inventory_items SET sizeStock = ? WHERE id = ?",
                        arrayOf(sizeStockJson, id)
                    )
                }
                itemCursor.close()

                // Store profile
                db.execSQL("ALTER TABLE store_profile ADD COLUMN logoUri TEXT NOT NULL DEFAULT ''")

                // Invoice sequences table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS invoice_sequences (
                        financialYear TEXT NOT NULL PRIMARY KEY,
                        lastNumber INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id TEXT PRIMARY KEY NOT NULL,
                        sender TEXT NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        actionTitle TEXT,
                        reportContent TEXT,
                        isOutOfScope INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_chat_messages_timestamp ON chat_messages(timestamp)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS attachments (
                        id TEXT NOT NULL PRIMARY KEY,
                        ownerType TEXT NOT NULL,
                        ownerId TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        thumbPath TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        caption TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_ownerType_ownerId ON attachments(ownerType, ownerId)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add new columns to cash_entries
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN bookType TEXT NOT NULL DEFAULT 'CASH'")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN reference TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN chequeStatus TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN partyName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN partyId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN isDeleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN deletedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN originalAmount REAL DEFAULT NULL")

                // 2. Classify UPI / BANK / CHEQUE / ONLINE as BANK bookType
                db.execSQL("""
                    UPDATE cash_entries 
                    SET bookType = 'BANK' 
                    WHERE UPPER(paymentMode) IN ('UPI', 'BANK', 'CHEQUE', 'BANK TRANSFER', 'ONLINE')
                """.trimIndent())

                // 3. Backfill partyName and partyId from linked transactions if available
                db.execSQL("""
                    UPDATE cash_entries 
                    SET partyId = COALESCE((SELECT customerId FROM transactions WHERE transactions.id = cash_entries.linkedId), ''),
                        partyName = COALESCE((SELECT customers.name FROM customers JOIN transactions ON transactions.customerId = customers.id WHERE transactions.id = cash_entries.linkedId), '')
                    WHERE linkedId IS NOT NULL AND linkedId != ''
                """.trimIndent())

                // 4. Create drawer_counts table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS drawer_counts (
                        id TEXT NOT NULL PRIMARY KEY,
                        date INTEGER NOT NULL,
                        actualCash REAL NOT NULL,
                        bookBalance REAL NOT NULL,
                        difference REAL NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_drawer_counts_date ON drawer_counts(date)")

                // 5. Add opening balance fields to app_settings
                db.execSQL("ALTER TABLE app_settings ADD COLUMN cashOpeningBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN cashOpeningDate INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN bankOpeningBalance REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN bankOpeningDate INTEGER NOT NULL DEFAULT 0")

                // 6. Indices for cash_entries
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_entries_bookType ON cash_entries(bookType)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_entries_isDeleted ON cash_entries(isDeleted)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add bank verification and explanation columns to cash_entries
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN bankVerified INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN verifiedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cash_entries ADD COLUMN unmatchedNote TEXT NOT NULL DEFAULT ''")

                // 2. Index for bank verification query performance
                db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_entries_bankVerified ON cash_entries(bankVerified)")

                // 3. Create bank_day_checks table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bank_day_checks (
                        id TEXT NOT NULL PRIMARY KEY,
                        date INTEGER NOT NULL,
                        sheetBalance REAL NOT NULL,
                        bookBalance REAL NOT NULL,
                        difference REAL NOT NULL,
                        note TEXT NOT NULL DEFAULT '',
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bank_day_checks_date ON bank_day_checks(date)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Create delivery_tasks table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS delivery_tasks (
                        id TEXT NOT NULL PRIMARY KEY,
                        partyName TEXT NOT NULL,
                        partyId TEXT,
                        note TEXT NOT NULL DEFAULT '',
                        date INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL DEFAULT 0,
                        completedAt INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_delivery_tasks_date ON delivery_tasks(date)")

                // 2. Add simpleMode, hasCompletedBatteryGuide, and lastBackupTimestamp to app_settings
                db.execSQL("ALTER TABLE app_settings ADD COLUMN simpleMode INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN hasCompletedBatteryGuide INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_settings ADD COLUMN lastBackupTimestamp INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Add dueDate to transactions
                db.execSQL("ALTER TABLE transactions ADD COLUMN dueDate INTEGER NOT NULL DEFAULT 0")

                // 2. Add dueDate to invoices
                db.execSQL("ALTER TABLE invoices ADD COLUMN dueDate INTEGER NOT NULL DEFAULT 0")

                // 3. Add email to store_profile
                db.execSQL("ALTER TABLE store_profile ADD COLUMN email TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "ghanshyam_billing.db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
