package app.stringcast.sdk.internal

import app.stringcast.sdk.internal.fakes.app.R as AppR
import app.stringcast.sdk.internal.fakes.feature.R as FeatureR
import app.stringcast.sdk.internal.fakes.legacy.R as LegacyR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStringsTest {

    /** Like Resources.getResourceEntryName: real names (with dots), null for unknown ids. */
    private val nameOf: (Int, String) -> String? = { id, field ->
        when (id) {
            0 -> null
            LegacyR.string.com_google_firebase_crashlytics_mapping_file_id -> "com.google.firebase.crashlytics.mapping_file_id"
            else -> field
        }
    }
    private val noDiscovery: () -> List<Class<*>> = { error("discovery must not run when R classes are given") }

    private fun names(entries: List<LocalStrings.Entry>) = entries.map { it.name }.toSet()

    @Test fun scansStringPluralsAndArraysOfExplicitRClass() {
        val entries = LocalStrings.owned(listOf(AppR::class.java), noDiscovery, KeyFilter(), nameOf)
        assertEquals(setOf("app_title", "welcome_body", "tab_home", "items_count", "planets"), names(entries))
        assertEquals(LocalStrings.Entry("plurals", "items_count", AppR.plurals.items_count), entries.first { it.name == "items_count" })
        assertEquals("array", entries.first { it.name == "planets" }.type)
        // Complete library names match exactly: the app's own `tab_home` is kept despite the `tab` rule.
        assertTrue("tab_home" in names(entries))
    }

    @Test fun multiModuleRClassesAreMergedAndDeduplicated() {
        val entries = LocalStrings.owned(listOf(AppR::class.java, FeatureR::class.java), noDiscovery, KeyFilter(), nameOf)
        assertEquals(setOf("app_title", "welcome_body", "tab_home", "items_count", "planets", "checkout_title"), names(entries))
        assertEquals(1, entries.count { it.name == "app_title" })
    }

    @Test fun transitiveRClassDropsLibraryStrings() {
        val entries = LocalStrings.owned(
            listOf(LegacyR::class.java), noDiscovery,
            KeyFilter(excludedKeys = setOf("debug_menu_title"), excludedKeyPrefixes = setOf("promo_")), nameOf,
        )
        assertEquals(setOf("login_title", "table_header", "sizes"), names(entries))
    }

    @Test fun withoutExplicitRClassesFallsBackToDiscovery() {
        var discovered = false
        val entries = LocalStrings.owned(emptyList(), {
            discovered = true
            LocalStrings.discoverRClasses(
                LocalStrings.candidatePackages("app.stringcast.sdk.internal.fakes.app.qa", null),
                javaClass.classLoader,
            )
        }, KeyFilter(), nameOf)
        assertTrue(discovered)
        assertEquals(setOf("app_title", "welcome_body", "tab_home", "items_count", "planets"), names(entries))
    }

    @Test fun candidatePackagesStripSuffixesAndIncludeApplicationClassPackage() {
        assertEquals(
            listOf("com.acme.app.qa", "com.acme.app", "com.acme", "com.other"),
            LocalStrings.candidatePackages("com.acme.app.qa", "com.other.App"),
        )
        assertEquals(emptyList<String>(), LocalStrings.candidatePackages(null, null))
    }

    @Test fun discoveryReturnsNothingWhenNoRClassExists() {
        assertEquals(emptyList<Class<*>>(), LocalStrings.discoverRClasses(listOf("does.not.exist"), javaClass.classLoader))
    }
}
