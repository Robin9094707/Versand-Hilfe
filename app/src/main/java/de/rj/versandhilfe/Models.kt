package de.rj.versandhilfe

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.UUID

val AppJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
@Serializable enum class ParcelStatus(val label: String) {
    DRAFT("In Vorbereitung"), READY("Bereit zur Abgabe"), SHIPPED("Unterwegs"), DELIVERED("Angekommen")
}
@Serializable enum class AttachmentKind(val label: String) {
    PHOTO("Paketfoto"), LABEL("Versandlabel / QR"), ADDRESS("Adresse"), RECEIPT("Einlieferungsbeleg")
}
@Serializable enum class Carrier(val label: String, val apiCode: String) {
    UNKNOWN("Noch offen", ""), DHL("DHL", "dhl-germany"), HERMES("Hermes", "hermes"),
    DPD("DPD", "dpd"), UPS("UPS", "ups"), GLS("GLS", "gls"), FEDEX("FedEx", "fedex"),
    POST("Deutsche Post", "deutsche-post"), OTHER("Andere", "");
    fun trackingUrl(number: String): String? {
        if (number.isBlank()) return null
        val n = URLEncoder.encode(number, StandardCharsets.UTF_8.name())
        return when (this) {
            DHL -> "https://www.dhl.de/de/privatkunden/dhl-sendungsverfolgung.html?piececode=$n"
            HERMES -> "https://www.myhermes.de/empfangen/sendungsverfolgung/sendungsinformation/#$n"
            DPD -> "https://tracking.dpd.de/status/de_DE/parcel/$n"
            UPS -> "https://www.ups.com/track?loc=de_DE&tracknum=$n"
            GLS -> "https://gls-group.com/DE/de/paketverfolgung/?match=$n"
            FEDEX -> "https://www.fedex.com/fedextrack/?trknbr=$n"
            POST -> "https://www.deutschepost.de/de/s/sendungsverfolgung.html?form.sendungsnummer=$n"
            else -> null
        }
    }
}
@Serializable data class Attachment(
    val id: String = UUID.randomUUID().toString(), val name: String, val mime: String,
    val file: String, val sha256: String, val kind: AttachmentKind,
    val detectedKinds: List<AttachmentKind> = emptyList(), val aiAnalyzed: Boolean = false
) { fun hasKind(value: AttachmentKind) = kind == value || value in detectedKinds }
@Serializable data class ParcelEvent(
    val at: Long = System.currentTimeMillis(), val text: String, val source: String = "Manuell"
)
@Serializable data class Parcel(
    val id: String = UUID.randomUUID().toString(), val number: Int = 1,
    val title: String = "", val recipient: String = "", val address: String = "",
    val platform: String = "Vinted", val notes: String = "", val location: String = "",
    val carrier: Carrier = Carrier.UNKNOWN, val trackingNumber: String = "",
    val customCourierCode: String = "", val autoTrack: Boolean = false,
    val status: ParcelStatus = ParcelStatus.DRAFT, val dueDate: String = "",
    val attachments: List<Attachment> = emptyList(), val events: List<ParcelEvent> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(), val createdAt: Long = System.currentTimeMillis(),
    val trackingSummary: String = "", val trackingCheckedAt: Long = 0, val trackingError: String = "",
    val orderNumber: String = "", val aiSummary: String = "", val aiEvidence: Map<String, String> = emptyMap(), val aiReviewRequired: Boolean = false
) {
    val code get() = "P-${number.toString().padStart(3, '0')}"
    val apiCourier get() = customCourierCode.trim().ifBlank { carrier.apiCode }
    fun isDue(): Boolean = !aiReviewRequired && dueDate.isNotBlank() && status in listOf(ParcelStatus.DRAFT, ParcelStatus.READY) &&
        runCatching { !LocalDate.parse(dueDate).isAfter(LocalDate.now().plusDays(1)) }.getOrDefault(false)
}
@Serializable data class Archive(
    val format: String = "de.rj.versandhilfe", val version: Int = 1,
    val savedAt: Long = System.currentTimeMillis(), val parcels: List<Parcel> = emptyList(),
    val assistant: AssistantState = AssistantState()
)
fun validateArchive(archive: Archive) {
    require(archive.format == "de.rj.versandhilfe" && archive.version == 1) { "Dieses Backup-Format wird nicht unterstützt." }
    require(archive.assistant.dailyLimit in 1..50 && archive.assistant.messages.size <= 80 && archive.assistant.name.length <= 80 && archive.assistant.encryptedKeys.length <= 30000) { "Ungültige Assistenten-Einstellungen." }
    require(archive.parcels.size <= 10000) { "Das Backup enthält zu viele Pakete." }
    require(archive.parcels.map { it.id }.distinct().size == archive.parcels.size) { "Doppelte Paket-IDs im Backup." }
    archive.parcels.forEach { p ->
        require(p.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Ungültige Paket-ID." }
        p.attachments.forEach { a ->
            require(a.file.matches(Regex("[a-f0-9]{64}\\.(jpg|png|webp|pdf)"))) { "Ungültiger Dateipfad im Backup." }
            require(a.sha256.matches(Regex("[a-f0-9]{64}")) && a.file.startsWith(a.sha256)) { "Ungültige Dateiprüfsumme." }
        }
    }
}

/** Merge keeps unrelated local records; IDs are identity, display numbers are reallocated on collision. */
fun mergeParcels(local: List<Parcel>, incoming: List<Parcel>): List<Parcel> {
    val merged = local.associateBy { it.id }.toMutableMap()
    incoming.forEach { p -> if (p.updatedAt > (merged[p.id]?.updatedAt ?: -1)) merged[p.id] = p }
    val used = mutableSetOf<Int>()
    var next = (merged.values.maxOfOrNull { it.number } ?: 0) + 1
    return merged.values.sortedBy { it.createdAt }.map { p ->
        val value = if (p.number > 0 && used.add(p.number)) p else p.copy(number = next++).also { used.add(it.number) }
        value
    }.sortedByDescending { it.createdAt }
}
