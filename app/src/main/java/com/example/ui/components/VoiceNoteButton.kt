package com.example.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.model.AppLanguage
import com.example.ui.theme.PrimaryBlue
import com.example.ui.util.LanguageStrings

@Composable
fun VoiceNoteButton(
    currentLang: AppLanguage,
    onSpeechResult: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                onSpeechResult(spoken)
            }
        }
    }

    IconButton(
        onClick = {
            val langCode = when (currentLang) {
                AppLanguage.HINDI -> "hi-IN"
                AppLanguage.GUJARATI -> "gu-IN"
                AppLanguage.ENGLISH -> "en-IN"
                AppLanguage.HINGLISH -> "hi-IN"
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, langCode)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langCode)
                putExtra(RecognizerIntent.EXTRA_PROMPT, LanguageStrings.get("speak_note_prompt", currentLang))
            }
            try {
                speechLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    LanguageStrings.get("voice_not_supported", currentLang),
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = LanguageStrings.get("voice_note_desc", currentLang),
            tint = PrimaryBlue,
            modifier = Modifier.size(22.dp)
        )
    }
}
