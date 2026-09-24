package com.palixander.pillsmanager

import android.app.NotificationManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Requires explicitly enabled notification and exact-alarm permissions on a test install. */
@RunWith(AndroidJUnit4::class)
class PhysicalAlarmTest {
    @Test fun operatingSystemDeliversReminder() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("physicalAlarm") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PillsApp
        assertTrue(app.reminders.enabled()); assertTrue(app.reminders.exact())
        val manager = app.getSystemService(NotificationManager::class.java)
        val scheduled = System.currentTimeMillis() + 10_000
        val profile = "qa-physical-alarm"
        val tag = "$profile/$scheduled"
        try {
            app.update {
                app.repository.dao.deleteProfile(profile)
                app.repository.dao.saveProfile(Profile(profile, "Проверка уведомления"))
                app.repository.dao.savePrescription(Prescription("qa-alarm-rx", profile, "Тест", "1", "09:00", LocalDate.now().toString(), null, ZoneId.systemDefault().id, generatedUntil = scheduled + 2 * Schedule.DAY))
                app.repository.dao.insertIntakes(listOf(Intake("qa-alarm-intake", "qa-alarm-rx", profile, "Тест", "1", ZoneId.systemDefault().id, scheduled)))
            }
            val deadline = System.currentTimeMillis() + 35_000
            while (manager.activeNotifications.none { it.tag == tag } && System.currentTimeMillis() < deadline) delay(500)
            assertTrue("AlarmManager did not deliver the reminder", manager.activeNotifications.any { it.tag == tag })
            assertEquals(app.getString(R.string.reminder_title), manager.activeNotifications.first { it.tag == tag }.notification.extras.getString("android.title"))
        } finally {
            app.update { app.repository.dao.deleteProfile(profile) }
            manager.cancel(tag, 1)
        }
    }
}
