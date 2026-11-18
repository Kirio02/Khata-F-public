package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ContactImportTest {

    private lateinit var context: Context
    private lateinit var repository: AppRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) {
            db.clearAllTables()
        }
        repository = AppRepository(context, asyncInit = false)
        repository.clearAllData()
    }

    @Test
    fun bulkImportSkipsNumbersAlreadyInKhataAndBlankNames() {
        repository.addCustomer("Ramesh", "98765 43210")

        val added = runBlocking {
            repository.addCustomersBulk(
                listOf(
                    "Ramesh Bhai" to "+91 98765 43210", // same number as the party already in the khata
                    "Suresh" to "9123456780",
                    "Suresh Home" to "09123456780",     // same number as Suresh, written differently
                    "   " to "9000000001",              // blank name
                    "Mahesh" to "9000000002"
                )
            )
        }

        assertEquals(2, added)
        val names = repository.customers.value.map { it.name }
        assertEquals(3, names.size)
        assertTrue(names.containsAll(listOf("Ramesh", "Suresh", "Mahesh")))
    }
}
