package de.rj.versandhilfe

import java.time.*

fun numberRange(digits: Int) = if (digits == 3) 999 else 99
fun allocateParcelNumber(parcels: List<Parcel>, digits: Int, start: Int): Int {
    val max = numberRange(digits)
    val used = parcels.filter { it.status != ParcelStatus.DELIVERED }.map { it.number }.toSet()
    return (0 until max).asSequence().map { (start.coerceIn(1, max) - 1 + it) % max + 1 }.firstOrNull { it !in used }
        ?: error("Alle $max Paketnummern sind belegt. Bitte unter Einstellungen dreistellige Nummern wählen oder abgeschlossene Pakete als angekommen markieren.")
}
object ReminderRules {
    fun nextAt(p: Parcel, settings: AssistantState, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (!settings.reminders || !ParcelFacts.pending(p)) return null
        if (p.snoozedUntil > now) return p.snoozedUntil
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val due = if (p.aiReviewRequired) null else ParcelFacts.date(p)
        val hour = settings.reminderHour.coerceIn(0, 23)
        val first = if (due != null) due.minusDays(1).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            else maxOf(p.createdAt + settings.reminderEveryHours * 3600000L, today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli())
        val interval = if (due != null && !due.isAfter(today)) 3 * 3600000L else settings.reminderEveryHours * 3600000L
        val afterLast = if (p.lastReminderAt > 0) p.lastReminderAt + interval else first
        var result = maxOf(first, afterLast, now + 1000)
        // Quiet night: ordinary reminders only between the configured morning hour and 20:00.
        val local = Instant.ofEpochMilli(result).atZone(zone)
        if (local.hour >= 21 || local.hour < hour) result = (if (local.hour >= 21) local.toLocalDate().plusDays(1) else local.toLocalDate()).atTime(hour.coerceAtMost(20), 0).atZone(zone).toInstant().toEpochMilli()
        return result
    }
}
