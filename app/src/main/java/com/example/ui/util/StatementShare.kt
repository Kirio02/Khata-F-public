package com.example.ui.util

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.model.AppLanguage
import com.example.model.Attachment
import com.example.model.Customer
import com.example.model.StoreProfile
import com.example.model.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun sendStatementPdfToWhatsApp(
    context: Context,
    customer: Customer,
    pdfFile: File,
    lang: AppLanguage = AppLanguage.HINGLISH
) {
    if (!pdfFile.exists()) {
        Toast.makeText(context, LanguageStrings.get("remind_pdf_failed", lang), Toast.LENGTH_SHORT).show()
        return
    }
    val waNumber = PhoneUtil.toWhatsAppNumber(customer.phone)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdfFile)
    val pdfIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (waNumber.isNotBlank()) putExtra("jid", "$waNumber@s.whatsapp.net")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val whatsAppPackages = listOf("com.whatsapp", "com.whatsapp.w4b")
    for (pkg in whatsAppPackages) {
        try {
            context.startActivity(Intent(pdfIntent).apply { setPackage(pkg) })
            return
        } catch (_: Exception) {
            // Try next WhatsApp package
        }
    }
    try {
        context.startActivity(
            Intent.createChooser(pdfIntent, LanguageStrings.get("share_pdf", lang))
        )
    } catch (_: Exception) {
        Toast.makeText(context, LanguageStrings.get("remind_pdf_failed", lang), Toast.LENGTH_SHORT).show()
    }
}

suspend fun sendFullStatementPdfToWhatsApp(
    context: Context,
    customer: Customer,
    allTransactions: List<Transaction>,
    allAttachments: List<Attachment>,
    storeProfile: StoreProfile,
    lang: AppLanguage
) {
    val pdfFile = try {
        withContext(Dispatchers.IO) {
            val statement = StatementGenerator.computeStatement(
                customer = customer,
                allTransactions = allTransactions,
                dateRange = StatementDateRange.ALL,
                filter = StatementFilter.ALL_ENTRIES
            )
            val txIds = statement.entries.map { it.transaction.id }.toSet()
            val photosByTx = allAttachments
                .filter { it.ownerType == "ENTRY" && it.ownerId in txIds && it.mimeType.startsWith("image") }
                .sortedBy { it.createdAt }
                .groupBy({ it.ownerId }, { it.filePath })
            StatementGenerator.generateStatementPdf(
                context = context,
                statement = statement,
                storeProfile = storeProfile,
                photosByTx = photosByTx,
                appLang = lang
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("StatementShare", "Failed to generate statement PDF: ${e.message}", e)
        null
    }

    if (pdfFile == null || !pdfFile.exists()) {
        Toast.makeText(context, LanguageStrings.get("remind_pdf_failed", lang), Toast.LENGTH_SHORT).show()
        return
    }
    sendStatementPdfToWhatsApp(
        context = context,
        customer = customer,
        pdfFile = pdfFile,
        lang = lang
    )
}
