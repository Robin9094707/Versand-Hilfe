package de.rj.versandhilfe

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable fun IntakeGuide(parcel: Parcel, step: Int, onChange: (Parcel) -> Unit, onStep: (Int) -> Unit, onComplete: ((Parcel) -> Unit)? = null) {
    var title by rememberSaveable(step) { mutableStateOf(parcel.title) }
    var name by rememberSaveable(step) { mutableStateOf(parcel.recipient) }
    var address by rememberSaveable(step) { mutableStateOf(parcel.address) }
    var location by rememberSaveable(step) { mutableStateOf(parcel.location) }
    var notes by rememberSaveable(step) { mutableStateOf(parcel.notes) }
    var due by rememberSaveable(step) { mutableStateOf(parcel.dueDate) }
    val allCodes = parcel.attachments.flatMap { it.rawCodes }
    val codes = remember(allCodes) { ShippingCodeParser.analyze(allCodes) }
    val pending = ParcelFacts.pending(parcel)
    val ready = title.isNotBlank() && name.isNotBlank() && location.isNotBlank()
    val validDate = due.isBlank() || runCatching { java.time.LocalDate.parse(due) }.isSuccess
    fun finish(status: ParcelStatus) {
        val result = parcel.copy(title = title.trim(), recipient = name.trim(), address = address.trim(), location = location.trim(), notes = notes.trim(), dueDate = due.trim(), status = status, aiReviewRequired = false)
        onChange(result); onStep(0); onComplete?.invoke(result)
    }
    FlowWindow(when (step) { 1 -> "Was ist im Paket?"; 2 -> "Für wen ist das Paket?"; 3 -> "Wo liegt das Paket?"; else -> "Alles richtig?" }, "${parcel.code} · Schritt $step von 4", .3f + step * .175f, bottom = {
        if (step < 4) {
            Button(onClick = {
                onChange(when (step) { 1 -> parcel.copy(title = title.trim()); 2 -> parcel.copy(recipient = name.trim(), address = address.trim()); else -> parcel.copy(location = location.trim()) })
                onStep(step + 1)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("Weiter") }
        } else {
            Button(onClick = { finish(if (pending) parcel.status else parcel.status) }, enabled = title.isNotBlank() && validDate && (parcel.status != ParcelStatus.READY || ready), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(when { !pending -> "Angaben übernehmen"; parcel.status == ParcelStatus.READY -> "Bereit zum Abgeben · speichern"; else -> "In Vorbereitung speichern" })
            }
            if (pending && parcel.status == ParcelStatus.READY) TextButton(onClick = { finish(ParcelStatus.DRAFT) }, enabled = title.isNotBlank() && validDate) { Text("Erst einmal anlegen") }
            if (pending && parcel.status == ParcelStatus.DRAFT) TextButton(onClick = { finish(ParcelStatus.READY) }, enabled = ready && validDate) { Text("Bereit zum Abgeben") }
        }
        TextButton(onClick = { onStep(0) }) { Text("Im Formular weiter") }
    }) {
        when (step) {
            1 -> { Text("Gib dem Paket einen Namen, den du gut wiedererkennst."); EditField("Was verschickst du?", title, { title = it }, placeholder = "Zum Beispiel: Blaue Jeans") }
            2 -> {
                Text(if (codes.recipient != null) "Dieser Empfänger steht im QR-Code. Der Absender wird nicht als Empfänger verwendet." else "Bitte prüfe die Zieladresse. Unklare Angaben kannst du selbst ergänzen.")
                if (codes.needsChoice) Text("Die Codes gehören nicht eindeutig zu einem Empfänger. Bitte wähle die richtige Zieladresse.", color = MaterialTheme.colorScheme.error)
                codes.recipients.forEach { recipient ->
                    OutlinedCard(onClick = { name = recipient.name; address = recipient.address }) { Column(Modifier.padding(16.dp)) { Text(recipient.name, fontWeight = FontWeight.Bold); Text(recipient.address); Text("Diesen Empfänger übernehmen", color = MaterialTheme.colorScheme.primary) } }
                }
                EditField("Empfänger / Benutzername", name, { name = it }); EditField("Zieladresse (falls bekannt)", address, { address = it }, multiline = true)
            }
            3 -> { Text("So findest du es später zwischen den anderen Kartons."); EditField("Lagerplatz", location, { location = it }, placeholder = "Zum Beispiel: Flur, linkes Regal"); InfoCard(androidx.compose.material.icons.Icons.Outlined.Inventory2, "Schreib ${parcel.code} auf den Karton", "Die Nummer gehört in der App und auf dem Paket zusammen. Das Beschriften ist freiwillig.") }
            else -> {
                Text("Prüfe die Angaben. Hier kannst du alles noch ergänzen.")
                EditField("Paketname", title, { title = it }); EditField("Empfänger", name, { name = it }); EditField("Zieladresse", address, { address = it }, multiline = true); EditField("Lagerplatz", location, { location = it })
                EditField("Abgeben bis (JJJJ-MM-TT, optional)", due, { due = it })
                if (!validDate) Text("Bitte ein Datum wie 2026-11-27 eingeben oder leer lassen.", color = MaterialTheme.colorScheme.error)
                EditField("Notizen", notes, { notes = it }, multiline = true)
                if (parcel.aiSummary.isNotBlank()) Text(parcel.aiSummary, style = MaterialTheme.typography.bodySmall)
                if (pending && parcel.status == ParcelStatus.READY && !ready) Text("Für „Bereit“ fehlen Paketname, Empfänger oder Lagerplatz. Du kannst es erst einmal in Vorbereitung speichern.")
                if (!pending) Text("Der Versandstatus bleibt: ${parcel.status.label}.")
            }
        }
    }
}
