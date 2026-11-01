package de.rj.versandhilfe

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString

class VintedQrPolicyTest {
    @Test fun onlyUnlockedNonEditableVintedShippingPreviewsAreEligible() {
        val headings = listOf("Digitaler Versandschein")
        assertTrue(VintedQrPolicy.eligible("fr.vinted", headings, headings, false, true))
        assertFalse(VintedQrPolicy.eligible("com.bank.example", headings, headings, false, true))
        assertFalse(VintedQrPolicy.eligible("fr.vinted", headings, headings, true, true))
        assertFalse(VintedQrPolicy.eligible("fr.vinted", headings, headings, false, false))
        assertFalse(VintedQrPolicy.eligible("fr.vinted", listOf("QR-Code"), listOf("Privater Chat"), false, true))
    }
    @Test fun paymentAndLoginTextBlockCaptureEvenWhenShippingIsMentioned() {
        listOf("Passwort", "PayPal Zahlung", "Payment", "Verification code", "Anmelden").forEach {
            assertFalse(VintedQrPolicy.eligible("fr.vinted", listOf("Versandlabel"), listOf("Versandlabel", it), false, true))
        }
    }
    @Test fun onlySelectedShippingContextIsRetained() {
        val context = VintedQrPolicy.safeContext(listOf("Digitaler Versandschein", "Meine private Nachricht", "person@example.invalid", "Versenden bis", "27.11.2026", "DHL", "Artikel", "Blaue Jeans"))
        assertTrue(context.contains("27.11.2026")); assertTrue(context.contains("Blaue Jeans"))
        assertFalse(context.contains("private")); assertFalse(context.contains("example.invalid"))
    }
    @Test fun rawQrDeduplicationIncludesClosedParcelsAndPreservesCase() {
        val a = Attachment(name = "qr", file = "a".repeat(64)+".png", mime = "image/png", sha256 = "a".repeat(64), kind = AttachmentKind.LABEL, rawCodes = listOf("ABC123"), qrCodes = listOf("ABC123"))
        val p = Parcel(attachments = listOf(a), status = ParcelStatus.DELIVERED)
        assertEquals(p.id, VintedQrPolicy.duplicate(listOf(p), listOf(" ABC123\n"))?.id)
        assertNull(VintedQrPolicy.duplicate(listOf(p), listOf("abc123")))
        assertNull(VintedQrPolicy.duplicate(listOf(p), emptyList()))
    }
    @Test fun scansAreBoundedAndNeedAnEventAfterTheInitialAttempts() {
        val budget = PreviewScanBudget()
        assertTrue(budget.allow("one", 10000, true)); assertFalse(budget.allow("one", 11000, true))
        assertTrue(budget.allow("one", 12000, false)); assertTrue(budget.allow("one", 14000, false))
        assertFalse(budget.allow("one", 16000, false)); assertFalse(budget.allow("one", 30000, false))
        assertTrue(budget.allow("one", 30000, true)); assertTrue(budget.allow("two", 31000, false))
    }
    @Test fun importEvidenceAndPendingReviewSurviveBackup() {
        val a = Attachment(name = "qr.png", mime = "image/png", file = "a".repeat(64)+".png", sha256 = "a".repeat(64), kind = AttachmentKind.LABEL, importText = "Versenden bis 27.11.2026", localCodeScanned = true)
        val archive = Archive(parcels = listOf(Parcel(attachments = listOf(a), screenReviewPending = true)), assistant = AssistantState(vintedQrHelp = true))
        val restored = AppJson.decodeFromString<Archive>(AppJson.encodeToString(archive));validateArchive(restored);assertEquals(archive, restored)
        assertFalse(AppJson.encodeToString(archive).contains("vintedQrConsent"))
    }
}
