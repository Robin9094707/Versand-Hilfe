package de.rj.versandhilfe

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

data class VaultAccess(val ready: Boolean = false, val unlocked: Boolean = false, val hasKey: Boolean = false, val password: Boolean = false, val tracking: Boolean = false)
@Composable fun rememberVaultAccess(repo: ParcelRepository, state: AssistantState): VaultAccess {
    val result by produceState(VaultAccess(), state.enabled, state.encryptedKeys, state.updatedAt) {
        value = withContext(Dispatchers.IO) { val v = SecretVault(repo.context); VaultAccess(v.ready(state), v.unlocked(state), v.openAi.hasKey(), v.hasBackupPassword(), v.tracking.hasKey()) }
    }
    return result
}
@Composable fun WaveProgress(progress: Float? = null) {
    val phase = rememberInfiniteTransition(label = "Fortschritt").animateFloat(0f, 6.2832f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "Welle")
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(Modifier.fillMaxWidth().height(18.dp)) {
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, size.height/2), androidx.compose.ui.geometry.Offset(size.width, size.height/2), 5.dp.toPx(), StrokeCap.Round)
        val end = size.width * (progress ?: .72f).coerceIn(.02f, 1f)
        val path = Path()
        var x = 0f
        while (x <= end) {
            val y = size.height / 2 + sin(x / 16.dp.toPx() - phase.value) * 4.dp.toPx()
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 2.dp.toPx()
        }
        drawPath(path, primary, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
    }
}
@Composable fun FlowWindow(title: String, subtitle: String, progress: Float? = null, bottom: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false, dismissOnClickOutside = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view, dark) {
            (view.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            onDispose { }
        }
        BackHandler { }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    WaveProgress(progress)
                    Text(title, style = MaterialTheme.typography.headlineMedium)
                }
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { bottom() }
            }
        }
    }
}
@Composable fun PackingChoice(onChoose: (ParcelStatus) -> Unit, onCancel: () -> Unit) {
    FlowWindow("Ist das Paket schon fertig?", "Neues Paket · Schritt 1", .1f, bottom = { TextButton(onClick = onCancel) { Text("Zurück zur Übersicht") } }) {
        ParcelArt(Modifier.fillMaxWidth().height(160.dp))
        Text("Wähle aus, wie weit du bist. Danach fügen wir Foto oder QR-Code hinzu.", style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(onClick = { onChoose(ParcelStatus.READY) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("✓", style = MaterialTheme.typography.headlineLarge); Text("Fertig gepackt", style = MaterialTheme.typography.titleLarge); Text("Bereit zum Abgeben") }
            }
            Card(onClick = { onChoose(ParcelStatus.DRAFT) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { Text("…", style = MaterialTheme.typography.headlineLarge); Text("Wird noch gepackt", style = MaterialTheme.typography.titleLarge); Text("In Vorbereitung") }
            }
        }
    }
}
@Composable fun IntakeUpload(ai: Boolean, onFile: () -> Unit, onScanner: () -> Unit, onCamera: () -> Unit, onManual: () -> Unit) {
    FlowWindow("Foto oder QR-Code hinzufügen", "Dein Paket · Schritt 2", .22f, bottom = { TextButton(onClick = onManual) { Text("Ohne Foto weiter") } }) {
        Text(if (ai) "Lade dein Foto, Versandlabel oder einen Screenshot hoch. Danach prüft die KI die Angaben und wir gehen sie zusammen durch." else "Die App liest QR-Codes direkt auf deinem Handy. Danach ergänzen wir die fehlenden Angaben.", style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onFile, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Foto / Datei auswählen") }
        FilledTonalButton(onClick = onScanner, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("QR-Code scannen") }
        OutlinedButton(onClick = onCamera, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) { Text("Foto aufnehmen") }
    }
}
@Composable fun RecognitionProgress(ai: Boolean) {
    FlowWindow("Details werden geprüft", if (ai) "QR-Code lesen & KI-Erkennung" else "Foto und QR-Code werden gelesen", null) {
        Spacer(Modifier.height(36.dp)); CircularProgressIndicator(Modifier.size(64.dp).align(Alignment.CenterHorizontally)); Spacer(Modifier.height(24.dp))
        Text("Einen Moment. Bitte warte, bis die Prüfung fertig ist.", style = MaterialTheme.typography.titleLarge)
        Text("Zuerst liest dein Handy die Codes. Im KI-Modus werden anschließend das Bild und die erkannten Daten geprüft. Danach kannst du alles ergänzen.")
    }
}
