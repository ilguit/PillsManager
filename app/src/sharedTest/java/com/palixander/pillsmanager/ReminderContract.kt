package com.palixander.pillsmanager

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

abstract class ReminderContract {
    abstract fun grant(context: Context)
    private lateinit var context: Context
    private lateinit var db: PillsDatabase
    private lateinit var repo: Repository
    private lateinit var reminders: Reminders
    private lateinit var manager: NotificationManager
    @Before fun setup() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        context = instrumentation.targetContext
        grant(context)
        db = androidx.room.Room.inMemoryDatabaseBuilder(context, PillsDatabase::class.java).build()
        repo = Repository(db); reminders = Reminders(context, repo)
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        repo.dao.saveProfile(Profile("notification-test", "Секретное имя"))
        repo.dao.savePrescription(Prescription("rx", "notification-test", "Секретное лекарство", "Секретная доза", "09:00", "2026-01-01", null, "UTC", generatedUntil = System.currentTimeMillis()))
    }
    @After fun close() { manager.cancelAll(); db.close() }
    private suspend fun seed(): List<Intake> {
        val due = System.currentTimeMillis() - 60_000
        val rows = listOf("a", "b").map { Intake(it, "rx", "notification-test", "Секретное лекарство", "1 таблетка", "UTC", due) }
        repo.dao.insertIntakes(rows); return rows
    }
    private fun active() = manager.activeNotifications
    @Test fun groupPrivacyPartialConfirmationAndNoRepeat() = runBlocking {
        val rows = seed(); reminders.reconcile(true, false)
        assertEquals(1, active().size)
        assertEquals("Пора принять лекарства", active().first().notification.extras.getString("android.title"))
        assertNull(active().first().notification.extras.getString("android.text"))
        assertTrue(repo.dao.allIntakes().all { it.notified })
        repo.mark(setOf(rows.first().id), "TAKEN")
        reminders.reconcile(false, false); assertEquals(1, active().size)
        manager.cancelAll(); reminders.reconcile(true, false); assertEquals(0, active().size)
        repo.mark(setOf(rows.last().id), "MISSED")
        reminders.reconcile(true, false); assertEquals(0, active().size)
    }
    @Test fun rebootSummaryAndDeletionClearNotifications() = runBlocking {
        seed(); reminders.reconcile(true, true)
        assertEquals(Reminders.SUMMARY, active().single().tag)
        repo.dao.deleteProfile("notification-test")
        reminders.reconcile(false, false)
        assertTrue(active().isEmpty())
    }
    @Test fun coincidentProfilesShareOneNotification() = runBlocking {
        val due = System.currentTimeMillis() - 60_000
        repo.dao.saveProfile(Profile("other-profile", "Другой профиль"))
        repo.dao.savePrescription(Prescription("other-rx", "other-profile", "Другое лекарство", "1 таблетка", "09:00", "2026-01-01", null, "UTC", generatedUntil = System.currentTimeMillis()))
        repo.dao.insertIntakes(listOf(
            Intake("first-profile", "rx", "notification-test", "Первое", "1 таблетка", "UTC", due),
            Intake("second-profile", "other-rx", "other-profile", "Второе", "1 таблетка", "UTC", due)
        ))

        reminders.reconcile(true, false)

        assertEquals(1, active().size)
        assertEquals("intake/$due", active().single().tag)
    }
    @Test fun restoredWaitingIntakesNotifyEvenIfDeliveredInSourceApp() = runBlocking {
        val rows = seed()
        repo.dao.updateIntakes(rows.map { it.copy(notified = true) })
        reminders.afterRestore()
        assertEquals(Reminders.SUMMARY, active().single().tag)
        assertTrue(repo.dao.allIntakes().all { it.notified })
        manager.cancelAll()
        reminders.reconcile(true, false)
        assertTrue(active().isEmpty())
    }
    @Test fun expiredNeverNotifies() = runBlocking {
        repo.dao.insertIntakes(listOf(Intake("old", "rx", "notification-test", "Имя", "Доза", "UTC", System.currentTimeMillis() - Schedule.DAY)))
        reminders.reconcile(true, true)
        assertTrue(active().isEmpty())
    }
}
