package dev.polyglot.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceholdersTest {

    @Test fun iosObjectPlaceholders() {
        assertEquals("Hello %s", Placeholders.normalize("Hello %@"))
        assertEquals("%1\$s and %2\$s", Placeholders.normalize("%1\$@ and %2\$@"))
    }

    @Test fun leavesAndroidPlaceholdersAlone() {
        val s = "%s %1\$s %d %2\$d %.2f %%"
        assertEquals(s, Placeholders.normalize(s))
    }

    @Test fun escapedPercentIsNotAPlaceholder() {
        assertEquals("100%%@home", Placeholders.normalize("100%%@home"))
        assertEquals("100%% %s", Placeholders.normalize("100%% %@"))
    }

    @Test fun iosIntegerLengthModifiers() {
        assertEquals("%d items, %1\$d, %d, %d", Placeholders.normalize("%ld items, %1\$lld, %lu, %u"))
    }

    @Test fun normalizedStringsFormat() {
        val v = Placeholders.normalize("%1\$@ has %2\$ld new messages")
        assertEquals("Ana has 3 new messages", String.format(v, "Ana", 3))
    }

    @Test fun noPercentFastPath() {
        assertEquals("plain", Placeholders.normalize("plain"))
        assertFalse(Placeholders.hasFormatSpecifiers("100%%"))
        assertTrue(Placeholders.hasFormatSpecifiers("%s"))
    }
}
