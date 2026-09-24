package com.palixander.pillsmanager

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class RepositoryTest {
    private lateinit var db: PillsDatabase
    private lateinit var repo: Repository
    private val now = Instant.parse("2026-09-13T08:00:00Z").toEpochMilli()
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PillsDatabase::class.java).build(); repo = Repository(db) }
    @After fun close() { db.close() }
    private suspend fun seed(id: String = "rx", profile: String = "p") {
        repo.dao.saveProfile(Profile(profile, profile))
        repo.save(Prescription(id, profile, "Лекарство", "1 таблетка", "09:00,18:00", "2026-09-13", null, "UTC", generatedUntil = now), now)
    }
    @Test fun partialEarlyConfirmationAndIdempotency() = runBlocking {
        seed(); seed("second")
        val first = repo.dao.allIntakes().first()
        repo.mark(setOf(first.id), "TAKEN", now, now = now)
        repo.mark(setOf(first.id), "TAKEN", now + 100, now = now + 100)
        val rows = repo.dao.allIntakes()
        assertEquals(1, rows.count { it.decision == "TAKEN" })
        assertEquals(now, rows.first { it.id == first.id }.takenAt)
        assertTrue(rows.any { it.scheduled == first.scheduled && it.decision == null })
    }
    @Test fun editPreservesHistoryAndReplacesFuture() = runBlocking {
        seed()
        val later = now + 2 * 3_600_000
        val p = repo.dao.allPrescriptions().first()
        repo.save(p.copy(name = "Новое", dose = "2 таблетки", times = "20:00"), later)
        val rows = repo.dao.allIntakes()
        assertEquals("Лекарство", rows.first().name)
        assertTrue(rows.filter { it.scheduled > later }.all { it.name == "Новое" && it.dose == "2 таблетки" })
        assertFalse(rows.any { Instant.ofEpochMilli(it.scheduled).atZone(java.time.ZoneOffset.UTC).hour == 18 })
    }
    @Test fun archiveAndUndoUseCancelledStatus() = runBlocking {
        seed()
        val row = repo.dao.allIntakes().first()
        repo.mark(setOf(row.id), "TAKEN", now, now = now)
        repo.archive("rx", now)
        repo.mark(setOf(row.id), null, correction = true, now = now)
        assertTrue(repo.dao.allIntakes().all { it.decision == "CANCELLED" })
    }
    @Test fun deletionCascadesOnlySelectedProfile() = runBlocking {
        seed(); seed("other", "q")
        repo.dao.deleteProfile("p")
        assertEquals(listOf("q"), repo.dao.allPrescriptions().map { it.profileId })
        assertTrue(repo.dao.allIntakes().all { it.profileId == "q" })
    }
    @Test fun correctionAfter24HoursAndUndo() = runBlocking {
        seed(); val row = repo.dao.allIntakes().first(); val late = now + 3 * Schedule.DAY
        repo.mark(setOf(row.id), "TAKEN", row.scheduled, correction = true, now = late)
        assertEquals(Status.TAKEN, Schedule.status(repo.dao.allIntakes().first(), late))
        repo.mark(setOf(row.id), null, correction = true, now = late)
        assertEquals(Status.MISSED, Schedule.status(repo.dao.allIntakes().first(), late))
    }
    @Test fun futureCourseStillHasNextOccurrence() = runBlocking {
        seed(); val p = repo.dao.allPrescriptions().first()
        repo.save(p.copy(start = "2027-01-01"), now)
        assertTrue(repo.dao.allIntakes().isNotEmpty())
        assertEquals("2027-01-01", Instant.ofEpochMilli(repo.dao.allIntakes().first().scheduled).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString())
    }
}
