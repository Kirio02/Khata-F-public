package com.example.model

import java.util.UUID

enum class PartyType(val raw: String) {
    CUSTOMER("CUSTOMER"),
    SUPPLIER("SUPPLIER");

    companion object {
        fun fromString(value: String?): PartyType {
            return when (value?.uppercase()) {
                "SUPPLIER" -> SUPPLIER
                else -> CUSTOMER
            }
        }
    }
}

data class Customer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val address: String = "",
    val gstin: String = "",
    val type: String = "CUSTOMER", // "CUSTOMER" or "SUPPLIER"
    val createdAt: Long = System.currentTimeMillis()
)

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val customerId: String,
    val amount: Double,
    val type: String, // "GAVE" or "GOT"
    val note: String = "",
    val date: Long = System.currentTimeMillis(),
    val paymentMode: String = "CASH", // CASH, UPI, BANK
    val linkedToTxId: String? = null, // Links payment to a specific credit bill
    val isCompleted: Boolean = false, // Plain checkbox (goods delivered/packed), no money effect
    val billNumber: String = "",
    val dueDate: Long = 0L,
    val challanNo: String = "" // Delivery challan number, typed by the owner; shown as "CH. NO. 985"
)

data class InventoryItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val itemCode: String = "",
    val hsnCode: String = "",
    val salePrice: Double,
    val purchasePrice: Double = 0.0,
    val category: String = "General",
    val sizeStock: Map<String, Int> = mapOf("Free" to 0),
    val lowStockThreshold: Int = 5,
    val packSize: Int = 1,
    val packRatio: Map<String, Int> = emptyMap(),
    val gstPercent: Double = 0.0
) {
    val stock: Int get() = sizeStock.values.sum()
    val sizes: List<String> get() = sizeStock.keys.toList()

    constructor(
        id: String = UUID.randomUUID().toString(),
        name: String,
        itemCode: String = "",
        hsnCode: String = "",
        salePrice: Double,
        purchasePrice: Double = 0.0,
        stock: Int,
        category: String = "General",
        sizes: List<String> = emptyList(),
        gstPercent: Double = 0.0,
        lowStockThreshold: Int = 5,
        packSize: Int = 1,
        packRatio: Map<String, Int> = emptyMap()
    ) : this(
        id = id,
        name = name,
        itemCode = itemCode,
        hsnCode = hsnCode,
        salePrice = salePrice,
        purchasePrice = purchasePrice,
        category = category,
        sizeStock = if (sizes.isNotEmpty()) {
            val perSize = stock / sizes.size
            val rem = stock % sizes.size
            sizes.mapIndexed { idx, s -> s to (perSize + if (idx == 0) rem else 0) }.toMap()
        } else {
            mapOf("Free" to stock)
        },
        lowStockThreshold = lowStockThreshold,
        packSize = packSize,
        packRatio = packRatio,
        gstPercent = gstPercent
    )
}

data class InvoiceItem(
    val id: String = UUID.randomUUID().toString(),
    val itemId: String = "",
    val name: String,
    val size: String = "Free",
    val hsnCode: String = "",
    val quantity: Int = 1,
    val unitPrice: Double,
    val gstPercent: Double = 0.0,
    val total: Double = quantity * unitPrice
)

data class Invoice(
    val id: String = UUID.randomUUID().toString(),
    val billNumber: String,
    val date: Long = System.currentTimeMillis(),
    val customerId: String = "",
    val customerName: String,
    val customerPhone: String = "",
    val items: List<InvoiceItem> = emptyList(),
    val subtotal: Double = 0.0,
    val gstAmount: Double = 0.0,
    val discount: Double = 0.0,
    val additionalCharges: Double = 0.0,
    val roundOff: Double = 0.0,
    val totalAmount: Double = 0.0,
    val paymentMode: String = "CASH", // CASH, UPI, CREDIT
    val stateOfSupply: String = "Gujarat",
    val gstin: String = "",
    val notes: String = "",
    val themeName: String = "PREMIUM_BLUE",
    val isCancelled: Boolean = false,
    val cancellationReason: String = "",
    val dueDate: Long = 0L
)

data class StoreProfile(
    val storeName: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val upiId: String = "",
    val gstin: String = "",
    val ownerName: String = "",
    val state: String = "Gujarat",
    val logoUri: String = ""
)

