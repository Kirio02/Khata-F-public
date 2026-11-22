package com.example

import android.content.Context
import com.example.data.AppRepository
import com.example.data.local.AppDatabase
import com.example.model.Invoice
import com.example.ui.util.EntryDate
import com.example.ui.util.RelativeDate
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Back-dating must move every linked record together and never change an amount. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EntryDateTest {

    private lateinit var repo: AppRepository
    private val now = System.currentTimeMillis()
    private val fiveDaysAgo = EntryDate.daysAgo(5, now)
    private val threeDaysAgo = EntryDate.daysAgo(3, now)

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("ghanshyam_billing_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val db = AppDatabase.getInstance(context)
        runBlocking(Dispatchers.IO) { db.clearAllTables() }
        repo = AppRepository(context, asyncInit = false)
        repo.clearAllData()
    }

    @Test
    fun pickingADayKeepsTheTimeOfDay() {
        val from = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 22, 21, 5, 7)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val c = Calendar.getInstance().apply { timeInMillis = EntryDate.onDay(2026, Calendar.MARCH, 1, from) }
        assertEquals(listOf(2026, Calendar.MARCH, 1, 21, 5), listOf(Calendar.YEAR, Calendar.MONTH, Calendar.DAY_OF_MONTH, Calendar.HOUR_OF_DAY, Calendar.MINUTE).map { c.get(it) })
    }

    @Test
    fun unpickedDateMeansTimeOfSavingNotTimeFormWasOpened() {
        assertEquals(now, EntryDate.resolve(EntryDate.NOW, now))
        assertEquals(fiveDaysAgo, EntryDate.resolve(fiveDaysAgo, now))
    }

    @Test
    fun dueDateCountsFromEntryDateAndCalendarPickWins() {
        assertEquals(0L, EntryDate.due(fiveDaysAgo, 0, EntryDate.NOW))
        assertEquals(7, RelativeDate.daysBetween(fiveDaysAgo, EntryDate.due(fiveDaysAgo, 7, EntryDate.NOW)))
        assertEquals(threeDaysAgo, EntryDate.due(fiveDaysAgo, 7, threeDaysAgo))
    }

    @Test
    fun backDatedPaymentHasSameDateInKhataAndCashbookAndIsNotTodays() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val tx = repo.addTransaction(party.id, 700.0, "GOT", date = fiveDaysAgo)
        assertEquals(fiveDaysAgo, repo.cashbook.value.single { it.linkedId == tx.id }.date)
        assertEquals(0.0, repo.getTodaySummary().first, 0.001)
        assertEquals(-700.0, repo.getCustomerBalance(party.id), 0.001)
    }

    @Test
    fun markReceivedUsesThePickedDate() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val bill = repo.addTransaction(party.id, 900.0, "GAVE", date = fiveDaysAgo)
        val payment = repo.markBillReceived(bill.id, 900.0, date = threeDaysAgo)!!
        assertEquals(threeDaysAgo, payment.date)
        assertEquals(threeDaysAgo, repo.cashbook.value.single { it.linkedId == payment.id }.date)
    }

    @Test
    fun markAllReceivedUsesThePickedDate() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val a = repo.addTransaction(party.id, 100.0, "GAVE", date = fiveDaysAgo)
        val b = repo.addTransaction(party.id, 200.0, "GAVE", date = fiveDaysAgo)
        repo.markBillsAllReceived(billIds = listOf(a.id, b.id), date = threeDaysAgo)
        repo.refreshAllInMemoryCaches()
        assertEquals(listOf(threeDaysAgo, threeDaysAgo), repo.cashbook.value.map { it.date })
        assertEquals(0.0, repo.getCustomerBalance(party.id), 0.001)
    }

    @Test
    fun reDatingABillMovesInvoicePaymentAndCashButNotLaterPayments() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val invoice = repo.createInvoice(
            invoice = Invoice(billNumber = "", date = threeDaysAgo, customerId = party.id, customerName = party.name, subtotal = 1000.0, totalAmount = 1000.0, paymentMode = "PARTIAL"),
            receivedNow = 400.0,
            receivedPaymentMode = "CASH"
        )
        val bill = repo.transactions.value.single { it.type == "GAVE" }
        val later = repo.markBillReceived(bill.id, 100.0, date = now)!!

        repo.editTransaction(bill.id, bill.amount, bill.note, fiveDaysAgo, bill.paymentMode)

        val txs = repo.transactions.value
        assertEquals(fiveDaysAgo, txs.single { it.id == bill.id }.date)
        assertEquals(fiveDaysAgo, txs.single { it.amount == 400.0 }.date)
        assertEquals(now, txs.single { it.id == later.id }.date)
        assertEquals(fiveDaysAgo, repo.cashbook.value.single { it.linkedId == invoice.id }.date)
        assertEquals(fiveDaysAgo, repo.invoices.value.single().date)
        assertEquals(500.0, repo.getCustomerBalance(party.id), 0.001)
    }

    @Test
    fun walkInSaleNeverOpensAKhataPartyInAnyLanguage() {
        val names = com.example.model.AppLanguage.entries.map {
            com.example.ui.util.LanguageStrings.get("quick_cash_customer", it)
        } + "Cash Customer"
        names.forEach { name ->
            repo.createInvoice(
                invoice = Invoice(billNumber = "", customerName = name, subtotal = 100.0, totalAmount = 100.0, paymentMode = "CASH"),
                receivedNow = 100.0,
                receivedPaymentMode = "CASH"
            )
        }
        assertEquals(emptyList<String>(), repo.customers.value.map { it.name })
        assertEquals(0, repo.transactions.value.size)
    }

    @Test
    fun editingOnlyTheAmountDoesNotMoveAnyDate() {
        val party = repo.addCustomer("Test Party", "9876500001")
        val tx = repo.addTransaction(party.id, 300.0, "GOT", date = fiveDaysAgo)
        repo.editTransaction(tx.id, 350.0, tx.note, tx.date, tx.paymentMode)
        assertEquals(fiveDaysAgo, repo.transactions.value.single().date)
        assertEquals(fiveDaysAgo, repo.cashbook.value.single().date)
    }
}
