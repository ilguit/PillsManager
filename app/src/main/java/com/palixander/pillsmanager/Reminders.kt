package com.palixander.pillsmanager

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.media.AudioAttributes
import android.media.RingtoneManager
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
            reminders.afterRestore()
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
    enum class Level { QUIET, NOTICEABLE, ALARM }
    companion object {
        const val CHANNEL = "medication_quiet"
        const val NOTICEABLE_CHANNEL = "medication_noticeable"
        const val ALARM_CHANNEL = "medication_alarm"
        const val SUMMARY = "recovery"
        private const val PREFS = "reminder_preferences"
        private const val SNOOZE_PREFIX = "snooze/"
        const val SNOOZE_MINUTES = 10L
    }
    private val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    suspend fun afterRestore() {
        val now = System.currentTimeMillis()
        // Notification delivery belongs to this installation, not the source backup.
        repository.dao.updateIntakes(repository.dao.allIntakes()
            .filter { Schedule.status(it, now) == Status.WAITING }
            .map { it.copy(notified = false) })
        notifications.cancelAll()
        reconcile(deliver = true, summary = true)
    }
    private fun channel(level: Level) = when (level) { Level.QUIET -> CHANNEL; Level.NOTICEABLE -> NOTICEABLE_CHANNEL; Level.ALARM -> ALARM_CHANNEL }
    private fun channel(level: Level, sound: String?): String {
        if (level == Level.QUIET || sound == null) return channel(level)
        return "${channel(level)}_${Integer.toUnsignedString(sound.hashCode(), 16)}"
    }
    fun enabled(): Boolean = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        notifications.areNotificationsEnabled()
    fun canUseFullScreen(): Boolean = android.os.Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent()
    fun exact() = alarms.canScheduleExactAlarms()
    private fun alarmIntent() = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun medicationIntent() = PendingIntent.getBroadcast(context, 1, Intent(context, ReminderReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun key(i: Intake) = "intake/${i.scheduled}"
    private fun createChannels() {
        val quiet = NotificationChannel(CHANNEL, context.getString(R.string.reminder_channel_quiet), NotificationManager.IMPORTANCE_LOW).apply {
            description = context.getString(R.string.reminder_level_quiet_description)
            setSound(null, null); enableVibration(false)
        }
        val noticeable = NotificationChannel(NOTICEABLE_CHANNEL, context.getString(R.string.reminder_channel_noticeable), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.reminder_level_noticeable_description)
            enableVibration(true)
        }
        val alarm = NotificationChannel(ALARM_CHANNEL, context.getString(R.string.reminder_channel_alarm), NotificationManager.IMPORTANCE_HIGH).apply {
            description = context.getString(R.string.reminder_level_alarm_description)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM), AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        notifications.createNotificationChannels(listOf(quiet, noticeable, alarm))
    }
    private fun createSoundChannel(level: Level, sound: String?): String {
        val id = channel(level, sound)
        if (sound == null || level == Level.QUIET || notifications.getNotificationChannel(id) != null) return id
        val name = if (level == Level.ALARM) context.getString(R.string.reminder_channel_alarm) else context.getString(R.string.reminder_channel_noticeable)
        val usage = if (level == Level.ALARM) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_NOTIFICATION
        notifications.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply {
            description = if (level == Level.ALARM) context.getString(R.string.reminder_level_alarm_description) else context.getString(R.string.reminder_level_noticeable_description)
            setSound(Uri.parse(sound), AudioAttributes.Builder().setUsage(usage).build())
            enableVibration(true); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        return id
    }
    private fun snoozeKey(scheduled: Long) = "$SNOOZE_PREFIX$scheduled"
    private fun snoozedUntil(scheduled: Long) = preferences.getLong(snoozeKey(scheduled), 0L)
    fun silence(scheduled: Long?) { if (scheduled != null) notifications.cancel("intake/$scheduled", 1) else notifications.cancelAll() }
    suspend fun snooze(scheduled: Long) {
        silence(scheduled)
        val until = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000
        preferences.edit().putLong(snoozeKey(scheduled), until).apply()
        repository.dao.updateIntakes(repository.dao.allIntakes().filter { it.scheduled == scheduled && Schedule.status(it, System.currentTimeMillis()) == Status.WAITING }.map { it.copy(notified = false) })
        reconcile(deliver = false, summary = false)
    }
    private fun notify(tag: String, scheduled: Long? = null, requestedLevel: Level = Level.QUIET, sound: String? = null) {
        val actualLevel = if (tag == SUMMARY) Level.QUIET else requestedLevel
        val channelId = createSoundChannel(actualLevel, if (tag == SUMMARY) null else sound)
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse("pills://intake/$tag")
            if (scheduled != null) putExtra("scheduled", scheduled)
            putExtra("alarm", actualLevel == Level.ALARM)
        }
        val tap = PendingIntent.getActivity(context, tag.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.reminder_title)).setContentIntent(tap).setAutoCancel(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_REMINDER)
            .setPublicVersion(Notification.Builder(context, channelId).setSmallIcon(R.drawable.ic_notification).setContentTitle(context.getString(R.string.reminder_title)).build())
            .setTimeoutAfter(Schedule.DAY)
        if (actualLevel == Level.ALARM && scheduled != null) {
            val snooze = Intent(context, SnoozeReceiver::class.java).apply { data = Uri.parse("pills://snooze/$scheduled"); putExtra("scheduled", scheduled) }
            val snoozeAction = PendingIntent.getBroadcast(context, tag.hashCode(), snooze, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setCategory(Notification.CATEGORY_ALARM).setOngoing(true).setAutoCancel(false)
                .setFullScreenIntent(tap, true).addAction(0, context.getString(R.string.snooze_ten_minutes), snoozeAction)
        }
        val notification = builder.build().apply { if (actualLevel == Level.ALARM) flags = flags or Notification.FLAG_INSISTENT or Notification.FLAG_NO_CLEAR }
        try { notifications.notify(tag, 1, notification) } catch (_: SecurityException) { /* Permission may be revoked between check and delivery. */ }
    }
    suspend fun reconcile(deliver: Boolean, summary: Boolean) {
        createChannels()
        val now = System.currentTimeMillis()
        val items = repository.dao.allIntakes()
        val prescriptions = repository.dao.allPrescriptions().associateBy { it.id }
        val waiting = items.filter { Schedule.status(it, now) == Status.WAITING }
        val groups = waiting.groupBy(::key)
        notifications.activeNotifications.forEach { n ->
            if ((n.tag == SUMMARY && waiting.isEmpty()) || (n.tag != SUMMARY && n.tag !in groups)) notifications.cancel(n.tag, n.id)
        }
        if (deliver && enabled()) {
            val fresh = waiting.filterNot { it.notified }.filter { snoozedUntil(it.scheduled) <= now }
            if (summary && waiting.isNotEmpty()) {
                notifications.cancelAll()
                notify(SUMMARY)
                repository.dao.updateIntakes(waiting.map { it.copy(notified = true) })
            } else if (!summary) {
                fresh.groupBy(::key).forEach { (tag, list) ->
                    val selected = list.mapNotNull { prescriptions[it.prescriptionId] }
                        .maxWithOrNull(compareBy<Prescription> { runCatching { Level.valueOf(it.reminderLevel) }.getOrDefault(Level.QUIET) }.thenBy { it.id })
                    notify(tag, list.first().scheduled, selected?.let { runCatching { Level.valueOf(it.reminderLevel) }.getOrDefault(Level.QUIET) } ?: Level.QUIET, selected?.reminderSound)
                }
                repository.dao.updateIntakes(fresh.map { it.copy(notified = true) })
            }
        }
        // Keep the user-visible medication alarm independent of housekeeping wakeups.
        val readyWaiting = waiting.filter { !it.notified && snoozedUntil(it.scheduled) <= now }
        val snoozed = waiting.filter { !it.notified }.map { snoozedUntil(it.scheduled) }.filter { it > now }
        val medication = listOfNotNull(
            if (!deliver && enabled() && readyWaiting.isNotEmpty()) now + 1_000 else null,
            items.filter { Schedule.status(it, now) == Status.PLANNED }.minOfOrNull { it.scheduled },
            snoozed.minOrNull()
        ).minOrNull()
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
class SnoozeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val scheduled = intent.getLongExtra("scheduled", Long.MIN_VALUE)
        if (scheduled == Long.MIN_VALUE) return
        val pending = goAsync()
        val app = context.applicationContext as PillsApp
        app.scope.launch { try { app.operationLock.withLock { app.reminders.snooze(scheduled) } } finally { pending.finish() } }
    }
}
class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = runUpdate(context, intent.action == Intent.ACTION_BOOT_COMPLETED)
}
