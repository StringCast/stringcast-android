package dev.polyglot.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contract check against a running backend (skipped by default):
 *
 *   POLYGLOT_LIVE_URL=http://localhost:8787 ./gradlew :polyglot:testDebugUnitTest
 *
 * Uses the seeded demo project (`p_demo` / `pk_demo_local`).
 */
class LiveBackendTest {
    private val base = System.getenv("POLYGLOT_LIVE_URL")?.trimEnd('/')
    private val project = System.getenv("POLYGLOT_LIVE_PROJECT") ?: "p_demo"
    private val key = System.getenv("POLYGLOT_LIVE_KEY") ?: "pk_demo_local"

    @Test fun manifestEtagAndBundles() {
        assumeTrue("POLYGLOT_LIVE_URL not set", base != null)
        val http = Http(key, "polyglot-android-test")
        val first = http.get("$base/v1/sdk/$project/manifest")
        assertEquals(200, first.code)
        val manifest = Json.parseManifest(first.text())
        assertEquals(project, manifest.projectId)
        assertTrue("manifest must send an ETag", first.etag != null)

        val second = http.get("$base/v1/sdk/$project/manifest", ifNoneMatch = first.etag)
        assertEquals(304, second.code)

        for (lang in manifest.languages) {
            val resp = http.get(lang.url, sendKey = false)
            assertEquals(200, resp.code)
            assertTrue("hash mismatch for ${lang.code}", Hashing.verify(lang.hash, resp.body!!))
            val bundle = Json.parseBundle(resp.text())
            assertEquals(manifest.version, bundle.version)
            assertTrue(LanguageResolver.sameTag(lang.code, bundle.language))
            assertEquals(lang.keyCount, bundle.strings.size)
        }
        println("LIVE OK: v${manifest.version} ${manifest.languageCodes} etag=${first.etag}")
    }
}
