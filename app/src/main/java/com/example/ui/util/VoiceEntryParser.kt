package com.example.ui.util

import com.example.model.Customer
import java.util.Locale

data class VoiceParsedEntry(
    val partyName: String,
    val matchedCustomer: Customer?,
    val amount: Double,
    val type: String, // "GOT" (Mujhe mile) or "GAVE" (Maine diye)
    val rawText: String,
    // Several customers fit the spoken name: the screen must ask which one, never guess.
    val candidates: List<Customer> = emptyList(),
    // No clear "ko diye" / "ne diye" cue was heard: the screen should make the owner confirm the direction.
    val typeUncertain: Boolean = false
)

object VoiceEntryParser {

    private val NUMBER_WORDS = mapOf(
        "ek" to 1.0, "one" to 1.0,
        "do" to 2.0, "be" to 2.0, "two" to 2.0,
        "teen" to 3.0, "tran" to 3.0, "three" to 3.0,
        "char" to 4.0, "chhar" to 4.0, "four" to 4.0,
        "paanch" to 5.0, "panch" to 5.0, "five" to 5.0,
        "chhah" to 6.0, "chha" to 6.0, "six" to 6.0,
        "saat" to 7.0, "seven" to 7.0,
        "aath" to 8.0, "eight" to 8.0,
        "nau" to 9.0, "nav" to 9.0, "nine" to 9.0,
        "das" to 10.0, "dus" to 10.0, "ten" to 10.0,
        "gyarah" to 11.0, "agyara" to 11.0,
        "barah" to 12.0, "bar" to 12.0,
        "terah" to 13.0, "ter" to 13.0,
        "chaudah" to 14.0, "choud" to 14.0,
        "pandrah" to 15.0, "pandar" to 15.0,
        "solah" to 16.0, "sol" to 16.0,
        "satrah" to 17.0, "sattar" to 17.0,
        "atharah" to 18.0, "adhar" to 18.0,
        "unnis" to 19.0, "oghis" to 19.0,
        "bees" to 20.0, "vis" to 20.0, "twenty" to 20.0,
        "pachees" to 25.0, "pachis" to 25.0,
        "tees" to 30.0, "tris" to 30.0, "thirty" to 30.0,
        "chalis" to 40.0, "chalis" to 40.0, "forty" to 40.0,
        "pachaas" to 50.0, "pachas" to 50.0, "fifty" to 50.0,
        "dhai" to 2.5, "dedh" to 1.5
    )

    private val MULTIPLIERS = mapOf(
        "lakh" to 100000.0,
        "laakh" to 100000.0,
        "lac" to 100000.0,
        "hazaar" to 1000.0,
        "hazar" to 1000.0,
        "hajar" to 1000.0,
        "hazār" to 1000.0,
        "sau" to 100.0,
        "so" to 100.0,
        "hundred" to 100.0,
        "thousand" to 1000.0
    )

    fun parse(text: String, customers: List<Customer>): VoiceParsedEntry {
        val clean = text.trim()
        val lower = clean.lowercase(Locale.ROOT)

        val amount = extractAmount(lower)
        val type = extractType(lower)
        val partyNameCandidate = extractPartyCandidate(lower, clean)

        // Match against existing customer list. Never silently pick the first of several similar names
        // (many "Ramesh" / "Patel" in one khata): exact name wins, else a single partial match, else ask.
        var candidates: List<Customer> = emptyList()
        val matchedCustomer: Customer? = if (partyNameCandidate.isBlank()) {
            null
        } else {
            val exact = customers.filter { it.name.trim().equals(partyNameCandidate.trim(), ignoreCase = true) }
            if (exact.size == 1) {
                exact[0]
            } else {
                val loose = (if (exact.isNotEmpty()) exact else customers.filter { c ->
                    partyNameCandidate.contains(c.name, ignoreCase = true) ||
                    c.name.contains(partyNameCandidate, ignoreCase = true)
                })
                if (loose.size == 1) {
                    loose[0]
                } else {
                    candidates = loose.take(5)
                    null
                }
            }
        }

        val finalPartyName = matchedCustomer?.name
            ?: if (partyNameCandidate.isBlank()) {
                ""
            } else {
                partyNameCandidate
                    .split(" ")
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
            }

        return VoiceParsedEntry(
            partyName = finalPartyName,
            matchedCustomer = matchedCustomer,
            amount = amount,
            type = type,
            rawText = clean,
            candidates = candidates,
            typeUncertain = !hasDirectionCue(lower)
        )
    }

