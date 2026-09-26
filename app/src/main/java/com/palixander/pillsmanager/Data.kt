package com.palixander.pillsmanager

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.*
import java.util.UUID

@Entity data class Profile(@PrimaryKey val id: String = UUID.randomUUID().toString(), val name: String)
@Entity(foreignKeys = [ForeignKey(entity = Profile::class, parentColumns = ["id"], childColumns = ["profileId"], onDelete = ForeignKey.CASCADE)], indices = [Index("profileId")])
data class Prescription(
    @PrimaryKey val id: String = UUID.randomUUID().toString(), val profileId: String,
    val name: String, val dose: String, val times: String, val start: String, val end: String?,
    val zone: String, val archived: Boolean = false, val generatedUntil: Long,
    @ColumnInfo(defaultValue = "'QUIET'") val reminderLevel: String = Reminders.Level.QUIET.name,
    val reminderSound: String? = null
)
@Entity(foreignKeys = [ForeignKey(entity = Prescription::class, parentColumns = ["id"], childColumns = ["prescriptionId"], onDelete = ForeignKey.CASCADE)], indices = [Index("prescriptionId"), Index("profileId"), Index("scheduled")])
data class Intake(
    @PrimaryKey val id: String, val prescriptionId: String, val profileId: String,
    val name: String, val dose: String, val zone: String, val scheduled: Long,
    val decision: String? = null, val takenAt: Long? = null, val notified: Boolean = false
)
enum class Status { PLANNED, WAITING, TAKEN, MISSED, CANCELLED }
object Schedule {
    const val DAY = 86_400_000L
    fun status(i: Intake, now: Long): Status = when (i.decision) {
        "TAKEN" -> Status.TAKEN; "MISSED" -> Status.MISSED; "CANCELLED" -> Status.CANCELLED
        else -> if (now < i.scheduled) Status.PLANNED else if (now - i.scheduled >= DAY) Status.MISSED else Status.WAITING
    }
    fun generate(p: Prescription, until: Long): List<Intake> {
        if (p.archived || until <= p.generatedUntil) return emptyList()
        val zone = ZoneId.of(p.zone)
        var day = maxOf(LocalDate.parse(p.start), Instant.ofEpochMilli(p.generatedUntil).atZone(zone).toLocalDate())
        val last = minOf(p.end?.let(LocalDate::parse) ?: LocalDate.MAX, Instant.ofEpochMilli(until).atZone(zone).toLocalDate())
        val result = mutableListOf<Intake>()
        while (!day.isAfter(last)) {
            p.times.split(",").map(LocalTime::parse).distinct().sorted().forEach { time ->
                // atZone shifts DST gaps forward and chooses the first offset in overlaps.
                val instant = day.atTime(time).atZone(zone).toInstant().toEpochMilli()
                if (instant > p.generatedUntil && instant <= until) result += Intake("${p.id}:$instant", p.id, p.profileId, p.name, p.dose, p.zone, instant)
            }
            day = day.plusDays(1)
        }
        return result.distinctBy { it.id }
    }
    fun normalizeTimes(input: String): String = input.split(",", ";", " ").filter { it.isNotBlank() }
        .map { value ->
            validateInput(Regex("[0-9]{1,2}:[0-9]{2}").matches(value), ValidationError.INVALID_TIME)
            val parts = value.split(":")
            LocalTime.of(parts[0].toInt(), parts[1].toInt())
        }.distinct().sorted()
        .also { validateInput(it.isNotEmpty(), ValidationError.TIME_REQUIRED) }.joinToString(",")
}
@Dao interface PillsDao {
    @Query("SELECT * FROM Profile ORDER BY name") fun profiles(): Flow<List<Profile>>
    @Query("SELECT * FROM Prescription ORDER BY name") fun prescriptions(): Flow<List<Prescription>>
    @Query("SELECT * FROM Intake ORDER BY scheduled") fun intakes(): Flow<List<Intake>>
    @Query("SELECT * FROM Profile ORDER BY name") suspend fun allProfiles(): List<Profile>
    @Query("DELETE FROM Profile") suspend fun deleteAllProfiles()
    @Insert suspend fun restoreIntakes(items: List<Intake>)
    @Query("SELECT * FROM Prescription") suspend fun allPrescriptions(): List<Prescription>
    @Query("SELECT * FROM Intake ORDER BY scheduled") suspend fun allIntakes(): List<Intake>
    @Upsert suspend fun saveProfile(p: Profile)
    @Upsert suspend fun savePrescription(p: Prescription)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertIntakes(items: List<Intake>)
    @Update suspend fun updateIntakes(items: List<Intake>)
    @Query("DELETE FROM Profile WHERE id = :id") suspend fun deleteProfile(id: String)
    @Query("DELETE FROM Prescription WHERE id = :id") suspend fun deletePrescription(id: String)
    @Query("DELETE FROM Intake WHERE id = :id") suspend fun deleteIntake(id: String)
    @Query("DELETE FROM Intake WHERE prescriptionId = :id AND scheduled > :now AND decision IS NULL") suspend fun deleteFuture(id: String, now: Long)
}
@Database(entities = [Profile::class, Prescription::class, Intake::class], version = 3, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)])
abstract class PillsDatabase : RoomDatabase() {
    abstract fun dao(): PillsDao
    companion object { fun open(context: Context) = Room.databaseBuilder(context, PillsDatabase::class.java, "pills.db").build() }
}
class Repository(val db: PillsDatabase) {
    val dao = db.dao()
    suspend fun exportBackup(): BackupData = db.withTransaction {
        BackupData(dao.allProfiles(), dao.allPrescriptions(), dao.allIntakes())
    }
    suspend fun importBackup(backup: BackupData, now: Long = System.currentTimeMillis()) = db.withTransaction {
        BackupFormat.validate(backup)
        dao.deleteAllProfiles()
        backup.profiles.forEach { dao.saveProfile(it) }
        backup.prescriptions.forEach { dao.savePrescription(it) }
        dao.restoreIntakes(backup.intakes)
        refresh(now)
    }
    suspend fun refresh(now: Long = System.currentTimeMillis()) = db.withTransaction {
        dao.allPrescriptions().filterNot { it.archived }.forEach { p ->
            val firstDay = LocalDate.parse(p.start).atStartOfDay(ZoneId.of(p.zone)).toInstant().toEpochMilli()
            val until = maxOf(p.generatedUntil, now + 2 * Schedule.DAY, firstDay + Schedule.DAY)
            dao.insertIntakes(Schedule.generate(p, until))
            dao.savePrescription(p.copy(generatedUntil = until))
        }
    }
    suspend fun save(p: Prescription, now: Long = System.currentTimeMillis()) = db.withTransaction {
        validateInput(p.name.isNotBlank() && p.dose.isNotBlank(), ValidationError.NAME_DOSE_REQUIRED)
        val start = LocalDate.parse(p.start)
        validateInput(p.end == null || !LocalDate.parse(p.end).isBefore(start), ValidationError.END_BEFORE_START)
        ZoneId.of(p.zone)
        Reminders.Level.valueOf(p.reminderLevel)
        val times = Schedule.normalizeTimes(p.times)
        dao.deleteFuture(p.id, now)
        dao.savePrescription(p.copy(name = p.name.trim(), dose = p.dose.trim(), times = times, generatedUntil = now))
        refresh(now)
    }
    suspend fun archive(id: String, now: Long = System.currentTimeMillis()) = db.withTransaction {
        val p = dao.allPrescriptions().find { it.id == id } ?: return@withTransaction
        val own = dao.allIntakes().filter { it.prescriptionId == id }
        if (own.none { it.scheduled <= now || it.decision != null }) {
            dao.deletePrescription(id)
            return@withTransaction
        }
        dao.savePrescription(p.copy(archived = true))
        dao.updateIntakes(own.filter { Schedule.status(it, now) in listOf(Status.PLANNED, Status.WAITING) }.map { it.copy(decision = "CANCELLED") })
    }
    suspend fun mark(ids: Set<String>, decision: String?, actual: Long = System.currentTimeMillis(), correction: Boolean = false, now: Long = System.currentTimeMillis()) = db.withTransaction {
        require(decision == null || decision in listOf("TAKEN", "MISSED"))
        validateInput(decision != "TAKEN" || actual <= now, ValidationError.FUTURE_ACTUAL_TIME)
        val archived = dao.allPrescriptions().filter { it.archived }.map { it.id }.toSet()
        val all = dao.allIntakes()
        val nextByProfile = all.filter { Schedule.status(it, now) == Status.PLANNED }.groupBy { it.profileId }.mapValues { (_, v) -> v.minOf { it.scheduled } }
        dao.updateIntakes(all.filter { it.id in ids }.mapNotNull { i ->
            val s = Schedule.status(i, now)
            if (!correction && !(s == Status.WAITING || (s == Status.PLANNED && decision == "TAKEN" && i.scheduled == nextByProfile[i.profileId]))) return@mapNotNull null
            val value = if (decision == null && i.prescriptionId in archived) "CANCELLED" else decision
            i.copy(decision = value, takenAt = if (value == "TAKEN") actual else null)
        })
    }
}
