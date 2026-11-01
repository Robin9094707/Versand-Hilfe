package de.rj.versandhilfe

/** Pure rules: no screen pixels, app text or personal details are retained by these decisions. */
object VintedQrPolicy {
    const val PACKAGE = "fr.vinted"
    private val preview = Regex("digital(?:er|en|e)?\\s+versandschein|versand(?:schein|label|etikett)|paketschein|shipping\\s+label|digital\\s+shipping", RegexOption.IGNORE_CASE)
    private val sensitive = Regex("passwort|password|einloggen|anmelden|log.?in|bankverbindung|kreditkarte|credit.card|paypal|zahlung|payment|bestätigungscode|verification.code", RegexOption.IGNORE_CASE)
    private val useful = Regex("versand|versend|verschick|abgeb|abgab|frist|ship(?:ping)?|send by|dhl|hermes|dpd|\\bups\\b|gls|fedex|artikel|item", RegexOption.IGNORE_CASE)
    fun eligible(packageName: String, headings: List<String>, visible: List<String>, editable: Boolean, unlocked: Boolean): Boolean =
        packageName == PACKAGE && unlocked && !editable && headings.any { preview.containsMatchIn(it) } && visible.none { sensitive.containsMatchIn(it) }
    fun safeContext(visible: List<String>): String {
        val chosen = linkedSetOf<String>()
        visible.forEachIndexed { index, value ->
            if (useful.containsMatchIn(value) && !sensitive.containsMatchIn(value)) {
                chosen += value.take(350)
                // Only explicit article/deadline labels may take the following short value.
                if (Regex("^(artikel|item|versenden bis|abgeben bis|abgabefrist|ship by)[: ]*$", RegexOption.IGNORE_CASE).matches(value.trim()))
                    visible.getOrNull(index + 1)?.takeIf { it.length <= 200 && !sensitive.containsMatchIn(it) }?.let { chosen += it }
            }
        }
        return chosen.joinToString("\n").take(3000)
    }
    fun canonical(code: String) = code.trim().replace("\r\n", "\n")
    fun duplicate(parcels: List<Parcel>, codes: List<String>): Parcel? {
        val sought = codes.map(::canonical).filter { it.isNotBlank() }.toSet()
        if (sought.isEmpty()) return null
        return parcels.firstOrNull { p -> p.attachments.any { a -> (a.rawCodes + a.qrCodes).any { canonical(it) in sought } } }
    }
}
/** Three scans for a newly opened preview; later scans require a real content event and a cooldown. */
class PreviewScanBudget {
    private var signature = ""
    private var count = 0
    private var last = 0L
    fun allow(scene: String, now: Long, contentEvent: Boolean): Boolean {
        if (signature != scene) { signature = scene; count = 0; last = 0 }
        if (now - last < 1400 && count > 0) return false
        if (count >= 3 && (!contentEvent || now - last < 10000)) return false
        count++; last = now; return true
    }
    fun reset() { signature = ""; count = 0; last = 0 }
}
