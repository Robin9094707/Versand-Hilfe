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
    val detectedKinds: List<AttachmentKind> = emptyList(), val aiAnalyzed: Boolean = false,
    val rawCodes: List<String> = emptyList(), val qrCodes: List<String> = emptyList()
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
    val numberDigits: Int = 3, val snoozedUntil: Long = 0, val lastReminderAt: Long = 0,
    val orderNumber: String = "", val aiSummary: String = "", val aiEvidence: Map<String, String> = emptyMap(), val aiReviewRequired: Boolean = false
) {
    val code get() = "P-${number.toString().padStart(numberDigits, '0')}"
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
    require((archive.assistant.dailyLimit == 0 || archive.assistant.dailyLimit in 1..100000) && archive.assistant.numberDigits in 2..3 && archive.assistant.nextPackageNumber in 1..999 && archive.assistant.reminderEveryHours in 6..168 && archive.assistant.messages.size <= 80 && archive.assistant.name.length <= 80 && archive.assistant.encryptedKeys.length <= 30000) { "Ungültige Assistenten-Einstellungen." }
    require(archive.parcels.size <= 10000) { "Das Backup enthält zu viele Pakete." }
    require(archive.parcels.map { it.id }.distinct().size == archive.parcels.size) { "Doppelte Paket-IDs im Backup." }
    archive.parcels.forEach { p ->
        require(p.number > 0 && p.numberDigits in 2..3) { "Ungültige Paketnummer im Backup." }
        require(p.id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) { "Ungültige Paket-ID." }
        p.attachments.forEach { a ->
            require(a.qrCodes.size <= 20 && a.qrCodes.all { it in a.rawCodes }) { "Ungültige QR-Auswahl." }
            require(a.rawCodes.size <= 20 && a.rawCodes.all { it.length <= 12000 }) { "Zu viele oder zu lange QR-Inhalte im Backup." }
            require(a.file.matches(Regex("[a-f0-9]{64}\\.(jpg|png|webp|pdf)"))) { "Ungültiger Dateipfad im Backup." }
            require(a.sha256.matches(Regex("[a-f0-9]{64}")) && a.file.startsWith(a.sha256)) { "Ungültige Dateiprüfsumme." }
        }
    }
}

/** Merge keeps unrelated local records; IDs are identity, display numbers are reallocated on collision. */
fun mergeParcels(local: List<Parcel>, incoming: List<Parcel>): List<Parcel> {
    val merged = local.associateBy { it.id }.toMutableMap()
    incoming.forEach { p ->
        val existing = merged[p.id]
        if (existing == null) merged[p.id] = p else {
            val latest = if (p.updatedAt > existing.updatedAt) p else existing
            merged[p.id] = latest.copy(number = existing.number, numberDigits = existing.numberDigits, createdAt = minOf(existing.createdAt, p.createdAt), events = (existing.events + p.events).distinctBy { Triple(it.at, it.text, it.source) }.sortedBy { it.at })
        }
    }
    val localIds = local.map { it.id }.toSet()
    val used = mutableSetOf<Int>()
    return merged.values.sortedWith(compareBy<Parcel> { if (it.id in localIds) 0 else 1 }.thenBy { it.createdAt }).map { p ->
        // Completed history can legitimately share numbers after wrapping; it must not be renumbered.
        if (p.status == ParcelStatus.DELIVERED || (p.number > 0 && used.add(p.number))) p
        else {
            val digits = if (used.count { it in 1..99 } >= 99) 3 else p.numberDigits
            val next = (1..numberRange(digits)).firstOrNull { it !in used }
                ?: error("Zu viele gleichzeitig offene Pakete zum Zusammenführen.")
            used.add(next)
            p.copy(number = next, numberDigits = digits, events = p.events + ParcelEvent(text = "Beim Import neue Paketnummer zugeteilt: ${p.code} → P-${next.toString().padStart(digits, '0')}"))
        }
    }.sortedByDescending { it.createdAt }
}
