package de.rj.versandhilfe

import java.text.Normalizer

/** Only the observed 27-field German OF/A V2.0 layout is decoded. Not a universal DHL spec. */
data class ShippingAddress(val name: String, val extra: String, val street: String, val postalCode: String, val city: String, val country: String) {
    val address get() = listOf(extra, street, "$postalCode $city", country).filter { it.isNotBlank() }.joinToString("\n")
}
data class ShippingCode(val sender: ShippingAddress, val recipient: ShippingAddress, val product: String)
data class CodeAnalysis(val parsed: List<ShippingCode>, val hasUnknownOf: Boolean) {
    val recipients get() = parsed.map { it.recipient }.distinct()
    val recipient get() = recipients.singleOrNull().takeIf { !hasUnknownOf }
    val needsChoice get() = hasUnknownOf || recipients.size > 1
    fun context(): String = buildString {
        parsed.forEachIndexed { index, code ->
            appendLine("Lokal erkannter DHL-OF/A-V2.0-Code ${index + 1}:")
            appendLine("ABSENDER (niemals als Empfänger übernehmen): ${code.sender.name}; ${code.sender.address}")
            appendLine("EMPFÄNGER: ${code.recipient.name}; ${code.recipient.address}")
            appendLine("Produkt: ${code.product}. Andere Zahlenfelder haben keine bestätigte Bedeutung. Keine Sendungsnummer oder Abgabefrist daraus ableiten.")
        }
        if (needsChoice) appendLine("Adresszuordnung nicht eindeutig: keine Adresse übernehmen, Nutzerin fragen.")
    }
}
object ShippingCodeParser {
    fun parse(raw: String): ShippingCode? {
        if (raw.length > 12000) return null
        val f = raw.trim().split('|')
        if (f.size != 27 || f[0] != "OF" || f[2] != "0" || f[4] != "A" || f.last() != "V2.0") return null
        if (!f[1].matches(Regex("[A-Z0-9]{6,24}")) || f[3].isBlank()) return null
        fun address(start: Int): ShippingAddress? {
            val a = f.subList(start, start + 6)
            if (a.any { it.length > 250 || it.any(Char::isISOControl) }) return null
            // This observed domestic variant has German postal addresses. Unknown layouts stay manual.
            if (a[0].isBlank() || a[2].isBlank() || !a[3].matches(Regex("[0-9]{5}")) || a[4].isBlank() || a[5] !in setOf("Germany", "Deutschland", "DE")) return null
            return ShippingAddress(a[0], a[1], a[2], a[3], a[4], a[5])
        }
        return ShippingCode(address(5) ?: return null, address(11) ?: return null, f[3])
    }
    fun analyze(codes: List<String>): CodeAnalysis {
        val unique = codes.distinct()
        return CodeAnalysis(unique.mapNotNull(::parse), unique.any { it.trim().startsWith("OF|") && parse(it) == null })
    }
    private fun normalized(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter(Char::isLetterOrDigit)
    fun same(a: String, b: String) = a.isNotBlank() && b.isNotBlank() && normalized(a) == normalized(b)
    fun containsAddress(value: String, address: ShippingAddress): Boolean {
        val n = normalized(value)
        return n.contains(normalized(address.street)) && n.contains(normalized(address.postalCode))
    }
}
/** Preserve manual edits; conflicts are shown for confirmation instead of silently overwriting them. */
fun applyLocalCodes(p: Parcel, attachment: Attachment, codes: List<String>, qrCodes: List<String> = emptyList()): Parcel {
    val raw = (attachment.rawCodes + codes).distinct().filter { it.length <= 12000 }.take(20)
    val analysis = ShippingCodeParser.analyze(raw)
    val recipient = analysis.recipient
    val conflict = recipient != null && ((p.recipient.isNotBlank() && !ShippingCodeParser.same(p.recipient, recipient.name)) || (p.address.isNotBlank() && !ShippingCodeParser.same(p.address, recipient.address)))
    val evidence = if (recipient == null) emptyMap() else mapOf("QR-Empfänger" to "${recipient.name} · ${recipient.address} ← zweiter Adressblock, DHL OF/A V2.0")
    val note = when {
        analysis.needsChoice -> "Mehrere oder unbekannte QR-Adressblöcke. Bitte Empfänger selbst bestätigen."
        conflict -> "Der QR-Empfänger weicht von deinen Angaben ab. Bitte beim Empfänger prüfen."
        recipient != null -> "Empfänger aus dem zweiten QR-Adressblock gelesen. Der Absender wurde nicht übernommen."
        else -> ""
    }
    return p.copy(recipient = p.recipient.ifBlank { recipient?.name.orEmpty() }, address = p.address.ifBlank { recipient?.address.orEmpty() },
        carrier = if (analysis.parsed.isNotEmpty() && p.carrier == Carrier.UNKNOWN) Carrier.DHL else p.carrier,
        attachments = p.attachments.map { if (it.id == attachment.id) it.copy(rawCodes = raw, qrCodes = (it.qrCodes + qrCodes).distinct().filter { it in raw }.take(20), detectedKinds = if (analysis.parsed.isNotEmpty()) (it.detectedKinds + it.kind + AttachmentKind.LABEL + AttachmentKind.ADDRESS).distinct() else it.detectedKinds) else it },
        aiEvidence = p.aiEvidence + evidence, aiSummary = listOf(p.aiSummary, note).filter { it.isNotBlank() }.distinct().joinToString("\n").take(3000),
        aiReviewRequired = p.aiReviewRequired || recipient != null || analysis.needsChoice)
}
