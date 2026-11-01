package de.rj.versandhilfe

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

const val AI_MODEL = "gpt-5.6-luna"
@Serializable data class AssistantState(
    val name: String = "", val enabled: Boolean = false, val dailyLimit: Int = 20,
    val reminders: Boolean = true, val reminderHour: Int = 9,
    val encryptedKeys: String = "", val updatedAt: Long = 0,
    val usageDate: String = "", val calls: Int = 0, val inputTokens: Long = 0, val outputTokens: Long = 0,
    val messages: List<ParcelChatMessage> = emptyList()
)
@Serializable data class ParcelChatMessage(
    val id: String = UUID.randomUUID().toString(), val role: String, val text: String,
    val parcelIds: List<String> = emptyList(), val at: Long = System.currentTimeMillis()
)
@Serializable data class ExtractedField(val value: String, val evidence: String)
@Serializable enum class AddressRole { EXPLICIT_RECIPIENT, UNCERTAIN }
@Serializable data class AiExtraction(
    val kinds: List<AttachmentKind> = emptyList(), val title: ExtractedField? = null,
    val recipient: ExtractedField? = null, val address: ExtractedField? = null,
    val platform: ExtractedField? = null, val carrier: ExtractedField? = null,
    val tracking: ExtractedField? = null, val orderNumber: ExtractedField? = null,
    val dueDate: ExtractedField? = null, val summary: String = "", val warning: String = "",
    val recipientRole: AddressRole = AddressRole.UNCERTAIN, val senderName: ExtractedField? = null,
    val senderAddress: ExtractedField? = null, val localCodes: List<String> = emptyList(),
    val localText: String = "", val sourceScanned: Boolean = false
)
@Serializable enum class ParcelIntent { SUMMARY, DUE, OVERDUE, READY, IN_TRANSIT, DELIVERED, FIND, HELP, REFUSE }
@Serializable data class ParcelQuery(val intent: ParcelIntent, val search: String = "", val days: Int = 3, val helpTopic: String = "")
data class ParcelAnswer(val text: String, val parcels: List<Parcel> = emptyList())
fun greeting(name: String, hour: Int = LocalTime.now().hour): String {
    val salutation = when (hour) { in 5..10 -> "Guten Morgen"; in 11..17 -> "Guten Tag"; else -> "Guten Abend" }
    return if (name.isBlank()) salutation else "$salutation, ${name.trim()}"
}
object ParcelFacts {
    const val refusal = "Ich bin nur für deine Pakete, Versandunterlagen und Abgabefristen da. Allgemeine oder private Fragen beantworte ich hier nicht."
    fun pending(p: Parcel) = p.status in listOf(ParcelStatus.DRAFT, ParcelStatus.READY)
    fun brief(items: List<Parcel>, today: LocalDate = LocalDate.now()): String {
        val active = items.count { it.status != ParcelStatus.DELIVERED }
        val due = items.count { pending(it) && !it.aiReviewRequired && date(it)?.let { d -> !d.isAfter(today.plusDays(1)) } == true }
        val ready = items.count { it.status == ParcelStatus.READY }
        return if (items.isEmpty()) "Noch keine Pakete. Ein Foto ist ein guter Anfang." else "$active aktive Pakete · $ready bereit zur Abgabe. " + if (due > 0) "$due Abgaben sind spätestens morgen fällig oder bereits überfällig." else "Keine bestätigte Abgabefrist bis morgen offen."
    }
    fun date(p: Parcel): LocalDate? = runCatching { LocalDate.parse(p.dueDate) }.getOrNull()
    fun priority(items: List<Parcel>, today: LocalDate = LocalDate.now()) = items.filter { pending(it) && !it.aiReviewRequired && date(it)?.let { d -> !d.isAfter(today.plusDays(3)) } == true }.sortedBy { it.dueDate }
    fun answer(query: ParcelQuery, items: List<Parcel>, today: LocalDate = LocalDate.now()): ParcelAnswer {
        if (query.intent == ParcelIntent.REFUSE) return ParcelAnswer(refusal)
        if (query.intent == ParcelIntent.HELP) return ParcelAnswer(when (query.helpTopic) {
            "backup" -> "Unter Sicherung kannst du deinen Ordner verbinden oder ein ZIP exportieren. Name, KI-Einstellungen, Chat und Erkennungsergebnisse werden mitgesichert. API-Schlüssel liegen nur passwortverschlüsselt im Backup. Nach Neuinstallation Ordner wählen und Schlüssel mit dem Backup-Passwort entsperren."
            "tracking" -> "Ein Versand-QR ist nicht automatisch eine Sendungsnummer. Trage eine bestätigte Sendungsnummer und den Anbieter ein. Für automatische Tracking-Updates ist zusätzlich ein TrackingMore-Schlüssel nötig; OpenAI ersetzt diesen nicht."
            "ai" -> "Im KI-Modus erkennt die App Inhalte aus Bildern und der ersten PDF-Seite. Ergänzte Angaben werden als ungeprüft markiert. Bitte prüfe besonders Adresse, Sendungsnummer und Frist vor dem Speichern."
            else -> "Neues Paket anlegen, Foto oder Screenshot hinzufügen und die Angaben prüfen. Schreibe die Paketnummer auf den Karton. Wähle „Bereit zur Abgabe“, zeige im Paketshop das Original-Label und markiere das Paket anschließend als abgegeben."
        })
        val matches = items.filter { p -> query.search.isBlank() || listOf(p.code, p.title, p.recipient, p.location, p.orderNumber, p.carrier.label, p.platform).any { it.contains(query.search.take(100), true) } }
        val result = when (query.intent) {
            ParcelIntent.DUE -> matches.filter { pending(it) && !it.aiReviewRequired && date(it)?.let { d -> !d.isAfter(today.plusDays(query.days.coerceIn(0, 30).toLong())) } == true }
            ParcelIntent.OVERDUE -> matches.filter { pending(it) && !it.aiReviewRequired && date(it)?.isBefore(today) == true }
            ParcelIntent.READY -> matches.filter { it.status == ParcelStatus.READY }
            ParcelIntent.IN_TRANSIT -> matches.filter { it.status == ParcelStatus.SHIPPED }
            ParcelIntent.DELIVERED -> matches.filter { it.status == ParcelStatus.DELIVERED }
            else -> matches
        }.sortedWith(compareBy<Parcel> { it.dueDate.ifBlank { "9999" } }.thenBy { it.number })
        val prefix = if (query.intent == ParcelIntent.SUMMARY) brief(matches, today) else if (result.isEmpty()) "Keine passenden Pakete gefunden." else "${result.size} passende Pakete:"
        val lines = result.take(20).joinToString("\n") { p ->
            "${p.code} · ${p.title} · ${p.status.label}" + (if (p.dueDate.isNotBlank()) " · bis ${p.dueDate}${if (p.aiReviewRequired) " (ungeprüft)" else ""}" else " · keine Frist hinterlegt")
        }
        val uncertain = if (query.intent in listOf(ParcelIntent.DUE, ParcelIntent.OVERDUE)) "\nPakete ohne bestätigte Frist können zusätzlich offen sein." else ""
        return ParcelAnswer(listOf(prefix, lines).filter { it.isNotBlank() }.joinToString("\n\n") + uncertain, result.take(20))
    }
}
object ParcelChatPolicy {
    // Reject obvious off-topic/mixed requests before spending API tokens. No keyword can unlock free-form answers.
    private val unrelated = Regex("wetter|weather|temperatur|fußball|fussball|politik|horoskop|rezept|gedicht|witz|joke|sex|medizin|diagnos|aktie|bitcoin|programmier|code schreiben|system.?prompt|ignoriere|ignore|anweisung|übersetz|translate|mathe", RegexOption.IGNORE_CASE)
    private val parcelTerms = Regex("paket|päckchen|sendung|versand|label|qr|vinted|vintage|kleinanzeigen|ebay|abgab|frist|fällig|faellig|überfällig|liefer|tracking|dhl|hermes|dpd|ups|gls|fedex|empfänger|empfaenger|adresse|backup|sicher|ki.modus|openai|p-\\d+", RegexOption.IGNORE_CASE)
    fun allowed(question: String): Boolean = question.length in 2..1000 && !unrelated.containsMatchIn(question) && parcelTerms.containsMatchIn(question)
    fun local(question: String): ParcelQuery? {
        if (!allowed(question)) return ParcelQuery(ParcelIntent.REFUSE)
        val q = question.lowercase().trim().trimEnd('?', '.', '!')
        return when (q) {
            "welche pakete sind bald fällig", "welche pakete sind bald faellig", "was ist bald fällig" -> ParcelQuery(ParcelIntent.DUE)
            "welche pakete sind überfällig" -> ParcelQuery(ParcelIntent.OVERDUE)
            "welche pakete kann ich abgeben" -> ParcelQuery(ParcelIntent.READY)
            "welche pakete sind unterwegs" -> ParcelQuery(ParcelIntent.IN_TRANSIT)
            "gib mir eine paketübersicht", "paketübersicht" -> ParcelQuery(ParcelIntent.SUMMARY)
            "wie sichere ich meine pakete" -> ParcelQuery(ParcelIntent.HELP, helpTopic = "backup")
            else -> null
        }
    }
}
fun applyExtraction(p: Parcel, attachment: Attachment, e: AiExtraction): Parcel {
    val kinds = e.kinds.distinct().ifEmpty { listOf(attachment.kind) }
    val primary = listOf(AttachmentKind.LABEL, AttachmentKind.ADDRESS, AttachmentKind.RECEIPT, AttachmentKind.PHOTO).first { it in kinds }
    val raw = (attachment.rawCodes + e.localCodes).distinct().filter { it.length <= 12000 }.take(20)
    val mapped = attachment.copy(kind = primary, detectedKinds = kinds, aiAnalyzed = true, rawCodes = raw)
    val codes = ShippingCodeParser.analyze(raw)
    val evidence = linkedMapOf<String, String>()
    fun value(name: String, field: ExtractedField?, current: String): String {
        if (field == null || field.value.isBlank() || field.evidence.isBlank()) return current
        evidence[name] = "${field.value.take(500)} ← ${field.evidence.take(500)}"
        return current.ifBlank { field.value.take(4000) }
    }
    val title = value("Artikel", e.title, p.title)
    val known = codes.recipient
    val senderName = e.senderName?.value.orEmpty()
    val senderAddress = e.senderAddress?.value.orEmpty()
    val nameIsSender = e.recipient?.value?.let { proposed -> ShippingCodeParser.same(proposed, senderName) || codes.parsed.any { ShippingCodeParser.same(proposed, it.sender.name) } } == true
    val addressIsSender = e.address?.value?.let { proposed -> ShippingCodeParser.same(proposed, senderAddress) || codes.parsed.any { ShippingCodeParser.containsAddress(proposed, it.sender) } } == true
    fun grounded(field: ExtractedField?): Boolean {
        if (!e.sourceScanned) return true
        val quote = field?.evidence.orEmpty().lowercase().filter(Char::isLetterOrDigit)
        val source = e.localText.lowercase().filter(Char::isLetterOrDigit)
        return quote.length >= 3 && source.contains(quote)
    }
    val roleConfirmed = e.recipientRole == AddressRole.EXPLICIT_RECIPIENT && !codes.needsChoice
    val recipientField = known?.let { ExtractedField(it.name, "Lokaler QR: zweiter Adressblock, Empfänger") } ?: e.recipient.takeIf { roleConfirmed && !nameIsSender && grounded(it) }
    val addressField = known?.let { ExtractedField(it.address, "Lokaler QR: zweiter Adressblock, Empfänger") } ?: e.address.takeIf { roleConfirmed && !addressIsSender && !nameIsSender && grounded(it) }
    val recipient = value("Empfänger", recipientField, p.recipient)
    val address = value("Adresse", addressField, p.address)
    val platform = value("Plattform", e.platform, if (p.platform == "Vinted" && p.title.isBlank()) "" else p.platform).ifBlank { p.platform }
    val order = value("Bestellnummer", e.orderNumber, p.orderNumber)
    val candidateTracking = e.tracking?.takeIf { field ->
        !field.value.startsWith("RET", true) && !field.value.startsWith("PAK", true) &&
        (!e.sourceScanned || TrackingDetector.candidates(emptyList(), e.localText).any { it.number.equals(field.value.replace(" ", ""), true) }) &&
        !field.value.matches(Regex("\\d{2}-\\d{5}-\\d{5}")) &&
            field.value.replace(" ", "").matches(Regex("[A-Za-z0-9]{8,35}")) &&
            Regex("sendung|tracking|paketnummer|shipment|awb|waybill", RegexOption.IGNORE_CASE).containsMatchIn(field.evidence)
    }
    val tracking = value("Sendungsnummer", candidateTracking, p.trackingNumber)
    val dueRaw = e.dueDate?.value.orEmpty()
    val dateEvidence = e.dueDate?.evidence.orEmpty()
    val validDate = grounded(e.dueDate) && dueRaw.isNotBlank() && runCatching { LocalDate.parse(dueRaw) }.isSuccess &&
        Regex("20[0-9]{2}").containsMatchIn(dateEvidence) &&
        Regex("abgab|versend|verschick|frist|send by|ship by|bis|deadline", RegexOption.IGNORE_CASE).containsMatchIn(dateEvidence) &&
        !Regex("erstellt|created|bestellt|ordered", RegexOption.IGNORE_CASE).containsMatchIn(dateEvidence)
    val due = value("Abgabefrist", if (validDate) e.dueDate else null, p.dueDate)
    val carrierName = value("Anbieter", e.carrier, "")
    val carrier = Carrier.entries.firstOrNull { it.name.equals(carrierName, true) || it.label.equals(carrierName, true) } ?: Carrier.UNKNOWN
    val roleWarning = if ((e.recipient != null || e.address != null) && known == null && (!roleConfirmed || nameIsSender || addressIsSender)) "Empfänger nicht sicher erkannt. Bitte selbst bestätigen. Die Absenderadresse wurde nicht übernommen." else ""
    val summary = listOf(e.summary, e.warning, roleWarning).filter { it.isNotBlank() }.joinToString("\n").take(2000)
    return applyLocalCodes(p.copy(title = title, recipient = recipient, address = address, platform = platform, orderNumber = order,
        trackingNumber = tracking, dueDate = due, carrier = if (p.carrier == Carrier.UNKNOWN) carrier else p.carrier,
        attachments = p.attachments.map { if (it.id == attachment.id) mapped else it },
        aiSummary = summary, aiEvidence = p.aiEvidence + evidence, aiReviewRequired = true), mapped, raw)
}