    private fun extractAmount(text: String): Double {
        // 1. Direct number with multiplier: "5 hazaar", "5000 hajar", "5 thousand", etc.
        val numMultiplierRegex = Regex("""(\d+(?:[.,]\d+)?)\s*(lakh|laakh|lac|hazaar|hazar|hajar|hazār|thousand|sau|so|hundred)\b""")
        val matchNumMult = numMultiplierRegex.find(text)
        if (matchNumMult != null) {
            val num = matchNumMult.groupValues[1].replace(",", "").toDoubleOrNull() ?: 1.0
            val multWord = matchNumMult.groupValues[2]
            val mult = MULTIPLIERS[multWord] ?: 1.0
            return num * mult
        }

        // 2. Word with multiplier: "paanch hazaar", "be hajar", "panch sau", "five thousand", etc.
        for ((word, baseVal) in NUMBER_WORDS) {
            for ((multWord, multVal) in MULTIPLIERS) {
                val pattern = Regex("""\b${Regex.escape(word)}\s*${Regex.escape(multWord)}\b""")
                if (pattern.containsMatchIn(text)) {
                    return baseVal * multVal
                }
            }
        }

        // Standalone multiplier e.g. "hazaar" -> 1000, "sau" -> 100
        for ((multWord, multVal) in MULTIPLIERS) {
            if (Regex("""\b$multWord\b""").containsMatchIn(text)) {
                return multVal
            }
        }

        // 3. Plain digits: e.g. "5000", "1250.50"
        val plainDigitsRegex = Regex("""\b\d+(?:[.,]\d+)?\b""")
        val matches = plainDigitsRegex.findAll(text).toList()
        for (m in matches) {
            val parsed = m.value.replace(",", "").toDoubleOrNull()
            if (parsed != null && parsed > 0.0) {
                return parsed
            }
        }

        // 4. Standalone number words
        for ((word, value) in NUMBER_WORDS) {
            if (Regex("""\b$word\b""").containsMatchIn(text)) {
                return value
            }
        }

        return 0.0
    }

    private val GIVE_VERBS = setOf("diye", "diya", "de", "dede", "dedo", "bheje", "bheja", "aapya")
    private val SPEAKER_WORDS = setOf("maine", "mene", "hamne", "main")

    private fun wordsOf(text: String): List<String> =
        text.split(Regex("""[\s,.\-]+""")).filter { it.isNotBlank() }

    /** True when the sentence clearly says who gave to whom (so the owner is not guessing the direction). */
    private fun hasDirectionCue(text: String): Boolean {
        val w = wordsOf(text)
        val hasVerb = w.any { it in GIVE_VERBS }
        return text.contains("udhar") || text.contains("le gaya") || text.contains("lidha") ||
            text.contains("gave") || text.contains("jama") || text.contains("mile") ||
            text.contains("mila") || text.contains("received") || text.contains("malya") ||
            text.contains("chukavya") ||
            (hasVerb && (w.contains("ko") || w.contains("ne") || w.any { it in SPEAKER_WORDS }))
    }

    private fun extractType(text: String): String {
        val w = wordsOf(text)
        val hasGiveVerb = w.any { it in GIVE_VERBS }

        // Money went OUT from the shop -> GAVE:
        //  "Ramesh ko 500 diya", "maine Ramesh ko 500 diye", "maine 500 diye", "udhar", "le gaya"
        if (text.contains("udhar") || text.contains("lidha") || text.contains("le gaya") || text.contains("gave")) {
            return "GAVE"
        }
        if (hasGiveVerb && w.contains("ko")) return "GAVE"
        if (hasGiveVerb && w.any { it in SPEAKER_WORDS } && !w.contains("se") && !w.contains("ne")) return "GAVE"

        // Money came IN -> GOT: "Ramesh ne 500 diye", "jama", "mile", "received"
        // (existing behaviour kept, including the Gujarati "ne ... aapya" rule used by the tests)
        return "GOT"
    }

    private fun extractPartyCandidate(lower: String, original: String): String {
        val stopWords = listOf(
            "maine", "mujhe", "mene", "hamne", "ko", "ne", "se", "pase", "thi",
            "diye", "diya", "de", "gaya", "aapya", "malya", "chukavya", "lidha",
            "jama", "udhar", "rupaye", "rupees", "rs", "roopya", "rupya", "taka", "paise",
            "cash", "upi", "bank", "cheque", "kare", "karo", "likho", "bhai",
            "lakh", "laakh", "lac", "hazaar", "hazar", "hajar", "hazār", "thousand", "sau", "so", "hundred",
            "ek", "one", "do", "be", "two", "teen", "tran", "three", "char", "chhar", "four",
            "paanch", "panch", "five", "chha", "chhah", "six", "saat", "seven", "aath", "eight",
            "nau", "nav", "nine", "das", "dus", "ten", "bees", "vis", "twenty", "tees", "tris", "thirty",
            "chalis", "forty", "pachaas", "pachas", "fifty"
        )

        val words = original.split(Regex("""[\s,.-]+"""))
        val candidateWords = words.filter { w ->
            val wLower = w.lowercase(Locale.ROOT)
            wLower !in stopWords && !wLower.all { it.isDigit() }
        }

        return candidateWords.joinToString(" ").trim()
    }
}
