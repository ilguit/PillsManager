package com.palixander.pillsmanager

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class LocalizationTest {
    private fun resources(language: String): android.content.res.Resources {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(config).resources
    }

    @Test fun russianPluralRulesCoverTeensAndCompoundNumbers() {
        val res = resources("ru")
        for ((count, expected) in mapOf(0 to "курсов", 1 to "курс", 2 to "курса", 5 to "курсов", 11 to "курсов", 21 to "курс", 22 to "курса", 25 to "курсов", 111 to "курсов")) {
            assertTrue("count=$count", res.getQuantityString(R.plurals.active_courses, count, count).endsWith(expected))
        }
    }

    @Test fun formattingUsesRequestedLocaleWithoutChangingStoredDates() {
        val date = LocalDate.parse("2026-09-24")
        assertNotEquals(displayDate(date, resources("ru")), displayDate(date, resources("en-US")))
        assertEquals("2026-09-24", date.toString())
    }

    @Test fun errorsAreLocalizedWithoutExposingRawExceptionMessages() {
        val res = resources("ru")
        assertEquals(res.getString(R.string.future_actual_time), res.errorMessage(ValidationException(ValidationError.FUTURE_ACTUAL_TIME), R.string.save_failed))
        assertEquals(res.getString(R.string.file_failed), res.errorMessage(IllegalStateException("internal details"), R.string.file_failed))
    }

    @Test fun userContentIsInsertedLiterallyAndUnsupportedLanguagesHaveFallback() {
        val res = resources("ja")
        assertEquals("Удалить Анна %s?", res.getString(R.string.delete_profile_title, "Анна %s"))
        assertTrue(res.getString(R.string.reminder_title).isNotBlank())
        assertEquals("Активных курсов: 21", resources("en").getQuantityString(R.plurals.active_courses, 21, 21))
    }
}
