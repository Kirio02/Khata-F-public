package com.example.ui.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Phone makers whose Android skins aggressively kill background apps. */
enum class PhoneBrand(val key: String) {
    SAMSUNG("samsung"),
    OPPO_FAMILY("oppo"),      // Oppo, Realme, OnePlus
    XIAOMI("xiaomi"),         // Xiaomi, Redmi, Poco
    VIVO("vivo"),             // Vivo, iQOO
    TRANSSION("transsion"),   // Tecno, Infinix, itel
    HUAWEI("huawei"),         // Huawei, Honor
    OTHER("other")
}

object BatteryOptimizationHelper {

    /**
     * The one-tap "Allow background activity" popup (ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) needs a
     * permission that Google Play restricts, so it is deliberately NOT used. App Info is opened instead:
     * every phone has Battery > Unrestricted there, which is what the on-screen tips describe.
     */
    fun getBatteryOptimizationIntent(context: Context): Intent = appDetailsIntent(context)

    /** True when the user already allowed this app to run in the background (battery "Unrestricted"). */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (_: Exception) {
        false
    }

    /**
     * Opens the battery screen for this app: App Info first (Battery > Unrestricted lives there),
     * then the phone's battery-optimization list. Returns true if something opened.
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        val attempts = listOf(
            appDetailsIntent(context),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        )
        for (intent in attempts) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: Exception) {
                // Not on this phone: try the next one
            }
        }
        return false
    }

    /** Works out the phone maker. [testManufacturer] lets unit tests pretend to be another phone. */
    fun detectBrand(testManufacturer: String? = null): PhoneBrand {
        val m = (testManufacturer ?: "${Build.MANUFACTURER} ${Build.BRAND}").lowercase()
        return when {
            m.contains("samsung") -> PhoneBrand.SAMSUNG
            m.contains("oppo") || m.contains("coloros") || m.contains("realme") || m.contains("oneplus") -> PhoneBrand.OPPO_FAMILY
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> PhoneBrand.XIAOMI
            m.contains("vivo") || m.contains("iqoo") -> PhoneBrand.VIVO
            m.contains("tecno") || m.contains("infinix") || m.contains("itel") || m.contains("transsion") -> PhoneBrand.TRANSSION
            m.contains("huawei") || m.contains("honor") -> PhoneBrand.HUAWEI
            else -> PhoneBrand.OTHER
        }
    }

    /** True for phones that are known to stop background apps unless the user allows it. */
    fun needsBackgroundSetup(testManufacturer: String? = null): Boolean =
        detectBrand(testManufacturer) != PhoneBrand.OTHER

    /** Name shown on screen, e.g. "Samsung". */
    fun displayName(): String =
        Build.MANUFACTURER.orEmpty().trim().replaceFirstChar { it.uppercase() }.ifBlank { "Android" }

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))

    private fun autoStartCandidates(brand: PhoneBrand): List<Intent> = when (brand) {
        PhoneBrand.SAMSUNG -> listOf(
            component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
            component("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.battery.BatteryActivity")
        )
        PhoneBrand.OPPO_FAMILY -> listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.coloros.powermanager", "com.coloros.powermanager.fuelga.PowerUsageModelActivity")
        )
        PhoneBrand.XIAOMI -> listOf(
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        )
        PhoneBrand.VIVO -> listOf(
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
        )
        PhoneBrand.TRANSSION -> listOf(
            component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            component("com.transsion.phonemaster", "com.cyin.himgr.whitelist.view.WhiteListActivity"),
            component("com.itel.autostart", "com.itel.autostart.AutoStartActivity")
        )
        PhoneBrand.HUAWEI -> listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        PhoneBrand.OTHER -> emptyList()
    }

    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }

    fun getAutoStartIntent(context: Context, testManufacturer: String? = null): Intent {
        val candidateIntents = autoStartCandidates(detectBrand(testManufacturer ?: Build.MANUFACTURER))

        for (intent in candidateIntents) {
            try {
                if (intent.resolveActivity(context.packageManager) != null) {
                    return intent
                }
            } catch (_: Exception) {}
        }

        if (candidateIntents.isNotEmpty()) {
            return candidateIntents.first()
        }

        return appDetailsIntent(context)
    }

    /**
     * Opens the phone maker's auto-start / background screen. Android 11+ hides other apps from
     * resolveActivity(), so each screen is simply tried in turn. If none opens, App Info opens
     * instead. Returns true only when the phone maker's own screen opened.
     */
    fun openAutoStartSettings(context: Context, testManufacturer: String? = null): Boolean {
        val candidates = autoStartCandidates(detectBrand(testManufacturer ?: Build.MANUFACTURER))
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (_: Exception) {
                // Not on this phone / not allowed: try the next one
            }
        }
        try {
            context.startActivity(appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return false
    }
}
