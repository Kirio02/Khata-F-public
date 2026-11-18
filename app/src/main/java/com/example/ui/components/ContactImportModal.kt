package com.example.ui.components

import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.model.AppLanguage
import com.example.ui.theme.*
import com.example.ui.util.LanguageStrings
import com.example.ui.util.PhoneUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PhoneContact(
    val name: String,
    val rawNumber: String,
    val normalizedNumber: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactImportModal(
    lang: AppLanguage,
    existingPhoneNumbers: Set<String>,
    onDismiss: () -> Unit,
    onContactsImported: (List<PhoneContact>) -> Unit
) {
    val context = LocalContext.current
    var contacts by remember { mutableStateOf<List<PhoneContact>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedNormalizedNumbers = remember { mutableStateListOf<String>() }

    // Load contacts on Dispatchers.IO
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val list = mutableListOf<PhoneContact>()
            try {
                val projection = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                context.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    projection,
                    null,
                    null,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                )?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                    val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    while (cursor.moveToNext()) {
                        val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                        val num = if (numIdx >= 0) cursor.getString(numIdx) ?: "" else ""
                        val normalized = PhoneUtil.normalize(num)
                        if (name.isNotBlank() && normalized.isNotBlank()) {
                            list.add(PhoneContact(name = name.trim(), rawNumber = num.trim(), normalizedNumber = normalized))
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("ContactImportModal", "Failed to query contacts: ${t.message}", t)
            }
            val deduped = list.distinctBy { it.normalizedNumber }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            withContext(Dispatchers.Main) {
                contacts = deduped
                isLoading = false
            }
        }
    }

    val filteredContacts = remember(contacts, searchQuery) {
        if (searchQuery.isBlank()) {
            contacts
        } else {
            val q = searchQuery.trim()
            contacts.filter {
                it.name.contains(q, ignoreCase = true) ||
                        it.normalizedNumber.contains(q) ||
                        it.rawNumber.contains(q)
            }
        }
    }

    val allVisibleSelected = filteredContacts.isNotEmpty() && filteredContacts.all { selectedNormalizedNumbers.contains(it.normalizedNumber) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
            color = SoftBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 2.dp,
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.minTouchTarget()
                        ) {
                            Icon(Icons.Default.Close, contentDescription = LanguageStrings.get("cancel_button", lang))
                        }

                        Text(
                            text = LanguageStrings.get("import_contacts_title", lang),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = AppSpacing.xs)
                        )

                        if (filteredContacts.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    if (allVisibleSelected) {
                                        filteredContacts.forEach { selectedNormalizedNumbers.remove(it.normalizedNumber) }
                                    } else {
                                        filteredContacts.forEach {
                                            if (!selectedNormalizedNumbers.contains(it.normalizedNumber)) {
                                                selectedNormalizedNumbers.add(it.normalizedNumber)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier.minTouchTarget()
                            ) {
                                val selectLabel = if (allVisibleSelected) LanguageStrings.get("unselect_all", lang) else LanguageStrings.get("select_all_visible", lang)
                                Text(
                                    text = selectLabel,
                                    color = PrimaryBlue,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Search Bar
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = LanguageStrings.get("search_contacts_hint", lang),
                            color = TextSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = LanguageStrings.get("search_hint", lang), tint = TextSecondary)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = LanguageStrings.get("cancel_button", lang))
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                    shape = AppCardShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite
                    )
                )

                if (isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = PrimaryBlue)
                    }
                } else if (filteredContacts.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = LanguageStrings.get("no_contacts_found", lang),
                            color = TextSecondary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = AppSpacing.md),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        items(filteredContacts, key = { it.normalizedNumber }) { contact ->
                            val isSelected = selectedNormalizedNumbers.contains(contact.normalizedNumber)
                            val isAlreadyPresent = existingPhoneNumbers.contains(contact.normalizedNumber)

                            AppCard(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (isSelected) {
                                            selectedNormalizedNumbers.remove(contact.normalizedNumber)
                                        } else {
                                            selectedNormalizedNumbers.add(contact.normalizedNumber)
                                        }
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isSelected,
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (!selectedNormalizedNumbers.contains(contact.normalizedNumber)) {
                                                    selectedNormalizedNumbers.add(contact.normalizedNumber)
                                                }
                                            } else {
                                                selectedNormalizedNumbers.remove(contact.normalizedNumber)
                                            }
                                        },
                                        modifier = Modifier.minTouchTarget()
                                    )

                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(horizontal = AppSpacing.xs)
                                    ) {
                                        Text(
                                            text = contact.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = TextPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = PhoneUtil.toDisplay(contact.normalizedNumber),
                                            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                                            color = TextSecondary,
                                            maxLines = 1
                                        )
                                    }

                                    if (isAlreadyPresent) {
                                        Surface(
                                            color = SoftBackground,
                                            shape = RoundedCornerShape(4.dp)
                                        ) {
                                            Text(
                                                text = LanguageStrings.get("already_in_khata", lang),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = TextSecondary,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom Action Bar
                Surface(
                    color = SurfaceWhite,
                    shadowElevation = 8.dp,
                    border = CardDefaults.outlinedCardBorder()
                ) {
                    val count = selectedNormalizedNumbers.size
                    val buttonTitle = if (count > 0) {
                        LanguageStrings.get("btn_add_parties_count", lang) + " ($count)"
                    } else {
                        LanguageStrings.get("btn_add_parties_count", lang)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                val selectedList = contacts.filter { selectedNormalizedNumbers.contains(it.normalizedNumber) }
                                onContactsImported(selectedList)
                            },
                            enabled = count > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                            shape = AppCardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minTouchTarget()
                        ) {
                            Text(
                                text = buttonTitle,
                                fontWeight = FontWeight.Bold,
                                color = SurfaceWhite
                            )
                        }
                    }
                }
            }
        }
    }
}
