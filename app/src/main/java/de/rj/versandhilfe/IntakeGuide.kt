package de.rj.versandhilfe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Follow-up questions are local: no extra paid model turns and no invented facts. */
@Composable fun IntakeGuide(parcel: Parcel, step: Int, onChange: (Parcel) -> Unit, onStep: (Int) -> Unit) {
    var title by rememberSaveable(step) { mutableStateOf(parcel.title) }
    var name by rememberSaveable(step) { mutableStateOf(parcel.recipient) }
    var address by rememberSaveable(step) { mutableStateOf(parcel.address) }
    var location by rememberSaveable(step) { mutableStateOf(parcel.location) }
    val allCodes = parcel.attachments.flatMap { it.rawCodes }
    val codes = remember(allCodes) { ShippingCodeParser.analyze(allCodes) }
    val known = codes.recipient
    val pending = ParcelFacts.pending(parcel)
    val complete = parcel.title.isNotBlank() && parcel.recipient.isNotBlank() && parcel.location.isNotBlank()
    AlertDialog(onDismissRequest = { onStep(0) }, icon = { Icon(Icons.Outlined.Inventory2, null) }, title = { Text(when (step) {
        1 -> "Was ist im Paket?"; 2 -> "Für wen ist das Paket?"; 3 -> "Wo liegt das Paket?"; else -> "Alles richtig?"
    }) }, text = {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Schritt $step von 4", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            when (step) {
                1 -> { Text("Gib dem Paket einen Namen, den du gut wiedererkennst."); EditField("Was verschickst du?", title, { title = it }, placeholder = "Zum Beispiel: Blaue Jeans") }
                2 -> {
                    Text(if (known != null) "Dieser Empfänger steht im QR-Code. Der Absender wird nicht als Empfänger verwendet." else "Bitte prüfe die Zieladresse. Wenn sie nicht sicher lesbar ist, trage sie selbst ein oder ergänze sie später.")
                    if (codes.needsChoice) Text("Die Codes passen nicht eindeutig zu einem Empfänger. Bitte wähle selbst die richtige Zieladresse.", color = MaterialTheme.colorScheme.error)
                    codes.recipients.forEach { recipient ->
                        OutlinedCard(onClick = { name = recipient.name; address = recipient.address }) {
                            Column(Modifier.padding(12.dp)) { Text(recipient.name, fontWeight = FontWeight.Bold); Text(recipient.address); Text("Diesen QR-Empfänger übernehmen", color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                    EditField("Empfänger / Benutzername", name, { name = it })
                    EditField("Zieladresse (falls bekannt)", address, { address = it }, multiline = true)
                }
                3 -> { Text("So findest du es später zwischen den anderen Kartons."); EditField("Lagerplatz", location, { location = it }, placeholder = "Zum Beispiel: Flur, linkes Regal") }
                else -> {
                    Text(parcel.title.ifBlank { "Paketname fehlt noch" }, fontWeight = FontWeight.Bold)
                    Text("Für: ${parcel.recipient.ifBlank { "noch offen" }}")
                    if (parcel.address.isNotBlank()) Text(parcel.address)
                    Text("Liegt bei: ${parcel.location.ifBlank { "noch offen" }}")
                    Text("Abgeben bis: ${parcel.dueDate.ifBlank { "keine bestätigte Frist" }}")
                    if (parcel.aiSummary.isNotBlank()) Text(parcel.aiSummary.take(900), style = MaterialTheme.typography.bodySmall)
                    Text(if (pending) "Du entscheidest: Ist es fertig gepackt oder möchtest du es erst einmal anlegen?" else "Der Versandstatus bleibt: ${parcel.status.label}.")
                    if (pending && !complete) Text("Für „Bereit“ fehlen noch Paketname, Empfänger oder Lagerplatz. Du kannst es erst einmal in Vorbereitung speichern.")
                    Text("Danach unten auf „Paket speichern“ tippen.", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }, confirmButton = {
        if (step < 4) Button(onClick = {
            onChange(when (step) { 1 -> parcel.copy(title = title.trim()); 2 -> parcel.copy(recipient = name.trim(), address = address.trim()); else -> parcel.copy(location = location.trim()) })
            onStep(step + 1)
        }) { Text("Weiter") }
        else if (!pending) Button(onClick = { onChange(parcel.copy(aiReviewRequired = false)); onStep(0) }) { Text("Angaben übernehmen") }
        else Button(onClick = { onChange(parcel.copy(status = ParcelStatus.READY, aiReviewRequired = false)); onStep(0) }, enabled = complete) { Text("Bereit zum Abgeben") }
    }, dismissButton = {
        if (step == 4 && pending) TextButton(onClick = { onChange(parcel.copy(status = ParcelStatus.DRAFT, aiReviewRequired = false)); onStep(0) }) { Text("Erst einmal anlegen") }
        else TextButton(onClick = { onStep(0) }) { Text("Im Formular weiter") }
    })
}
