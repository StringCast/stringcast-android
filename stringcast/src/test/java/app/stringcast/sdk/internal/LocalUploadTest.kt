package app.stringcast.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalUploadTest {

    private fun entry(name: String, type: String = "string") = LocalStrings.Entry(type, name, name.hashCode())

    /** Fake compiled resources: language → key → value; missing translations fall back to "en". */
    private class FakeResources(val values: Map<String, Map<String, Value>>) {
        fun read(e: LocalStrings.Entry, lang: String): Value? = values[lang]?.get(e.name) ?: values["en"]?.get(e.name)
    }

    private class FakeTransport(val failOn: Set<Int> = emptySet()) {
        val calls = ArrayList<Pair<String, List<Pair<String, Value>>>>()
        fun post(language: String, keys: List<Pair<String, Value>>): Http.Response {
            calls += language to keys
            if ((calls.size - 1) in failOn) return Http.Response(500, "boom".toByteArray(), null)
            val body = """{"created":${keys.size},"filled":1,"ignored":0}"""
            return Http.Response(200, body.toByteArray(), null)
        }
    }

    // --------------------------------------------------------------------------------------
    // Translation diffing
    // --------------------------------------------------------------------------------------

    @Test fun translationEqualToBaseIsSkipped() {
        val res = FakeResources(
            mapOf(
                "en" to mapOf("hello" to Value.Text("Hello"), "ok" to Value.Text("OK"), "bye" to Value.Text("Bye")),
                "es" to mapOf("hello" to Value.Text("Hola"), "ok" to Value.Text("OK")), // "bye" not translated
            ),
        )
        val uploader = LocalUploader(res::read) { _, _ -> error("unused") }
        val batches = uploader.buildBatches(listOf(entry("hello"), entry("ok"), entry("bye")), "en", listOf("en", "es"))
        assertEquals(listOf("en", "es"), batches.map { it.language })
        assertEquals(listOf("hello", "ok", "bye"), batches[0].keys.map { it.first })
        assertEquals(listOf("hello" to Value.Text("Hola")), batches[1].keys)
    }

    @Test fun pluralsAndArraysCompareTheWholeValue() {
        val basePlural = Value.Plural(mapOf("one" to "%d item", "other" to "%d items"))
        // Fallback read with Russian categories: only base forms appear → not translated.
        val ruFallback = Value.Plural(mapOf("one" to "%d item", "few" to "%d items", "many" to "%d items", "other" to "%d items"))
        assertFalse(LocalUploader.isTranslated(basePlural, ruFallback))
        assertFalse(LocalUploader.isTranslated(basePlural, basePlural))
        assertTrue(LocalUploader.isTranslated(basePlural, Value.Plural(mapOf("one" to "%d artículo", "other" to "%d items"))))

        val baseArr = Value.StringArray(listOf("Mercury", "Venus"))
        assertFalse(LocalUploader.isTranslated(baseArr, Value.StringArray(listOf("Mercury", "Venus"))))
        assertTrue(LocalUploader.isTranslated(baseArr, Value.StringArray(listOf("Mercurio", "Venus"))))

        assertFalse(LocalUploader.isTranslated(Value.Text("a"), Value.Text("a")))
        assertTrue(LocalUploader.isTranslated(Value.Text("a"), Value.Text("b")))
    }

    @Test fun baseLanguageIsNeverSentAsTranslationAndLanguagesAreDeduplicated() {
        val res = FakeResources(mapOf("en" to mapOf("a" to Value.Text("A")), "fr" to mapOf("a" to Value.Text("Á"))))
        val uploader = LocalUploader(res::read) { _, _ -> error("unused") }
        val batches = uploader.buildBatches(listOf(entry("a")), "en", listOf("EN", "en", "fr", "FR", ""))
        assertEquals(listOf("en", "fr"), batches.map { it.language })
    }

    @Test fun keysWithoutBaseValueAreNotUploaded() {
        val res = FakeResources(mapOf("en" to mapOf("a" to Value.Text("A")), "es" to mapOf("orphan" to Value.Text("x"))))
        val uploader = LocalUploader(res::read) { _, _ -> error("unused") }
        val batches = uploader.buildBatches(listOf(entry("a"), entry("orphan")), "en", listOf("es"))
        assertEquals(1, batches.size)
        assertEquals(listOf("a"), batches[0].keys.map { it.first })
    }

    // --------------------------------------------------------------------------------------
    // Batching
    // --------------------------------------------------------------------------------------

    @Test fun batchesHoldAtMost500KeysAndOneLanguage() {
        val entries = (1..1201).map { entry("k$it") }
        val en = entries.associate { it.name to (Value.Text("v ${it.name}") as Value) }
        val es = entries.take(600).associate { it.name to (Value.Text("es ${it.name}") as Value) }
        val res = FakeResources(mapOf("en" to en, "es" to es))
        val transport = FakeTransport()
        val outcome = LocalUploader(res::read, transport::post).upload(entries, "en", listOf("en", "es", "de"))

        assertEquals(listOf("en" to 500, "en" to 500, "en" to 201, "es" to 500, "es" to 100), transport.calls.map { it.first to it.second.size })
        assertTrue(transport.calls.all { it.second.size <= LocalUploader.MAX_BATCH })
        assertTrue(outcome.isSuccess)
        assertEquals(1201, outcome.total)
        assertEquals(600, outcome.translations)
        assertEquals(1801, outcome.created)
        assertEquals(5, outcome.filled)
    }

    @Test fun failedBatchIsReportedButOthersStillSent() {
        val entries = (1..3).map { entry("k$it") }
        val res = FakeResources(mapOf("en" to entries.associate { it.name to (Value.Text(it.name) as Value) }))
        val transport = FakeTransport(failOn = setOf(0))
        val outcome = LocalUploader(res::read, transport::post).upload(entries, "en", listOf("en"))
        assertFalse(outcome.isSuccess)
        assertNotNull(outcome.error)
        assertEquals(1, transport.calls.size)
    }

    @Test fun transportExceptionsNeverEscape() {
        val res = FakeResources(mapOf("en" to mapOf("a" to Value.Text("A"))))
        val outcome = LocalUploader(res::read) { _, _ -> throw java.io.IOException("offline") }.upload(listOf(entry("a")), "en", emptyList())
        assertFalse(outcome.isSuccess)
        assertTrue(outcome.error!!.contains("offline"))
    }

    @Test fun nothingToUploadIsAFailure() {
        val transport = FakeTransport()
        val outcome = LocalUploader({ _, _ -> null }, transport::post).upload(emptyList(), "en", listOf("es"))
        assertFalse(outcome.isSuccess)
        assertTrue(transport.calls.isEmpty())
    }

    // --------------------------------------------------------------------------------------
    // Once-per-build marker
    // --------------------------------------------------------------------------------------

    private class FakeStore(var value: String? = null) : AutoUploadGate.MarkerStore {
        var puts = 0
        override fun get(): String? = value
        override fun put(value: String) {
            puts++
            this.value = value
        }
    }

    private val ok = LocalUploader.Outcome(10, 2, 10, 2, 0, null)
    private val failed = LocalUploader.Outcome(10, 0, 5, 0, 0, "HTTP 500")

    @Test fun markerFormat() {
        assertEquals("1.4.0|10400|0.1.1", AutoUploadGate.marker("1.4.0", 10400L, "0.1.1"))
        assertEquals("|0|0.1.1", AutoUploadGate.marker(null, 0L, "0.1.1"))
    }

    @Test fun skipsWhenMarkerAlreadyStored() {
        val store = FakeStore("1.0|1|0.1.1")
        var ran = false
        val result = AutoUploadGate(store).run("1.0|1|0.1.1") { ran = true; ok }
        assertEquals(AutoUploadGate.Result.SKIPPED, result)
        assertFalse(ran)
        assertEquals(0, store.puts)
    }

    @Test fun storesMarkerOnlyAfterFullSuccess() {
        val store = FakeStore("1.0|1|0.1.0") // previous build / SDK version
        assertEquals(AutoUploadGate.Result.FAILED, AutoUploadGate(store).run("1.0|1|0.1.1") { failed })
        assertEquals("1.0|1|0.1.0", store.value)
        assertEquals(AutoUploadGate.Result.FAILED, AutoUploadGate(store).run("1.0|1|0.1.1") { throw IllegalStateException("x") })
        assertEquals(0, store.puts)

        assertEquals(AutoUploadGate.Result.UPLOADED, AutoUploadGate(store).run("1.0|1|0.1.1") { ok })
        assertEquals("1.0|1|0.1.1", store.value)
        assertEquals(AutoUploadGate.Result.SKIPPED, AutoUploadGate(store).run("1.0|1|0.1.1") { ok })
        assertEquals(1, store.puts)
    }

    @Test fun firstRunWithEmptyStore() {
        val store = FakeStore()
        assertNull(store.value)
        assertEquals(AutoUploadGate.Result.UPLOADED, AutoUploadGate(store).run("2|2|0.1.1") { ok })
        assertEquals("2|2|0.1.1", store.value)
    }
}
