package dev.stringcast.sdk.internal

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Parsing / serialisation of the contract's JSON documents. Pure JVM (org.json only). */
internal object Json {

    private val PLURAL_CATEGORIES = setOf("zero", "one", "two", "few", "many", "other")

    @Throws(JSONException::class)
    fun parseBundle(text: String): Bundle {
        val o = JSONObject(text)
        val strings = o.optJSONObject("strings") ?: JSONObject()
        val map = HashMap<String, Value>(strings.length() * 2)
        val it = strings.keys()
        while (it.hasNext()) {
            val key = it.next()
            parseValue(strings.opt(key))?.let { v -> map[key] = v }
        }
        return Bundle(
            projectId = o.optString("projectId", ""),
            version = o.optInt("version", 0),
            language = LanguageResolver.normalize(o.optString("language", "")),
            publishedAt = o.optString("publishedAt", "").ifEmpty { null },
            strings = map,
        )
    }

    /** Returns null for values that don't match any §2 type (they are ignored, never fatal). */
    fun parseValue(raw: Any?): Value? = when (raw) {
        is String -> Value.Text(raw)
        is JSONObject -> {
            val forms = LinkedHashMap<String, String>()
            val keys = raw.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = raw.opt(k)
                if (k in PLURAL_CATEGORIES && v is String) forms[k] = v
            }
            if (forms.isEmpty()) null else Value.Plural(forms)
        }
        is JSONArray -> {
            val items = ArrayList<String>(raw.length())
            for (i in 0 until raw.length()) {
                val v = raw.opt(i)
                items.add(if (v == null || v == JSONObject.NULL) "" else v.toString())
            }
            Value.StringArray(items)
        }
        else -> null
    }

    @Throws(JSONException::class)
    fun parseManifest(text: String): Manifest {
        val o = JSONObject(text)
        val langs = o.optJSONArray("languages") ?: JSONArray()
        val list = ArrayList<ManifestLanguage>(langs.length())
        for (i in 0 until langs.length()) {
            val l = langs.optJSONObject(i) ?: continue
            val code = l.optString("code", "")
            val url = l.optString("url", "")
            if (code.isEmpty() || url.isEmpty()) continue
            list.add(
                ManifestLanguage(
                    code = LanguageResolver.normalize(code),
                    url = url,
                    hash = l.optString("hash", "").ifEmpty { null },
                    keyCount = l.optInt("keyCount", 0),
                ),
            )
        }
        return Manifest(
            projectId = o.optString("projectId", ""),
            version = o.optInt("version", 0),
            publishedAt = o.optString("publishedAt", "").ifEmpty { null },
            baseLanguage = LanguageResolver.normalize(o.optString("baseLanguage", "")),
            languages = list,
        )
    }

    /** Serialises a value back to the §2 JSON shape (used for `/missing` payloads). */
    fun toJson(value: Value): Any = when (value) {
        is Value.Text -> value.value
        is Value.Plural -> JSONObject().apply { value.forms.forEach { (k, v) -> put(k, v) } }
        is Value.StringArray -> JSONArray().apply { value.items.forEach { put(it) } }
    }

    fun missingPayload(platform: String, language: String, keys: List<Pair<String, Value>>): String {
        val arr = JSONArray()
        for ((k, v) in keys) arr.put(JSONObject().put("key", k).put("value", toJson(v)))
        return JSONObject()
            .put("platform", platform)
            .put("language", language)
            .put("keys", arr)
            .toString()
    }
}
