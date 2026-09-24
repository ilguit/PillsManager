package com.palixander.pillsmanager

import android.Manifest
import android.app.Application
import android.content.Context
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ReminderHostTest : ReminderContract() {
    override fun grant(context: Context) { shadowOf(context.applicationContext as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) }
}
@RunWith(RobolectricTestRunner::class)
class ActivityHostTest {
    @Test fun mainScreenLaunches() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().visible().get()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertFalse(activity.isFinishing)
            assertTrue(activity.findViewById<android.view.ViewGroup>(android.R.id.content).childCount > 0)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class RestoredNotificationHostTest {
    @Test fun permissionGrantedAfterImportStillDeliversWaitingIntake() = kotlinx.coroutines.runBlocking {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val application = context.applicationContext as Application
        val db = androidx.room.Room.inMemoryDatabaseBuilder(context, PillsDatabase::class.java).build()
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        try {
            val repo = Repository(db)
            val now = System.currentTimeMillis()
            val time = now - 60_000
            repo.dao.saveProfile(Profile("p", "Профиль"))
            repo.dao.savePrescription(Prescription("r", "p", "Препарат", "1", "09:00", "2026-01-01", null, "UTC", generatedUntil = now))
            repo.dao.insertIntakes(listOf(Intake("r:$time", "r", "p", "Препарат", "1", "UTC", time, notified = true)))
            shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            val reminders = Reminders(context, repo)
            reminders.afterRestore()
            assertTrue(manager.activeNotifications.isEmpty())
            assertFalse(repo.dao.allIntakes().single().notified)
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            reminders.reconcile(true, false)
            assertEquals(1, manager.activeNotifications.size)
        } finally {
            manager.cancelAll()
            shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            db.close()
        }
    }
}
