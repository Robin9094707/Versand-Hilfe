package de.rj.versandhilfe

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixture only. Real labels and addresses must never enter the repository. */
class ShippingCodeTest {
    private val raw = "OF|EXAMPLE12|0|Paket bis 2 kg|A|Anna Beispiel||Musterweg 1|10115|Berlin|Germany|Ben Muster||Testweg 2|20095|Hamburg|Germany||||20095156002000|123456789012|27112026||0||V2.0"
    private fun attachment(codes: List<String> = emptyList()) = Attachment(name = "label.png", mime = "image/png", file = "a".repeat(64) + ".png", sha256 = "a".repeat(64), kind = AttachmentKind.PHOTO, rawCodes = codes)
    @Test fun ofV2UsesSecondAddressAndIgnoresOpaqueMetadata() {
        val result = ShippingCodeParser.parse(raw)!!
        assertEquals("Anna Beispiel", result.sender.name)
        assertEquals("Ben Muster", result.recipient.name)
        assertEquals("Testweg 2\n20095 Hamburg\nGermany", result.recipient.address)
        val a = attachment()
        val p = applyLocalCodes(Parcel(attachments = listOf(a)), a, listOf(raw))
        assertEquals("Ben Muster", p.recipient); assertFalse(p.address.contains("Musterweg"))
        assertEquals(Carrier.DHL, p.carrier); assertEquals("", p.trackingNumber); assertEquals("", p.dueDate)
        assertTrue(p.attachments.single().hasKind(AttachmentKind.LABEL))
        assertTrue(p.attachments.single().hasKind(AttachmentKind.PHOTO))
        assertTrue(p.aiReviewRequired)
    }
    @Test fun localRecipientWinsEvenIfModelSwapsTheRoles() {
        val a = attachment()
        val malicious = AiExtraction(kinds = listOf(AttachmentKind.LABEL), recipient = ExtractedField("Anna Beispiel", "Empfänger Anna Beispiel"), address = ExtractedField("Musterweg 1, 10115 Berlin", "Empfänger Musterweg 1, 10115 Berlin"), recipientRole = AddressRole.EXPLICIT_RECIPIENT,
            tracking = ExtractedField("123456789012", "Sendungsnummer 123456789012"), dueDate = ExtractedField("2026-11-27", "Versenden bis 27.11.2026"), localCodes = listOf(raw), sourceScanned = true)
        val p = applyExtraction(Parcel(attachments = listOf(a)), a, malicious)
        assertEquals("Ben Muster", p.recipient); assertEquals("Testweg 2\n20095 Hamburg\nGermany", p.address)
        assertEquals("", p.trackingNumber); assertEquals("", p.dueDate)
    }
    @Test fun unknownLayoutsAndMultipleRecipientsNeverBecomeGuessedAddresses() {
        listOf(raw.replace("V2.0", "V3.0"), raw.replace("|A|", "|B|"), raw + "|extra", raw.replace("|20095|", "|ABCDE|")).forEach { assertNull(ShippingCodeParser.parse(it)) }
        val a = attachment()
        val second = raw.replace("Ben Muster", "Clara Muster").replace("Testweg 2", "Testweg 3")
        val codes = listOf(raw, second)
        assertNull(ShippingCodeParser.analyze(codes).recipient)
        assertNull(ShippingCodeParser.analyze(listOf(raw, raw.replace("V2.0", "V3.0"))).recipient)
        val e = AiExtraction(kinds = listOf(AttachmentKind.LABEL), recipient = ExtractedField("Ben Muster", "Ben Muster"), address = ExtractedField("Testweg 2", "Testweg 2"), recipientRole = AddressRole.EXPLICIT_RECIPIENT, localCodes = codes)
        val p = applyExtraction(Parcel(attachments = listOf(a)), a, e)
        assertEquals("", p.recipient); assertEquals("", p.address)
    }
    @Test fun unlabelledAddressesAndKnownSenderAreNotAutoFilled() {
        val a = attachment()
        val e = AiExtraction(kinds = listOf(AttachmentKind.ADDRESS), recipient = ExtractedField("Anna Beispiel", "Anna Beispiel"), address = ExtractedField("Musterweg 1", "Musterweg 1"))
        assertEquals("", applyExtraction(Parcel(attachments = listOf(a)), a, e).recipient)
        val swapped = e.copy(recipientRole = AddressRole.EXPLICIT_RECIPIENT, senderName = e.recipient, senderAddress = e.address)
        val p = applyExtraction(Parcel(attachments = listOf(a)), a, swapped)
        assertEquals("", p.recipient); assertEquals("", p.address)
    }
    @Test fun manualEditsArePreservedAndConflictIsVisible() {
        val a = attachment()
        val p = applyLocalCodes(Parcel(recipient = "Manuell gewählt", address = "Andere Zieladresse", attachments = listOf(a)), a, listOf(raw))
        assertEquals("Manuell gewählt", p.recipient); assertEquals("Andere Zieladresse", p.address)
        assertTrue(p.aiSummary.contains("weicht")); assertTrue(p.aiReviewRequired)
    }
    @Test fun returnAndOpaqueCodesAreNotTrackingIdsOrAddresses() {
        val codes = listOf("RET123456789012", "PAK1234567890", "random-reference")
        assertTrue(ShippingCodeParser.analyze(codes).parsed.isEmpty())
        assertTrue(TrackingDetector.candidates(codes, "DHL Digitaler Versandschein RET123456789012").isEmpty())
        val a = attachment()
        val p = applyExtraction(Parcel(attachments = listOf(a)), a, AiExtraction(kinds = listOf(AttachmentKind.LABEL), tracking = ExtractedField(codes.first(), "Sendungsnummer ${codes.first()}")))
        assertEquals("", p.trackingNumber)
    }
    @Test fun rawCodesAndDecodedRecipientRoundTripThroughBackup() {
        val a = attachment(listOf(raw))
        val p = applyLocalCodes(Parcel(title = "Jeans", attachments = listOf(a)), a, listOf(raw))
        val archive = Archive(parcels = listOf(p))
        val restored = AppJson.decodeFromString<Archive>(AppJson.encodeToString(archive))
        validateArchive(restored)
        assertEquals(archive, restored)
        assertEquals("Ben Muster", ShippingCodeParser.analyze(restored.parcels.single().attachments.single().rawCodes).recipient!!.name)
    }
}
