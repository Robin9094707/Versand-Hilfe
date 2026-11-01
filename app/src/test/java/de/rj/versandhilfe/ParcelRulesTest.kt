package de.rj.versandhilfe

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ParcelRulesTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    @Test fun numbersWrapButNeverCollideWithOpenParcels() {
        val open = listOf(Parcel(number = 99), Parcel(number = 1), Parcel(number = 2, status = ParcelStatus.SHIPPED))
        assertEquals(3, allocateParcelNumber(open, 2, 99))
        assertEquals(1, allocateParcelNumber(listOf(Parcel(number = 1, status = ParcelStatus.DELIVERED)), 2, 1))
        assertEquals(999, allocateParcelNumber(emptyList(), 3, 999 + 1)) // out-of-range cursor normalizes to 999
    }
    @Test fun fullNumberRangeRequiresThreeDigitsRatherThanSilentDuplicates() {
        assertThrows(IllegalStateException::class.java) { allocateParcelNumber((1..99).map { Parcel(number = it) }, 2, 1) }
        assertEquals(100, allocateParcelNumber((1..99).map { Parcel(number = it) }, 3, 1))
        assertEquals("P-01", Parcel(number = 1, numberDigits = 2).code)
        assertEquals("P-001", Parcel(number = 1, numberDigits = 3).code)
    }
    @Test fun remindersCoverUnknownDeadlinesOverdueAndSnooze() {
        val now = at("2026-11-01T12:00:00")
        val settings = AssistantState()
        val noDeadline = Parcel(createdAt = at("2026-10-30T10:00:00"))
        assertTrue(ReminderRules.nextAt(noDeadline, settings, now, zone)!! <= now + 2000)
        val due = Parcel(dueDate = "2026-11-01", lastReminderAt = now)
        assertEquals(now + 3 * 3600000L, ReminderRules.nextAt(due, settings, now, zone))
        assertEquals(now + 2 * 3600000L, ReminderRules.nextAt(due.copy(snoozedUntil = now + 2 * 3600000L), settings, now, zone))
        assertNull(ReminderRules.nextAt(due.copy(status = ParcelStatus.SHIPPED), settings, now, zone))
        assertNull(ReminderRules.nextAt(due, settings.copy(reminders = false), now, zone))
    }
    @Test fun ordinaryRemindersRespectQuietHoursAndUnreviewedDates() {
        val now = at("2026-11-01T22:00:00")
        assertEquals(at("2026-11-02T09:00:00"), ReminderRules.nextAt(Parcel(createdAt = 1), AssistantState(), now, zone))
        val future = Parcel(createdAt = at("2026-11-01T12:00:00"), dueDate = "2020-01-01", aiReviewRequired = true)
        val next = ReminderRules.nextAt(future, AssistantState(), at("2026-11-01T13:00:00"), zone)!!
        assertTrue(next >= future.createdAt + 24 * 3600000L)
    }
    @Test fun backupMergeRetainsAllAuditEntriesAndOriginalCreationTime() {
        val a = Parcel(id = "one", createdAt = 10, updatedAt = 30, events = listOf(ParcelEvent(10, "Paket angelegt")))
        val b = a.copy(createdAt = 20, updatedAt = 40, events = listOf(ParcelEvent(40, "Unterwegs")))
        val merged = mergeParcels(listOf(a), listOf(b)).single()
        assertEquals(10L, merged.createdAt); assertEquals(2, merged.events.size)
    }
}
