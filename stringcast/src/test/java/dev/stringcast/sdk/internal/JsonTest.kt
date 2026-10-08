package dev.stringcast.sdk.internal

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {

    private val bundleJson = """
        {
          "projectId": "p_8f3k2j",
          "version": 7,
          "language": "es_mx",
          "publishedAt": "2026-10-08T15:04:05.000Z",
          "strings": {
            "welcome_title": "Bienvenido",
            "items_count": { "one": "%d artículo", "other": "%d artículos", "bogus": "x" },
            "planets": ["Mercurio", "Venus"],
            "weird": 42,
            "empty_plural": { "foo": "bar" }
          }
        }
    """.trimIndent()

    @Test fun parsesBundleHeader() {
        val b = Json.parseBundle(bundleJson)
        assertEquals("p_8f3k2j", b.projectId)
        assertEquals(7, b.version)
        assertEquals("es-MX", b.language)
        assertEquals("2026-10-08T15:04:05.000Z", b.publishedAt)
    }

    @Test fun parsesStringPluralAndArrayValues() {
        val s = Json.parseBundle(bundleJson).strings
        assertEquals(Value.Text("Bienvenido"), s["welcome_title"])
        assertEquals(Value.Plural(mapOf("one" to "%d artículo", "other" to "%d artículos")), s["items_count"])
        assertEquals(Value.StringArray(listOf("Mercurio", "Venus")), s["planets"])
    }

    @Test fun ignoresUnknownValueShapes() {
        val s = Json.parseBundle(bundleJson).strings
        assertNull(s["weird"])
        assertNull(s["empty_plural"])
        assertEquals(3, s.size)
    }

    @Test fun toleratesMissingStrings() {
        val b = Json.parseBundle("""{"projectId":"p","version":1,"language":"en"}""")
        assertTrue(b.strings.isEmpty())
    }

    @Test fun parsesManifest() {
        val m = Json.parseManifest(
            """
            {"projectId":"p_8f3k2j","version":7,"publishedAt":"2026-10-08T15:04:05.000Z","baseLanguage":"en",
             "languages":[
               {"code":"en","url":"https://cdn.example.com/p_8f3k2j/v7/en.json","hash":"sha256-abc","keyCount":120},
               {"code":"pt_br","url":"https://cdn.example.com/p_8f3k2j/v7/pt-BR.json","hash":"sha256-def","keyCount":118},
               {"code":"","url":""}
             ]}
            """.trimIndent(),
        )
        assertEquals(7, m.version)
        assertEquals("en", m.baseLanguage)
        assertEquals(listOf("en", "pt-BR"), m.languageCodes)
        assertEquals(120, m.language("EN")!!.keyCount)
        assertEquals("sha256-def", m.language("pt-br")!!.hash)
    }

    @Test fun emptyManifest() {
        val m = Json.parseManifest("""{"projectId":"p","version":0,"publishedAt":null,"baseLanguage":"en","languages":[]}""")
        assertEquals(0, m.version)
        assertTrue(m.languages.isEmpty())
        assertNull(m.publishedAt)
    }

    @Test fun missingPayloadShape() {
        val o = JSONObject(
            Json.missingPayload(
                "android",
                "en",
                listOf(
                    "promo" to Value.Text("Get 20% off"),
                    "items" to Value.Plural(mapOf("one" to "%d item", "other" to "%d items")),
                    "planets" to Value.StringArray(listOf("Mercury")),
                ),
            ),
        )
        assertEquals("android", o.getString("platform"))
        assertEquals("en", o.getString("language"))
        val keys = o.getJSONArray("keys")
        assertEquals(3, keys.length())
        assertEquals("Get 20% off", keys.getJSONObject(0).getString("value"))
        assertEquals("%d items", keys.getJSONObject(1).getJSONObject("value").getString("other"))
        assertEquals("Mercury", keys.getJSONObject(2).getJSONArray("value").getString(0))
    }
}
