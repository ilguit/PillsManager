package com.palixander.pillsmanager

import android.content.res.Resources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal class LocalizedException(val stringId: Int) : IllegalArgumentException()
internal fun Resources.errorMessage(error: Exception, fallback: Int): String = getString(when (error) {
    is LocalizedException -> error.stringId
    is ValidationException -> when (error.error) {
        ValidationError.FUTURE_ACTUAL_TIME -> R.string.future_actual_time
        ValidationError.END_BEFORE_START -> R.string.end_before_start
        ValidationError.NAME_DOSE_REQUIRED -> R.string.name_dose_required
        ValidationError.TIME_REQUIRED -> R.string.time_required
        ValidationError.INVALID_TIME -> R.string.invalid_time
        ValidationError.BACKUP_INVALID_IDS -> R.string.backup_invalid_ids
        ValidationError.BACKUP_INVALID -> R.string.backup_invalid
        ValidationError.BACKUP_UNSUPPORTED_VERSION -> R.string.backup_unsupported_version
        ValidationError.BACKUP_WRONG_FORMAT -> R.string.backup_wrong_format
        ValidationError.BACKUP_OBJECT_EXPECTED -> R.string.backup_object_expected
        ValidationError.BACKUP_TOO_LARGE -> R.string.backup_too_large
    }
    else -> fallback
})
internal fun Status.label(resources: Resources): String = resources.getString(when (this) {
    Status.PLANNED -> R.string.status_planned
    Status.WAITING -> R.string.status_waiting
    Status.TAKEN -> R.string.status_taken
    Status.MISSED -> R.string.status_missed
    Status.CANCELLED -> R.string.status_cancelled
})
internal fun displayDate(date: LocalDate, resources: Resources): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(resources.configuration.locales[0]))

internal fun displayDateTime(millis: Long, resources: Resources): String =
    java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(resources.configuration.locales[0]))
