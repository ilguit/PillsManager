package com.palixander.pillsmanager

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*

/** Explicit opt-in fixtures for visual device QA; never alters existing profiles. */
@RunWith(AndroidJUnit4::class)
class DeviceFixtureTest {
    @Test fun fixture() = runBlocking {
        val mode = InstrumentationRegistry.getArguments().getString("fixture")
        assumeTrue(mode == "seed" || mode == "clear")
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PillsApp
        app.update {
            listOf("qa-anna", "qa-boris").forEach { app.repository.dao.deleteProfile(it) }
            if (mode == "seed") {
                val now = System.currentTimeMillis()
                val zone = ZoneId.systemDefault()
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
                app.repository.dao.saveProfile(Profile("qa-anna", "Проверка · Анна"))
                app.repository.dao.saveProfile(Profile("qa-boris", "Проверка · Борис"))
                val entries = listOf(
                    Triple("qa-a", "qa-anna", "Альфа (тест)"),
                    Triple("qa-b", "qa-anna", "Бета (тест)"),
                    Triple("qa-c", "qa-boris", "Гамма (тест)")
                )
                val before = now - 10 * 60_000
                val future = now + 60 * 60_000
                entries.forEach { (id, profile, name) ->
                    app.repository.dao.savePrescription(Prescription(id, profile, name, "1 таблетка", "09:00,18:00", today, today, zone.id, generatedUntil = now + 2 * Schedule.DAY))
                    app.repository.dao.insertIntakes(listOf(
                        Intake("$id:past", id, profile, name, "1 таблетка", zone.id, before,
                            decision = if (id == "qa-a") "TAKEN" else null, takenAt = if (id == "qa-a") before + 60_000 else null),
                        Intake("$id:future", id, profile, name, "1 таблетка", zone.id, future)
                    ))
                }
            }
        }
    }
}
