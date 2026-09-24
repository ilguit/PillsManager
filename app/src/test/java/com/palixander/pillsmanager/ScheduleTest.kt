package com.palixander.pillsmanager

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ScheduleTest {
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun prescription(zone: String = "UTC", start: String = "2026-09-13", times: String = "09:00,18:00", end: String? = null, after: Long = ms("2026-09-13T08:00:00Z")) = Prescription(id = "rx", profileId = "person", name = "Препарат", dose = "1 таблетка", times = times, start = start, end = end, zone = zone, generatedUntil = after)
    private val intake = Intake("one", "rx", "person", "Препарат", "1", "UTC", ms("2026-09-13T09:00:00Z"))
    @Test fun statusBoundaries() {
        val t = intake.scheduled
        assertEquals(Status.PLANNED, Schedule.status(intake, t - 1))
        assertEquals(Status.WAITING, Schedule.status(intake, t))
        assertEquals(Status.WAITING, Schedule.status(intake, t + Schedule.DAY - 1))
        assertEquals(Status.MISSED, Schedule.status(intake, t + Schedule.DAY))
    }
    @Test fun decisionsOverrideClock() {
        for (s in listOf(Status.TAKEN, Status.MISSED, Status.CANCELLED)) assertEquals(s, Schedule.status(intake.copy(decision = s.name), intake.scheduled - 1))
    }
    @Test fun creationOmitsPastAndIncludesEndDay() {
        val p = prescription(after = ms("2026-09-13T15:00:00Z"), end = "2026-09-14")
        val rows = Schedule.generate(p, ms("2026-09-16T00:00:00Z"))
        assertEquals(listOf(ms("2026-09-13T18:00:00Z"), ms("2026-09-14T09:00:00Z"), ms("2026-09-14T18:00:00Z")), rows.map { it.scheduled })
    }
    @Test fun generationIsStableAndIncremental() {
        val p = prescription()
        val firstEnd = ms("2026-09-14T00:00:00Z")
        val end = ms("2026-09-15T00:00:00Z")
        val full = Schedule.generate(p, end)
        assertEquals(full, Schedule.generate(p, firstEnd) + Schedule.generate(p.copy(generatedUntil = firstEnd), end))
        assertEquals(full.map { it.id }.distinct().size, full.size)
    }
    @Test fun profilesHaveIndependentIds() {
        val p = prescription()
        assertNotEquals(Schedule.generate(p, intake.scheduled).first().id, Schedule.generate(p.copy(id = "other", profileId = "other-person"), intake.scheduled).first().id)
    }
    @Test fun snapshotsAndArchivedCourse() {
        val p = prescription()
        val row = Schedule.generate(p, intake.scheduled).first()
        assertEquals(p.name, row.name); assertEquals(p.dose, row.dose)
        assertTrue(Schedule.generate(p.copy(archived = true), intake.scheduled).isEmpty())
    }
    @Test fun fixedZoneIgnoresPhoneLocation() {
        val p = prescription(zone = "Asia/Yekaterinburg", after = ms("2026-09-13T00:00:00Z"))
        val row = Schedule.generate(p, ms("2026-09-13T10:00:00Z")).first()
        assertEquals(7, Instant.ofEpochMilli(row.scheduled).atZone(ZoneId.of("Europe/Moscow")).hour)
    }
    @Test fun springGapMovesForward() {
        val p = prescription("Europe/Berlin", "2026-03-29", "02:30", after = ms("2026-03-28T23:00:00Z"))
        val rows = Schedule.generate(p, ms("2026-03-29T23:00:00Z"))
        assertEquals(listOf(ms("2026-03-29T01:30:00Z")), rows.map { it.scheduled })
    }
    @Test fun autumnOverlapOccursOnlyOnceAtFirstOffset() {
        val p = prescription("Europe/Berlin", "2026-10-25", "02:30", after = ms("2026-10-24T22:00:00Z"))
        assertEquals(listOf(ms("2026-10-25T00:30:00Z")), Schedule.generate(p, ms("2026-10-25T23:00:00Z")).map { it.scheduled })
    }
    @Test fun normalizeTimesSortsAndDeduplicates() { assertEquals("09:00,18:00", Schedule.normalizeTimes("18:00, 9:00;09:00")) }
    @Test(expected = Exception::class) fun invalidTimeRejected() { Schedule.normalizeTimes("28:30") }
    @Test fun longAbsenceIncludesMissedHistory() {
        val p = prescription(end = "2026-09-15")
        val rows = Schedule.generate(p, ms("2026-12-01T00:00:00Z"))
        assertEquals(6, rows.size)
        assertTrue(rows.all { Schedule.status(it, ms("2026-12-01T00:00:00Z")) == Status.MISSED })
    }
}
