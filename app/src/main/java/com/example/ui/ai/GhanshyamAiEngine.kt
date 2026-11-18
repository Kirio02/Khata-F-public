package com.example.ui.ai

import android.content.Context
import com.example.data.AppRepository
import com.example.model.*
import com.example.ui.security.SecureSettingsManager
import com.example.ui.theme.formatIndianCurrency
import com.example.ui.util.VoiceEntryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

sealed class AiResponse {
    data class Success(
        val replyText: String,
        val actionTitle: String? = null,
        val reportContent: String? = null,
        val executeAction: (() -> Unit)? = null
    ) : AiResponse()

    data class OutOfScope(val replyText: String) : AiResponse()
}

class GhanshyamAiEngine(
    private val repository: AppRepository,
    private val context: Context? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        const val GEMINI_MODEL = "gemini-2.5-flash"

        fun stripMarkdown(text: String): String {
            return text
                .replace(Regex("""[*#_`~>]"""), "")
                .replace(Regex("""\[(.*?)\]\(.*?\)"""), "$1")
                .replace(Regex("""^[•\-\*]\s*""", RegexOption.MULTILINE), "- ")
                .trim()
        }
    }

    suspend fun processQuery(userInput: String): AiResponse = withContext(Dispatchers.IO) {
        val query = userInput.trim()
        if (query.isBlank()) {
            return@withContext AiResponse.Success("Aap kuch boliye ya type kijiye, main dukaan ke kaam me madad karunga.")
        }

        // 1. Check for Out-of-Scope queries
        val lower = query.lowercase()
        // Whole-word match only, and never block a question that names one of the shop's own parties
        // (e.g. a customer called "Modi" or "Pm Traders"). "pm"/"modi"/"score" were removed: too many false hits.
        val mentionsKnownParty = repository.customers.value.any {
            it.name.trim().length >= 3 && lower.contains(it.name.trim().lowercase())
        }
        val outOfScopeRegex = Regex("""\b(weather|mausam|song|gaana|movie|film|cricket|joke|chutkula|poem|kavita|politics|recipe)\b""")
        val isOutOfScope = outOfScopeRegex.containsMatchIn(lower) ||
            lower.contains("khana kaise banaye") || lower.contains("translate english to french")
        if (!mentionsKnownParty && isOutOfScope) {
            return@withContext AiResponse.OutOfScope(
                "Maaf kijiye, ye Ghanshyam Billing ke scope ke bahar hai. Main sirf aapki dukaan ka khata, billing, cashbook aur financial reports manage kar sakta hoon."
            )
        }

        // 2. Try Gemini with Function Calling if an API key is available
        val apiKey = context?.let { SecureSettingsManager.getGeminiApiKey(it) } ?: ""
        if (apiKey.isNotBlank()) {
            try {
                val geminiResult = callGeminiWithFunctionCalling(query, apiKey)
                if (geminiResult != null) {
                    return@withContext geminiResult
                }
            } catch (e: Exception) {
                android.util.Log.w("GhanshyamAiEngine", "Gemini function calling failed, falling back to local engine: ${e.message}", e)
            }
        }

        // 3. Robust Offline Fallback Engine (Keyword & Rule Based)
        return@withContext processOfflineQuery(query, lower, isOffline = true)
    }

    // ==========================================
    // GEMINI FUNCTION CALLING ENGINE
    // ==========================================

    private suspend fun callGeminiWithFunctionCalling(userPrompt: String, apiKey: String): AiResponse? = withContext(Dispatchers.IO) {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$GEMINI_MODEL:generateContent"

        val contents = JSONArray()
        contents.put(
            JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            }
        )

        val tools = buildGeminiToolsDeclaration()
        val systemInstruction = JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text",
                """
                You are the intelligent business accounting assistant for 'Ghanshyam Kids Wear & Wholesale'.
                CRITICAL RULES:
                1. Answers about money, balances, sales, cash, bills, and debts MUST come ONLY from the provided functions. Never guess or hallucinate any numbers.
                2. WRITE ACTIONS: If the user wants to add a customer, record udhar/jama, or mark a bill received, NEVER say it is already done. Call the corresponding propose_* function so that a proposal card is created for user confirmation.
                3. Plain text only: Do NOT use markdown symbols (no asterisks, hashes, backticks).
                4. Reply concisely in plain text in the app language (Hindi, Gujarati, Hinglish, or English).
                """.trimIndent()
            )))
        }

        var proposedActionTitle: String? = null
        var proposedAction: (() -> Unit)? = null
        var finalReply: String? = null
        var maxIterations = 5

        while (maxIterations-- > 0) {
            val requestBodyJson = JSONObject().apply {
                put("contents", contents)
                put("tools", tools)
                put("systemInstruction", systemInstruction)
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("x-goog-api-key", apiKey)
                .post(requestBodyJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                android.util.Log.e("GhanshyamAiEngine", "Gemini API error ${response.code}: $errBody")
                return@withContext null
            }

            val resStr = response.body?.string() ?: return@withContext null
            val resJson = JSONObject(resStr)

            val candidates = resJson.optJSONArray("candidates") ?: return@withContext null
            if (candidates.length() == 0) return@withContext null

            val candidate = candidates.getJSONObject(0)
            val content = candidate.optJSONObject("content") ?: return@withContext null
            val parts = content.optJSONArray("parts") ?: return@withContext null

            // Append model candidate to conversation contents
            contents.put(content)

            var hasFunctionCall = false
            val functionResponseParts = JSONArray()

            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    finalReply = part.getString("text")
                }
                if (part.has("functionCall")) {
                    hasFunctionCall = true
                    val fnCall = part.getJSONObject("functionCall")
                    val fnName = fnCall.getString("name")
                    val fnArgs = fnCall.optJSONObject("args") ?: JSONObject()

                    val fnResult = executeFunction(
                        name = fnName,
                        args = fnArgs,
                        onProposalCreated = { title, action ->
                            proposedActionTitle = title
                            proposedAction = action
                        }
                    )

                    functionResponseParts.put(
                        JSONObject().apply {
                            put("functionResponse", JSONObject().apply {
                                put("name", fnName)
                                put("response", fnResult)
                            })
                        }
                    )
                }
            }

            if (hasFunctionCall) {
                // Send function response back to model
                contents.put(
                    JSONObject().apply {
                        put("role", "function")
                        put("parts", functionResponseParts)
                    }
                )
            } else {
                // Done! Got final text response
                break
            }
        }

        if (!finalReply.isNullOrBlank()) {
            return@withContext AiResponse.Success(
                replyText = stripMarkdown(finalReply!!),
                actionTitle = proposedActionTitle,
                executeAction = proposedAction
            )
        }
        return@withContext null
    }

    private fun buildGeminiToolsDeclaration(): JSONArray {
        val functionDeclarations = JSONArray().apply {
            // 1. get_summary(from, to)
            put(JSONObject().apply {
                put("name", "get_summary")
                put("description", "Get overall business summary: total receivable (Lena Hai), total payable (Dena Hai), today's sales, net cash in hand, and customer count.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("from", JSONObject().put("type", "INTEGER").put("description", "Start timestamp in ms"))
                        put("to", JSONObject().put("type", "INTEGER").put("description", "End timestamp in ms"))
                    })
                })
            })

            // 2. find_party(query)
            put(JSONObject().apply {
                put("name", "find_party")
                put("description", "Search for a party or customer by name or phone substring. Returns party details, ID, and balance.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("query", JSONObject().put("type", "STRING").put("description", "Customer name or phone substring"))
                    })
                    put("required", JSONArray().put("query"))
                })
            })

            // 3. get_party_ledger(partyId, from, to)
            put(JSONObject().apply {
                put("name", "get_party_ledger")
                put("description", "Get the complete transaction history and outstanding balance for a specific party by partyId.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("partyId", JSONObject().put("type", "STRING").put("description", "Unique customer ID"))
                        put("from", JSONObject().put("type", "INTEGER"))
                        put("to", JSONObject().put("type", "INTEGER"))
                    })
                    put("required", JSONArray().put("partyId"))
                })
            })

            // 4. get_overdue(days)
            put(JSONObject().apply {
                put("name", "get_overdue")
                put("description", "Get customers with overdue credit older than the specified number of days (e.g. 7, 15, 30 days).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("days", JSONObject().put("type", "INTEGER").put("description", "Number of days, default 30"))
                    })
                })
            })

            // 5. get_top_debtors(n)
            put(JSONObject().apply {
                put("name", "get_top_debtors")
                put("description", "Get top N parties who owe the most money (highest positive balance).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("n", JSONObject().put("type", "INTEGER").put("description", "Number of debtors to return, default 5"))
                    })
                })
            })

            // 6. get_cash_book(book, from, to)
            put(JSONObject().apply {
                put("name", "get_cash_book")
                put("description", "Get records for CASH book or BANK book (UPI/Bank/Cheque) or ALL in a date range.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("book", JSONObject().put("type", "STRING").put("description", "Book type: CASH, BANK, or ALL"))
                        put("from", JSONObject().put("type", "INTEGER"))
                        put("to", JSONObject().put("type", "INTEGER"))
                    })
                })
            })

            // 7. get_bank_unmatched(from, to)
            put(JSONObject().apply {
                put("name", "get_bank_unmatched")
                put("description", "Get bank book entries that have not yet been matched or verified with the bank sheet.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("from", JSONObject().put("type", "INTEGER"))
                        put("to", JSONObject().put("type", "INTEGER"))
                    })
                })
            })

            // 8. get_bills(from, to, status)
            put(JSONObject().apply {
                put("name", "get_bills")
                put("description", "Get bills/invoices issued in a date range filtered by status (ALL, PENDING, PAID, CANCELLED).")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("from", JSONObject().put("type", "INTEGER"))
                        put("to", JSONObject().put("type", "INTEGER"))
                        put("status", JSONObject().put("type", "STRING").put("description", "ALL, PENDING, PAID, or CANCELLED"))
                    })
                })
            })

            // Write proposals
            put(JSONObject().apply {
                put("name", "propose_add_party")
                put("description", "Propose adding a new party/customer. This creates a proposal card for user confirmation and does not write immediately.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().put("type", "STRING"))
                        put("phone", JSONObject().put("type", "STRING"))
                        put("address", JSONObject().put("type", "STRING"))
                        put("type", JSONObject().put("type", "STRING").put("description", "CUSTOMER or SUPPLIER"))
                    })
                    put("required", JSONArray().put("name"))
                })
            })

            put(JSONObject().apply {
                put("name", "propose_add_entry")
                put("description", "Propose recording a credit (GAVE/Udhar) or debit (GOT/Jama) entry in a customer's khata.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("customerName", JSONObject().put("type", "STRING"))
                        put("amount", JSONObject().put("type", "NUMBER"))
                        put("type", JSONObject().put("type", "STRING").put("description", "GAVE for Udhar, GOT for Jama"))
                        put("note", JSONObject().put("type", "STRING"))
                    })
                    put("required", JSONArray().put("customerName").put("amount").put("type"))
                })
            })

            put(JSONObject().apply {
                put("name", "propose_mark_received")
                put("description", "Propose marking an existing pending bill as received.")
                put("parameters", JSONObject().apply {
                    put("type", "OBJECT")
                    put("properties", JSONObject().apply {
                        put("customerName", JSONObject().put("type", "STRING"))
                        put("amount", JSONObject().put("type", "NUMBER"))
                        put("paymentMode", JSONObject().put("type", "STRING").put("description", "CASH, UPI, or BANK"))
                    })
                    put("required", JSONArray().put("customerName").put("amount"))
                })
            })
        }

        return JSONArray().put(JSONObject().apply { put("functionDeclarations", functionDeclarations) })
    }

    private sealed class PartyMatch {
        data class One(val customer: Customer) : PartyMatch()
        data class Many(val list: List<Customer>) : PartyMatch()
        object None : PartyMatch()
    }

    /** Exact name wins; otherwise a single partial match; several matches are returned so the user is asked. */
    private fun resolveParty(name: String): PartyMatch {
        val q = name.trim()
        if (q.isBlank()) return PartyMatch.None
        val all = repository.customers.value
        val exact = all.filter { it.name.trim().equals(q, ignoreCase = true) }
        if (exact.size == 1) return PartyMatch.One(exact[0])
        val pool = if (exact.isNotEmpty()) exact else all.filter {
            it.name.isNotBlank() && (it.name.contains(q, ignoreCase = true) || q.contains(it.name.trim(), ignoreCase = true))
        }
        return when (pool.size) {
            0 -> PartyMatch.None
            1 -> PartyMatch.One(pool[0])
            else -> PartyMatch.Many(pool.take(5))
        }
    }

    private fun executeFunction(
        name: String,
        args: JSONObject,
        onProposalCreated: (String, () -> Unit) -> Unit
    ): JSONObject {
        return when (name) {
            "get_summary" -> {
                val totalReceivable = repository.getTotalReceivable()
                val totalPayable = repository.getTotalPayable()
                val customers = repository.customers.value
                val cashIn = repository.cashbook.value.filter { !it.isDeleted && it.type == "IN" }.sumOf { it.amount }
                val cashOut = repository.cashbook.value.filter { !it.isDeleted && it.type == "OUT" }.sumOf { it.amount }

                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startOfDay = cal.timeInMillis
                val todaySales = repository.invoices.value
                    .filter { it.date >= startOfDay && !it.isCancelled }
                    .sumOf { it.totalAmount }

                JSONObject().apply {
                    put("totalReceivable", totalReceivable)
                    put("totalPayable", totalPayable)
                    put("todaySales", todaySales)
                    put("netCashInHand", cashIn - cashOut)
                    put("customerCount", customers.size)
                }
            }

            "find_party" -> {
                val query = args.optString("query", "").lowercase()
                val matches = repository.customers.value.filter {
                    it.name.lowercase().contains(query) || it.phone.contains(query)
                }
                val arr = JSONArray()
                matches.forEach { c ->
                    arr.put(JSONObject().apply {
                        put("id", c.id)
                        put("name", c.name)
                        put("phone", c.phone)
                        put("balance", repository.getCustomerBalance(c.id))
                        put("type", c.type)
                    })
                }
                JSONObject().apply { put("parties", arr) }
            }

            "get_party_ledger" -> {
                val partyId = args.optString("partyId", "")
                val from = args.optLong("from", 0L)
                val to = args.optLong("to", Long.MAX_VALUE)
                val customer = repository.customers.value.find { it.id == partyId }
                if (customer == null) {
                    JSONObject().apply { put("error", "Party not found with id $partyId") }
                } else {
                    val txs = repository.transactions.value.filter { it.customerId == partyId && it.date in from..to }
                    val balance = repository.getCustomerBalance(partyId)
                    val txArr = JSONArray()
                    txs.forEach { t ->
                        txArr.put(JSONObject().apply {
                            put("amount", t.amount)
                            put("type", t.type)
                            put("note", t.note)
                            put("date", t.date)
                            put("mode", t.paymentMode)
                        })
                    }
                    JSONObject().apply {
                        put("name", customer.name)
                        put("phone", customer.phone)
                        put("balance", balance)
                        put("transactions", txArr)
                    }
                }
            }

            "get_top_debtors" -> {
                val n = args.optInt("n", 5).coerceAtLeast(1)
                val debtors = repository.customers.value
                    .map { it to repository.getCustomerBalance(it.id) }
                    .filter { it.second > 0.01 }
                    .sortedByDescending { it.second }
                    .take(n)

                val arr = JSONArray()
                debtors.forEach { (c, bal) ->
                    arr.put(JSONObject().apply {
                        put("name", c.name)
                        put("phone", c.phone)
                        put("balance", bal)
                    })
                }
                JSONObject().apply { put("debtors", arr) }
            }

            "get_overdue" -> {
                val days = args.optInt("days", 30)
                val txByCustomer = repository.transactions.value.groupBy { it.customerId }
                val overdue = repository.customers.value.filter { c ->
                    c.type != "SUPPLIER" &&
                        com.example.data.OverdueLogic.isOverdue(txByCustomer[c.id] ?: emptyList(), graceDays = days)
                }
                val arr = JSONArray()
                overdue.forEach { c ->
                    arr.put(JSONObject().apply {
                        put("name", c.name)
                        put("phone", c.phone)
                        put("balance", repository.getCustomerBalance(c.id))
                    })
                }
                JSONObject().apply { put("overdueParties", arr) }
            }

            "get_cash_book", "get_cashbook" -> {
                val book = args.optString("book", "ALL").uppercase()
                val from = args.optLong("from", 0L)
                val to = args.optLong("to", Long.MAX_VALUE)

                val allEntries = repository.cashbook.value.filter { it.date in from..to && !it.isDeleted }
                val filtered = when (book) {
                    "CASH" -> allEntries.filter { it.bookType.equals("CASH", ignoreCase = true) || it.paymentMode.equals("CASH", ignoreCase = true) }
                    "BANK" -> allEntries.filter { it.bookType.equals("BANK", ignoreCase = true) || !it.paymentMode.equals("CASH", ignoreCase = true) }
                    else -> allEntries
                }

                val totalIn = filtered.filter { it.type == "IN" }.sumOf { it.amount }
                val totalOut = filtered.filter { it.type == "OUT" }.sumOf { it.amount }
                val netBalance = totalIn - totalOut

                val opening = when (book) {
                    "CASH" -> repository.cashOpeningBalance.value
                    "BANK" -> repository.bankOpeningBalance.value
                    else -> repository.cashOpeningBalance.value + repository.bankOpeningBalance.value
                }

                JSONObject().apply {
                    put("book", book)
                    put("totalIn", totalIn)
                    put("totalOut", totalOut)
                    put("netBalance", netBalance)
                    put("openingBalance", opening)
                    put("closingBalance", opening + netBalance)
                    put("entryCount", filtered.size)
                }
            }

            "get_bank_unmatched" -> {
                val from = args.optLong("from", 0L)
                val to = args.optLong("to", Long.MAX_VALUE)
                val unmatched = repository.cashbook.value.filter {
                    it.date in from..to && !it.isDeleted &&
                    (it.bookType.equals("BANK", ignoreCase = true) || !it.paymentMode.equals("CASH", ignoreCase = true)) &&
                    !it.bankVerified
                }
                val totalUnmatchedAmount = unmatched.sumOf { it.amount }
                val arr = JSONArray()
                unmatched.forEach { b ->
                    arr.put(JSONObject().apply {
                        put("id", b.id)
                        put("date", b.date)
                        put("partyName", b.partyName)
                        put("amount", b.amount)
                        put("type", b.type)
                        put("paymentMode", b.paymentMode)
                        put("reference", b.reference)
                        put("unmatchedNote", b.unmatchedNote)
                    })
                }
                JSONObject().apply {
                    put("unmatchedCount", unmatched.size)
                    put("totalUnmatchedAmount", totalUnmatchedAmount)
                    put("unmatchedEntries", arr)
                }
            }

            "get_bills", "get_sales" -> {
                val from = args.optLong("from", 0L)
                val to = args.optLong("to", Long.MAX_VALUE)
                val status = args.optString("status", "ALL").uppercase()

                val invoices = repository.invoices.value.filter { it.date in from..to }
                val filtered = when (status) {
                    "CANCELLED" -> invoices.filter { it.isCancelled }
                    "PENDING" -> invoices.filter { !it.isCancelled && it.paymentMode == "CREDIT" }
                    "PAID" -> invoices.filter { !it.isCancelled && (it.paymentMode == "CASH" || it.paymentMode == "UPI" || it.paymentMode == "BANK") }
                    else -> invoices
                }
                val totalAmount = filtered.sumOf { it.totalAmount }
                val arr = JSONArray()
                filtered.forEach { inv ->
                    arr.put(JSONObject().apply {
                        put("billNumber", inv.billNumber)
                        put("customerName", inv.customerName)
                        put("date", inv.date)
                        put("totalAmount", inv.totalAmount)
                        put("paymentMode", inv.paymentMode)
                        put("isCancelled", inv.isCancelled)
                        put("cancellationReason", inv.cancellationReason)
                    })
                }
                JSONObject().apply {
                    put("statusFilter", status)
                    put("billsCount", filtered.size)
                    put("totalAmount", totalAmount)
                    put("bills", arr)
                }
            }

            "propose_add_party" -> {
                val name = args.getString("name").trim()
                val phone = args.optString("phone", "").trim()
                val address = args.optString("address", "").trim()
                val type = args.optString("type", "CUSTOMER").uppercase()

                onProposalCreated("Add Party: $name") {
                    repository.addCustomer(name = name, phone = phone, address = address, type = type)
                }
                JSONObject().apply {
                    put("status", "PROPOSAL_READY")
                    put("message", "Proposal created for adding party '$name'. User can tap Apply.")
                }
            }

            "propose_add_entry" -> {
                val custName = args.getString("customerName").trim()
                val amount = args.getDouble("amount")
                val type = args.getString("type").uppercase()
                val note = args.optString("note", "Added via Voice AI").trim()
                val actionLabel = if (type == "GAVE") "Udhar" else "Jama"

                when (val match = resolveParty(custName)) {
                    is PartyMatch.Many -> JSONObject().apply {
                        put("error", "Several parties match '$custName': ${match.list.joinToString { it.name }}. Ask the user which one, do not guess.")
                    }
                    is PartyMatch.None -> JSONObject().apply {
                        put("error", "No party named '$custName' exists. Ask the user whether to add this new party first (propose_add_party).")
                    }
                    is PartyMatch.One -> {
                        val target = match.customer
                        onProposalCreated("Record $actionLabel: ₹$amount for ${target.name}") {
                            repository.addTransaction(
                                customerId = target.id,
                                amount = amount,
                                type = type,
                                note = note
                            )
                        }
                        JSONObject().apply {
                            put("status", "PROPOSAL_READY")
                            put("message", "Proposal created for recording $actionLabel of ₹$amount for ${target.name}. User can tap Apply.")
                        }
                    }
                }
            }

            "propose_mark_received" -> {
                val custName = args.getString("customerName").trim()
                val amount = args.getDouble("amount")
                val mode = args.optString("paymentMode", "CASH")

                when (val match = resolveParty(custName)) {
                    is PartyMatch.Many -> JSONObject().apply {
                        put("error", "Several parties match '$custName': ${match.list.joinToString { it.name }}. Ask the user which one, do not guess.")
                    }
                    is PartyMatch.None -> JSONObject().apply {
                        put("error", "No party named '$custName' exists. Ask the user to check the name.")
                    }
                    is PartyMatch.One -> {
                        val cust = match.customer
                        onProposalCreated("Mark Received: ₹$amount from ${cust.name}") {
                            // Oldest unpaid bill first, same as the khata's own payment allocation.
                            val pendingBill = repository.transactions.value
                                .filter { it.customerId == cust.id && it.linkedToTxId == null && repository.getBillOutstandingAmount(it) > 0.001 }
                                .minByOrNull { it.date }
                            if (pendingBill != null) {
                                repository.markBillReceived(
                                    billId = pendingBill.id,
                                    amount = amount,
                                    paymentMode = mode,
                                    addToCashbook = true
                                )
                            } else {
                                repository.addTransaction(
                                    customerId = cust.id,
                                    amount = amount,
                                    type = "GOT",
                                    note = "Received payment",
                                    paymentMode = mode
                                )
                            }
                        }
                        JSONObject().apply {
                            put("status", "PROPOSAL_READY")
                            put("message", "Proposal created to mark ₹$amount received from ${cust.name}. User can tap Apply.")
                        }
                    }
                }
            }

            else -> JSONObject().apply { put("error", "Unknown function $name") }
        }
    }

    // ==========================================
    // OFFLINE DETERMINISTIC FALLBACK ENGINE
    // ==========================================

    private fun processOfflineQuery(query: String, lower: String, isOffline: Boolean = false): AiResponse {
        val offlinePrefix = if (isOffline) "Offline mode: " else ""

        // 1. Party balance queries (e.g. "Ramesh ka kitna baaki hai?", "Ramesh ka balance")
        if (lower.contains("baaki") || lower.contains("baki") || lower.contains("balance") ||
            lower.contains("kitna") || lower.contains("udhar") || lower.contains("lena") || lower.contains("dena")) {
            val matchedCust = repository.customers.value.firstOrNull { c ->
                val cLower = c.name.lowercase().trim()
                lower.contains(cLower) || cLower.split(" ").any { part -> part.length > 2 && lower.contains(part) }
            }
            if (matchedCust != null) {
                val bal = repository.getCustomerBalance(matchedCust.id)
                val balFormatted = formatIndianCurrency(kotlin.math.abs(bal))
                val reply = if (bal > 0) {
                    "${matchedCust.name} ka $balFormatted lena baaki hai."
                } else if (bal < 0) {
                    "${matchedCust.name} ka $balFormatted jama (advance) hai."
                } else {
                    "${matchedCust.name} ka hisaab barabar (zero) hai."
                }
                return AiResponse.Success(replyText = "$offlinePrefix$reply")
            }
        }

        // 2. Collection queries (e.g. "is hafte kitna paisa aaya?", "aaj kitna paisa aaya?")
        if (lower.contains("paisa aaya") || lower.contains("kitna aaya") || lower.contains("jama hua") ||
            (lower.contains("kitna") && lower.contains("aaya"))) {
            val isWeek = lower.contains("hafte") || lower.contains("hafta") || lower.contains("week")
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (isWeek) {
                    set(Calendar.DAY_OF_WEEK, firstDayOfWeek)
                }
            }
            val fromTime = cal.timeInMillis
            val cashIn = repository.cashbook.value.filter { it.date >= fromTime && !it.isDeleted && it.type == "IN" }.sumOf { it.amount }
            val txIn = repository.transactions.value.filter { it.date >= fromTime && it.type == "GOT" }.sumOf { it.amount }
            val totalIn = if (cashIn > 0) cashIn else txIn
            val label = if (isWeek) "Is hafte" else "Aaj"
            return AiResponse.Success(
                replyText = "$offlinePrefix$label total ₹${formatIndianCurrency(totalIn)} paisa aaya hai."
            )
        }

        // 3. Propose add party (e.g. "Suresh ko add karo", "Add party Suresh", "add customer Suresh Patel 9898011223")
        if ((lower.contains("add") || lower.contains("jodo") || lower.contains("nava") || lower.contains("new")) &&
            (lower.contains("karo") || lower.contains("party") || lower.contains("customer") || lower.contains("ko"))) {
            val phoneRegex = Regex("""\b([0-9]{10,12})\b""")
            val foundPhone = phoneRegex.find(query)?.value ?: ""
            val nameCleaned = query
                .replace(Regex("""\b([0-9]{10,12})\b"""), "")
                .replace(Regex("""(?i)\b(add|party|customer|ko|karo|jodo|nava|new|banao|khata|me)\b"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
            if (nameCleaned.isBlank()) {
                return AiResponse.Success(replyText = "${offlinePrefix}Party ka naam bataiye. Jaise: 'Suresh Patel ko add karo'.")
            }

            return AiResponse.Success(
                replyText = "${offlinePrefix}${nameCleaned} ko naye customer ke roop me add karne ka proposal ready hai. Lagoo karne ke liye Apply par tap karein.",
                actionTitle = "Add Party: $nameCleaned",
                executeAction = {
                    repository.addCustomer(name = nameCleaned, phone = foundPhone)
                }
            )
        }

        // 4. Query: "who owes me more than 5000?"
        if ((lower.contains("who owes") || lower.contains("owes me") || lower.contains("kiska udhar") || lower.contains("kis se lena")) &&
            (lower.contains("more than") || lower.contains("zyada") || lower.contains("upar") || lower.contains(">") || extractNumbers(query).isNotEmpty())) {
            val threshold = extractNumbers(query).firstOrNull() ?: 5000.0
            val matching = repository.customers.value
                .map { it to repository.getCustomerBalance(it.id) }
                .filter { it.second >= threshold }
                .sortedByDescending { it.second }

            val sb = StringBuilder()
            if (matching.isEmpty()) {
                sb.append("Aapka koi bhi customer aisa nahi hai jiska udhar ₹${formatIndianCurrency(threshold)} (${threshold.toLong()}) se zyada ho.")
            } else {
                sb.append("Following customers owe you more than ₹${formatIndianCurrency(threshold)}:\n")
                matching.forEach { (c, bal) ->
                    sb.append("- ${c.name} (${c.phone}): ₹${formatIndianCurrency(bal)} (${bal.toLong()})\n")
                }
            }
            return AiResponse.Success(replyText = "$offlinePrefix${sb.toString().trim()}")
        }

        // 5. Query: "total cash sales this week" or "today cash sales"
        if (lower.contains("cash sales") || lower.contains("cash sale") || lower.contains("cash bill")) {
            val invoices = repository.invoices.value.filter {
                (it.paymentMode == "CASH" || it.paymentMode == "UPI") && !it.isCancelled
            }
            val totalCash = invoices.sumOf { it.totalAmount }
            return AiResponse.Success(
                replyText = "$offlinePrefix Total cash sales are ₹${formatIndianCurrency(totalCash)} (${totalCash.toLong()}) across ${invoices.size} cash invoices."
            )
        }

        // 6. Reports
        if (lower.contains("report") || lower.contains("statement") || lower.contains("hisaab") || lower.contains("hisab")) {
            return generateBusinessReport()
        }

        // 7. General Udhar or Jama proposal
        if (lower.contains("udhar") || lower.contains("diya") || lower.contains("gave") || lower.contains("mila") || lower.contains("jama") || lower.contains("got")) {
            return handleTransactionOffline(query, lower)
        }

        return AiResponse.Success(
            replyText = "${offlinePrefix}Main aapki dukaan me Khata, Billing, Cashbook aur Reports manage karne me madad kar sakta hoon. Jaise: 'Ramesh ka kitna baaki hai?', 'Is hafte kitna paisa aaya?', ya 'Suresh ko add karo'."
        )
    }

    private fun handleTransactionOffline(query: String, lower: String): AiResponse {
        val parsed = VoiceEntryParser.parse(query, repository.customers.value)
        if (parsed.amount <= 0.0) {
            return AiResponse.Success("Kitne rupaye? Amount ke saath bataiye. Jaise: 'Ramesh ko 500 udhar'.")
        }
        if (parsed.partyName.isBlank()) {
            return AiResponse.Success("Kis party ka? Naam bataiye. Jaise: 'Ramesh ko 500 udhar'.")
        }
        if (parsed.candidates.isNotEmpty()) {
            return AiResponse.Success("Kaunsa party? ${parsed.candidates.joinToString(", ") { it.name }}. Poora naam bolkar dobara bataiye.")
        }
        val target = parsed.matchedCustomer
            ?: return AiResponse.Success("'${parsed.partyName}' naam ka party nahi mila. Pehle 'Add party' karein, phir entry bataiye.")

        val type = parsed.type
        val amount = parsed.amount
        val typeText = if (type == "GAVE") "Udhar (aapne diye)" else "Jama (aapko mile)"
        val check = if (parsed.typeUncertain) " Dhyan se dekhein: ye $typeText hai." else ""

        return AiResponse.Success(
            replyText = "${target.name} ke khate me ₹${formatIndianCurrency(amount)} $typeText likhne ka proposal taiyar hai.$check",
            actionTitle = "Record $typeText: ₹${formatIndianCurrency(amount)} for ${target.name}",
            executeAction = {
                repository.addTransaction(
                    customerId = target.id,
                    amount = amount,
                    type = type,
                    note = "Recorded via Voice AI"
                )
            }
        )
    }

    private fun generateBusinessReport(): AiResponse {
        val customers = repository.customers.value
        val totalReceivable = repository.getTotalReceivable()
        val totalPayable = repository.getTotalPayable()
        val invoices = repository.invoices.value
        val cashbook = repository.cashbook.value

        val totalBilled = invoices.sumOf { it.totalAmount }
        val cashIn = cashbook.filter { !it.isDeleted && it.type == "IN" }.sumOf { it.amount }
        val cashOut = cashbook.filter { !it.isDeleted && it.type == "OUT" }.sumOf { it.amount }

        val sb = StringBuilder()
        sb.append("GHANSHYAM BILLING BUSINESS REPORT\n")
        sb.append("===============================\n")
        sb.append("Total Customers: ${customers.size}\n")
        sb.append("Total Udhar (Receivable): ₹${formatIndianCurrency(totalReceivable)}\n")
        sb.append("Total Advance (Payable): ₹${formatIndianCurrency(totalPayable)}\n\n")
        sb.append("Invoices Issued: ${invoices.size} (Total ₹${formatIndianCurrency(totalBilled)})\n")
        sb.append("Net Cash Balance: ₹${formatIndianCurrency(cashIn - cashOut)}\n\n")

        sb.append("Top Customers With Outstanding Udhar:\n")
        val withUdhar = customers.map { it to repository.getCustomerBalance(it.id) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(5)

        if (withUdhar.isEmpty()) {
            sb.append("- Koi pending udhar nahi hai. Sab hisaab clear hai!\n")
        } else {
            withUdhar.forEach { (c, bal) ->
                sb.append("- ${c.name}: ₹${formatIndianCurrency(bal)}\n")
            }
        }

        return AiResponse.Success(
            replyText = "Aapki dukaan ka business statement taiyar hai. Total Lena Hai: ₹${formatIndianCurrency(totalReceivable)}.",
            actionTitle = "Complete Business Statement",
            reportContent = sb.toString()
        )
    }

    private fun extractNumbers(text: String): List<Double> {
        val regex = Regex("([0-9]+(?:\\.[0-9]+)?)")
        return regex.findAll(text).mapNotNull { it.value.toDoubleOrNull() }.toList()
    }
}
