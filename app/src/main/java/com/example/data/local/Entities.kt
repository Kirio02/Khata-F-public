package com.example.data.local

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.example.model.*
import java.util.UUID

@Entity(tableName = "customers")
data class CustomerEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val address: String = "",
    val gstin: String = "",
    val type: String = "CUSTOMER", // "CUSTOMER" or "SUPPLIER"
    val createdAt: Long = System.currentTimeMillis()
)

fun CustomerEntity.toDomain() = Customer(
    id = id,
    name = name,
    phone = phone,
    address = address,
    gstin = gstin,
    type = type,
    createdAt = createdAt
)

fun Customer.toEntity() = CustomerEntity(
    id = id,
    name = name,
    phone = phone,
    address = address,
    gstin = gstin,
    type = type,
    createdAt = createdAt
)

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["customerId"]),
        Index(value = ["date"]),
        Index(value = ["linkedToTxId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = CustomerEntity::class,
            parentColumns = ["id"],
            childColumns = ["customerId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class TransactionEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val customerId: String,
    val amount: Double,
    val type: String, // "GAVE", "GOT"
    val note: String = "",
    val date: Long = System.currentTimeMillis(),
    val paymentMode: String = "CASH",
    val linkedToTxId: String? = null,
    val isCompleted: Boolean = false,
    val billNumber: String = "",
    val dueDate: Long = 0L,
    val challanNo: String = ""
)

fun TransactionEntity.toDomain() = Transaction(
    id = id,
    customerId = customerId,
    amount = amount,
    type = type,
    note = note,
    date = date,
    paymentMode = paymentMode,
    linkedToTxId = linkedToTxId,
    isCompleted = isCompleted,
    billNumber = billNumber,
    dueDate = dueDate,
    challanNo = challanNo
)

fun Transaction.toEntity() = TransactionEntity(
    id = id,
    customerId = customerId,
    amount = amount,
    type = type,
    note = note,
    date = date,
    paymentMode = paymentMode,
    linkedToTxId = linkedToTxId,
    isCompleted = isCompleted,
    billNumber = billNumber,
    dueDate = dueDate,
    challanNo = challanNo
)

@Entity(tableName = "inventory_items")
data class InventoryItemEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val itemCode: String = "",
    val hsnCode: String = "",
    val salePrice: Double,
    val purchasePrice: Double = 0.0,
    val stock: Int = 0,
    val category: String = "General",
    val sizes: List<String> = emptyList(),
    val sizeStock: Map<String, Int> = mapOf("Free" to 0),
    val lowStockThreshold: Int = 5,
    val packSize: Int = 1,
    val packRatio: Map<String, Int> = emptyMap(),
    val gstPercent: Double = 0.0
)

fun InventoryItemEntity.toDomain() = InventoryItem(
    id = id,
    name = name,
    itemCode = itemCode,
    hsnCode = hsnCode,
    salePrice = salePrice,
    purchasePrice = purchasePrice,
    category = category,
    sizeStock = if (sizeStock.isNotEmpty()) sizeStock else (if (sizes.isNotEmpty()) sizes.associateWith { stock / sizes.size } else mapOf("Free" to stock)),
    lowStockThreshold = lowStockThreshold,
    packSize = packSize,
    packRatio = packRatio,
    gstPercent = gstPercent
)

fun InventoryItem.toEntity() = InventoryItemEntity(
    id = id,
    name = name,
    itemCode = itemCode,
    hsnCode = hsnCode,
    salePrice = salePrice,
    purchasePrice = purchasePrice,
    stock = stock,
    category = category,
    sizes = sizes,
    sizeStock = sizeStock,
    lowStockThreshold = lowStockThreshold,
    packSize = packSize,
    packRatio = packRatio,
    gstPercent = gstPercent
)

@Entity(
    tableName = "invoices",
    indices = [
        Index(value = ["date"]),
        Index(value = ["billNumber"])
    ]
)
data class InvoiceEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val billNumber: String,
    val date: Long = System.currentTimeMillis(),
    val customerId: String = "",
    val customerName: String,
    val customerPhone: String = "",
    val subtotal: Double,
    val gstAmount: Double,
    val discount: Double = 0.0,
    val additionalCharges: Double = 0.0,
    val roundOff: Double = 0.0,
    val totalAmount: Double,
    val paymentMode: String = "CASH",
    val stateOfSupply: String = "Gujarat",
    val gstin: String = "",
    val notes: String = "",
    val themeName: String = "PREMIUM_BLUE",
    val isCancelled: Boolean = false,
    val cancellationReason: String = "",
    val dueDate: Long = 0L
)

