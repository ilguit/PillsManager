package com.palixander.pillsmanager

import java.time.LocalTime
import java.util.Locale

/** A daily row can refer to the same course as another time in the list. */
data class PrescriptionTime(val prescription: Prescription, val time: LocalTime)
data class TimelineGroup(val profileId: String, val scheduled: Long, val rows: List<Intake>)

object Presentation {
    fun prescriptionTimes(items: List<Prescription>): List<PrescriptionTime> = items
        .flatMap { p -> p.times.split(",").map(LocalTime::parse).distinct().map { PrescriptionTime(p, it) } }
        .sortedWith(compareBy<PrescriptionTime> { it.time }
            .thenBy { it.prescription.name.lowercase(Locale.ROOT) }.thenBy { it.prescription.id })

    fun timeline(items: List<Intake>, profiles: List<Profile>, now: Long): List<TimelineGroup> {
        val selected = items.groupBy { it.profileId }.values.flatMap { own ->
            val last = own.filter { Schedule.status(it, now) == Status.TAKEN }.maxByOrNull { it.takenAt ?: Long.MIN_VALUE }
            val next = own.filter { Schedule.status(it, now) == Status.PLANNED }.minOfOrNull { it.scheduled }
            own.filter {
                Schedule.status(it, now) == Status.WAITING ||
                    (Schedule.status(it, now) == Status.TAKEN && it.scheduled == last?.scheduled) ||
                    (Schedule.status(it, now) == Status.PLANNED && it.scheduled == next)
            }
        }
        val names = profiles.associate { it.id to it.name.lowercase(Locale.ROOT) }
        return selected.groupBy { it.profileId to it.scheduled }.map { (key, rows) ->
            TimelineGroup(key.first, key.second, rows.sortedWith(compareBy<Intake> { it.name.lowercase(Locale.ROOT) }.thenBy { it.id }))
        }.sortedWith(compareBy<TimelineGroup> { it.scheduled }.thenBy { names[it.profileId] ?: "" }.thenBy { it.profileId })
    }
}
