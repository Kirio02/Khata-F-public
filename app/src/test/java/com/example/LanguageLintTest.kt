package com.example

import com.example.ui.util.LanguageStrings
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LanguageLintTest {

    @Test
    fun testNoHardcodedTextLiteralsInUi() {
        val candidateDirs = listOf(
            File("src/main/java/com/example/ui"),
            File("app/src/main/java/com/example/ui"),
            File("/app/applet/app/src/main/java/com/example/ui")
        )
        val uiDir = candidateDirs.firstOrNull { it.exists() && it.isDirectory }
        assertNotNull("UI directory must exist in candidate paths", uiDir)

        val uiFiles = uiDir!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertNotNull("UI files should be present", uiFiles)
        assertTrue("UI files list should not be empty", uiFiles.isNotEmpty())

        val violations = mutableListOf<String>()

        // Regex detecting Text("...") literals in Compose: Text\s*\(\s*"
        // Negative lookbehind ensures it's specifically the Compose Text function, not drawText("...") or getPlainText("...")
        val textLiteralRegex = Regex("""(?<![a-zA-Z0-9_])Text\s*\(\s*"""")

        uiFiles.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val trimmed = line.trim()
                // Skip comments
                if (!trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")) {
                    if (textLiteralRegex.containsMatchIn(line)) {
                        violations.add("${file.name}:${index + 1}: $trimmed")
                    }
                }
            }
        }

        assertTrue(
            "Found ${violations.size} hardcoded Text(\"...\") literal(s) in ui/ files. All user-facing strings must use LanguageStrings.get(...):\n" +
                    violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun testLanguageStringsCompletenessAcrossAllLanguages() {
        val supportedLangs = listOf("hinglish", "hi", "gu", "en")

        // Verify key business and UI strings exist and have non-empty translations for all 4 languages
        val testKeys = listOf(
            "tab_khata",
            "tab_billing",
            "tab_stock",
            "tab_cashbook",
            "you_gave_btn",
            "you_got_btn",
            "btn_new_bill_short",
            "tab_new_bill",
            "tab_past_bills",
            "tax_invoice_label",
            "bill_of_supply_label",
            "voice_ai_title",
            "daily_report_title",
            "empty_khata_title",
            "empty_bills_title",
            "empty_stock_title",
            "empty_cashbook_title",
            "cancel_button",
            "search_contacts_hint",
            "bill_note_hint",
            "btn_clear",
            "none_label",
            "you_will_get",
            "you_will_give",
            "settled",
            "address_label",
            "opening_balance_note",
            "contacts_permission_title",
            "permission_denied_title",
            "permission_required_title",
            "onboarding_shop_subtitle",
            "invalid_pin_msg"
        )

        for (key in testKeys) {
            for (lang in supportedLangs) {
                val str = LanguageStrings.get(key, lang)
                assertFalse("Key '$key' for language '$lang' must not be empty or fall back to missing key", str.isBlank())
                assertFalse("Key '$key' for language '$lang' must not equal the raw key name", str == key)
            }
        }
    }
}