@Entity(
    tableName = "invoice_items",
    indices = [
        Index(value = ["invoiceId"])
    ],
    foreignKeys = [
        ForeignKey(
            entity = InvoiceEntity::class,
            parentColumns = ["id"],
            childColumns = ["invoiceId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class InvoiceItemEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val invoiceId: String,
    val itemId: String = "",
    val name: String,
    val size: String = "Free",
    val hsnCode: String = "",
    val quantity: Int = 1,
    val unitPrice: Double,
    val gstPercent: Double = 0.0,
    val total: Double
)

fun InvoiceItemEntity.toDomain() = InvoiceItem(
    id = id,
    itemId = itemId,
    name = name,
    size = size,
    hsnCode = hsnCode,
    quantity = quantity,
    unitPrice = unitPrice,
    gstPercent = gstPercent,
    total = total
)

fun InvoiceItem.toEntity(invoiceId: String) = InvoiceItemEntity(
    id = id,
    invoiceId = invoiceId,
    itemId = itemId,
    name = name,
    size = size,
    hsnCode = hsnCode,
    quantity = quantity,
    unitPrice = unitPrice,
    gstPercent = gstPercent,
    total = total
)

data class InvoiceWithItems(
    @Embedded
    val invoice: InvoiceEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "invoiceId"
    )
    val items: List<InvoiceItemEntity>
)

fun InvoiceWithItems.toDomain() = Invoice(
    id = invoice.id,
    billNumber = invoice.billNumber,
    date = invoice.date,
    customerId = invoice.customerId,
    customerName = invoice.customerName,
    customerPhone = invoice.customerPhone,
    items = items.map { it.toDomain() },
    subtotal = invoice.subtotal,
    gstAmount = invoice.gstAmount,
    discount = invoice.discount,
    additionalCharges = invoice.additionalCharges,
    roundOff = invoice.roundOff,
    totalAmount = invoice.totalAmount,
    paymentMode = invoice.paymentMode,
    stateOfSupply = invoice.stateOfSupply,
    gstin = invoice.gstin,
    notes = invoice.notes,
    themeName = invoice.themeName,
    isCancelled = invoice.isCancelled,
    cancellationReason = invoice.cancellationReason,
    dueDate = invoice.dueDate
)

fun Invoice.toEntity() = InvoiceEntity(
    id = id,
    billNumber = billNumber,
    date = date,
    customerId = customerId,
    customerName = customerName,
    customerPhone = customerPhone,
    subtotal = subtotal,
    gstAmount = gstAmount,
    discount = discount,
    additionalCharges = additionalCharges,
    roundOff = roundOff,
    totalAmount = totalAmount,
    paymentMode = paymentMode,
    stateOfSupply = stateOfSupply,
    gstin = gstin,
    notes = notes,
    themeName = themeName,
    isCancelled = isCancelled,
    cancellationReason = cancellationReason,
    dueDate = dueDate
)

@Entity(tableName = "invoice_sequences")
data class InvoiceSequenceEntity(
    @PrimaryKey
    val financialYear: String, // e.g. "26-27"
    val lastNumber: Int // e.g. 1, 2, 3...
)

@Entity(
    tableName = "cash_entries",
    indices = [
        Index(value = ["date"]),
        Index(value = ["bookType"]),
        Index(value = ["isDeleted"]),
        Index(value = ["bankVerified"])
    ]
)
data class CashEntryEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val type: String, // "IN", "OUT"
    val amount: Double,
    val category: String,
    val remark: String = "",
    val date: Long = System.currentTimeMillis(),
    val paymentMode: String = "CASH",
    val linkedId: String? = null,
    val source: String = "manual",
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

fun CashEntryEntity.toDomain() = CashEntry(
    id = id,
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
    partyId = partyId,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    originalAmount = originalAmount,
    bankVerified = bankVerified,
    verifiedAt = verifiedAt,
    unmatchedNote = unmatchedNote
)

fun CashEntry.toEntity() = CashEntryEntity(
    id = id,
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
    partyId = partyId,
    isDeleted = isDeleted,
    deletedAt = deletedAt,
    originalAmount = originalAmount,
    bankVerified = bankVerified,
    verifiedAt = verifiedAt,
    unmatchedNote = unmatchedNote
)

@Entity(
    tableName = "drawer_counts",
    indices = [
        Index(value = ["date"])
    ]
)
data class DrawerCountEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val actualCash: Double,
    val bookBalance: Double,
    val difference: Double,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

fun DrawerCountEntity.toDomain() = DrawerCount(
    id = id,
    date = date,
    actualCash = actualCash,
    bookBalance = bookBalance,
    difference = difference,
    note = note,
    createdAt = createdAt
)

fun DrawerCount.toEntity() = DrawerCountEntity(
    id = id,
    date = date,
    actualCash = actualCash,
    bookBalance = bookBalance,
    difference = difference,
    note = note,
    createdAt = createdAt
)

@Entity(
    tableName = "bank_day_checks",
    indices = [
        Index(value = ["date"])
    ]
)
data class BankDayCheckEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val date: Long = System.currentTimeMillis(),
    val sheetBalance: Double,
    val bookBalance: Double,
    val difference: Double,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

fun BankDayCheckEntity.toDomain() = BankDayCheck(
    id = id,
    date = date,
    sheetBalance = sheetBalance,
    bookBalance = bookBalance,
    difference = difference,
    note = note,
    createdAt = createdAt
)

fun BankDayCheck.toEntity() = BankDayCheckEntity(
    id = id,
    date = date,
    sheetBalance = sheetBalance,
    bookBalance = bookBalance,
    difference = difference,
    note = note,
    createdAt = createdAt
)

@Entity(
    tableName = "delivery_tasks",
    indices = [
        Index(value = ["date"])
    ]
)
data class DeliveryTaskEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val partyName: String,
    val partyId: String? = null,
    val note: String = "",
    val date: Long = System.currentTimeMillis(),
    val isCompleted: Boolean = false,
    val completedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

fun DeliveryTaskEntity.toDomain() = DeliveryTask(
    id = id,
    partyName = partyName,
    partyId = partyId,
    note = note,
    date = date,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = createdAt
)

fun DeliveryTask.toEntity() = DeliveryTaskEntity(
    id = id,
    partyName = partyName,
    partyId = partyId,
    note = note,
    date = date,
    isCompleted = isCompleted,
    completedAt = completedAt,
    createdAt = createdAt
)

@Entity(tableName = "store_profile")
data class StoreProfileEntity(
    @PrimaryKey
    val id: Int = 1,
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

fun StoreProfileEntity.toDomain() = StoreProfile(
    storeName = storeName,
    address = address,
    phone = phone,
    email = email,
    upiId = upiId,
    gstin = gstin,
    ownerName = ownerName,
    state = state,
    logoUri = logoUri
)

fun StoreProfile.toEntity() = StoreProfileEntity(
    id = 1,
    storeName = storeName,
    address = address,
    phone = phone,
    email = email,
    upiId = upiId,
    gstin = gstin,
    ownerName = ownerName,
    state = state,
    logoUri = logoUri
)

@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    val id: Int = 1,
    val language: String = "hinglish",
    val theme: String = "PREMIUM_BLUE",
    val hasCompletedOnboarding: Boolean = false,
    val cashOpeningBalance: Double = 0.0,
    val cashOpeningDate: Long = 0L,
    val bankOpeningBalance: Double = 0.0,
    val bankOpeningDate: Long = 0L,
    val simpleMode: Boolean = true,
    val hasCompletedBatteryGuide: Boolean = false,
    val lastBackupTimestamp: Long = 0L
)

@Entity(
    tableName = "chat_messages",
    indices = [Index(value = ["timestamp"])]
)
data class ChatMessageEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val sender: String, // "USER" or "AI"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val actionTitle: String? = null,
    val reportContent: String? = null,
    val isOutOfScope: Boolean = false
)

@Entity(
    tableName = "attachments",
    indices = [
        Index(value = ["ownerType", "ownerId"])
    ]
)
data class AttachmentEntity(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val ownerType: String, // "ENTRY", "CASH_ENTRY", "PARTY"
    val ownerId: String,
    val filePath: String,
    val thumbPath: String = "",
    val mimeType: String = "image/jpeg",
    val sizeBytes: Long = 0L,
    val caption: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

fun AttachmentEntity.toDomain() = Attachment(
    id = id,
    ownerType = ownerType,
    ownerId = ownerId,
    filePath = filePath,
    thumbPath = thumbPath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    caption = caption,
    createdAt = createdAt
)

fun Attachment.toEntity() = AttachmentEntity(
    id = id,
    ownerType = ownerType,
    ownerId = ownerId,
    filePath = filePath,
    thumbPath = thumbPath,
    mimeType = mimeType,
    sizeBytes = sizeBytes,
    caption = caption,
    createdAt = createdAt
)


