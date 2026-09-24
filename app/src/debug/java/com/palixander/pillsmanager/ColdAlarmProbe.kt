package com.palixander.pillsmanager

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Shell-only debug probe; no instrumentation process remains alive during delivery. */
class ColdAlarmProbe : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as PillsApp
        app.scope.launch {
            try {
                val id = "debug-cold-alarm-probe"
                app.update {
                    app.repository.dao.deleteProfile(id)
                    if (!intent.getBooleanExtra("cleanup", false)) {
                        val due = System.currentTimeMillis() + 60_000
                        app.repository.dao.saveProfile(Profile(id, "Проверка фонового напоминания"))
                        app.repository.dao.savePrescription(Prescription(id, id, "Тест", "1", "09:00", LocalDate.now().toString(), null, "UTC", generatedUntil = due + 3 * Schedule.DAY))
                        app.repository.dao.insertIntakes(listOf(Intake(id, id, id, "Тест", "1", "UTC", due)))
                        Log.i("ColdAlarmProbe", "scheduled=$due")
                    }
                }
                Log.i("ColdAlarmProbe", "ready")
            } finally { pending.finish() }
        }
    }
}
