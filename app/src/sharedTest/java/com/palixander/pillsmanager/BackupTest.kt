package com.palixander.pillsmanager

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BackupTest {
    private lateinit var db: PillsDatabase
    private lateinit var repo: Repository
    private val now = Instant.parse("2026-09-24T08:00:00Z").toEpochMilli()
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PillsDatabase::class.java).build()
        repo = Repository(db)
    }
    @After fun close() { db.close() }
    private fun fixture(): BackupData {
        val active = Prescription("active", "p", "Таблетки Ω", "½ таблетки", "09:00,18:00", "2026-09-01", null, "Asia/Yekaterinburg", generatedUntil = now + 3 * Schedule.DAY)
        val archived = active.copy(id = "archived", profileId = "q", archived = true, end = "2026-09-23")
        val rows = listOf(null, "TAKEN", "MISSED", "CANCELLED").mapIndexed { index, decision ->
            val time = now - index * 3600000L
            val p = if (decision == "CANCELLED") archived else active
            Intake("${p.id}:$time", p.id, p.profileId, "Прежнее название", "1 таблетка", p.zone, time,
                decision, if (decision == "TAKEN") time + 1000 else null, notified = index % 2 == 0)
        } + Intake("active:${now + Schedule.DAY}", "active", "p", active.name, active.dose, active.zone, now + Schedule.DAY)
        val archivedHistory = listOf("TAKEN", "MISSED").mapIndexed { index, decision ->
            val time = now - (index + 2) * Schedule.DAY
            Intake("archived:$time", "archived", "q", "Старый архивный препарат", "2 таблетки", "UTC", time,
                decision, if (decision == "TAKEN") time else null, notified = true)
        }
        return BackupData(listOf(Profile("p", "Александр"), Profile("q", "Архив")), listOf(active, archived), rows + archivedHistory)
    }
    private fun assertSame(expected: BackupData, actual: BackupData) {
        assertEquals(expected.profiles.toSet(), actual.profiles.toSet())
        assertEquals(expected.prescriptions.toSet(), actual.prescriptions.toSet())
        assertEquals(expected.intakes.toSet(), actual.intakes.toSet())
    }
    @Test fun portableRoundTripIncludesArchiveHistoryAndNullableFields() = runBlocking {
        val data = fixture()
        val encoded = BackupFormat.encode(data)
        assertFalse(encoded.contains("com.palixander"))
        assertFalse(encoded.contains("ru.pillsmanager"))
        val decoded = BackupFormat.read(encoded.byteInputStream())
        assertEquals(data, decoded)
        repo.importBackup(decoded, now)
        assertSame(data, repo.exportBackup())
    }
    @Test fun restoreReplacesExistingDataAndIsRepeatable() = runBlocking {
        repo.dao.saveProfile(Profile("old", "Удаляемый"))
        val data = fixture()
        repeat(2) { repo.importBackup(data, now) }
        assertSame(data, repo.exportBackup())
    }
    @Test fun invalidReferencesLeaveDatabaseUntouched() = runBlocking {
        val data = fixture()
        repo.importBackup(data, now)
        val invalid = data.copy(intakes = data.intakes.map { it.copy(profileId = "missing") })
        try { repo.importBackup(invalid, now); fail("Must reject") } catch (_: IllegalArgumentException) { }
        assertSame(data, repo.exportBackup())
    }
    @Test fun failedInsertRollsBackDeletionAndAllWrites() = runBlocking {
        val data = fixture()
        repo.importBackup(data, now)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_import BEFORE INSERT ON Intake WHEN NEW.name = 'reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val invalid = data.copy(intakes = data.intakes.map { it.copy(name = "reject") })
        try { repo.importBackup(invalid, now); fail("Must reject") } catch (_: android.database.sqlite.SQLiteException) { }
        assertSame(data, repo.exportBackup())
    }
    @Test fun rejectsUnknownVersionMissingFieldsBadTypesAndDuplicates() {
        val encoded = BackupFormat.encode(fixture())
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("version", 4) }, { it.remove("intakes") },
            { it.getJSONArray("profiles").put(it.getJSONArray("profiles").getJSONObject(0)) },
            { it.getJSONArray("prescriptions").getJSONObject(0).put("archived", "false") },
            { it.getJSONArray("prescriptions").getJSONObject(0).put("zone", "invalid") },
            { it.getJSONArray("intakes").getJSONObject(0).put("decision", "UNKNOWN") },
            { it.getJSONArray("intakes").getJSONObject(0).put("scheduled", 1.5) }
        )
        mutations.forEach { change ->
            val json = JSONObject(encoded); change(json)
            try { BackupFormat.decode(json.toString()); fail("Must reject") } catch (_: IllegalArgumentException) { }
        }
        listOf("{", encoded + "garbage").forEach {
            try { BackupFormat.decode(it); fail("Must reject") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun refreshAfterImportExtendsOnlyActiveCourses() = runBlocking {
        val data = fixture()
        repo.importBackup(data, now + 5 * Schedule.DAY)
        val result = repo.exportBackup()
        assertTrue(result.intakes.containsAll(data.intakes))
        assertTrue(result.intakes.size > data.intakes.size)
        assertEquals(data.intakes.filter { it.prescriptionId == "archived" }.toSet(), result.intakes.filter { it.prescriptionId == "archived" }.toSet())
    }
    @Test fun emptyBackupIsAnExplicitFullReplacement() = runBlocking {
        repo.importBackup(fixture(), now)
        val empty = BackupData(emptyList(), emptyList(), emptyList())
        repo.importBackup(BackupFormat.decode(BackupFormat.encode(empty)), now)
        assertSame(empty, repo.exportBackup())
    }
}
