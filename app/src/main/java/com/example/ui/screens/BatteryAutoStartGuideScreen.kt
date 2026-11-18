package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.*
import com.example.ui.util.BatteryOptimizationHelper
import com.example.ui.util.LanguageStrings

@Composable
fun BatteryAutoStartGuideScreen(
    currentLang: AppLanguage = AppLanguage.HINGLISH,
    onSkip: (() -> Unit)? = null,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    var batteryClicked by remember { mutableStateOf(false) }
    var autoStartClicked by remember { mutableStateOf(false) }

    // Real status: re-checked every time the user comes back from the Settings screen.
    var batteryAllowed by remember { mutableStateOf(BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryAllowed = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val brand = remember { BatteryOptimizationHelper.detectBrand() }
    val phoneName = remember { BatteryOptimizationHelper.displayName() }
    val stepOne = LanguageStrings.get("btn_battery_opt", currentLang)
    val stepTwo = LanguageStrings.get("btn_autostart", currentLang)
    val howTo = LanguageStrings.get("bg_how_to", currentLang)
    val tipOne = LanguageStrings.get("bg_battery_${brand.key}", currentLang)
    val tipTwo = LanguageStrings.get("bg_autostart_${brand.key}", currentLang)
    val cannotOpenMessage = LanguageStrings.get("bg_cannot_open", currentLang)
    val appInfoMessage = LanguageStrings.get("bg_opened_app_info", currentLang)

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("battery_autostart_guide_screen"),
        color = SoftBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = LanguageStrings.get("battery_guide_title", currentLang),
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = LanguageStrings.get("battery_guide_subtitle", currentLang),
                fontSize = 13.sp,
                color = TextSecondary,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = LanguageStrings.format("bg_phone_label", currentLang, phoneName),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryBlue,
                modifier = Modifier.testTag("battery_guide_phone_label")
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Action 1: Battery Optimization
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = UdharRed, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stepOne, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = howTo, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = tipOne, fontSize = 13.sp, lineHeight = 18.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = LanguageStrings.get(if (batteryAllowed) "bg_status_allowed" else "bg_status_not_allowed", currentLang),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (batteryAllowed) JamaGreen else UdharRed,
                        modifier = Modifier.testTag("battery_status_text")
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            batteryClicked = true
                            if (!BatteryOptimizationHelper.openBatteryOptimizationSettings(context)) {
                                Toast.makeText(context, cannotOpenMessage, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("btn_open_battery_opt"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
                    ) {
                        Text(
                            text = LanguageStrings.get("btn_battery_opt", currentLang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Action 2: Auto-Start / Sleeping Apps
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                border = CardDefaults.outlinedCardBorder()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, tint = JamaGreen, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = stepTwo, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = howTo, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(text = tipTwo, fontSize = 13.sp, lineHeight = 18.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            autoStartClicked = true
                            val openedMakerScreen = BatteryOptimizationHelper.openAutoStartSettings(context)
                            if (!openedMakerScreen) {
                                Toast.makeText(context, appInfoMessage, Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("btn_open_autostart"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JamaGreen)
                    ) {
                        Text(
                            text = LanguageStrings.get("btn_autostart", currentLang),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Done Tick Button
            Button(
                onClick = onDone,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .testTag("btn_battery_guide_done"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = LanguageStrings.get("btn_done", currentLang),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (onSkip != null) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("btn_battery_guide_later")
                ) {
                    Text(
                        text = LanguageStrings.get("btn_later", currentLang),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
