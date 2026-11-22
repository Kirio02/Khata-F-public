package com.example.ui.security

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings

@Composable
fun LockScreen(
    appLockManager: AppLockManager,
    storeName: String,
    onUnlocked: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    var enteredPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val canBiometric = remember { appLockManager.isBiometricEnabled() }

    var lockoutRemaining by remember { mutableStateOf(appLockManager.getRemainingLockoutSeconds()) }

    LaunchedEffect(Unit) {
        while (true) {
            lockoutRemaining = appLockManager.getRemainingLockoutSeconds()
            kotlinx.coroutines.delay(1000)
        }
    }

    fun triggerBiometrics() {
        if (activity != null && canBiometric && lockoutRemaining <= 0) {
            appLockManager.showBiometricPrompt(
                activity = activity,
                onSuccess = {
                    errorMessage = null
                    onUnlocked()
                },
                onError = { err ->
                    // Fall back quietly to PIN keypad
                }
            )
        }
    }

    // Launch biometric on initial display if enabled and not locked out
    LaunchedEffect(Unit) {
        if (canBiometric && lockoutRemaining <= 0) {
            triggerBiometrics()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceWhite)
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Charcoal Swoosh Header Band with Shree Ganeshay Namah and Shop Name
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = BrandCharcoal,
            shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp),
            shadowElevation = 4.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 20.dp, horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = LanguageStrings.get("shree_ganeshay_namah"),
                    style = MaterialTheme.typography.labelSmall,
                    color = GoldAccent,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = storeName.ifBlank { "Ghanshyam Kids Wear" },
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = BrandSerifFontFamily),
                    fontWeight = FontWeight.Bold,
                    color = OnBrand,
                    fontSize = 20.sp
                )
            }
        }

        Spacer(modifier = Modifier.weight(0.1f))

        // Icon & Security Title
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(AccentCyan.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = LanguageStrings.get("pin_setup_desc"),
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 4 PIN Dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (i in 0 until 4) {
                val isFilled = i < enteredPin.length
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isFilled) PrimaryBlue else Color.Transparent)
                        .border(2.dp, if (isFilled) PrimaryBlue else FieldBorder, CircleShape)
                )
            }
        }

        // Lockout Countdown or Error Display
        if (lockoutRemaining > 0) {
            Text(
                text = java.lang.String.format(java.util.Locale.getDefault(), LanguageStrings.get("pin_locked_out_msg"), lockoutRemaining),
                color = UdharRed,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 14.dp)
            )
        } else if (errorMessage != null) {
            Text(
                text = errorMessage ?: "",
                color = UdharRed,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Numeric Keypad
        val keys = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("BIO", "0", "DEL")
        )

        for (row in keys) {
            Row(
                modifier = Modifier.fillMaxWidth(0.82f),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (key in row) {
                    when (key) {
                        "BIO" -> {
                            if (canBiometric && lockoutRemaining <= 0) {
                                KeypadButton(
                                    icon = Icons.Default.Fingerprint,
                                    onClick = { triggerBiometrics() }
                                )
                            } else {
                                Spacer(modifier = Modifier.size(68.dp))
                            }
                        }
                        "DEL" -> {
                            KeypadButton(
                                icon = Icons.AutoMirrored.Filled.Backspace,
                                onClick = {
                                    if (enteredPin.isNotEmpty() && lockoutRemaining <= 0) {
                                        enteredPin = enteredPin.dropLast(1)
                                        errorMessage = null
                                    }
                                }
                            )
                        }
                        else -> {
                            KeypadButton(
                                text = key,
                                onClick = {
                                    if (lockoutRemaining <= 0 && enteredPin.length < 4) {
                                        val newPin = enteredPin + key
                                        enteredPin = newPin
                                        errorMessage = null
                                        if (newPin.length == 4) {
                                            if (appLockManager.verifyPin(newPin)) {
                                                onUnlocked()
                                            } else {
                                                val remaining = appLockManager.getRemainingLockoutSeconds()
                                                if (remaining > 0) {
                                                    lockoutRemaining = remaining
                                                } else {
                                                    errorMessage = LanguageStrings.get("invalid_pin_msg")
                                                }
                                                enteredPin = ""
                                            }
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun KeypadButton(
    text: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = true, radius = 34.dp),
                onClick = onClick
            ),
        shape = CircleShape,
        color = ChipActiveBg,
        tonalElevation = 2.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (text != null) {
                Text(
                    text = text,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = PrimaryBlue
                )
            }
        }
    }
}
