@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package de.rj.versandhilfe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val ParcelSaver = Saver<Parcel, String>(save = { AppJson.encodeToString(it) }, restore = { AppJson.decodeFromString<Parcel>(it) })
@Composable fun EditorScreen(repo: ParcelRepository, original: Parcel, onBack: () -> Unit, onSave: (Parcel) -> Unit, onMessage: (String) -> Unit) {
    var parcel by rememberSaveable(original.id, stateSaver = ParcelSaver) { mutableStateOf(original) }
    val aiState by repo.assistant.collectAsStateWithLifecycle()
    val access = rememberVaultAccess(repo, aiState)
    val aiReady = access.ready
    var kindName by rememberSaveable { mutableStateOf(if (aiState.enabled) "AUTO" else AttachmentKind.PHOTO.name) }
    val kind = AttachmentKind.entries.firstOrNull { it.name == kindName } ?: AttachmentKind.PHOTO
    var working by remember { mutableStateOf(false) }
    var scan by remember { mutableStateOf<ScanResult?>(null) }
    var viewer by remember { mutableStateOf<Attachment?>(null) }
    var discard by remember { mutableStateOf(false) }
    var dateDialog by remember { mutableStateOf(false) }
    var packingChoice by rememberSaveable(original.id) { mutableStateOf(original.title.isBlank()) }
    var uploadStep by rememberSaveable { mutableStateOf(false) }
    var guideStep by rememberSaveable { mutableIntStateOf(0) }
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun leave() { if (parcel != original) discard = true else onBack() }
    fun add(uri: Uri, camera: Boolean = false, scannedCodes: List<String> = emptyList()) {
        if (working) return
        working = true
        uploadStep = false
        scope.launch {
            var attached: Attachment? = null
            try {
                val attachment = repo.attach(uri, if (scannedCodes.isNotEmpty()) AttachmentKind.LABEL else kind)
                attached = attachment
                parcel = parcel.copy(attachments = parcel.attachments + attachment)
                // Always decode locally first, including package photos and offline imports.
                val local = runCatching { recognize(repo, attachment) }.getOrElse { ScanResult(emptyList(), "", emptyList(), Carrier.UNKNOWN) }
                val result = local.copy(codes = (scannedCodes + local.codes).distinct().take(20), qrCodes = (scannedCodes + local.qrCodes).distinct().take(20))
                parcel = applyLocalCodes(parcel, attachment, result.codes, result.qrCodes)
                val stored = parcel.attachments.first { it.id == attachment.id }
                if (kindName == "AUTO" && aiReady) {
                    val extraction = ParcelAi(repo).analyze(stored, result)
                    parcel = applyExtraction(parcel, stored, extraction)
                    guideStep = 1
                    onMessage("Erkannt. Wir gehen die Angaben kurz zusammen durch.")
                } else if (result.codes.isNotEmpty() || kind in listOf(AttachmentKind.LABEL, AttachmentKind.ADDRESS)) {
                    if (original.title.isBlank()) guideStep = 1 else scan = result
                } else if (original.title.isBlank()) guideStep = 1
                if (camera) File(cameraPath).delete()
            } catch (e: Exception) {
                onMessage(e.message ?: "Anhang konnte nicht gelesen werden")
                attached?.let { a -> scan = runCatching { recognize(repo, a) }.getOrNull() }
            } finally { working = false; if (camera) File(cameraPath).delete() }
        }
    }
    val scannerPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scannerOpen = true else onMessage("Zum Scannen braucht die App die Kamera. Du kannst auch ein Foto auswählen.")
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) add(uri) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraPath.isNotBlank()) add(FileProvider.getUriForFile(context, "${context.packageName}.files", File(cameraPath)), true)
    }
    val photoPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && cameraPath.isNotBlank()) runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", File(cameraPath))) }
            .onFailure { onMessage("Keine Kamera-App verfügbar. Bitte ein Foto auswählen.") }
        else onMessage("Zum Fotografieren bitte die Kamera erlauben oder ein Foto auswählen.")
    }
    LaunchedEffect(original.id, aiReady, packingChoice) {
        if (packingChoice) return@LaunchedEffect
        if (aiState.enabled && !aiReady) return@LaunchedEffect
        val pending = parcel.attachments.filter { !it.aiAnalyzed }
        if (pending.isNotEmpty()) {
            working = true
            try { pending.forEach { a ->
                val local = runCatching { recognize(repo, a) }.getOrElse { ScanResult(a.rawCodes, "", emptyList(), Carrier.UNKNOWN) }
                parcel = applyLocalCodes(parcel, a, local.codes, local.qrCodes)
                val stored = parcel.attachments.first { it.id == a.id }
                if (aiReady) parcel = applyExtraction(parcel, stored, ParcelAi(repo).analyze(stored, local))
            }; if (aiReady || parcel.attachments.any { it.rawCodes.isNotEmpty() }) guideStep = 1 }
            catch (e: Exception) { onMessage(e.message ?: "Automatische Erkennung nicht verfügbar. Angaben bleiben manuell bearbeitbar.") }
            finally { working = false }
        }
    }
    BackHandler { if (!working) leave() }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(if (original.title.isBlank()) "Neues Paket" else "Paket bearbeiten") }, navigationIcon = { IconButton(onClick = { if (!working) leave() }) { Icon(Icons.Outlined.ArrowBack, "Zurück") } }, actions = {
            TextButton(onClick = {
                val before = original.status
                val events = if (parcel.status != before) listOf(ParcelEvent(text = parcel.status.label)) + parcel.events else if (parcel.events.isEmpty()) listOf(ParcelEvent(text = "Paket angelegt")) else parcel.events
                onSave(parcel.copy(title = parcel.title.trim(), events = events))
            }, enabled = parcel.title.isNotBlank() && !working && !parcel.aiReviewRequired) { Text("Speichern", fontWeight = FontWeight.Bold) }
        })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item { InfoCard(Icons.Outlined.Inventory2, "${parcel.code} · Deine Paketnummer", "Schreib diese Nummer auf den Karton. So gehören Foto und Label immer eindeutig zusammen.", MaterialTheme.colorScheme.primaryContainer) }
            if (aiReady) item { FilledTonalButton(onClick = { kindName = "AUTO"; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.AutoAwesome, null); Spacer(Modifier.width(10.dp)); Text("Foto / Screenshot automatisch ausfüllen") } }
            item { OutlinedButton(onClick = {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) scannerOpen = true
                else scannerPermission.launch(android.Manifest.permission.CAMERA)
            }, enabled = !working, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp)); Text("QR-Code mit Kamera scannen") } }
            if (parcel.aiReviewRequired || parcel.aiSummary.isNotBlank() || parcel.aiEvidence.isNotEmpty()) item { AiReviewCard(parcel) { parcel = parcel.copy(aiReviewRequired = false) } }
            if (aiReady || parcel.attachments.any { it.rawCodes.isNotEmpty() }) item { TextButton(onClick = { guideStep = 1 }, enabled = !working) { Text("Angaben Schritt für Schritt prüfen") } }
            item { SectionTitle("Was geht auf die Reise?", "Ein guter Name hilft beim Wiederfinden.") }
            item { EditField("Artikel / Paketname *", parcel.title, { parcel = parcel.copy(title = it) }, placeholder = "Zum Beispiel: Blaue Jeans · Größe 40") }
            item { ChoiceField("Verkaufsplattform", parcel.platform, listOf("Vinted", "Kleinanzeigen", "eBay", "Facebook", "Privat", "Andere")) { parcel = parcel.copy(platform = it) } }
            item { EditField("Empfänger / Benutzername", parcel.recipient, { parcel = parcel.copy(recipient = it) }) }
            item { EditField("Adresse (optional)", parcel.address, { parcel = parcel.copy(address = it) }, multiline = true) }
            item { EditField("Wo liegt das Paket?", parcel.location, { parcel = parcel.copy(location = it) }, placeholder = "Zum Beispiel: Flur, linkes Regal") }
            item { HorizontalDivider(); Spacer(Modifier.height(14.dp)); SectionTitle("Fotos & Versandunterlagen", "Fotos, Screenshots und PDF-Dateien. Maximal 40 MB pro Datei.") }
            item { ScrollChips((if (aiReady) listOf("Automatisch mit KI") else emptyList()) + AttachmentKind.entries.map { it.label }, if (kindName == "AUTO") "Automatisch mit KI" else kind.label) { label -> kindName = if (label == "Automatisch mit KI") "AUTO" else AttachmentKind.entries.first { it.label == label }.name } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) }, enabled = !working, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.AttachFile, null); Spacer(Modifier.width(6.dp)); Text("Datei / Foto") }
                    OutlinedButton(onClick = {
                        val file = File(context.cacheDir, "shared/camera-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }
                        cameraPath = file.path
                        if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                            runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }.onFailure { onMessage("Keine Kamera-App verfügbar. Bitte ein Foto auswählen.") }
                        else photoPermission.launch(android.Manifest.permission.CAMERA)
                    }, enabled = !working, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.PhotoCamera, null); Spacer(Modifier.width(6.dp)); Text("Kamera") }
                }
            }
            if (working) item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp)); Text("Datei wird gelesen und erkannt …") } }
            items(parcel.attachments, key = { it.id }) { a ->
                AttachmentRow(repo, a, onOpen = { viewer = a }, onScan = {
                    if (!working) { working = true; scope.launch { try {
                        val result = recognize(repo, a)
                        parcel = applyLocalCodes(parcel, a, result.codes, result.qrCodes)
                        scan = result
                    } catch (e: Exception) { onMessage("Erkennung fehlgeschlagen: ${e.message}") } finally { working = false } } }
                }, onRemove = { parcel = parcel.copy(attachments = parcel.attachments.filterNot { it.id == a.id }) })
            }
            item { HorizontalDivider(); Spacer(Modifier.height(14.dp)); SectionTitle("Versand & Zuordnung", "Ein Versand-QR ist nicht immer eine Sendungsnummer. Bitte die Erkennung prüfen.") }
            item { ChoiceField("Versanddienstleister", parcel.carrier.label, Carrier.entries.map { it.label }) { label -> parcel = parcel.copy(carrier = Carrier.entries.first { it.label == label }) } }
            item { EditField("Bestell- / Verkaufsnummer (optional)", parcel.orderNumber, { parcel = parcel.copy(orderNumber = it) }) }
            item { EditField("Sendungsnummer (optional)", parcel.trackingNumber, { parcel = parcel.copy(trackingNumber = it.trim()) }) }
            item { ChoiceField("Status", parcel.status.label, ParcelStatus.entries.map { it.label }) { label -> parcel = parcel.copy(status = ParcelStatus.entries.first { it.label == label }) } }
            item { ActionRow(Icons.Outlined.CalendarMonth, if (parcel.dueDate.isBlank()) "Abgabefrist auswählen" else "Abgeben bis ${parcel.dueDate}", "Optionale Erinnerung, bevor die Frist verstreicht", { dateDialog = true }); if (parcel.dueDate.isNotBlank()) TextButton(onClick = { parcel = parcel.copy(dueDate = "") }) { Text("Frist entfernen") } }
            item { EditField("Notizen", parcel.notes, { parcel = parcel.copy(notes = it) }, multiline = true, placeholder = "Inhalt, Größe, Besonderheiten …") }
            item { HorizontalDivider(); Spacer(Modifier.height(14.dp)); SectionTitle("Sendungsverfolgung", "Mit einer Sendungsnummer kannst du die Anbieterseite kostenlos öffnen. Automatische Updates über TrackingMore bleiben optional.") }
            item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Dieses Paket automatisch verfolgen", style = MaterialTheme.typography.titleSmall); Text("Sendungsnummer + Anbieter werden an TrackingMore gesendet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(parcel.autoTrack, {
                if (it && !ApiKeyStore(context).hasKey()) onMessage("Zuerst unter Sicherung einen API-Schlüssel einrichten")
                else if (it && (parcel.trackingNumber.isBlank() || parcel.apiCourier.isBlank())) onMessage("Bitte zuerst Sendungsnummer und Anbieter eintragen")
                else parcel = parcel.copy(autoTrack = it)
            }) } }
            item { EditField("TrackingMore-Anbieter-Code (optional)", parcel.customCourierCode, { parcel = parcel.copy(customCourierCode = it.trim()) }, placeholder = parcel.carrier.apiCode.ifBlank { "Für weitere Versanddienstleister" }); Text("Nur nötig für andere Anbieter oder abweichende Versandprodukte. Den Code findest du bei TrackingMore.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Spacer(Modifier.height(8.dp)); Button(onClick = {
                val events = if (parcel.status != original.status) listOf(ParcelEvent(text = parcel.status.label)) + parcel.events else parcel.events.ifEmpty { listOf(ParcelEvent(text = "Paket angelegt")) }
                onSave(parcel.copy(title = parcel.title.trim(), events = events))
            }, enabled = parcel.title.isNotBlank() && !working && !parcel.aiReviewRequired, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("Paket speichern") }; Spacer(Modifier.height(24.dp)) }
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Änderungen verwerfen?") }, text = { Text("Dieses Paket enthält ungespeicherte Änderungen.") }, confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("Verwerfen") } }, dismissButton = { Button(onClick = { discard = false }) { Text("Weiter bearbeiten") } })
    if (dateDialog) {
        val dateState = rememberDatePickerState(initialSelectedDateMillis = runCatching { LocalDate.parse(parcel.dueDate).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull())
        DatePickerDialog(onDismissRequest = { dateDialog = false }, confirmButton = { TextButton(onClick = { dateState.selectedDateMillis?.let { parcel = parcel.copy(dueDate = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; dateDialog = false }) { Text("Übernehmen") } }, dismissButton = { TextButton(onClick = { dateDialog = false }) { Text("Abbrechen") } }) { DatePicker(dateState) }
    }
    scan?.let { result -> ScanDialog(result, onDismiss = { scan = null }, onRecipient = { recipient ->
        parcel = parcel.copy(recipient = recipient.name, address = recipient.address, carrier = Carrier.DHL, aiReviewRequired = true)
        scan = null; guideStep = 1
    }, onCandidate = { c -> parcel = parcel.copy(trackingNumber = c.number, carrier = if (c.carrier == Carrier.UNKNOWN) parcel.carrier else c.carrier); scan = null },
        onText = { asAddress -> parcel = if (asAddress) parcel.copy(address = result.text) else parcel.copy(notes = listOf(parcel.notes, result.text).filter { it.isNotBlank() }.joinToString("\n\n")); scan = null }) }
    if (packingChoice) PackingChoice(onChoose = { parcel = parcel.copy(status = it); packingChoice = false; uploadStep = parcel.attachments.isEmpty() }, onCancel = onBack)
    if (uploadStep && !scannerOpen && !working) IntakeUpload(aiReady,
        onFile = { kindName = if (aiReady) "AUTO" else AttachmentKind.LABEL.name; picker.launch(arrayOf("image/jpeg", "image/png", "image/webp", "application/pdf")) },
        onScanner = { if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) scannerOpen = true else scannerPermission.launch(android.Manifest.permission.CAMERA) },
        onCamera = {
            val file = File(context.cacheDir, "shared/camera-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs(); createNewFile() }; cameraPath = file.path
            kindName = if (aiReady) "AUTO" else AttachmentKind.PHOTO.name
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) else photoPermission.launch(android.Manifest.permission.CAMERA)
        }, onManual = { uploadStep = false; guideStep = 1 })
    if (working) RecognitionProgress(aiReady)
    if (guideStep > 0 && !working && !packingChoice && !uploadStep) IntakeGuide(parcel, guideStep, { parcel = it }, { guideStep = it }, onComplete = onSave)
    if (scannerOpen) LiveScanner(onDismiss = { scannerOpen = false }, onCaptured = { uri, codes, file ->
        scannerOpen = false
        cameraPath = file.path
        kindName = if (aiReady) "AUTO" else AttachmentKind.LABEL.name
        add(uri, true, codes)
    })
    viewer?.let { a -> AttachmentViewer(repo, a) { viewer = null } }
}
@Composable fun SectionTitle(title: String, subtitle: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
@Composable fun EditField(label: String, value: String, onValue: (String) -> Unit, multiline: Boolean = false, placeholder: String = "") {
    OutlinedTextField(value, { onValue(it.take(12000)) }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = if (placeholder.isNotBlank()) { { Text(placeholder) } } else null, singleLine = !multiline, minLines = if (multiline) 3 else 1, shape = RoundedCornerShape(20.dp))
}
@Composable fun AttachmentRow(repo: ParcelRepository, a: Attachment, onOpen: () -> Unit, onScan: (() -> Unit)? = null, onRemove: (() -> Unit)? = null) {
    OutlinedCard(onClick = onOpen, shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (a.mime.startsWith("image/")) AsyncImage(File(repo.assets, a.file), a.kind.label, Modifier.size(58.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            else Icon(Icons.Outlined.PictureAsPdf, null, Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) { Text(a.detectedKinds.ifEmpty { listOf(a.kind) }.joinToString(" + ") { it.label }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(a.name, style = MaterialTheme.typography.bodySmall, maxLines = 2) }
            if (onScan != null) IconButton(onClick = onScan) { Icon(Icons.Outlined.DocumentScanner, "Code und Text erkennen") }
            if (onRemove != null) IconButton(onClick = onRemove) { Icon(Icons.Outlined.Close, "Anhang entfernen") }
        }
    }
}
@Composable fun ScanDialog(result: ScanResult, onDismiss: () -> Unit, onCandidate: (TrackingCandidate) -> Unit, onRecipient: (ShippingAddress) -> Unit, onText: (Boolean) -> Unit) {
    val context = LocalContext.current
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Erkennung prüfen") }, icon = { Icon(Icons.Outlined.DocumentScanner, null) }, text = {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val analysis = ShippingCodeParser.analyze(result.codes)
            analysis.recipients.forEach { recipient ->
                OutlinedCard(onClick = { onRecipient(recipient) }) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Empfänger aus dem QR-Code", fontWeight = FontWeight.Bold); Text(recipient.name); Text(recipient.address)
                    Text("Empfänger übernehmen", color = MaterialTheme.colorScheme.primary)
                } }
            }
            if (analysis.parsed.isNotEmpty()) Text("Der Absender im ersten Adressblock wird nicht als Empfänger übernommen.")
            if (analysis.needsChoice) Text("Adresszuordnung unklar. Bitte den richtigen Empfänger selbst bestätigen.", color = MaterialTheme.colorScheme.error)
            Text("Versand-QR-Codes können nur zum Erstellen eines Labels dienen. Übernimm eine Nummer erst nach dem Abgleich mit der Sendungsnummer.")
            if (result.suggestedCarrier != Carrier.UNKNOWN) Text("Anbieterhinweis: ${result.suggestedCarrier.label}", fontWeight = FontWeight.Bold)
            if (result.candidates.isEmpty()) Text("Keine eindeutige Sendungsnummer erkannt. Den ursprünglichen QR-Code kannst du im Paketshop trotzdem anzeigen.")
            result.candidates.forEach { c -> OutlinedCard(onClick = { onCandidate(c) }) { Column(Modifier.padding(12.dp)) { Text(c.number, fontWeight = FontWeight.Bold); Text("${c.carrier.label} · ${c.reason}", style = MaterialTheme.typography.bodySmall); Text("Als Sendungsnummer übernehmen", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) } } }
            if (result.codes.isNotEmpty()) { Text("Gelesene Codes", fontWeight = FontWeight.Bold); result.codes.forEach { code -> SelectionContainer { Text(code.take(3000), style = MaterialTheme.typography.bodySmall) } } }
            if (result.text.isNotBlank()) {
                Text("Gelesener Text", fontWeight = FontWeight.Bold)
                SelectionContainer { Text(result.text.take(12000), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onText(false) }) { Text("Text an Notizen anhängen") }
                TextButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Erkannter Text", result.text)) }) { Text("Text kopieren") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Fertig") } })
}
@Composable fun DetailScreen(repo: ParcelRepository, p: Parcel, onBack: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onStatus: (ParcelStatus) -> Unit, onRefresh: () -> Unit) {
    var viewer by remember { mutableStateOf<Attachment?>(null) }
    var delete by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val printScope = rememberCoroutineScope()
    var printError by remember { mutableStateOf("") }
    val photo = p.attachments.firstOrNull { it.hasKind(AttachmentKind.PHOTO) && it.mime.startsWith("image/") }
    val label = p.attachments.firstOrNull { it.hasKind(AttachmentKind.LABEL) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text(p.code, fontWeight = FontWeight.Bold) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "Zurück") } }, actions = { IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Bearbeiten") } })
        LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            if (photo != null) item { Card(onClick = { viewer = photo }, shape = RoundedCornerShape(28.dp)) { AsyncImage(File(repo.assets, photo.file), "Paketfoto", Modifier.fillMaxWidth().height(220.dp), contentScale = ContentScale.Crop) } }
            item { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Eyebrow("${p.code} · ${p.platform}"); Text(p.title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { StatusPill(p.status); SmallPill(p.carrier.label, Icons.Outlined.LocalShipping) } } }
            if (label != null) item { Button(onClick = { viewer = label }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp), shape = RoundedCornerShape(22.dp)) { Icon(Icons.Outlined.QrCode2, null, Modifier.size(28.dp)); Spacer(Modifier.width(12.dp)); Text("Versandlabel / QR anzeigen", style = MaterialTheme.typography.titleMedium) } }
            if (label == null) item { InfoCard(Icons.Outlined.AttachFile, "Versandlabel fehlt noch", "Über den Stift kannst du einen Screenshot, ein Foto oder eine PDF hinzufügen.", MaterialTheme.colorScheme.tertiaryContainer) }
            item { Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                DetailLine("Erfasst am", readableTime(p.createdAt)); DetailLine("Empfänger", p.recipient.ifBlank { "Noch nicht eingetragen" }); if (p.address.isNotBlank()) DetailLine("Adresse", p.address); if (p.location.isNotBlank()) DetailLine("Liegt bei", p.location); if (p.dueDate.isNotBlank()) DetailLine("Abgabefrist", p.dueDate); if (p.notes.isNotBlank()) DetailLine("Notizen", p.notes); if (p.orderNumber.isNotBlank()) DetailLine("Bestellnummer", p.orderNumber); if (p.aiSummary.isNotBlank()) DetailLine("Erkennungshinweis", p.aiSummary)
            } } }
            item { ChoiceField("Status ändern", p.status.label, ParcelStatus.entries.map { it.label }) { labelText -> onStatus(ParcelStatus.entries.first { it.label == labelText }) } }
            item { SectionTitle("Sendungsverfolgung", p.trackingNumber.ifBlank { "Noch keine Sendungsnummer eingetragen" }) }
            if (p.trackingNumber.isNotBlank()) item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val url = p.carrier.trackingUrl(p.trackingNumber)
                if (url != null) FilledTonalButton(onClick = { openWeb(context, url) }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.OpenInNew, null); Spacer(Modifier.width(6.dp)); Text("Kostenlos verfolgen") }
                OutlinedButton(onClick = { (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Sendungsnummer", p.trackingNumber)) }) { Icon(Icons.Outlined.ContentCopy, "Sendungsnummer kopieren") }
            } }
            if (p.trackingSummary.isNotBlank()) item { InfoCard(Icons.Outlined.LocalShipping, p.trackingSummary, "Geprüft: ${readableTime(p.trackingCheckedAt)}") }
            if (p.trackingError.isNotBlank()) item { InfoCard(Icons.Outlined.Info, "Tracking braucht Aufmerksamkeit", p.trackingError, MaterialTheme.colorScheme.errorContainer) }
            if (p.autoTrack) item { OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Tracking jetzt aktualisieren") } }
            if (!p.autoTrack) item { Text("Automatische Updates sind für dieses Paket aus. Du kannst sie beim Bearbeiten einschalten, sobald ein API-Schlüssel eingerichtet ist.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (p.attachments.isNotEmpty()) item { Text("Alle Anhänge", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(p.attachments, key = { it.id }) { a -> AttachmentRow(repo, a, { viewer = a }) }
            item { OutlinedButton(onClick = { printScope.launch {
                runCatching {
                    val file = File(context.cacheDir, "shared/Verlauf-${p.id}.pdf")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ParcelPdf.history(file, p) }
                    printPdf(context, file, "${p.code} Versandübersicht")
                }.onFailure { printError = "Die Übersicht konnte nicht gedruckt werden." }
            } }, modifier = Modifier.fillMaxWidth()) { Text("Paketverlauf als PDF / drucken") }; if (printError.isNotBlank()) Text(printError) }
            item { Text("Paketverlauf", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
            items(p.events.sortedByDescending { it.at }) { e -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Column { Text(e.text, style = MaterialTheme.typography.bodyMedium); Text("${readableTime(e.at)} · ${e.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } } }
            item { TextButton(onClick = { delete = true }) { Icon(Icons.Outlined.DeleteOutline, null); Spacer(Modifier.width(8.dp)); Text("Paket entfernen") }; Spacer(Modifier.height(24.dp)) }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("${p.code} entfernen?") }, text = { Text("Das Paket verschwindet aus deiner aktuellen Sammlung. Ältere Ordnersicherungen und exportierte ZIP-Dateien bleiben erhalten.") }, confirmButton = { TextButton(onClick = { delete = false; onDelete() }) { Text("Entfernen") } }, dismissButton = { Button(onClick = { delete = false }) { Text("Behalten") } })
    viewer?.let { a -> AttachmentViewer(repo, a) { viewer = null } }
}
@Composable fun DetailLine(title: String, text: String) { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) } } }
@Composable fun AttachmentViewer(repo: ParcelRepository, a: Attachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var page by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var totalPages by remember { mutableIntStateOf(1) }
    var error by remember { mutableStateOf<String?>(null) }
    val file = File(repo.assets, a.file)
    val bitmap by produceState<android.graphics.Bitmap?>(null, a.file, page) {
        value = null
        if (a.mime == "application/pdf") runCatching { renderPage(file, page) }.onSuccess { value = it.first; totalPages = it.second }.onFailure { error = "Die PDF konnte nicht geöffnet werden." }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = androidx.compose.ui.platform.LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            val old = window?.attributes?.screenBrightness
            if (window != null) { val attr = window.attributes; attr.screenBrightness = 1f; window.attributes = attr }
            onDispose { if (window != null && old != null) { val attr = window.attributes; attr.screenBrightness = old; window.attributes = attr } }
        }
        Column(Modifier.fillMaxSize().background(Color.White).safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Schließen", tint = Color.Black) }
                Column(Modifier.weight(1f)) { Text(a.kind.label, color = Color.Black, fontWeight = FontWeight.Bold); Text("Mit zwei Fingern vergrößern", color = Color.DarkGray, style = MaterialTheme.typography.labelSmall) }
                IconButton(onClick = { zoom = 1f; dx = 0f; dy = 0f }) { Icon(Icons.Outlined.FitScreen, "Zoom zurücksetzen", tint = Color.Black) }
                IconButton(onClick = {
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                    val intent = Intent(Intent.ACTION_SEND).setType(a.mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    intent.clipData = ClipData.newRawUri("Anhang", uri)
                    runCatching { context.startActivity(Intent.createChooser(intent, "Anhang teilen")) }
                }) { Icon(Icons.Outlined.Share, "Teilen / Drucken", tint = Color.Black) }
            }
            Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(0.dp)).pointerInput(a.file, page) { detectTransformGestures { _, pan, factor, _ -> zoom = (zoom * factor).coerceIn(1f, 8f); dx = (dx + pan.x).coerceIn(-size.width * zoom, size.width * zoom); dy = (dy + pan.y).coerceIn(-size.height * zoom, size.height * zoom) } }, contentAlignment = Alignment.Center) {
                val imageModifier = Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = dx; translationY = dy }
                if (error != null) Text(error!!, color = Color.Black)
                else if (a.mime == "application/pdf") { if (bitmap != null) Image(bitmap!!.asImageBitmap(), a.name, imageModifier, contentScale = ContentScale.Fit) else CircularProgressIndicator() }
                else AsyncImage(file, a.name, imageModifier, contentScale = ContentScale.Fit)
            }
            if (a.mime == "application/pdf" && totalPages > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { page--; zoom = 1f; dx = 0f; dy = 0f }, enabled = page > 0) { Text("Zurück") }; Text("${page + 1} / $totalPages", color = Color.Black); TextButton(onClick = { page++; zoom = 1f; dx = 0f; dy = 0f }, enabled = page < totalPages - 1) { Text("Weiter") }
            }
            Text("${a.name} · Originaldatei", Modifier.padding(16.dp), color = Color.DarkGray, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}
