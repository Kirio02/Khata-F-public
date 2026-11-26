package com.example

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.local.AppDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RoomMigrationTest {

    private val TEST_DB = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun testMigration1To2() {
        var db = helper.createDatabase(TEST_DB, 1)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, createdAt) VALUES ('c1', 'Ramesh', '9876543210', 'Market', '', 1000)")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, isCancelled, billNumber) VALUES ('t1', 'c1', 500.0, 'GAVE', 'Test note', 1000, 0, 'B1')")
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)
        val cursor = db.query("SELECT paymentMode, isCompleted FROM transactions WHERE id = 't1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("CASH", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration2To3() {
        var db = helper.createDatabase(TEST_DB, 2)
        db.execSQL("INSERT INTO invoices (id, billNumber, date, customerName, customerPhone, subtotal, gstAmount, discount, additionalCharges, totalAmount, paymentMode, stateOfSupply, gstin, notes, themeName) VALUES ('inv1', 'B1', 1000, 'Ramesh', '9876543210', 1000.0, 0.0, 0.0, 0.0, 1000.0, 'CASH', 'Gujarat', '', '', 'PREMIUM_BLUE')")
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3)
        val cursor = db.query("SELECT customerId, roundOff, isCancelled FROM invoices WHERE id = 'inv1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        assertEquals(0.0, cursor.getDouble(1), 0.01)
        assertEquals(0, cursor.getInt(2))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration3To4() {
        var db = helper.createDatabase(TEST_DB, 3)
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='chat_messages'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration4To5() {
        var db = helper.createDatabase(TEST_DB, 4)
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='attachments'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration5To6() {
        var db = helper.createDatabase(TEST_DB, 5)
        db.execSQL("INSERT INTO cash_entries (id, amount, type, category, remark, date, paymentMode, source) VALUES ('ce1', 1200.0, 'IN', 'Sales', 'Test remark', 1000, 'UPI', 'MANUAL')")
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)
        val cursor = db.query("SELECT bookType FROM cash_entries WHERE id = 'ce1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("BANK", cursor.getString(0))
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration5To6_withInvoiceLinkedId() {
        var db = helper.createDatabase(TEST_DB, 5)
        // Insert a cash entry whose linkedId is an invoice id (not in transactions table)
        // In version 5 schema, customers table has columns (id, name, phone, address, gstin, type, createdAt)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, type, createdAt) VALUES ('c1', 'Ramesh', '9876543210', 'Market', '', 'CUSTOMER', 1000)")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, paymentMode, linkedToTxId, isCompleted, billNumber) VALUES ('tx_real', 'c1', 500.0, 'GAVE', 'Tx note', 1000, 'CASH', NULL, 0, 'B1')")
        
        // ce_inv has linkedId pointing to an invoice id 'inv_999' which matches no row in transactions
        db.execSQL("INSERT INTO cash_entries (id, amount, type, category, remark, date, paymentMode, source, linkedId) VALUES ('ce_inv', 2500.0, 'IN', 'Sales', 'Invoice payment', 1000, 'CASH', 'MANUAL', 'inv_999')")
        // ce_tx has linkedId pointing to 'tx_real'
        db.execSQL("INSERT INTO cash_entries (id, amount, type, category, remark, date, paymentMode, source, linkedId) VALUES ('ce_tx', 500.0, 'IN', 'Payment', 'Customer payment', 1000, 'CASH', 'MANUAL', 'tx_real')")
        db.close()

        // Running migration 5 -> 6 must succeed without constraint violations
        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)

        // Verify ce_inv kept its other data and has safe empty partyId/partyName
        val cursorInv = db.query("SELECT id, amount, type, category, remark, linkedId, partyId, partyName, bookType FROM cash_entries WHERE id = 'ce_inv'")
        assertTrue("ce_inv must exist after migration", cursorInv.moveToFirst())
        assertEquals("ce_inv", cursorInv.getString(0))
        assertEquals(2500.0, cursorInv.getDouble(1), 0.01)
        assertEquals("IN", cursorInv.getString(2))
        assertEquals("Sales", cursorInv.getString(3))
        assertEquals("Invoice payment", cursorInv.getString(4))
        assertEquals("inv_999", cursorInv.getString(5))
        assertEquals("", cursorInv.getString(6)) // partyId is safely backfilled to empty string
        assertEquals("", cursorInv.getString(7)) // partyName is safely backfilled to empty string
        assertEquals("CASH", cursorInv.getString(8))
        cursorInv.close()

        // Verify ce_tx resolved partyId and partyName correctly
        val cursorTx = db.query("SELECT partyId, partyName FROM cash_entries WHERE id = 'ce_tx'")
        assertTrue("ce_tx must exist after migration", cursorTx.moveToFirst())
        assertEquals("c1", cursorTx.getString(0))
        assertEquals("Ramesh", cursorTx.getString(1))
        cursorTx.close()

        db.close()
    }

    @Test
    fun testMigration6To7() {
        var db = helper.createDatabase(TEST_DB, 6)
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='bank_day_checks'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration7To8() {
        var db = helper.createDatabase(TEST_DB, 7)
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AppDatabase.MIGRATION_7_8)
        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='delivery_tasks'")
        assertTrue(cursor.moveToFirst())
        cursor.close()
        db.close()
    }

    @Test
    fun testMigration8To9() {
        var db = helper.createDatabase(TEST_DB, 8)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, createdAt, type) VALUES ('c1', 'Ramesh', '9876543210', 'Market', '', 1000, 'CUSTOMER')")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, paymentMode, linkedToTxId, isCompleted, billNumber) VALUES ('t1', 'c1', 500.0, 'GAVE', 'Test note', 1000, 'CASH', NULL, 0, 'B1')")
        db.execSQL("INSERT INTO store_profile (id, storeName, address, phone, upiId, gstin, ownerName, state, logoUri) VALUES (1, 'Ghanshyam', 'Market', '9876543210', '', '', '', 'Gujarat', '')")
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)
        val cursor = db.query("SELECT dueDate FROM transactions WHERE id = 't1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0L, cursor.getLong(0))
        cursor.close()

        val pCursor = db.query("SELECT email FROM store_profile WHERE id = 1")
        assertTrue(pCursor.moveToFirst())
        assertEquals("", pCursor.getString(0))
        pCursor.close()
        db.close()
    }

    @Test
    fun testEveryStepSequentially1To9() {
        // Step 1: Create at version 1
        var db = helper.createDatabase(TEST_DB, 1)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, createdAt) VALUES ('c1', 'Ramesh', '9876543210', 'Market', '', 1000)")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, isCancelled, billNumber) VALUES ('t1', 'c1', 500.0, 'GAVE', 'Tx1', 1000, 0, 'B1')")
        db.close()

        // Step 1 -> 2
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true, AppDatabase.MIGRATION_1_2)
        db.close()

        // Step 2 -> 3
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true, AppDatabase.MIGRATION_2_3)
        db.close()

        // Step 3 -> 4
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true, AppDatabase.MIGRATION_3_4)
        db.close()

        // Step 4 -> 5
        db = helper.runMigrationsAndValidate(TEST_DB, 5, true, AppDatabase.MIGRATION_4_5)
        db.close()

        // Step 5 -> 6
        db = helper.runMigrationsAndValidate(TEST_DB, 6, true, AppDatabase.MIGRATION_5_6)
        db.close()

        // Step 6 -> 7
        db = helper.runMigrationsAndValidate(TEST_DB, 7, true, AppDatabase.MIGRATION_6_7)
        db.close()

        // Step 7 -> 8
        db = helper.runMigrationsAndValidate(TEST_DB, 8, true, AppDatabase.MIGRATION_7_8)
        db.close()

        // Step 8 -> 9
        db = helper.runMigrationsAndValidate(TEST_DB, 9, true, AppDatabase.MIGRATION_8_9)

        // Verify final schema & data integrity at version 9
        val cursor = db.query("SELECT id, name FROM customers WHERE id = 'c1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Ramesh", cursor.getString(1))
        cursor.close()

        val txCursor = db.query("SELECT id, amount, dueDate FROM transactions WHERE id = 't1'")
        assertTrue(txCursor.moveToFirst())
        assertEquals(500.0, txCursor.getDouble(1), 0.01)
        assertEquals(0L, txCursor.getLong(2))
        txCursor.close()
        db.close()
    }

    @Test
    fun testAllMigrations1To9() {
        var db = helper.createDatabase(TEST_DB, 1)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, createdAt) VALUES ('c1', 'Ramesh', '9876543210', 'Market', '', 1000)")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, isCancelled, billNumber) VALUES ('t1', 'c1', 500.0, 'GAVE', 'First Tx', 1000, 0, 'B1')")
        db.close()

        db = helper.runMigrationsAndValidate(
            TEST_DB,
            9,
            true,
            AppDatabase.MIGRATION_1_2,
            AppDatabase.MIGRATION_2_3,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9
        )

        // Verify customer and transaction data survived end-to-end migration
        val cursor = db.query("SELECT id, name FROM customers WHERE id = 'c1'")
        assertTrue("Customer record should survive 1->9 migration", cursor.moveToFirst())
        assertEquals("Ramesh", cursor.getString(1))
        cursor.close()

        val txCursor = db.query("SELECT id, amount, dueDate FROM transactions WHERE id = 't1'")
        assertTrue("Transaction record should survive 1->9 migration", txCursor.moveToFirst())
        assertEquals(500.0, txCursor.getDouble(1), 0.01)
        assertEquals(0L, txCursor.getLong(2))
        txCursor.close()
        db.close()
    }

    @Test
    fun testMigration9To10KeepsEntriesAndAddsEmptyChallan() {
        var db = helper.createDatabase(TEST_DB, 9)
        db.execSQL("INSERT INTO customers (id, name, phone, address, gstin, createdAt, type) VALUES ('c1', 'Test Party', '9876500001', '', '', 1000, 'CUSTOMER')")
        db.execSQL("INSERT INTO transactions (id, customerId, amount, type, note, date, paymentMode, linkedToTxId, isCompleted, billNumber, dueDate) VALUES ('t1', 'c1', 750.0, 'GAVE', '985', 1000, 'CASH', NULL, 0, '', 0)")
        db.close()

        db = helper.runMigrationsAndValidate(TEST_DB, 10, true, AppDatabase.MIGRATION_9_10)
        val c = db.query("SELECT amount, note, challanNo FROM transactions WHERE id = 't1'")
        assertTrue(c.moveToFirst())
        assertEquals(750.0, c.getDouble(0), 0.001)
        assertEquals("985", c.getString(1))
        assertEquals("", c.getString(2))
        c.close()
        db.close()
    }
}
