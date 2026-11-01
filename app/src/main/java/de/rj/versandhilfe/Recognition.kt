package de.rj.versandhilfe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.URLDecoder

/** Detection is deliberately conservative: carrier logos alone never turn arbitrary QR data into a tracking ID. */
data class ScanResult(val codes: List<String>, val text: String, val candidates: List<TrackingCandidate>, val suggestedCarrier: Carrier)
data class TrackingCandidate(val number: String, val carrier: Carrier, val reason: String)
object TrackingDetector {
    fun carrier(text: String): Carrier {
        val t = text.lowercase()
        return when {
            Regex("\\bups\\b|ups\\.com").containsMatchIn(t) -> Carrier.UPS
            "myhermes" in t || Regex("\\bhermes\\b").containsMatchIn(t) -> Carrier.HERMES
            Regex("\\bdpd\\b|dpd\\.").containsMatchIn(t) -> Carrier.DPD
            Regex("\\bdhl\\b|dhl\\.").containsMatchIn(t) -> Carrier.DHL
            Regex("\\bgls\\b|gls-group").containsMatchIn(t) -> Carrier.GLS
            "fedex" in t -> Carrier.FEDEX
            "deutsche post" in t -> Carrier.POST
            else -> Carrier.UNKNOWN
        }
    }
    fun candidates(codes: List<String>, text: String): List<TrackingCandidate> {
        val result = mutableListOf<TrackingCandidate>()
        // UPS has an unambiguous alphanumeric prefix; generic numeric IDs need carrier context.
        val ups = Regex("(?<![A-Z0-9])1Z[A-Z0-9]{16}(?![A-Z0-9])", RegexOption.IGNORE_CASE)
        (codes + text).forEach { raw ->
            ups.findAll(raw).forEach { result += TrackingCandidate(it.value.uppercase(), Carrier.UPS, "UPS-Format – bitte prüfen") }
            if (raw.startsWith("https://", true)) {
                runCatching {
                    val uri = URI(raw)
                    val host = uri.host?.lowercase() ?: return@runCatching
                    val trusted = listOf("dhl.de", "dhl.com", "myhermes.de", "dpd.de", "dpd.com", "ups.com", "gls-group.com", "fedex.com", "deutschepost.de").any { host == it || host.endsWith(".$it") }
                    if (!trusted) return@runCatching
                    val c = carrier(host)
                    uri.rawQuery.orEmpty().split('&').forEach { query ->
                        val pair = query.split('=', limit = 2)
                        if (pair.size == 2 && pair[0].lowercase() in setOf("piececode", "trackingnumber", "tracking_number", "tracknum", "trknbr", "match", "parcel", "shipmentid", "form.sendungsnummer")) {
                            val n = URLDecoder.decode(pair[1], "UTF-8").replace(" ", "")
                            if (n.matches(Regex("[A-Za-z0-9]{8,35}"))) result += TrackingCandidate(n, c, "Aus einem Tracking-Link – bitte prüfen")
                        }
                    }
                }
            }
        }
        val hinted = carrier(text + " " + codes.joinToString(" "))
        if (hinted != Carrier.UNKNOWN) {
            val labeled = Regex("(?:Sendungsnummer|Sendungs[- ]?Nr\\.?|Tracking(?:nummer| number)?|Paketnummer|Shipment(?: number)?)[ :#\\n]*([A-Z0-9][A-Z0-9 -]{7,38})", RegexOption.IGNORE_CASE)
            labeled.findAll(text).forEach { m ->
                val n = m.groupValues[1].trim().replace(" ", "").replace("-", "")
                if (n.matches(Regex("[A-Z0-9]{8,35}", RegexOption.IGNORE_CASE)) && n.any(Char::isDigit)) result += TrackingCandidate(n, hinted, "Beschriftete Sendungsnummer – bitte prüfen")
            }
            codes.filter { it.matches(Regex("[0-9]{10,22}")) }.forEach {
                result += TrackingCandidate(it, hinted, "Barcode mit Anbieterhinweis – kann auch ein Versandcode sein")
            }
        }
        return result.distinctBy { it.number }.take(12)
    }
}

suspend fun renderPage(file: File, index: Int = 0): Pair<Bitmap, Int> = withContext(Dispatchers.IO) {
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            val count = renderer.pageCount
            require(count > 0) { "Die PDF enthält keine Seiten." }
            renderer.openPage(index.coerceIn(0, count - 1)).use { page ->
                val factor = (1800f / maxOf(page.width, page.height)).coerceAtMost(3f)
                val bitmap = Bitmap.createBitmap((page.width * factor).toInt().coerceAtLeast(1), (page.height * factor).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap to count
            }
        }
    }
}
suspend fun recognize(repository: ParcelRepository, attachment: Attachment): ScanResult = withContext(Dispatchers.IO) {
    val file = File(repository.assets, attachment.file)
    val scanner = BarcodeScanning.getClient()
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    var bitmap: Bitmap? = null
    try {
        val image = if (attachment.mime == "application/pdf") {
            bitmap = renderPage(file).first
            InputImage.fromBitmap(bitmap!!, 0)
        } else InputImage.fromFilePath(repository.context, android.net.Uri.fromFile(file))
        val codes = scanner.process(image).await().mapNotNull { it.rawValue }.distinct()
        val text = recognizer.process(image).await().text
        val allCodes = (attachment.rawCodes + codes).distinct().filter { it.length <= 12000 }.take(20)
        val carrier = if (ShippingCodeParser.analyze(allCodes).parsed.isNotEmpty()) Carrier.DHL else TrackingDetector.carrier(text + allCodes.joinToString(" "))
        ScanResult(allCodes, text, TrackingDetector.candidates(allCodes, text), carrier)
    } finally { scanner.close(); recognizer.close(); bitmap?.recycle() }
}
