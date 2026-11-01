package de.rj.versandhilfe

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable fun VintedHelpSettings(repo: ParcelRepository, onMessage: (String) -> Unit) {
    val state by repo.assistant.collectAsStateWithLifecycle()
    val connected by VintedHelpState.connected.collectAsStateWithLifecycle()
    val status by VintedHelpState.status.collectAsStateWithLifecycle()
    var consentDialog by remember { mutableStateOf(false) }
    var localConsent by remember { mutableStateOf(repo.prefs.getBoolean("vintedQrConsent", false)) }
    val scope = rememberCoroutineScope()
    fun openSettings() { repo.context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Vinted-QR-Hilfe", "Einmal einschalten. In Vinted die Versandvorschau öffnen. Übernahme bestätigen.")
        Text("Die optionale Bedienungshilfe sucht nur in erkannten Versandvorschauen der Vinted-App nach QR-Codes. Sie prüft bereits gespeicherte Codes und fragt vor einer neuen Übernahme. Kein Dauer-Video und keine automatischen Klicks in Vinted.")
        Row {
            Switch(state.vintedQrHelp && localConsent, { enabled ->
                if (enabled) consentDialog = true
                else {
                    localConsent = false; repo.prefs.edit().putBoolean("vintedQrConsent", false).apply()
                    scope.launch { repo.modifyAssistant { it.copy(vintedQrHelp = false) } }
                }
            }, enabled = Build.VERSION.SDK_INT >= 30)
            Text("Vinted-QR-Hilfe einschalten", Modifier.padding(12.dp))
        }
        if (Build.VERSION.SDK_INT < 30) Text("Für diese Funktion wird Android 11 oder neuer benötigt. Teilen und Fotoimport funktionieren weiterhin.")
        if (state.vintedQrHelp && localConsent) {
            InfoCard(Icons.Outlined.QrCodeScanner, if (connected) "Bedienungshilfe verbunden" else "Android-Freigabe fehlt noch", if (connected) status else "Öffne die Bedienungshilfen, suche „Versandhilfe · Vinted-QR“ und schalte sie ein.")
            FilledTonalButton(onClick = ::openSettings, modifier = Modifier.fillMaxWidth()) { Text("Android-Bedienungshilfen öffnen") }
            if (Build.VERSION.SDK_INT >= 33) {
                Text("Falls Android „Eingeschränkte Einstellung“ meldet: App-Info von Versandhilfe öffnen → oben ⋮ → „Eingeschränkte Einstellungen zulassen“. Anschließend die Bedienungshilfe einschalten. Die genaue Bezeichnung kann auf Samsung abweichen.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { repo.context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${repo.context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("App-Info öffnen") }
            }
            OutlinedButton(onClick = { repo.prefs.edit().putLong("vintedQrPauseUntil", System.currentTimeMillis() + 60000).apply(); onMessage("Die Erkennung pausiert für eine Minute.") }) { Text("Eine Minute pausieren") }
        }
        Text("Nur lesbare Versandvorschauen werden berücksichtigt. Bei geschützten Bildschirmen, anderer Vinted-Oberfläche oder fehlenden Informationen kann die Erkennung ausbleiben. Dann Screenshot → Teilen → Versandhilfe nutzen. Die Freigabe wird von Android verwaltet und muss nach einer Neuinstallation erneut erteilt werden.", style = MaterialTheme.typography.bodySmall)
    }
    if (consentDialog) AlertDialog(onDismissRequest = { consentDialog = false }, title = { Text("Vinted-QR-Hilfe einrichten?") }, text = {
        Text("Android erlaubt dieser Bedienungshilfe technisch weitreichenden Bildschirmzugriff. Versandhilfe nutzt ihn nur für erkennbare Versandvorschauen in der Vinted-App. Fensterwechsel werden berücksichtigt, damit die Erkennung beim Verlassen stoppt.\n\nAuf Android 11–13 wird ein Bildschirmbild kurz im Arbeitsspeicher verarbeitet. Es wird nicht als Ganzes gespeichert. Erst nach „Übernehmen“ werden der QR-Ausschnitt und ausgewählte Versandtexte in deiner Sammlung gespeichert. Keine Garantie, dass vorübergehend nie andere Bildinhalte erfasst werden.\n\nLogin, Zahlungsseiten und Eingabefelder werden übersprungen. Die KI startet erst, wenn du die Paketangaben in Versandhilfe prüfst. Du kannst die Hilfe jederzeit hier oder in Android ausschalten.", Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()))
    }, confirmButton = { Button(onClick = {
        consentDialog = false; localConsent = true
        repo.prefs.edit().putBoolean("vintedQrConsent", true).apply()
        scope.launch { runCatching { repo.modifyAssistant { it.copy(vintedQrHelp = true) }; openSettings() }.onFailure { onMessage(it.message ?: "Einrichtung nicht möglich") } }
    }) { Text("Verstanden · einrichten") } }, dismissButton = { TextButton(onClick = { consentDialog = false }) { Text("Abbrechen") } })
}
