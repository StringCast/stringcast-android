package app.stringcast.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluralFallbackTest {

    @Test fun englishLikeOneOther() {
        assertEquals("one", PluralFallback.select("en", 1))
        assertEquals("other", PluralFallback.select("en", 0))
        assertEquals("other", PluralFallback.select("en-US", 2))
        assertEquals("one", PluralFallback.select("es", 1))
        assertEquals("other", PluralFallback.select("de", 5))
    }

    @Test fun frenchAndBrazilianTreatZeroAsOne() {
        assertEquals("one", PluralFallback.select("fr", 0))
        assertEquals("one", PluralFallback.select("fr", 1))
        assertEquals("other", PluralFallback.select("fr", 2))
        assertEquals("one", PluralFallback.select("pt-BR", 0))
        assertEquals("other", PluralFallback.select("pt-PT", 0))
    }

    @Test fun languagesWithoutPlurals() {
        assertEquals("other", PluralFallback.select("ja", 1))
        assertEquals("other", PluralFallback.select("zh-Hans", 1))
    }

    @Test fun slavic() {
        assertEquals("one", PluralFallback.select("ru", 21))
        assertEquals("few", PluralFallback.select("ru", 3))
        assertEquals("many", PluralFallback.select("ru", 11))
        assertEquals("many", PluralFallback.select("ru", 5))
        assertEquals("few", PluralFallback.select("pl", 22))
        assertEquals("many", PluralFallback.select("pl", 12))
        assertEquals("few", PluralFallback.select("cs", 3))
    }

    @Test fun arabic() {
        assertEquals("zero", PluralFallback.select("ar", 0))
        assertEquals("two", PluralFallback.select("ar", 2))
        assertEquals("few", PluralFallback.select("ar", 105))
        assertEquals("many", PluralFallback.select("ar", 11))
        assertEquals("other", PluralFallback.select("ar", 100))
    }

    @Test fun pickFallsBackToOther() {
        val forms = mapOf("one" to "%d item", "other" to "%d items")
        assertEquals("%d item", PluralFallback.pick(forms, "one"))
        assertEquals("%d items", PluralFallback.pick(forms, "few"))
        assertEquals("only", PluralFallback.pick(mapOf("one" to "only"), "many"))
        assertNull(PluralFallback.pick(emptyMap(), "other"))
    }
}
