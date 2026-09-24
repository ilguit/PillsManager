package com.palixander.pillsmanager

// Stable codes; persistence and validation never depend on the interface language.
enum class ValidationError {
    FUTURE_ACTUAL_TIME,
    END_BEFORE_START,
    NAME_DOSE_REQUIRED,
    TIME_REQUIRED,
    INVALID_TIME,
    BACKUP_INVALID_IDS,
    BACKUP_INVALID,
    BACKUP_UNSUPPORTED_VERSION,
    BACKUP_WRONG_FORMAT,
    BACKUP_OBJECT_EXPECTED,
    BACKUP_TOO_LARGE,
}
class ValidationException(val error: ValidationError, cause: Throwable? = null) : IllegalArgumentException(error.name, cause)
internal fun validateInput(condition: Boolean, error: ValidationError) {
    if (!condition) throw ValidationException(error)
}
