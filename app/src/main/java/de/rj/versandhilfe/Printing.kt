package de.rj.versandhilfe

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfDocument
import android.os.*
import android.print.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.zxing.*
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Only locally verified QR symbols can be regenerated, never arbitrary barcode raw values. */
data class QrPrintCard(val parcel: Parcel, val code: String)
object ParcelPdf {
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float = 11f, bold: Boolean = false) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
        canvas.drawText(value.take(100), x, y, paint)
    }
    fun labels(file: File, cards: List<QrPrintCard>, perPage: Int = 4) {
        require(cards.isNotEmpty() && perPage in listOf(1, 4))
        PdfDocument().use { document ->
            cards.chunked(perPage).forEachIndexed { pageIndex, group ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                val canvas = page.canvas
                group.forEachIndexed { index, item ->
                    val width = if (perPage == 1) 547f else 273.5f
                    val height = if (perPage == 1) 794f else 397f
                    val left = 24f + (if (perPage == 1) 0 else index % 2) * width
                    val top = 24f + (if (perPage == 1) 0 else index / 2) * height
                    canvas.save(); canvas.clipRect(left, top, left + width - 8, top + height - 8)
                    text(canvas, item.parcel.code, left + 12, top + 28, 22f, true)
                    text(canvas, item.parcel.title.take(35), left + 12, top + 48, 11f, true)
                    text(canvas, "Für: ${item.parcel.recipient}".take(38), left + 12, top + 65)
                    text(canvas, "${item.parcel.carrier.label} · Abgabe-QR", left + 12, top + 81, 10f)
                    val side = minOf(width - 32, height - 150, 340f)
                    val matrix = MultiFormatWriter().encode(item.code, BarcodeFormat.QR_CODE, 1000, 1000, mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
                    val bitmap = Bitmap.createBitmap(matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
                    val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE }
                    bitmap.setPixels(pixels, 0, matrix.width, 0, 0, matrix.width, matrix.height)
                    canvas.drawBitmap(bitmap, null, RectF(left + 12, top + 94, left + 12 + side, top + 94 + side), Paint().apply { isFilterBitmap = false })
                    bitmap.recycle()
                    text(canvas, "Zur Zuordnung / im Paketshop vorzeigen", left + 12, top + side + 112, 9f)
                    text(canvas, "Kein frankiertes Versandlabel.", left + 12, top + side + 125, 9f)
                    canvas.restore()
                }
                document.finishPage(page)
            }
            file.parentFile?.mkdirs(); file.outputStream().use { document.writeTo(it) }
        }
    }
    fun history(file: File, p: Parcel) {
        PdfDocument().use { document ->
            val lines = listOf("${p.code} · ${p.title}", "Eigene Versandübersicht", "Kein Einlieferungsbeleg des Versanddienstleisters.", "Für: ${p.recipient}") + p.address.lines() + listOf("Anbieter: ${p.carrier.label}", "Sendungsnummer: ${p.trackingNumber.ifBlank { "nicht hinterlegt" }}", "Erfasst: ${readableTime(p.createdAt)}", "Status: ${p.status.label}", "Verlauf:") + p.events.sortedBy { it.at }.map { "${readableTime(it.at)} · ${it.text}" }
            lines.chunked(44).forEachIndexed { index, section ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                section.forEachIndexed { row, line -> text(page.canvas, line, 32f, 45f + row * 17, if (row == 0 && index == 0) 16f else 10f, row == 0) }
                document.finishPage(page)
            }
            file.parentFile?.mkdirs(); file.outputStream().use { document.writeTo(it) }
        }
    }
}
fun printPdf(context: Context, file: File, name: String) {
    context.getSystemService(PrintManager::class.java).print(name, object : PrintDocumentAdapter() {
        override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancellationSignal.isCanceled) callback.onLayoutCancelled() else callback.onLayoutFinished(PrintDocumentInfo.Builder(file.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }
        override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal, callback: WriteResultCallback) {
            if (cancellationSignal.isCanceled) { callback.onWriteCancelled(); return }
            try { ParcelFileDescriptor.AutoCloseOutputStream(destination).use { out -> file.inputStream().use { it.copyTo(out) } }; callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES)) }
            catch (_: Exception) { callback.onWriteFailed("PDF konnte nicht gedruckt werden") }
        }
    }, PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME).build())
}
@Composable fun PrintScreen(repo: ParcelRepository, parcels: List<Parcel>, onBack: () -> Unit, onMessage: (String) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    var perPage by rememberSaveable { mutableIntStateOf(4) }
    var busy by remember { mutableStateOf(false) }
    var generated by remember { mutableStateOf<File?>(null) }
    var choices by remember { mutableStateOf<List<Pair<Parcel, List<String>>>>(emptyList()) }
    var chosenCodes by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var saveOnly by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch { runCatching { withContext(Dispatchers.IO) { repo.context.contentResolver.openOutputStream(uri, "wt")!!.use { out -> generated!!.inputStream().use { it.copyTo(out) } } } }.onSuccess { onMessage("QR-Karten als PDF gespeichert") }.onFailure { onMessage("PDF konnte nicht gespeichert werden") } }
    }
    fun generate() {
        busy = true
        scope.launch {
            try {
                val file = File(repo.context.cacheDir, "shared/QR-Karten-${System.currentTimeMillis()}.pdf")
                val cards = choices.map { (p, codes) -> QrPrintCard(p, chosenCodes[p.id] ?: codes.single()) }
                withContext(Dispatchers.Default) { ParcelPdf.labels(file, cards, perPage) }
                generated = file; choices = emptyList()
                if (saveOnly) save.launch("Versandhilfe-QR-Karten.pdf") else printPdf(repo.context, file, "Versandhilfe QR-Karten")
            } catch (e: Exception) { onMessage(e.message ?: "QR-Karten konnten nicht erstellt werden") } finally { busy = false }
        }
    }
    fun prepare(asFile: Boolean) {
        if (selected.isEmpty() || busy) return
        busy = true; saveOnly = asFile
        scope.launch {
            var generating = false
            try {
                choices = withContext(Dispatchers.IO) { selected.map { id ->
                    val p = parcels.first { it.id == id }
                    val found = p.attachments.flatMap { a -> a.qrCodes.ifEmpty { runCatching { recognize(repo, a).qrCodes }.getOrDefault(emptyList()) } }.distinct()
                    require(found.isNotEmpty()) { "${p.code}: Kein lesbarer QR-Code gefunden. Bitte das Original klarer fotografieren oder hochladen." }
                    p to found
                } }
                chosenCodes = choices.filter { it.second.size == 1 }.associate { it.first.id to it.second.single() }
                if (choices.all { it.second.size == 1 }) { generating = true; generate() }
            } catch (e: Exception) { choices = emptyList(); onMessage(e.message.orEmpty()) } finally { if (!generating) busy = false }
        }
    }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { TextButton(onClick = onBack) { Text("Zurück") }; ScreenHeading("QR-Karten drucken", "Saubere QR-Codes mit der gleichen Paketnummer wie in der App.") }
        item { Text("Für die Zuordnung oder zum Vorzeigen im Paketshop. Der Versanddienstleister erstellt daraus das eigentliche Versandlabel. Diese Karten ersetzen es nicht.") }
        item { ChoiceField("Aufteilung auf A4", if (perPage == 1) "1 QR-Karte pro Seite" else "Bis zu 4 pro Seite", listOf("1 QR-Karte pro Seite", "Bis zu 4 pro Seite")) { perPage = if (it.startsWith("1")) 1 else 4 } }
        items(parcels, key = { it.id }) { p -> OutlinedCard(onClick = { selected = if (p.id in selected) selected - p.id else selected + p.id }) { Row(Modifier.fillMaxWidth().padding(12.dp)) { Checkbox(p.id in selected, { selected = if (p.id in selected) selected - p.id else selected + p.id }); Column { Text("${p.code} · ${p.title}"); Text(p.recipient) } } } }
        item { Button(onClick = { prepare(false) }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("${selected.size} QR-Karten drucken") }; OutlinedButton(onClick = { prepare(true) }, enabled = selected.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth()) { Text("Als PDF speichern") } }
    }
    if (busy) RecognitionProgress(false)
    if (choices.any { it.second.size > 1 } && !busy) FlowWindow("Welcher QR-Code gehört dazu?", "Bitte den passenden Code auswählen", 1f, bottom = { Button(onClick = { generate() }, enabled = choices.all { it.first.id in chosenCodes }) { Text("Auswahl übernehmen") } }) {
        choices.filter { it.second.size > 1 }.forEach { (p, codes) ->
            Text("${p.code} · ${p.title}")
            codes.forEach { code -> OutlinedCard(onClick = { chosenCodes = chosenCodes + (p.id to code) }) { Column(Modifier.padding(12.dp)) { Text(if (chosenCodes[p.id] == code) "✓ Ausgewählt" else "Diesen Code wählen"); Text(ShippingCodeParser.parse(code)?.recipient?.let { "${it.name}\n${it.address}" } ?: code.take(300)) } } }
        }
    }
}
