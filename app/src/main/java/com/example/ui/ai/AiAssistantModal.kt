package com.example.ui.ai

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.AppRepository
import com.example.data.local.ChatMessageEntity
import com.example.ui.security.SecureSettingsManager
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

data class ChatMessage(
    val sender: String, // "USER" or "AI"
    val text: String,
    val actionTitle: String? = null,
    val reportContent: String? = null,
    val onExecute: (() -> Unit)? = null,
    val isOutOfScope: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAssistantModal(
    repository: AppRepository,
    initialReportContent: String? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val aiEngine = remember { GhanshyamAiEngine(repository, context) }
    val currentLang by repository.language.collectAsState()

    // TTS initialization
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    var isTtsReady by remember { mutableStateOf(false) }
    var isSpeaking by remember { mutableStateOf(false) }
    var showPoorVoiceHint by remember { mutableStateOf(false) }

    fun cleanTextForTts(text: String, language: com.example.model.AppLanguage): String {
        var s = GhanshyamAiEngine.stripMarkdown(text)
        s = s.replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]+"""), "")
        s = s.replace(Regex("""[\u2600-\u27BF]+"""), "")
        val currencyWord = when (language) {
            com.example.model.AppLanguage.HINDI -> "rupaye"
            com.example.model.AppLanguage.GUJARATI -> "rupya"
            else -> "rupees"
        }
        s = s.replace(Regex("""₹\s*([0-9,]+(?:\.[0-9]{1,2})?)"""), "$1 $currencyWord")
        s = s.replace(Regex("""Rs\.?\s*([0-9,]+(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE), "$1 $currencyWord")
        return s.trim()
    }

    DisposableEffect(currentLang) {
        val targetLocale = when (currentLang) {
            com.example.model.AppLanguage.GUJARATI -> Locale("gu", "IN")
            com.example.model.AppLanguage.HINDI -> Locale("hi", "IN")
            com.example.model.AppLanguage.ENGLISH -> Locale("en", "IN")
            com.example.model.AppLanguage.HINGLISH -> Locale("hi", "IN")
        }

        val speechEngine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                try {
                    tts?.language = targetLocale
                    tts?.setSpeechRate(0.95f)
                    val voices = tts?.voices
                    if (!voices.isNullOrEmpty()) {
                        val matching = voices.filter { it.locale.language == targetLocale.language }
                        val bestVoice = matching.maxByOrNull { it.quality } ?: matching.firstOrNull()
                        if (bestVoice != null) {
                            tts?.voice = bestVoice
                        }
                        val isPoor = matching.isEmpty() || (bestVoice != null && bestVoice.quality <= android.speech.tts.Voice.QUALITY_LOW)
                        if (isPoor && !SecureSettingsManager.isPoorVoiceHintShown(context)) {
                            showPoorVoiceHint = true
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AiAssistantModal", "TTS voice configuration error: ${e.message}")
                }
            }
        }

        speechEngine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                isSpeaking = true
            }
            override fun onDone(utteranceId: String?) {
                isSpeaking = false
            }
            override fun onError(utteranceId: String?) {
                isSpeaking = false
            }
        })

        tts = speechEngine
        onDispose {
            speechEngine.stop()
            speechEngine.shutdown()
        }
    }

    fun speak(text: String) {
        if (isTtsReady && tts != null) {
            val cleaned = cleanTextForTts(text, currentLang)
            tts?.speak(cleaned, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_${System.currentTimeMillis()}")
            isSpeaking = true
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        isSpeaking = false
    }

    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val messages = remember {
        mutableStateListOf<ChatMessage>()
    }

    // Load persisted chat history (last 50)
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { repository.loadRecentChatMessages() }
        if (saved.isNotEmpty()) {
            messages.clear()
            saved.forEach { s ->
                messages.add(
                    ChatMessage(
                        sender = s.sender,
                        text = s.text,
                        actionTitle = s.actionTitle,
                        reportContent = s.reportContent,
                        isOutOfScope = s.isOutOfScope
                    )
                )
            }
        } else {
            messages.add(
                ChatMessage(
                    sender = "AI",
                    text = "Namaste! Main aapka Voice AI Assistant hoon. Hindi, Hinglish ya English me boliye — khata, bill, stock ya report sab handle karunga."
                )
            )
        }

        if (!initialReportContent.isNullOrBlank()) {
            val reportMsg = ChatMessage(
                sender = "AI",
                text = "Aapka evening daily business report ready hai. Neeche summary dekhein ya WhatsApp par share karein.",
                actionTitle = "Daily Business Report",
                reportContent = initialReportContent
            )
            messages.add(reportMsg)
            repository.saveChatMessage(
                sender = "AI",
                text = reportMsg.text,
                actionTitle = reportMsg.actionTitle,
                reportContent = reportMsg.reportContent
            )
        }
    }

    fun sendMessage(query: String, fromVoice: Boolean = false) {
        if (query.isBlank()) return
        messages.add(ChatMessage(sender = "USER", text = query))
        repository.saveChatMessage(sender = "USER", text = query)
        isProcessing = true

        coroutineScope.launch {
            val response = aiEngine.processQuery(query)
            isProcessing = false
            when (response) {
                is AiResponse.Success -> {
                    messages.add(
                        ChatMessage(
                            sender = "AI",
                            text = response.replyText,
                            actionTitle = response.actionTitle,
                            reportContent = response.reportContent,
                            onExecute = response.executeAction
                        )
                    )
                    repository.saveChatMessage(
                        sender = "AI",
                        text = response.replyText,
                        actionTitle = response.actionTitle,
                        reportContent = response.reportContent,
                        isOutOfScope = false
                    )
                    if (fromVoice && SecureSettingsManager.isSpeakRepliesEnabled(context)) {
                        speak(response.replyText)
                    }
                }
                is AiResponse.OutOfScope -> {
                    messages.add(
                        ChatMessage(
                            sender = "AI",
                            text = response.replyText,
                            isOutOfScope = true
                        )
                    )
                    repository.saveChatMessage(
                        sender = "AI",
                        text = response.replyText,
                        isOutOfScope = true
                    )
                    if (fromVoice && SecureSettingsManager.isSpeakRepliesEnabled(context)) {
                        speak(response.replyText)
                    }
                }
            }
        }
    }

    // Voice recognition launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                inputText = spokenText
                sendMessage(spokenText, fromVoice = true)
            }
        }
    }

    fun startListening() {
        val (primaryLang, supportedList) = when (currentLang) {
            com.example.model.AppLanguage.GUJARATI -> Pair("gu-IN", arrayListOf("gu-IN", "hi-IN", "en-IN"))
            com.example.model.AppLanguage.ENGLISH -> Pair("en-IN", arrayListOf("en-IN", "hi-IN"))
            else -> Pair("hi-IN", arrayListOf("hi-IN", "en-IN"))
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLang)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, primaryLang)
            putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, supportedList)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Boliye ($primaryLang)...")
        }
        try {
            speechLauncher.launch(intent)
        } catch (e: Exception) {
            android.util.Log.w("AiAssistantModal", "Speech recognition unavailable: ${e.message}", e)
            messages.add(ChatMessage(sender = "AI", text = "Mic open nahi ho saka. Kripya type karein."))
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            stopSpeaking()
            onDismiss()
        },
        containerColor = SurfaceWhite,
        dragHandle = { BottomSheetDefaults.DragHandle() },
        modifier = Modifier.fillMaxHeight(0.88f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SmartToy, contentDescription = null, tint = SurfaceWhite, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(LanguageStrings.get("ai_modal_title"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text(LanguageStrings.get("ai_modal_subtitle"), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSpeaking) {
                        Button(
                            onClick = { stopSpeaking() },
                            colors = ButtonDefaults.buttonColors(containerColor = UdharRed),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier.testTag("btn_stop_speaking")
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, tint = SurfaceWhite, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(LanguageStrings.get("btn_stop_speaking"), fontSize = 12.sp, color = SurfaceWhite)
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    IconButton(onClick = {
                        stopSpeaking()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
            }

            // Poor voice hint
            if (showPoorVoiceHint) {
                Card(
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = ChipInactiveBg),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = LanguageStrings.get("tts_poor_voice_hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                showPoorVoiceHint = false
                                SecureSettingsManager.setPoorVoiceHintShown(context, true)
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp), tint = TextSecondary)
                        }
                    }
                }
            }

            // Quick suggestion chips
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val suggestions = listOf(
                    "📊 Show today sales report",
                    "❌ Rajesh ka 1850 udhar cancel karo",
                    "☕ Chai samosa kharcha 60",
                    "➕ Ramesh ko 500 udhar likho",
                    "🧾 Generate bill for 950"
                )
                items(suggestions) { s ->
                    Surface(
                        color = ChipInactiveBg,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.clickable { sendMessage(s, fromVoice = false) }
                    ) {
                        Text(
                            text = s,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary
                        )
                    }
                }
            }

            // Chat Messages List
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(messages) { index, msg ->
                    val isAi = msg.sender == "AI"
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = if (isAi) Alignment.Start else Alignment.End
                    ) {
                        Card(
                            shape = RoundedCornerShape(
                                topStart = 16.dp,
                                topEnd = 16.dp,
                                bottomStart = if (isAi) 4.dp else 16.dp,
                                bottomEnd = if (isAi) 16.dp else 4.dp
                            ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isAi) {
                                    if (msg.isOutOfScope) ChipInactiveBg else ChipActiveBg
                                } else PrimaryBlue
                            ),
                            border = CardDefaults.outlinedCardBorder(),
                            modifier = Modifier.widthIn(max = 320.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Text(
                                        text = msg.text,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isAi) TextPrimary else SurfaceWhite,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isAi) {
                                        IconButton(
                                            onClick = { speak(msg.text) },
                                            modifier = Modifier
                                                .size(28.dp)
                                                .testTag("btn_speak_msg_$index")
                                        ) {
                                            Icon(
                                                Icons.Default.VolumeUp,
                                                contentDescription = "Listen",
                                                tint = PrimaryBlue,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                // Action card execution button (Confirmation Card)
                                if (msg.onExecute != null && msg.actionTitle != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    var executed by remember { mutableStateOf(false) }
                                    Button(
                                        onClick = {
                                            msg.onExecute.invoke()
                                            executed = true
                                        },
                                        enabled = !executed,
                                        colors = ButtonDefaults.buttonColors(containerColor = if (executed) JamaGreen else PrimaryBlue),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("btn_apply_proposal")
                                    ) {
                                        Icon(if (executed) Icons.Default.Check else Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(if (executed) LanguageStrings.get("proposal_applied") else "${LanguageStrings.get("btn_apply_proposal")}: ${msg.actionTitle}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }

                                // Report content box with copy and share buttons
                                if (!msg.reportContent.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = SurfaceWhite),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = msg.reportContent,
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                                                color = TextPrimary
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Store Report", msg.reportContent))
                                                    },
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(LanguageStrings.get("btn_copy"), fontSize = 11.sp)
                                                }

                                                Button(
                                                    onClick = {
                                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "text/plain"
                                                            putExtra(Intent.EXTRA_TEXT, msg.reportContent)
                                                        }
                                                        context.startActivity(Intent.createChooser(intent, "Share Report"))
                                                    },
                                                    colors = ButtonDefaults.buttonColors(containerColor = JamaGreen),
                                                    shape = RoundedCornerShape(6.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(LanguageStrings.get("btn_share"), fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isProcessing) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = PrimaryBlue)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(LanguageStrings.get("ai_thinking"), style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                    }
                }
            }

            // Input Bar with Voice Mic & Send Buttons
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(vertical = 8.dp),
                color = SurfaceWhite
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { startListening() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(ChipActiveBg)
                            .testTag("btn_voice_mic")
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = "Voice Input", tint = PrimaryBlue, modifier = Modifier.size(24.dp))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = { Text(LanguageStrings.get("ai_input_placeholder"), fontSize = 13.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_input_field"),
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                val t = inputText
                                inputText = ""
                                sendMessage(t, fromVoice = false)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(PrimaryBlue)
                            .testTag("btn_send_message")
                    ) {
                        Icon(Icons.Default.Send, contentDescription = "Send", tint = SurfaceWhite, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}
