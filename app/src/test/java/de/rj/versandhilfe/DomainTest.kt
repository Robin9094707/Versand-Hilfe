package de.rj.versandhilfe

import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    @Test fun opaqueShippingQrDoesNotBecomeTracking() {
        assertTrue(TrackingDetector.candidates(listOf("DHL:ABGABE:abc-def"), "DHL Versandlabel").isEmpty())
        assertTrue(TrackingDetector.candidates(listOf("https://evil.test/?piececode=123456789012"), "").isEmpty())
        assertTrue(TrackingDetector.candidates(listOf("12345678901234"), "").isEmpty())
    }
    @Test fun upsAndTrustedTrackingLinksProduceReviewableCandidates() {
        assertEquals(Carrier.UPS, TrackingDetector.candidates(listOf("1Z999AA10123456784"), "").first().carrier)
        val candidate = TrackingDetector.candidates(listOf("https://www.dhl.de/de/privatkunden?piececode=00340434123456789012"), "").first()
        assertEquals("00340434123456789012", candidate.number)
        assertEquals(Carrier.DHL, candidate.carrier)
    }
    @Test fun maliciousSimilarHostIsNotTrusted() {
        assertTrue(TrackingDetector.candidates(listOf("https://dhl.de.attacker.test/?piececode=00340434123456789012"), "").isEmpty())
    }
    @Test fun mergePreservesLocalAndUsesNewerVersionWithoutNumberCollisions() {
        val a = Parcel(id = "a", title = "Alt", number = 1, updatedAt = 10, createdAt = 1)
        val b = Parcel(id = "b", title = "Lokal", number = 2, updatedAt = 20, createdAt = 2)
        val c = Parcel(id = "c", title = "Import", number = 2, updatedAt = 30, createdAt = 3)
        val merged = mergeParcels(listOf(a, b), listOf(a.copy(title = "Neu", updatedAt = 30), c))
        assertEquals(3, merged.size)
        assertEquals("Neu", merged.first { it.id == "a" }.title)
        assertEquals(3, merged.map { it.number }.distinct().size)
    }
    @Test fun futureBackupVersionAndPathTraversalAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { validateArchive(Archive(version = 100)) }
        val a = Attachment(name = "x", mime = "image/png", file = "../../bad.png", sha256 = "a".repeat(64), kind = AttachmentKind.PHOTO)
        assertThrows(IllegalArgumentException::class.java) { validateArchive(Archive(parcels = listOf(Parcel(attachments = listOf(a))))) }
    }
    @Test fun archiveRoundTripKeepsLabelsEventsAndUnicode() {
        val item = Parcel(title = "Mamas schöne Jacke 🧥", address = "Müller\nStraße 4", events = listOf(ParcelEvent(text = "Abgegeben")))
        val archive = Archive(parcels = listOf(item))
        assertEquals(archive, AppJson.decodeFromString<Archive>(AppJson.encodeToString(archive)))
        validateArchive(archive)
    }
}