data class TaxSummaryRow(
    val rate: Double,
    val taxableAmount: Double,
    val cgstRate: Double,
    val cgstAmount: Double,
    val sgstRate: Double,
    val sgstAmount: Double,
    val igstRate: Double,
    val igstAmount: Double,
    val totalTax: Double
)

data class CashEntry(
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "IN" or "OUT"
    val amount: Double,
    val category: String,
    val remark: String = "",
    val date: Long = System.currentTimeMillis(),
    val paymentMode: String = "CASH", // CASH, UPI, BANK, CHEQUE
    val linkedId: String? = null,
    val source: String = "manual", // "manual", "invoice", "khata", "settlement"
    val bookType: String = "CASH", // "CASH", "BANK"
    val reference: String = "",
    val chequeStatus: String = "", // "PENDING", "CLEARED"
    val partyName: String = "",
    val partyId: String = "",
    val isDeleted: Boolean = false,
    val deletedAt: Long = 0L,
    val originalAmount: Double? = null,
    val bankVerified: Boolean = false,
    val verifiedAt: Long = 0L,
    val unmatchedNote: String = ""
)

data class DrawerCount(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val actualCash: Double = 0.0,
    val bookBalance: Double = 0.0,
    val difference: Double = 0.0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class BankDayCheck(
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val sheetBalance: Double = 0.0,
    val bookBalance: Double = 0.0,
    val difference: Double = 0.0,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

data class DeliveryTask(
    val id: String = UUID.randomUUID().toString(),
    val partyName: String,
    val partyId: String? = null,
    val note: String = "",
    val date: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val completedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)


data class ImportResult(
    val success: Boolean,
    val customerCount: Int = 0,
    val transactionCount: Int = 0,
    val inventoryCount: Int = 0,
    val invoiceCount: Int = 0,
    val cashbookCount: Int = 0,
    val attachmentCount: Int = 0,
    val isLegacyJsonWithoutPhotos: Boolean = false,
    val errorMessage: String? = null,
    val repairedCount: Int = 0
) {
    val isValid: Boolean get() = success
    val message: String get() {
        if (errorMessage != null) return errorMessage
        val baseMsg = if (repairedCount > 0) {
            "Import successful ($customerCount customers, $transactionCount txs, $inventoryCount items, $invoiceCount bills, $cashbookCount cash, $attachmentCount attachments). Note: $repairedCount unlinked transaction(s) attached to 'Recovered entries'."
        } else {
            "Import successful ($customerCount customers, $transactionCount txs, $inventoryCount items, $invoiceCount bills, $cashbookCount cash, $attachmentCount attachments)"
        }
        return if (isLegacyJsonWithoutPhotos) {
            "$baseMsg\n⚠️ Notice: Legacy JSON backup does not contain photos or attachments."
        } else {
            baseMsg
        }
    }
}


data class CsvCustomerPreview(
    val name: String,
    val phone: String,
    val balance: Double,
    val isDuplicate: Boolean,
    val type: String = "CUSTOMER",
    val error: String? = null, // LanguageStrings key; a row with an error is never imported
    val line: Int = 0
) {
    val canImport: Boolean get() = error == null && !isDuplicate
}

enum class AppLanguage(val code: String, val displayName: String, val nativeName: String) {
    HINGLISH("hinglish", "Hinglish", "Hinglish (Default)"),
    HINDI("hi", "Hindi", "हिंदी"),
    GUJARATI("gu", "Gujarati", "ગુજરાતી"),
    ENGLISH("en", "English", "English")
}

data class InvoiceThemeConfig(
    val id: String,
    val name: String,
    val primaryColorHex: String,
    val accentColorHex: String,
    val isPremium: Boolean = false,
    val badgeText: String = ""
)

enum class AttachmentOwnerType(val raw: String) {
    ENTRY("ENTRY"),
    CASH_ENTRY("CASH_ENTRY"),
    PARTY("PARTY"),
    INVOICE("INVOICE");

    companion object {
        fun fromString(value: String?): AttachmentOwnerType {
            return entries.find { it.raw.equals(value, ignoreCase = true) } ?: ENTRY
        }
    }
}

data class Attachment(
    val id: String = UUID.randomUUID().toString(),
    val ownerType: String = AttachmentOwnerType.ENTRY.raw,
    val ownerId: String,
    val filePath: String,
    val thumbPath: String = "",
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val caption: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

