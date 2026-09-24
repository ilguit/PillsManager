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
