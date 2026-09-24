package com.palixander.pillsmanager

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Portable format: deliberately independent of the installed application ID. */
data class BackupData(val profiles: List<Profile>, val prescriptions: List<Prescription>, val intakes: List<Intake>)

object BackupFormat {
    const val MAX_BYTES = 32 * 1024 * 1024
    private const val FORMAT = "pillsmanager-backup"
    private fun obj(vararg fields: Pair<String, Any?>) = JSONObject().apply {
        fields.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
    }
    fun encode(data: BackupData): String {
        validate(data)
        return obj(
            "format" to FORMAT, "version" to 1,
            "profiles" to JSONArray(data.profiles.map { obj("id" to it.id, "name" to it.name) }),
            "prescriptions" to JSONArray(data.prescriptions.map { obj(
                "id" to it.id, "profileId" to it.profileId, "name" to it.name, "dose" to it.dose,
                "times" to it.times, "start" to it.start, "end" to it.end, "zone" to it.zone,
                "archived" to it.archived, "generatedUntil" to it.generatedUntil
            ) }),
            "intakes" to JSONArray(data.intakes.map { obj(
                "id" to it.id, "prescriptionId" to it.prescriptionId, "profileId" to it.profileId,
                "name" to it.name, "dose" to it.dose, "zone" to it.zone, "scheduled" to it.scheduled,
                "decision" to it.decision, "takenAt" to it.takenAt, "notified" to it.notified
            ) })
        ).toString()
    }
    fun read(input: InputStream): BackupData {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        require(bytes.size <= MAX_BYTES) { "Файл слишком большой (максимум 32 МБ)" }
        return decode(bytes.toString(Charsets.UTF_8))
    }
    fun decode(text: String): BackupData {
        try {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val parser = JSONTokener(text)
            val root = parser.nextValue() as? JSONObject ?: error("Ожидается объект")
            require(parser.nextClean() == '\u0000')
            require(root.string("format") == FORMAT) { "Это не файл экспорта Лекарств" }
            require(root.number("version") == 1L) { "Эта версия файла не поддерживается" }
            val data = BackupData(
                root.rows("profiles") { Profile(it.string("id"), it.string("name")) },
                root.rows("prescriptions") { Prescription(
                    it.string("id"), it.string("profileId"), it.string("name"), it.string("dose"),
                    it.string("times"), it.string("start"), it.nullableString("end"), it.string("zone"),
                    it.boolean("archived"), it.number("generatedUntil")
                ) },
                root.rows("intakes") { Intake(
                    it.string("id"), it.string("prescriptionId"), it.string("profileId"), it.string("name"),
                    it.string("dose"), it.string("zone"), it.number("scheduled"), it.nullableString("decision"),
                    if (it.get("takenAt") == JSONObject.NULL) null else it.number("takenAt"), it.boolean("notified")
                ) }
            )
            validate(data)
            return data
        } catch (e: Exception) {
            throw IllegalArgumentException("Не удалось прочитать файл: неверный формат, версия или повреждённые данные", e)
        }
    }
    private fun JSONObject.string(key: String): String = (get(key) as? String ?: error(key)).also { require(it.length <= 10000) }
    private fun JSONObject.nullableString(key: String): String? = if (get(key) == JSONObject.NULL) null else string(key)
    private fun JSONObject.boolean(key: String): Boolean = get(key) as? Boolean ?: error(key)
    private fun JSONObject.number(key: String): Long = when (val value = get(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> error(key)
    }
    private fun <T> JSONObject.rows(key: String, parse: (JSONObject) -> T): List<T> {
        val array = getJSONArray(key)
        require(array.length() <= 200000)
        return List(array.length()) { parse(array.getJSONObject(it)) }
    }
    fun validate(data: BackupData) {
        fun ids(values: List<String>) {
            require(values.all { it.isNotBlank() } && values.distinct().size == values.size) { "Повторяющиеся или пустые идентификаторы" }
        }
        ids(data.profiles.map { it.id }); ids(data.prescriptions.map { it.id }); ids(data.intakes.map { it.id })
        val profiles = data.profiles.map { it.id }.toSet()
        val prescriptions = data.prescriptions.associateBy { it.id }
        data.profiles.forEach { require(it.name.isNotBlank()) }
        data.prescriptions.forEach {
            require(it.profileId in profiles && it.name.isNotBlank() && it.dose.isNotBlank())
            val start = LocalDate.parse(it.start)
            require(it.end == null || !LocalDate.parse(it.end).isBefore(start))
            ZoneId.of(it.zone)
            Schedule.normalizeTimes(it.times)
            // The scheduler reads canonical ISO times directly.
            it.times.split(",").forEach(java.time.LocalTime::parse)
            Instant.ofEpochMilli(it.generatedUntil).atZone(ZoneId.of(it.zone))
        }
        data.intakes.forEach {
            val prescription = prescriptions[it.prescriptionId]
            require(prescription != null && it.profileId == prescription.profileId)
            require(it.id == "${it.prescriptionId}:${it.scheduled}")
            require(it.name.isNotBlank() && it.dose.isNotBlank())
            ZoneId.of(it.zone)
            require(it.decision == null || it.decision in setOf("TAKEN", "MISSED", "CANCELLED"))
            require((it.decision == "TAKEN") == (it.takenAt != null))
            require(it.scheduled <= prescription.generatedUntil)
        }
    }
}
