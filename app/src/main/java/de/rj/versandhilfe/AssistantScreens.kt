@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package de.rj.versandhilfe

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun AssistantSettings(repo: ParcelRepository, onMessage: (String) -> Unit) {
    val state by repo.assistant.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val vault = remember { SecretVault(context) }
    var name by rememberSaveable(state.name) { mutableStateOf(state.name) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun job(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch { try { block() } catch (e: Exception) { onMessage(e.message ?: "Einstellungen konnten nicht gespeichert werden") } finally { busy = false } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("KI & dein Profil", "Dein persönlicher Versandhelfer – ganz freiwillig.")
        OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(), label = { Text("Wie heißt du?") }, singleLine = true, shape = RoundedCornerShape(20.dp))
        FilledTonalButton(onClick = { job { repo.modifyAssistant { it.copy(name = name.trim().take(80)) }; onMessage("Name gespeichert") } }, enabled = !busy) { Text("Namen speichern") }
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Backup-Passwort") }, supportingText = { Text(if (vault.hasBackupPassword()) "Bereits auf diesem Gerät geschützt hinterlegt. Leer lassen oder neues Passwort wählen." else "Mindestens 10 Zeichen. Gut aufbewahren: Zum Wiederherstellen der API-Schlüssel nötig.") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        Button(onClick = { job { repo.configureAssistant(name, null, password, state.enabled); password = ""; onMessage("Profil und verschlüsseltes Schlüsselbackup gespeichert") } }, enabled = !busy && (password.length >= 10 || vault.hasBackupPassword()), modifier = Modifier.fillMaxWidth()) { Text("Profil & Schlüsselbackup speichern") }
        if (state.encryptedKeys.isNotBlank() && !vault.hasBackupPassword()) {
            InfoCard(Icons.Outlined.Lock, "Schlüsselbackup gefunden", "Nach einer Neuinstallation dein Backup-Passwort eingeben und entsperren. OpenAI und TrackingMore sind danach wieder verfügbar.")
            FilledTonalButton(onClick = { job { repo.unlockKeys(password); password = ""; onMessage("API-Schlüssel entsperrt") } }, enabled = !busy && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("API-Schlüssel aus Backup entsperren") }
        }
        InfoCard(Icons.Outlined.AutoAwesome, "Automatisch erkennen mit GPT-5.6 Luna", "Foto, QR-Label, Adresse oder Vinted-Screenshot: Die KI schlägt die Zuordnung und passende Felder vor. Deine Bilder und der erkannte Text werden dazu an OpenAI gesendet. Fehler bleiben möglich – Angaben vor dem Speichern prüfen.", MaterialTheme.colorScheme.primaryContainer)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("Ich möchte Bilder und Versandtexte mit meinem eigenen OpenAI-API-Schlüssel auswerten. API-Nutzung wird separat abgerechnet.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) }
        OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text(if (vault.openAi.hasKey()) "Neuer OpenAI-Schlüssel (einer ist gespeichert)" else "OpenAI-API-Schlüssel") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(20.dp), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { job { repo.configureAssistant(name, key.ifBlank { null }, password, true); key = ""; password = ""; onMessage("KI-Modus aktiviert – neue Bilder werden automatisch erkannt") } }, enabled = !busy && consent && (key.isNotBlank() || vault.openAi.hasKey()) && (password.length >= 10 || vault.hasBackupPassword())) { Text("KI-Modus aktivieren") }
            if (state.enabled) TextButton(onClick = { job { repo.modifyAssistant { it.copy(enabled = false) }; onMessage("KI-Modus ausgeschaltet") } }, enabled = !busy) { Text("Ausschalten") }
        }
        Text(if (vault.ready(state)) "KI-Modus ist aktiv. Neue Bilder werden automatisch analysiert." else "KI-Modus ist aus oder der Schlüssel ist noch gesperrt. Die normale App bleibt vollständig nutzbar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceField("Maximale KI-Aufrufe pro Tag", state.dailyLimit.toString(), listOf("5", "10", "20", "50")) { value -> job { repo.modifyAssistant { it.copy(dailyLimit = value.toInt()) } } }
        val today = java.time.LocalDate.now().toString()
        val calls = if (state.usageDate == today) state.calls else 0
        val estimate = if (state.usageDate == today) (state.inputTokens * 0.20 + state.outputTokens * 1.20) / 1_000_000.0 else 0.0
        Text("Heute: $calls / ${state.dailyLimit} Aufrufe · Token-Schätzung: ${"%.4f".format(java.util.Locale.US, estimate)} USD\nPreisgrundlage: 0,20 USD Eingabe / 1,20 USD Ausgabe je 1 Mio. Tokens (Stand 01.11.2026). Orientierung, keine Abrechnungszusage. Auch fehlgeschlagene Aufrufversuche zählen zum App-Limit. Allgemeine Fragen und Standardübersichten brauchen keinen API-Aufruf.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ActionRow(Icons.Outlined.OpenInNew, "OpenAI-API-Schlüssel erstellen", "Eigener API-Zugang", { openWeb(context, "https://platform.openai.com/api-keys") })
        ActionRow(Icons.Outlined.OpenInNew, "API-Nutzung & Kosten prüfen", "Abrechnung bei OpenAI", { openWeb(context, "https://platform.openai.com/usage") })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        HorizontalDivider()
    }
}
@Composable fun WelcomeCard(repo: ParcelRepository, parcels: List<Parcel>, onOpen: (String) -> Unit, onChat: () -> Unit) {
    val state by repo.assistant.collectAsStateWithLifecycle()
    val ready = SecretVault(repo.context).ready(state)
    Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(greeting(state.name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(ParcelFacts.brief(parcels), style = MaterialTheme.typography.bodyMedium)
            ParcelFacts.priority(parcels).take(3).forEach { p -> TextButton(onClick = { onOpen(p.id) }, contentPadding = PaddingValues(0.dp)) { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("${p.code} · ${p.title} · ${p.dueDate}") } }
            if (ready) FilledTonalButton(onClick = onChat) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(8.dp)); Text("Deinen Pakethelfer fragen") }
            Text("Aus deinen gespeicherten Paketen · Fristen werden lokal berechnet", style = MaterialTheme.typography.labelSmall)
        }
    }
}
@Composable fun ParcelChatScreen(repo: ParcelRepository, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val state by repo.assistant.collectAsStateWithLifecycle()
    val parcels by repo.parcels.collectAsStateWithLifecycle()
    val ready = SecretVault(repo.context).ready(state)
    var question by rememberSaveable { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clear by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    fun ask(value: String) {
        if (working || value.isBlank() || !ready) return
        question = ""; error = null; working = true
        scope.launch {
            try { ParcelAi(repo).ask(value.trim().take(1000)) } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.message ?: "Deine Frage konnte nicht verarbeitet werden" } finally { working = false }
        }
    }
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) list.animateScrollToItem(state.messages.size) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Dein Pakethelfer") }, actions = { IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.DeleteOutline, "Chat leeren") }; IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "KI-Einstellungen") } })
        if (!ready) { EmptyState("KI-Modus einschalten", "Hinterlege unter KI & Profil deinen OpenAI-Schlüssel. Paketübersicht und Erinnerungen funktionieren auch ohne ihn.", "Zu KI & Profil", onSettings); return }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { InfoCard(Icons.Outlined.AutoAwesome, "Nur für deinen Versand", "Frag nach fälligen Paketen, Sendungen oder Hilfe zur App. Antworten verwenden gespeicherte Fakten. Ich ändere keine Pakete, verschicke nichts und bestätige keine Zustellung.", MaterialTheme.colorScheme.primaryContainer) }
            items(state.messages, key = { it.id }) { message ->
                Surface(shape = RoundedCornerShape(24.dp), color = if (message.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (message.role == "user") state.name.ifBlank { "Du" } else "Pakethelfer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        androidx.compose.foundation.text.selection.SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium) }
                        message.parcelIds.mapNotNull { id -> parcels.find { it.id == id } }.forEach { p -> TextButton(onClick = { onOpen(p.id) }) { Icon(Icons.Outlined.Inventory2, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("${p.code} öffnen · ${p.title}") } }
                        Text(readableTime(message.at), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (working) item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(20.dp)); Text("Ich schaue nach …") } }
            if (error != null) item { InfoCard(Icons.Outlined.Info, "Das hat noch nicht geklappt", error!!, MaterialTheme.colorScheme.errorContainer) }
        }
        androidx.compose.foundation.lazy.LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("Welche Pakete sind bald fällig?", "Welche Pakete kann ich abgeben?", "Welche Pakete sind unterwegs?", "Gib mir eine Paketübersicht")) { suggestion -> SuggestionChip(onClick = { ask(suggestion) }, label = { Text(suggestion) }, enabled = !working) }
        }
        Row(Modifier.fillMaxWidth().imePadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(question, { question = it.take(1000) }, Modifier.weight(1f), placeholder = { Text("Frag etwas zu deinen Paketen …") }, maxLines = 3, shape = RoundedCornerShape(24.dp))
            FilledIconButton(onClick = { ask(question) }, enabled = question.isNotBlank() && !working) { Icon(Icons.Outlined.Send, "Frage senden") }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Chat leeren?") }, text = { Text("Der Verlauf wird aus der aktuellen Sammlung entfernt. Frühere Backups bleiben erhalten.") }, confirmButton = { TextButton(onClick = { clear = false; scope.launch { repo.modifyAssistant { it.copy(messages = emptyList()) } } }) { Text("Leeren") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Abbrechen") } })
}
@Composable fun AiReviewCard(parcel: Parcel, onConfirm: () -> Unit) {
    var show by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InfoCard(Icons.Outlined.AutoAwesome, "Automatisch ausgefüllt – bitte prüfen", parcel.aiSummary.ifBlank { "Die KI hat Angaben ergänzt. Bitte Adresse, Nummer und Abgabefrist abgleichen." }, MaterialTheme.colorScheme.tertiaryContainer)
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(!parcel.aiReviewRequired, { if (it) onConfirm() }); Text("Ich habe die übernommenen Angaben geprüft", style = MaterialTheme.typography.bodyMedium) }
        TextButton(onClick = { show = true }) { Text("Quellen & erkannte Angaben ansehen") }
    }
    if (show) AlertDialog(onDismissRequest = { show = false }, title = { Text("Erkennungsquellen") }, text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Vorhandene Werte bleiben erhalten. Unten siehst du auch erkannte Vorschläge, die einen bestehenden Eintrag nicht überschrieben haben.")
        parcel.aiEvidence.forEach { (key, value) -> Text("$key\n$value", style = MaterialTheme.typography.bodySmall) }
    } }, confirmButton = { TextButton(onClick = { show = false }) { Text("Schließen") } })
}
