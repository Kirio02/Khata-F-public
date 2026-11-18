package com.example

import com.example.ui.util.BatteryOptimizationHelper
import com.example.ui.util.LanguageStrings
import com.example.ui.util.PhoneBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryBrandTest {

    @Test
    fun detectsCommonIndianPhoneBrands() {
        assertEquals(PhoneBrand.SAMSUNG, BatteryOptimizationHelper.detectBrand("samsung"))
        assertEquals(PhoneBrand.OPPO_FAMILY, BatteryOptimizationHelper.detectBrand("realme"))
        assertEquals(PhoneBrand.OPPO_FAMILY, BatteryOptimizationHelper.detectBrand("OnePlus"))
        assertEquals(PhoneBrand.XIAOMI, BatteryOptimizationHelper.detectBrand("Xiaomi POCO"))
        assertEquals(PhoneBrand.XIAOMI, BatteryOptimizationHelper.detectBrand("redmi"))
        assertEquals(PhoneBrand.VIVO, BatteryOptimizationHelper.detectBrand("vivo"))
        assertEquals(PhoneBrand.TRANSSION, BatteryOptimizationHelper.detectBrand("INFINIX"))
        assertEquals(PhoneBrand.TRANSSION, BatteryOptimizationHelper.detectBrand("TECNO"))
        assertEquals(PhoneBrand.HUAWEI, BatteryOptimizationHelper.detectBrand("HONOR"))
        assertEquals(PhoneBrand.OTHER, BatteryOptimizationHelper.detectBrand("Google"))
    }

    @Test
    fun onlyAggressiveBrandsNeedBackgroundSetup() {
        assertTrue(BatteryOptimizationHelper.needsBackgroundSetup("xiaomi"))
        assertTrue(BatteryOptimizationHelper.needsBackgroundSetup("vivo"))
        assertFalse(BatteryOptimizationHelper.needsBackgroundSetup("motorola"))
        assertFalse(BatteryOptimizationHelper.needsBackgroundSetup("google"))
    }

    @Test
    fun everyBrandHasStepsInAllFourLanguages() {
        val langs = listOf("hinglish", "hi", "gu", "en")
        for (brand in PhoneBrand.values()) {
            for (prefix in listOf("bg_battery_", "bg_autostart_")) {
                val key = prefix + brand.key
                for (lang in langs) {
                    val text = LanguageStrings.get(key, lang)
                    assertFalse("Missing '$key' for '$lang'", text.isBlank() || text == key)
                }
            }
        }
        for (key in listOf("bg_phone_label", "bg_how_to", "btn_later", "bg_opened_app_info", "bg_cannot_open")) {
            for (lang in langs) {
                val text = LanguageStrings.get(key, lang)
                assertFalse("Missing '$key' for '$lang'", text.isBlank() || text == key)
            }
        }
    }
    @Test
    fun batteryStatusStringsExistInAllLanguages() {
        for (key in listOf("bg_status_allowed", "bg_status_not_allowed")) {
            for (lang in com.example.model.AppLanguage.values()) {
                val text = LanguageStrings.get(key, lang)
                assertFalse("Missing '$key' for '$lang'", text.isBlank() || text == key)
            }
        }
    }

    @Test
    fun manifestDoesNotDeclarePlayRestrictedBatteryPermission() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        assertFalse(
            "Google Play restricts this permission and often rejects apps that declare it",
            manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        )
    }
}
