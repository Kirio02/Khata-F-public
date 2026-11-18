package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.AppLanguage
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings

@Composable
fun FirstRunLanguageScreen(
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val gujaratiLabel = "ગુજરાતી"
    val hindiLabel = "हिन्दी"
    val hinglishLabel = "Hinglish"
    val englishLabel = "English"

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("first_run_language_screen"),
        color = SoftBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(PrimaryBlue.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = PrimaryBlue,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            val welcomeText = LanguageStrings.get("first_run_welcome", "hinglish")
            Text(
                text = welcomeText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )

            val chooseText = LanguageStrings.get("choose_language_title", "hinglish")
            Text(
                text = chooseText,
                fontSize = 15.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Three Huge Buttons: Gujarati, Hindi, Hinglish
            Button(
                onClick = { onLanguageSelected(AppLanguage.GUJARATI) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .testTag("lang_btn_gujarati"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(
                    text = gujaratiLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { onLanguageSelected(AppLanguage.HINDI) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .testTag("lang_btn_hindi"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(
                    text = hindiLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = { onLanguageSelected(AppLanguage.HINGLISH) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .testTag("lang_btn_hinglish"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue)
            ) {
                Text(
                    text = hinglishLabel,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // English in a smaller button
            OutlinedButton(
                onClick = { onLanguageSelected(AppLanguage.ENGLISH) },
                modifier = Modifier
                    .width(180.dp)
                    .height(44.dp)
                    .testTag("lang_btn_english"),
                shape = RoundedCornerShape(22.dp)
            ) {
                Text(
                    text = englishLabel,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
            }
        }
    }
}
