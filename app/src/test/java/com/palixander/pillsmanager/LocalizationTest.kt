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
        assertEquals("Delete Анна %s?", res.getString(R.string.delete_profile_title, "Анна %s"))
        assertTrue(res.getString(R.string.reminder_title).isNotBlank())
        assertEquals("21 active courses", res.getQuantityString(R.plurals.active_courses, 21, 21))
    }

    @Test fun supportedLanguagesResolveTranslatedResources() {
        for ((language, close) in mapOf("ru" to "Закрыть", "en" to "Close", "es" to "Cerrar", "pt-BR" to "Fechar", "be" to "Закрыць", "uk" to "Закрити")) {
            val res = resources(language)
            assertEquals(language, close, res.getString(R.string.close))
            val summary = res.getString(R.string.import_body, 2, 3, 1, 42)
            assertTrue(language, summary.contains("42"))
            assertFalse(language, summary.contains("%"))
            assertTrue(language, summary.contains("\n\n"))
        }
    }

    @Test fun translatedPluralRulesCoverZeroTeensAndCompoundNumbers() {
        val endings = mapOf(
            "en" to listOf("courses", "course", "courses", "courses", "courses", "courses", "courses", "courses", "courses"),
            "es" to listOf("activos", "activo", "activos", "activos", "activos", "activos", "activos", "activos", "activos"),
            "pt-BR" to listOf("ativo", "ativo", "ativos", "ativos", "ativos", "ativos", "ativos", "ativos", "ativos"),
            "be" to listOf("курсаў", "курс", "курсы", "курсаў", "курсаў", "курс", "курсы", "курсаў", "курсаў"),
            "uk" to listOf("курсів", "курс", "курси", "курсів", "курсів", "курс", "курси", "курсів", "курсів"),
        )
        for ((language, expected) in endings) {
            val res = resources(language)
            for ((count, ending) in listOf(0, 1, 2, 5, 11, 21, 22, 25, 111).zip(expected)) {
                assertTrue("$language count=$count", res.getQuantityString(R.plurals.active_courses, count, count).endsWith(ending))
            }
        }
    }
}
