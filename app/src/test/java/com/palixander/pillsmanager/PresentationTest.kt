package com.palixander.pillsmanager

import org.junit.Assert.*
import org.junit.Test

class PresentationTest {
    private val now = 1_000_000_000L
    private fun rx(id: String, name: String, times: String) = Prescription(id, "p", name, "1", times, "2026-09-13", null, "UTC", generatedUntil = 0)
    private fun intake(id: String, profile: String, time: Long, name: String = id, decision: String? = null, takenAt: Long? = null) = Intake(id, "rx", profile, name, "1", "UTC", time, decision, takenAt)
    @Test fun profileRowsSortEveryTimeBeforeName() {
        val rows = Presentation.prescriptionTimes(listOf(rx("b", "Б", "18:00,09:00"), rx("a", "А", "09:00,12:00")))
        assertEquals(listOf("09:00/А", "09:00/Б", "12:00/А", "18:00/Б"), rows.map { "${it.time}/${it.prescription.name}" })
    }
    @Test fun timelineOrdersAcrossProfilesAndKeepsCoincidentProfilesSeparate() {
        val profiles = listOf(Profile("a", "А"), Profile("b", "Б"))
        val rows = listOf(intake("a-late", "a", now + 100), intake("b-early", "b", now - 100), intake("a-early", "a", now - 100))
        val groups = Presentation.timeline(rows, profiles, now)
        assertEquals(listOf(now - 100, now - 100, now + 100), groups.map { it.scheduled })
        assertEquals(listOf("a", "b", "a"), groups.map { it.profileId })
    }
    @Test fun mixedStatusesShareOneGroupAndNamesAreSorted() {
        val rows = listOf(intake("b", "p", now - 100, "Б"), intake("a", "p", now - 100, "А", "TAKEN", now - 50))
        val groups = Presentation.timeline(rows, listOf(Profile("p", "Профиль")), now)
        assertEquals(1, groups.size)
        assertEquals(listOf("А", "Б"), groups.single().rows.map { it.name })
        assertEquals(listOf(Status.TAKEN, Status.WAITING), groups.single().rows.map { Schedule.status(it, now) })
    }
    @Test fun keepsLastAndNextWithoutExpandingIntoEntireHistory() {
        val rows = listOf(intake("old", "p", now - 1000, decision = "TAKEN", takenAt = now - 1000), intake("last", "p", now - 500, decision = "TAKEN", takenAt = now - 500), intake("next", "p", now + 100), intake("later", "p", now + 200))
        assertEquals(listOf("last", "next"), Presentation.timeline(rows, listOf(Profile("p", "П")), now).flatMap { it.rows }.map { it.id })
    }
}
