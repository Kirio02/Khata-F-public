package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Moving balances over from Khatabook: a row is either imported exactly, or not at all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BalanceImportTest {

    private lateinit var repo: AppRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repo = AppRepository(context, asyncInit = false)
        repo.clearAllData()
    }

    private fun importAndGetBalances(csv: String): Map<String, Double> {
        repo.importCustomersFromCsv(repo.previewCustomersFromCsv(csv))
        return repo.customers.value.associate { it.name to repo.getCustomerBalance(it.id) }
    }

    @Test
    fun quotedIndianAmountKeepsItsValue() {
        val csv = "name,phone,balance\nTest A,9876500001,\"4,750.50\"\nTest B,9876500002,\"1,04,750\""
        assertEquals(mapOf("Test A" to 4750.5, "Test B" to 104750.0), importAndGetBalances(csv))
    }

    @Test
    fun unquotedCommaInAmountIsNeverSavedAsASmallerAmount() {
        assertEquals(emptyMap<String, Double>(), importAndGetBalances("name,phone,balance\nTest A,9876500001,4,750"))
    }

    @Test
    fun rupeeSignsAreUnderstood() {
        val csv = "name,phone,balance\nTest A,9876500001,₹4750\nTest B,9876500002,Rs. 700\nTest C,9876500003,-₹300"
        assertEquals(mapOf("Test A" to 4750.0, "Test B" to 700.0, "Test C" to -300.0), importAndGetBalances(csv))
    }

    @Test
    fun unreadableAmountIsNotSavedAsZero() {
        val csv = "name,phone,balance\nTest A,9876500001,five hundred\nTest B,9876500002,NaN"
        assertEquals(emptyMap<String, Double>(), importAndGetBalances(csv))
    }

    @Test
    fun excelUtf8HeaderDoesNotBecomeAParty() {
        assertEquals(mapOf("Test A" to 100.0), importAndGetBalances("﻿name,phone,balance\r\nTest A,9876500001,100\r\n"))
    }

    @Test
    fun excelScientificPhoneIsNotSaved() {
        assertEquals(emptyMap<String, Double>(), importAndGetBalances("name,phone,balance\nTest A,9.87654E+09,100"))
    }

    @Test
    fun nameWithACommaStaysWhole() {
        assertEquals(mapOf("Test, Surat" to 250.0), importAndGetBalances("name,phone,balance\n\"Test, Surat\",9876500001,250"))
    }

    @Test
    fun existingPartyIsFoundWhateverThePhoneFormat() {
        repo.addCustomer("Saved Name", "919876500001")
        val preview = repo.previewCustomersFromCsv("name,phone,balance\nOther Spelling,98765 00001,900")
        assertTrue(preview.single().isDuplicate)
    }

    @Test
    fun runningTheSameFileTwiceDoesNotDoubleAnything() {
        val csv = "name,phone,balance\nTest A,9876500001,400\nTest B,,250"
        importAndGetBalances(csv)
        assertEquals(mapOf("Test A" to 400.0, "Test B" to 250.0), importAndGetBalances(csv))
    }

    @Test
    fun staleOrDoubleTappedPreviewDoesNotDoubleAnything() {
        val preview = repo.previewCustomersFromCsv("name,phone,balance\nTest A,9876500001,400")
        repo.importCustomersFromCsv(preview)
        repo.importCustomersFromCsv(preview)
        assertEquals(1, repo.customers.value.size)
    }
}
