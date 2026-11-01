package de.rj.versandhilfe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable fun ProfileSetup(repo: ParcelRepository) {
    var name by rememberSaveable { mutableStateOf("") }
    var ai by rememberSaveable { mutableStateOf(true) }
    var key by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var consent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Eyebrow("Willkommen bei Versandhilfe")
        ParcelArt(Modifier.fillMaxWidth().height(140.dp))
        Text("Schön, dass du da bist", style = MaterialTheme.typography.headlineLarge)
        Text("Wie darf dich dein Pakethelfer begrüßen?")
        EditField("Dein Name", name, { name = it.take(80) })
        Row { Checkbox(ai, { ai = it }); Column { Text("Mit KI leichter ausfüllen", style = MaterialTheme.typography.titleMedium); Text("Du kannst die App auch ohne KI benutzen.") } }
        if (ai) {
            OutlinedTextField(key, { key = it.trim() }, Modifier.fillMaxWidth(), label = { Text("OpenAI-API-Schlüssel") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Backup-Passwort · mindestens 10 Zeichen") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Text("Bewahre das Passwort auf. Damit kannst du deine Schlüssel nach einer Neuinstallation aus dem Backup wieder freischalten.")
            Row { Checkbox(consent, { consent = it }); Text("Ich möchte Bilder und Versanddaten mit meinem eigenen Schlüssel an OpenAI senden. Die API wird separat abgerechnet.", style = MaterialTheme.typography.bodyMedium) }
            ActionRow(androidx.compose.material.icons.Icons.Outlined.OpenInNew, "API-Schlüssel erstellen", "OpenAI-Konto öffnen", { openWeb(repo.context, "https://platform.openai.com/api-keys") })
        }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        Button(onClick = {
            busy = true
            scope.launch {
                try {
                    if (ai) repo.configureAssistant(name, key, password, true)
                    repo.modifyAssistant { it.copy(name = name.trim(), profileComplete = true, enabled = ai) }
                    key = ""; password = ""
                } catch (e: Exception) { error = e.message ?: "Bitte Angaben prüfen" } finally { busy = false }
            }
        }, enabled = !busy && name.isNotBlank() && (!ai || (key.isNotBlank() && password.length >= 10 && consent)), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Los geht’s") }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}
@Composable fun ParcelSettings(repo: ParcelRepository, onNotification: () -> Unit, onMessage: (String) -> Unit) {
    val state by repo.assistant.collectAsState()
    val scope = rememberCoroutineScope()
    val scheduler = remember { ReminderScheduler(repo.context) }
    fun setting(change: (AssistantState) -> AssistantState) { scope.launch { runCatching { repo.modifyAssistant(change) }.onFailure { onMessage(it.message.orEmpty()) } } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SectionTitle("Paketnummern & Erinnerungen", "Alles bleibt in deiner Sicherung erhalten.")
        ChoiceField("Nummern für neue Pakete", if (state.numberDigits == 2) "Zweistellig · 01–99" else "Dreistellig · 001–999", listOf("Zweistellig · 01–99", "Dreistellig · 001–999")) { v -> setting { it.copy(numberDigits = if (v.startsWith("Zwei")) 2 else 3) } }
        Text("Schreib die Nummer freiwillig auf den Karton. Beim Zurückspringen bleiben Nummern offener Pakete gesperrt. Alte Pakete behalten ihre Nummer und ihren Verlauf.", style = MaterialTheme.typography.bodySmall)
        ChoiceField("Auch ohne Frist erinnern", "Alle ${state.reminderEveryHours} Stunden", listOf("Alle 12 Stunden", "Alle 24 Stunden", "Alle 48 Stunden")) { v -> setting { it.copy(reminderEveryHours = v.split(' ')[1].toInt()) } }
        Row { Checkbox(state.fullScreenReminders, { v -> setting { it.copy(fullScreenReminders = v) } }); Text("Große, leise Erinnerung anzeigen") }
        Text("Am Abgabetag erinnern wir bei offenen Paketen alle 3 Stunden, tagsüber bis 21 Uhr. Du kannst 1, 2 oder 3 Stunden schlummern. Android und Samsung-Energiesparen können Hinweise verzögern.", style = MaterialTheme.typography.bodySmall)
        ActionRow(androidx.compose.material.icons.Icons.Outlined.Notifications, "Benachrichtigungen erlauben", "Für Erinnerungen außerhalb der App", onNotification)
        if (android.os.Build.VERSION.SDK_INT >= 31) ActionRow(androidx.compose.material.icons.Icons.Outlined.Alarm, "Pünktliche Alarme erlauben", "Android-Freigabe · wichtig für den 10-/20-Sekunden-Test", { scheduler.requestExact() })
        if (android.os.Build.VERSION.SDK_INT >= 34) ActionRow(androidx.compose.material.icons.Icons.Outlined.Fullscreen, "Vollbild-Erinnerungen erlauben", "Ohne Freigabe erscheint ein großer Benachrichtigungshinweis", { scheduler.requestFullScreen() })
        Text("Alarm ausprobieren", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(10, 20).forEach { seconds ->
            OutlinedButton(onClick = { scheduler.test(seconds); onMessage(if (scheduler.exactAllowed()) "Testalarm in $seconds Sekunden. Du kannst die App jetzt schließen." else "Test geplant. Für genau $seconds Sekunden bitte zuerst pünktliche Alarme erlauben.") }, modifier = Modifier.weight(1f)) { Text("In $seconds Sek.") }
        } }
    }
}
