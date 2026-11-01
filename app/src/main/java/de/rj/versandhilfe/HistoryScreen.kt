package de.rj.versandhilfe

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable fun HistoryScreen(parcels: List<Parcel>, onOpen: (String) -> Unit, onPrint: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selectedText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var mode by rememberSaveable { mutableStateOf("Kalender") }
    val month = YearMonth.parse(monthText)
    val selected = LocalDate.parse(selectedText)
    val zone = ZoneId.systemDefault()
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val entries = remember(parcels, zone) { parcels.flatMap { p ->
        (listOf(ParcelEvent(at = p.createdAt, text = "Paket angelegt")) + p.events).distinctBy { it.at to it.text }.map { p to it }
    }.sortedByDescending { it.second.at } }
    val dates = remember(entries, parcels) { entries.map { day(it.second.at) }.toSet() + parcels.filter { !it.aiReviewRequired && ParcelFacts.pending(it) }.mapNotNull { ParcelFacts.date(it) } }
    val dueHere = remember(parcels, selected) { parcels.filter { !it.aiReviewRequired && ParcelFacts.pending(it) && ParcelFacts.date(it) == selected } }
    val relevant = remember(entries, selected, mode) { if (mode == "Alle Abgaben") entries.filter { it.second.text in listOf(ParcelStatus.SHIPPED.label, ParcelStatus.DELIVERED.label) } else entries.filter { day(it.second.at) == selected } }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { ScreenHeading("Verlauf & Kalender", "Wann angelegt, abgegeben oder angekommen? Alles bleibt beim Paket.") }
        item { Button(onClick = onPrint, modifier = Modifier.fillMaxWidth()) { Text("QR-Karten als A4-PDF drucken") } }
        item { ChoiceField("Ansicht", mode, listOf("Kalender", "Alle Abgaben")) { mode = it } }
        if (mode == "Kalender") item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { monthText = month.minusMonths(1).toString() }) { Text("‹") }
                    Text(month.atDay(1).format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)), style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = { monthText = month.plusMonths(1).toString() }) { Text("›") }
                }
                Row { listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach { Text(it, Modifier.weight(1f)) } }
                val offset = month.atDay(1).dayOfWeek.value - 1
                repeat((offset + month.lengthOfMonth() + 6) / 7) { row ->
                    Row {
                        repeat(7) { col ->
                            val number = row * 7 + col - offset + 1
                            if (number !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f))
                            else {
                                val date = month.atDay(number)
                                Surface(onClick = { selectedText = date.toString() }, modifier = Modifier.weight(1f).heightIn(min = 46.dp), shape = MaterialTheme.shapes.small,
                                    color = if (date == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                                    Column(Modifier.padding(6.dp)) { Text(number.toString()); if (date in dates) Text("•", color = MaterialTheme.colorScheme.primary) }
                                }
                            }
                        }
                    }
                }
            }
        }
        item { Text(if (mode == "Kalender") selected.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) else "Alle abgegebenen und angekommenen Pakete", style = MaterialTheme.typography.titleLarge) }
        if (mode == "Kalender") items(dueHere, key = { "due-${it.id}" }) { p ->
            FilledTonalButton(onClick = { onOpen(p.id) }, modifier = Modifier.fillMaxWidth()) { Text("Abgabe fällig · ${p.code} · ${p.title}") }
        }
        if (relevant.isEmpty() && (mode != "Kalender" || dueHere.isEmpty())) item { Text("Für diese Auswahl gibt es noch keine Einträge.") }
        items(relevant.size) { index -> val (p, event) = relevant[index]
            OutlinedCard(onClick = { onOpen(p.id) }) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text("${p.code} · ${p.title}", style = MaterialTheme.typography.titleMedium); Text(event.text); Text(readableTime(event.at), style = MaterialTheme.typography.bodySmall) } }
        }
        item { Text("Zeitpunkte werden von der App festgehalten und sind nicht einzeln bearbeitbar oder löschbar. Sie verschwinden nur, wenn du das gesamte Paket entfernst.", style = MaterialTheme.typography.bodySmall) }
    }
}
