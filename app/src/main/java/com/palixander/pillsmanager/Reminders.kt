package com.palixander.pillsmanager

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PillsApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val repository by lazy { Repository(PillsDatabase.open(this)) }
    val reminders by lazy { Reminders(this, repository) }
    val operationLock = Mutex()
    suspend fun restoreBackup(backup: BackupData) = withContext(NonCancellable + Dispatchers.IO) {
        operationLock.withLock {
            repository.importBackup(backup)
            reminders.clearNotifications()
            reminders.reconcile(deliver = true, summary = true)
        }
    }
    // Finish scheduling even if the screen that initiated a save is destroyed.
    suspend fun update(deliver: Boolean = false, summary: Boolean = false, action: suspend () -> Unit = {}) = withContext(NonCancellable + Dispatchers.IO) { operationLock.withLock {
        repository.refresh()
        action()
        repository.refresh()
        reminders.reconcile(deliver, summary)
    } }
}
class Reminders(private val context: Context, private val repository: Repository) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    companion object { const val CHANNEL = "medication"; const val SUMMARY = "recovery" }
    fun clearNotifications() { notifications.cancelAll() }
    fun enabled(): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        notifications.areNotificationsEnabled() && notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun exact() = alarms.canScheduleExactAlarms()
    private fun alarmIntent() = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun medicationIntent() = PendingIntent.getBroadcast(context, 1, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun key(i: Intake) = "intake/${i.scheduled}"
    private fun notify(tag: String, scheduled: Long? = null) {
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("pills://intake/$tag")
            if (scheduled != null) putExtra("scheduled", scheduled)
        }
        val tap = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Пора принять лекарства").setContentIntent(tap).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_REMINDER)
            .setPublicVersion(Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Пора принять лекарства").build())
            .setTimeoutAfter(Schedule.DAY).build()
        try { notifications.notify(tag, 1, notification) } catch (_: SecurityException) { /* Permission may be revoked between check and delivery. */ }
    }
    suspend fun reconcile(deliver: Boolean, summary: Boolean) {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Приём лекарств", NotificationManager.IMPORTANCE_HIGH))
        val now = System.currentTimeMillis()
        val items = repository.dao.allIntakes()
        val waiting = items.filter { Schedule.status(it, now) == Status.WAITING }
        val groups = waiting.groupBy(::key)
        notifications.activeNotifications.forEach { n ->
            if ((n.tag == SUMMARY && waiting.isEmpty()) || (n.tag != SUMMARY && n.tag !in groups)) notifications.cancel(n.tag, n.id)
        }
        if (deliver && enabled()) {
            val fresh = waiting.filterNot { it.notified }
            if (summary && waiting.isNotEmpty()) {
                notifications.cancelAll()
                notify(SUMMARY)
                repository.dao.updateIntakes(waiting.map { it.copy(notified = true) })
            } else if (!summary) {
                fresh.groupBy(::key).forEach { (tag, list) -> notify(tag, list.first().scheduled) }
                repository.dao.updateIntakes(fresh.map { it.copy(notified = true) })
            }
        }
        // Keep the user-visible medication alarm independent of housekeeping wakeups.
        val medication = if (!deliver && enabled() && waiting.any { !it.notified }) now + 1_000
            else items.filter { Schedule.status(it, now) == Status.PLANNED }.minOfOrNull { it.scheduled }
        if (medication != null) {
            val show = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                if (exact()) alarms.setAlarmClock(AlarmManager.AlarmClockInfo(medication, show), medicationIntent())
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, medication, medicationIntent())
            } catch (_: SecurityException) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, medication, medicationIntent())
            }
        } else alarms.cancel(medicationIntent())
        val next = buildList {
            add(now + Schedule.DAY)
            waiting.minOfOrNull { it.scheduled + Schedule.DAY }?.let(::add)
        }.min()
        if (items.isNotEmpty()) {
            try {
                if (exact()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent())
                else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent())
            } catch (_: SecurityException) { alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, alarmIntent()) }
        } else alarms.cancel(alarmIntent())
    }
}
private fun BroadcastReceiver.runUpdate(context: Context, summary: Boolean) {
    val pending = goAsync()
    val app = context.applicationContext as PillsApp
    val lock = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PillsManager:receiver")
    lock.acquire(60_000)
    app.scope.launch {
        try { app.update(deliver = true, summary = summary) }
        finally { if (lock.isHeld) lock.release(); pending.finish() }
    }
}
class ReminderReceiver : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) = runUpdate(context, false) }
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runUpdate(context, intent.action == Intent.ACTION_BOOT_COMPLETED)
}
