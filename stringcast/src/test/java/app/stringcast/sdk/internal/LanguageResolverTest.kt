package app.stringcast.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageResolverTest {

    private val project = listOf("en", "es", "es-MX", "pt-BR", "zh-Hans", "zh-Hant", "fr")

    private fun resolve(vararg preferred: String, override: String? = null, available: List<String> = project, base: String = "en") =
        LanguageResolver.resolve(override, preferred.toList(), available, base)

    @Test fun normalizesCaseAndUnderscore() {
        assertEquals("pt-BR", LanguageResolver.normalize("pt_br"))
        assertEquals("zh-Hans-CN", LanguageResolver.normalize("ZH_hans_cn"))
        assertEquals("es-419", LanguageResolver.normalize("es-419"))
        assertEquals("he", LanguageResolver.normalize("iw"))
        assertEquals("", LanguageResolver.normalize(null))
        assertTrue(LanguageResolver.sameTag("PT_br", "pt-BR"))
        assertFalse(LanguageResolver.sameTag("pt", "pt-BR"))
    }

    @Test fun exactMatchWins() {
        assertEquals("es-MX", resolve("es-MX"))
        assertEquals("pt-BR", resolve("pt_br"))
    }

    @Test fun languageWithScript() {
        assertEquals("zh-Hans", resolve("zh-Hans-CN"))
        assertEquals("zh-Hant", resolve("zh-Hant-TW"))
        // Implied script for scriptless Chinese tags.
        assertEquals("zh-Hant", resolve("zh-TW"))
        assertEquals("zh-Hans", resolve("zh-CN"))
    }

    @Test fun languageOnly() {
        assertEquals("es", resolve("es-AR"))
        assertEquals("fr", resolve("fr-CA"))
    }

    @Test fun anyProjectLanguageWithSameLanguageSubtag() {
        assertEquals("pt-BR", resolve("pt-PT"))
        assertEquals("pt-BR", resolve("pt"))
        assertEquals("es-419", resolve("es-ES", available = listOf("en", "es-419")))
    }

    @Test fun iteratesPreferredInOrderFirstHitWins() {
        assertEquals("fr", resolve("de-DE", "fr-FR", "es"))
        assertEquals("es", resolve("es", "fr"))
    }

    @Test fun fallsBackToBaseLanguage() {
        assertEquals("en", resolve("de-DE", "ja-JP"))
        assertEquals("en", resolve())
    }

    @Test fun overrideBeatsAll() {
        assertEquals("fr", resolve("es-MX", override = "fr"))
        assertEquals("es", resolve("fr", override = "es-CO"))
        // Unknown override is kept (lookups then fall back to the base bundle).
        assertEquals("de", resolve("fr", override = "de"))
    }

    @Test fun noManifestYet() {
        assertEquals("es-MX", LanguageResolver.resolve(null, listOf("es-MX"), emptyList(), null))
        assertEquals("", LanguageResolver.resolve(null, emptyList(), emptyList(), null))
    }

    @Test fun matchReturnsNullWhenNothingFits() {
        assertNull(LanguageResolver.match("de", project))
    }
}
