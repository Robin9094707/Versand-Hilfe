@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package de.rj.versandhilfe

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun readableTime(value: Long): String = if (value == 0L) "Noch nicht" else DateTimeFormatter.ofPattern("dd.MM.yyyy · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
@Composable fun Eyebrow(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold) }
@Composable fun ScreenHeading(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium) }
        action?.invoke()
    }
}
@Composable fun ParcelArt(modifier: Modifier = Modifier) {
    val motion = rememberInfiniteTransition(label = "Paket schwebt")
    val float by motion.animateFloat(-6f, 6f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Schweben")
    Canvas(modifier) {
        val unit = minOf(size.width, size.height) / 220f
        val cx = size.width / 2f
        val cy = size.height / 2f + float * unit
        drawCircle(Color(0xFFE8DCFF), 92 * unit, Offset(cx, cy))
        fun face(points: List<Pair<Float, Float>>, color: Color) {
            val path = Path().apply { points.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(cx + x * unit, cy + y * unit) else lineTo(cx + x * unit, cy + y * unit) }; close() }
            drawPath(path, color)
        }
        drawOval(Color(0xFF7046D3).copy(alpha = .1f), Offset(cx - 55 * unit, cy + 67 * unit), androidx.compose.ui.geometry.Size(110 * unit, 14 * unit))
        face(listOf(-63f to -28f, 0f to -61f, 63f to -28f, 0f to 7f), Color(0xFFFFD6B7))
        face(listOf(-63f to -24f, -3f to 11f, -3f to 73f, -63f to 38f), Color(0xFFD5B0FE))
        face(listOf(3f to 11f, 63f to -24f, 63f to 38f, 3f to 73f), Color(0xFF8757D6))
        face(listOf(-26f to -48f, 37f to -14f, 51f to -22f, -12f to -55f), Color(0xFFAA85E1))
        face(listOf(37f to -10f, 50f to -18f, 50f to 5f, 37f to 13f), Color(0xFFEEDBFF))
        drawCircle(Color(0xFFDAF2E5), 26 * unit, Offset(cx + 65 * unit, cy + 43 * unit))
        val check = Path().apply { moveTo(cx + 53 * unit, cy + 43 * unit); lineTo(cx + 62 * unit, cy + 52 * unit); lineTo(cx + 78 * unit, cy + 33 * unit) }
        drawPath(check, Color(0xFF286348), style = androidx.compose.ui.graphics.drawscope.Stroke(5 * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        drawCircle(Color(0xFFFFBE96), 6 * unit, Offset(cx - 77 * unit, cy - 61 * unit))
        drawCircle(Color(0xFFB49ADE), 4 * unit, Offset(cx + 76 * unit, cy - 65 * unit))
    }
}
@Composable fun OnboardingScreen(onChooseFolder: () -> Unit) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Viele Pakete.\nAlles im Blick.", "Ein Paket.\nAlles beisammen.", "Deine Sammlung.\nGut aufgehoben.")
    val descriptions = listOf("Für deine Vinted-Funde und alles, was ein neues Zuhause bekommt. Versand ganz ohne Durcheinander.", "Paketfoto, Versand-QR, Adresse und Notizen an einem Ort. Schreib die Paketnummer auf den Karton – fertig.", "Wähle einen eigenen Ordner, zum Beispiel Dokumente/Versandhilfe. Fotos, Labels und Pakete bleiben dort auch nach dem Löschen der App. Danach denselben Ordner erneut wählen.")
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp)); Eyebrow("Versandhilfe • Für dich gemacht")
        ParcelArt(Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp, max = 290.dp))
        AnimatedContent(page, label = "Einleitung") { p -> Column {
            Text(titles[p], fontSize = 36.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(20.dp)); Text(descriptions[p], style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        Spacer(Modifier.height(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { repeat(3) { i -> Box(Modifier.size(if (i == page) 24.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)) } }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { if (page < 2) page++ else onChooseFolder() }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
            Text(if (page == 2) "Sicherungsordner auswählen" else "Weiter", style = MaterialTheme.typography.titleMedium); Spacer(Modifier.width(12.dp)); Icon(if (page == 2) Icons.Outlined.FolderOpen else Icons.Outlined.ArrowForward, null)
        }
        TextButton(onClick = { if (page > 0) page-- else page = 2 }, modifier = Modifier.padding(bottom = 16.dp)) { Text(if (page > 0) "Zurück" else "Ich habe schon eine Sammlung") }
    }
}
@Composable fun HomeScreen(repo: ParcelRepository, parcels: List<Parcel>, onAdd: () -> Unit, onOpen: (String) -> Unit, onSettings: () -> Unit, onChat: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Aktiv") }
    var provider by rememberSaveable { mutableStateOf("Alle Anbieter") }
    var sort by rememberSaveable { mutableStateOf("Neueste zuerst") }
    var showFilters by remember { mutableStateOf(false) }
    val backup by repo.backupMessage.collectAsStateWithLifecycle()
    val active = parcels.count { it.status != ParcelStatus.DELIVERED }
    val ready = parcels.count { it.status == ParcelStatus.READY }
    val filtered = parcels.filter { p ->
        (filter == "Alle" || (filter == "Aktiv" && p.status != ParcelStatus.DELIVERED) || filter == p.status.label) &&
            (provider == "Alle Anbieter" || provider == p.carrier.label) &&
            listOf(p.title, p.code, p.recipient, p.address, p.notes, p.location, p.platform, p.trackingNumber).any { it.contains(query, true) }
    }.let { list -> if (sort == "Frist zuerst") list.sortedWith(compareBy<Parcel> { it.dueDate.ifBlank { "9999" } }.thenBy { it.number }) else list.sortedByDescending { it.createdAt } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().testTag("parcel-list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 104.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { ScreenHeading("Dein Versand", "Ein bisschen Ordnung. Viel mehr Überblick.") { IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, "Einstellungen") } } }
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(32.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Alles an seinem Platz")
                            Text("$active", fontSize = 52.sp, lineHeight = 58.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(if (active == 1) "aktives Paket" else "aktive Pakete", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Spacer(Modifier.height(12.dp)); Text("$ready bereit zur Abgabe", color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                        }
                        ParcelArt(Modifier.size(140.dp))
                    }
                }
            }
            item { WelcomeCard(repo, parcels, onOpen, onChat) }
            if (parcels.any { it.isDue() }) item { InfoCard(Icons.Outlined.Schedule, "Die nächste Abgabe wartet", "${parcels.count { it.isDue() }} Pakete solltest du heute oder morgen abgeben.", MaterialTheme.colorScheme.tertiaryContainer) }
            if (repo.prefs.getBoolean("dirty", false) || repo.folderUri == null) item { Surface(onClick = onSettings, color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.CloudOff, null); Spacer(Modifier.width(12.dp)); Text(backup, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Icon(Icons.Outlined.ChevronRight, null) } } }
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Text("Paket, Name oder Nummer suchen") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, trailingIcon = { if (query.isNotBlank()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Suche leeren") } }, singleLine = true, shape = RoundedCornerShape(24.dp)) }
            item { ScrollChips(listOf("Aktiv", "Alle") + ParcelStatus.entries.map { it.label }, filter) { filter = it } }
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Deine Pakete · ${filtered.size}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showFilters = true }) { Icon(Icons.Outlined.Tune, "Anbieter und Sortierung") }
            } }
            if (filtered.isEmpty()) item { EmptyState(if (parcels.isEmpty()) "Das erste Paket wartet" else "Keine passenden Pakete", if (parcels.isEmpty()) "Foto machen, Label hinzufügen, Paketnummer aufschreiben. So einfach geht’s." else "Versuche einen anderen Suchbegriff oder Filter.", if (parcels.isEmpty()) "Erstes Paket anlegen" else null, onAdd) }
            items(filtered, key = { it.id }) { parcel -> Box(Modifier.animateItem()) { ParcelCard(repo, parcel, { onOpen(parcel.id) }) } }
        }
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Neues Paket") }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp), containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
    }
    if (showFilters) ModalBottomSheet(onDismissRequest = { showFilters = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Deine Ansicht", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            ChoiceField("Anbieter", provider, listOf("Alle Anbieter") + Carrier.entries.map { it.label }) { provider = it }
            ChoiceField("Sortierung", sort, listOf("Neueste zuerst", "Frist zuerst")) { sort = it }
            Button(onClick = { showFilters = false }, modifier = Modifier.fillMaxWidth()) { Text("Anwenden") }
        }
    }
}
@Composable fun ParcelCard(repo: ParcelRepository, parcel: Parcel, onClick: () -> Unit, selected: Boolean? = null, onSelect: (() -> Unit)? = null) {
    Card(onClick = onClick, shape = RoundedCornerShape(26.dp), colors = CardDefaults.cardColors(containerColor = if (selected == true) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val photo = parcel.attachments.firstOrNull { it.hasKind(AttachmentKind.PHOTO) && it.mime.startsWith("image/") }
                if (photo != null) AsyncImage(File(repo.assets, photo.file), "Foto von ${parcel.title}", Modifier.size(74.dp).clip(RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                else Box(Modifier.size(74.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Inventory2, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary) }
                Column(Modifier.weight(1f)) {
                    Text("${parcel.code}  ·  ${parcel.platform}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Text(parcel.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(parcel.recipient.ifBlank { "Empfänger noch offen" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (selected != null) Checkbox(selected, { onSelect?.invoke() }) else Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(parcel.status)
                SmallPill(parcel.carrier.label, Icons.Outlined.LocalShipping)
                if (parcel.attachments.any { it.hasKind(AttachmentKind.LABEL) }) SmallPill("Label", Icons.Outlined.QrCode2)
                if (parcel.isDue()) SmallPill("Frist: ${parcel.dueDate}", Icons.Outlined.Schedule, true)
            }
            if (parcel.location.isNotBlank()) Text("Liegt bei: ${parcel.location}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
@Composable fun StatusPill(status: ParcelStatus) {
    val color = when (status) { ParcelStatus.DELIVERED -> MaterialTheme.colorScheme.secondaryContainer; ParcelStatus.READY -> MaterialTheme.colorScheme.tertiaryContainer; ParcelStatus.SHIPPED -> MaterialTheme.colorScheme.primaryContainer; else -> MaterialTheme.colorScheme.surfaceContainerHigh }
    Surface(color = color, shape = CircleShape) { Text(status.label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface) }
}
@Composable fun SmallPill(text: String, icon: ImageVector, warning: Boolean = false) { Surface(color = if (warning) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh, shape = CircleShape) { Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) { Icon(icon, null, Modifier.size(13.dp)); Text(text, style = MaterialTheme.typography.labelSmall) } } }
@Composable fun InfoCard(icon: ImageVector, title: String, text: String, color: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Surface(color = color, shape = RoundedCornerShape(24.dp)) { Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) { Icon(icon, null, Modifier.size(25.dp)); Column { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(text, style = MaterialTheme.typography.bodyMedium) } } }
}
@Composable fun EmptyState(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) { Icon(Icons.Outlined.Inventory2, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary); Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold); Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium); if (action != null) FilledTonalButton(onClick = onAction) { Text(action) } }
}
@Composable fun ScrollChips(values: List<String>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(values) { value -> FilterChip(selected == value, { onSelect(value) }, label = { Text(value) }) } }
}
@Composable fun ChoiceField(label: String, value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedCard(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) { Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) }; Icon(Icons.Outlined.ExpandMore, null) } }
        DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem(text = { Text(choice) }, onClick = { onSelect(choice); expanded = false }) } }
    }
}
@Composable fun DropoffScreen(repo: ParcelRepository, parcels: List<Parcel>, onOpen: (String) -> Unit, onStatus: (Set<String>) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val ready = parcels.filter { it.status == ParcelStatus.READY }.sortedBy { it.carrier.label }
    val chosen = selected.toSet().intersect(ready.map { it.id }.toSet())
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("Zeit zum Abgeben", "Foto vergleichen. Label zeigen. Fertig.") }
        item { InfoCard(Icons.Outlined.Edit, "Die Nummer macht den Unterschied", "Schreib P-001, P-002 … auf deine Kartons. Im Paketshop findest du so sofort den passenden QR-Code.") }
        if (ready.isEmpty()) item { EmptyState("Alles erledigt", "Pakete mit dem Status „Bereit zur Abgabe“ erscheinen hier.") }
        if (ready.isNotEmpty()) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) { Text("${ready.size} Pakete bereit", style = MaterialTheme.typography.titleMedium); TextButton(onClick = { selected = if (chosen.size == ready.size) emptyList() else ready.map { it.id } }) { Text(if (chosen.size == ready.size) "Auswahl lösen" else "Alle auswählen") } } }
        items(ready, key = { it.id }) { p -> ParcelCard(repo, p, { onOpen(p.id) }, p.id in chosen) { selected = if (p.id in selected) selected - p.id else selected + p.id } }
        if (chosen.isNotEmpty()) item { Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Icon(Icons.Outlined.Check, null); Spacer(Modifier.width(8.dp)); Text("${chosen.size} als abgegeben markieren") } }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Pakete wirklich abgegeben?") }, text = { Text("Markiere nur Pakete, die du tatsächlich beim Versanddienstleister abgegeben hast. Den Einlieferungsbeleg kannst du danach beim Paket hinzufügen.") }, confirmButton = { Button(onClick = { confirm = false; onStatus(chosen); selected = emptyList() }) { Text("Abgegeben") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Zurück") } })
}
@Composable fun SettingsScreen(repo: ParcelRepository, onFolder: () -> Unit, onBackup: () -> Unit, onExport: () -> Unit, onImport: () -> Unit, onMessage: (String) -> Unit, onNotification: () -> Unit) {
    val backup by repo.backupMessage.collectAsStateWithLifecycle()
    var apiKey by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(ApiKeyStore(repo.context).hasKey()) }
    var consent by rememberSaveable { mutableStateOf(false) }
    val assistant by repo.assistant.collectAsStateWithLifecycle()
    val settingsScope = rememberCoroutineScope()
    val reminders = assistant.reminders
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { ScreenHeading("Gut aufgehoben", "Dein Profil, KI-Modus und Sicherung.") }
        item { AssistantSettings(repo, onMessage) }
        item { InfoCard(Icons.Outlined.FolderOpen, repo.folderName, "$backup\nLetzte Sicherung: ${readableTime(repo.lastBackup)}") }
        item { Text("Sicherung & Wiederherstellung", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { ActionRow(Icons.Outlined.FolderOpen, "Ordner auswählen / wieder verbinden", "Nach Neuinstallation denselben Ordner wählen", onFolder) }
        item { ActionRow(Icons.Outlined.CloudUpload, "Jetzt im Ordner sichern", "Alle Pakete, Bilder, PDFs und Notizen", onBackup) }
        item { ActionRow(Icons.Outlined.IosShare, "ZIP-Backup exportieren", "Zum Kopieren auf ein anderes Gerät", onExport) }
        item { ActionRow(Icons.Outlined.Restore, "ZIP-Backup importieren", "Sammlungen zusammenführen, nichts pauschal ersetzen", onImport) }
        item { Text("Der gewählte Ordner bleibt beim Deinstallieren erhalten. Sichere zusätzlich eine ZIP-Datei außerhalb des Handys, falls das Gerät verloren geht. Alte Sicherungen enthalten auch früher entfernte Pakete. ZIPs und Ordner enthalten persönliche Daten unverschlüsselt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { HorizontalDivider() }
        item { Text("Optionale Sendungsverfolgung", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        item { InfoCard(Icons.Outlined.TravelExplore, "Automatisch mit TrackingMore", "Für automatische Updates wird ein eigenes TrackingMore-Konto mit API-Schlüssel benötigt. Je nach Tarif kostenpflichtig. Ohne Schlüssel bleiben die direkten Anbieter-Links verfügbar.", MaterialTheme.colorScheme.primaryContainer) }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("Ich möchte Sendungsnummer und Anbieter an TrackingMore übertragen. Fotos, Namen, Adressen und Notizen werden dafür nicht gesendet.", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium) } }
        item { OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text(if (hasKey) "Neuer API-Schlüssel (einer ist gespeichert)" else "TrackingMore API-Schlüssel") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(autoCorrectEnabled = false), shape = RoundedCornerShape(20.dp)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { settingsScope.launch { runCatching { repo.saveTrackingKey(apiKey); hasKey = true; apiKey = "" }.onSuccess { onMessage("TrackingMore-Schlüssel geschützt und im Schlüsselbackup gespeichert") }.onFailure { onMessage(it.message ?: "Zuerst ein Backup-Passwort unter KI & Profil einrichten") } } }, enabled = consent && apiKey.isNotBlank()) { Text("Schlüssel speichern") }; if (hasKey) TextButton(onClick = { settingsScope.launch { runCatching { repo.saveTrackingKey(""); hasKey = false }.onFailure { onMessage(it.message ?: "Schlüssel konnte nicht entfernt werden") } } }) { Text("Entfernen") } } }
        item { Text("Automatik pro Paket einschalten. Android prüft ungefähr alle 6 Stunden, abhängig von Akku und Internet. Kein Echtzeitversprechen. Der Schlüssel wird lokal mit Android Keystore und im Backup zusätzlich mit deinem Backup-Passwort geschützt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { ActionRow(Icons.Outlined.OpenInNew, "TrackingMore einrichten", "Konto, API-Zugang und Tarife", { openWeb(context, "https://www.trackingmore.com/tracking-api.html") }) }
        item { HorizontalDivider() }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("An Abgabefristen erinnern", style = MaterialTheme.typography.titleMedium); Text("Heute, morgen oder bereits überfällig", style = MaterialTheme.typography.bodySmall) }; Switch(reminders, { value -> settingsScope.launch { repo.modifyAssistant { it.copy(reminders = value) } } }) } }
        item { ChoiceField("Tägliche Erinnerung frühestens ab", "${assistant.reminderHour}:00 Uhr", listOf("9:00 Uhr", "12:00 Uhr", "18:00 Uhr")) { value -> settingsScope.launch { repo.modifyAssistant { it.copy(reminderHour = value.substringBefore(":").toInt()) } } } }
        item { Text("Android führt Erinnerungen im Hintergrund auch nach dem normalen Schließen aus. Akkuoptimierung kann sie verschieben; nach „Stopp erzwingen“ muss die App einmal wieder geöffnet werden. Kein Dauerbetrieb und keine ständigen KI-Aufrufe nötig.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { ActionRow(Icons.Outlined.NotificationsActive, "Benachrichtigungen erlauben", "Paket-Updates und Abgabefristen", onNotification) }
        item { Text("Versandhilfe 2.0.0\nMit Liebe zur Ordnung. Für die vielen kleinen Reisen deiner Pakete.", Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
@Composable fun ActionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Outlined.ChevronRight, null) } }
}
fun openWeb(context: android.content.Context, url: String) { runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }
