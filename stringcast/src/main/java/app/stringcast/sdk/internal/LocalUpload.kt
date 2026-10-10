package app.stringcast.sdk.internal

import org.json.JSONObject

/**
 * Draft-mode upload of the app's compiled strings to `POST /v1/sdk/{projectId}/missing`:
 *
 * 1. every owned key's value in the project's **base** language (read from the compiled resources
 *    resolved for the base locale, so a device in Spanish still uploads `values/`), then
 * 2. for every other project language, the compiled value resolved for that locale — but only
 *    when it differs from the base value. Android falls back to `values/` when a translation is
 *    missing, so "equal to base" means "not translated" ([isTranslated]).
 *
 * One language per request, at most [MAX_BATCH] keys per request. The server creates missing keys
 * and fills empty values; it never overwrites a non-empty one.
 *
 * Pure logic: resource reading and HTTP are injected so this is unit-testable on the JVM.
 */
internal class LocalUploader(
    /** Compiled value of an owned entry in a language (null if unreadable). */
    private val readValue: (entry: LocalStrings.Entry, language: String) -> Value?,
    /** POSTs one batch; may throw on transport errors. */
    private val post: (language: String, keys: List<Pair<String, Value>>) -> Http.Response,
) {

    data class Batch(val language: String, val keys: List<Pair<String, Value>>)

    data class Outcome(
        /** Owned keys with a base-language value. */
        val total: Int,
        /** Translations (non-base values) sent. */
        val translations: Int,
        val created: Int,
        val filled: Int,
        val ignored: Int,
        /** Non-null if (part of) the upload failed. */
        val error: String?,
    ) {
        val isSuccess: Boolean get() = error == null
    }

    /** Builds every request: base language first, then one group per other project language. */
    fun buildBatches(entries: List<LocalStrings.Entry>, baseLanguage: String, languages: List<String>): List<Batch> {
        val base = LinkedHashMap<LocalStrings.Entry, Value>()
        for (e in entries) safeRead(e, baseLanguage)?.let { base[e] = it }
        val out = ArrayList<Batch>()
        base.entries.map { it.key.name to it.value }.chunked(MAX_BATCH).forEach { out += Batch(baseLanguage, it) }

        val others = LinkedHashSet<String>()
        for (l in languages) {
            if (l.isBlank() || LanguageResolver.sameTag(l, baseLanguage)) continue
            if (others.none { LanguageResolver.sameTag(it, l) }) others += l
        }
        for (lang in others) {
            val translated = ArrayList<Pair<String, Value>>()
            for ((e, baseValue) in base) {
                val v = safeRead(e, lang) ?: continue
                if (isTranslated(baseValue, v)) translated += e.name to v
            }
            translated.chunked(MAX_BATCH).forEach { out += Batch(lang, it) }
        }
        return out
    }

    /** Uploads everything; never throws. */
    fun upload(entries: List<LocalStrings.Entry>, baseLanguage: String, languages: List<String>): Outcome {
        val batches = try {
            buildBatches(entries, baseLanguage, languages)
        } catch (t: Throwable) {
            return Outcome(0, 0, 0, 0, 0, "Reading local strings failed: $t")
        }
        val total = batches.filter { it.language == baseLanguage }.sumOf { it.keys.size }
        val translations = batches.filter { it.language != baseLanguage }.sumOf { it.keys.size }
        if (total == 0) {
            return Outcome(0, 0, 0, 0, 0, "No app strings found; pass your R classes via StringCastConfig.rClasses")
        }
        var created = 0
        var filled = 0
        var ignored = 0
        var error: String? = null
        for (b in batches) {
            try {
                val resp = post(b.language, b.keys)
                if (resp.isSuccess) {
                    val o = runCatching { JSONObject(resp.text()) }.getOrNull()
                    created += o?.optInt("created") ?: 0
                    filled += o?.optInt("filled") ?: 0
                    ignored += o?.optInt("ignored") ?: 0
                } else {
                    error = "HTTP ${resp.code} (${b.language}): ${resp.text().take(300)}"
                }
            } catch (t: Throwable) {
                error = "Upload failed (${b.language}): $t"
            }
        }
        return Outcome(total, translations, created, filled, ignored, error)
    }

    private fun safeRead(e: LocalStrings.Entry, language: String): Value? = try {
        readValue(e, language)
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val MAX_BATCH = 500

        /**
         * True if [value] (read for a non-base locale) is a real translation of [base]. Android
         * falls back to `values/` for missing translations, so a value equal to base is not one.
         * Plurals compare the whole value: the other locale's plural categories differ from the
         * base's, but a fallback only ever yields base forms, so "every form is one of the base
         * forms" means untranslated.
         */
        fun isTranslated(base: Value, value: Value): Boolean = when {
            base is Value.Text && value is Value.Text -> base.value != value.value
            base is Value.StringArray && value is Value.StringArray -> base.items != value.items
            base is Value.Plural && value is Value.Plural -> {
                val baseForms = base.forms.values.toSet()
                !value.forms.values.all { it in baseForms }
            }
            else -> base != value
        }
    }
}

/**
 * "Once per app build" bookkeeping for the automatic upload. The marker
 * (`versionName|longVersionCode|sdkVersion`) is stored only after a fully successful upload.
 */
internal class AutoUploadGate(private val store: MarkerStore) {

    interface MarkerStore {
        fun get(): String?
        fun put(value: String)
    }

    enum class Result { SKIPPED, UPLOADED, FAILED }

    fun isDone(marker: String): Boolean = store.get() == marker

    /** Runs [upload] unless [marker] is already stored; stores it only when the upload succeeded. */
    fun run(marker: String, upload: () -> LocalUploader.Outcome): Result {
        if (isDone(marker)) return Result.SKIPPED
        val outcome = try {
            upload()
        } catch (t: Throwable) {
            Logger.w("Automatic upload failed", t)
            return Result.FAILED
        }
        if (!outcome.isSuccess) {
            Logger.w("Automatic upload incomplete (will retry next launch): ${outcome.error}")
            return Result.FAILED
        }
        store.put(marker)
        return Result.UPLOADED
    }

    companion object {
        fun marker(versionName: String?, longVersionCode: Long, sdkVersion: String): String =
            "${versionName.orEmpty()}|$longVersionCode|$sdkVersion"
    }
}
