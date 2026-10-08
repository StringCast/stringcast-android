package dev.polyglot.sdk.internal

import java.util.Locale

/**
 * Language resolution exactly as contract §6.4. Pure JVM, no Android dependencies.
 *
 * Tags are compared case-insensitively with `_` normalised to `-`.
 */
internal object LanguageResolver {

    /** Legacy ISO 639 codes still emitted by old Android/Java Locale APIs. */
    private val LEGACY = mapOf("iw" to "he", "in" to "id", "ji" to "yi")

    /** Normalises `pt_br` → `pt-BR`, `zh_hans_cn` → `zh-Hans-CN`, `iw` → `he`. */
    fun normalize(tag: String?): String {
        if (tag.isNullOrBlank()) return ""
        val parts = tag.trim().replace('_', '-').split('-').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return ""
        val out = ArrayList<String>(parts.size)
        parts.forEachIndexed { i, p ->
            out += when {
                i == 0 -> p.lowercase(Locale.ROOT).let { LEGACY[it] ?: it }
                p.length == 4 && p.all { it.isLetter() } -> // script
                    p.substring(0, 1).uppercase(Locale.ROOT) + p.substring(1).lowercase(Locale.ROOT)
                (p.length == 2 && p.all { it.isLetter() }) || (p.length == 3 && p.all { it.isDigit() }) -> // region
                    p.uppercase(Locale.ROOT)
                else -> p.lowercase(Locale.ROOT)
            }
        }
        return out.joinToString("-")
    }

    fun sameTag(a: String?, b: String?): Boolean = normalize(a).equals(normalize(b), ignoreCase = true)

    fun languageSubtag(tag: String): String = normalize(tag).substringBefore('-')

    /** Returns the 4-letter script subtag (e.g. `Hans`) if present. */
    fun scriptSubtag(tag: String): String? =
        normalize(tag).split('-').drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }

    /**
     * Implied script for tags that commonly omit it. Only Chinese is handled because it is the
     * case where the project languages are typically stored with a script (`zh-Hans`, `zh-Hant`).
     */
    private fun impliedScript(tag: String): String? {
        val parts = normalize(tag).split('-')
        if (parts[0] != "zh") return null
        val region = parts.drop(1).firstOrNull { it.length == 2 }
        return when (region) {
            "TW", "HK", "MO" -> "Hant"
            null -> null
            else -> "Hans"
        }
    }

    /**
     * Matches one preferred tag against the available project languages:
     * exact → language+script → language only → any project language with the same language subtag.
     */
    fun match(preferred: String, available: List<String>): String? {
        val pref = normalize(preferred)
        if (pref.isEmpty()) return null
        // 1. exact match
        available.firstOrNull { sameTag(it, pref) }?.let { return normalize(it) }
        val lang = languageSubtag(pref)
        // 2. same language with script (zh-Hans-CN -> zh-Hans)
        val script = scriptSubtag(pref) ?: impliedScript(pref)
        if (script != null) {
            available.firstOrNull { sameTag(it, "$lang-$script") }?.let { return normalize(it) }
        }
        // 3. language only (es-MX -> es)
        available.firstOrNull { sameTag(it, lang) }?.let { return normalize(it) }
        // 4. any project language starting with the same language subtag (es -> es-419)
        available.firstOrNull { languageSubtag(it) == lang }?.let { return normalize(it) }
        return null
    }

    /**
     * Full §6.4 resolution.
     *
     * @param override `languageOverride` / `setLanguage` value – beats everything. It is still
     *   matched against the project's languages (so `es-MX` can select `es`); if nothing matches,
     *   the override itself is returned (lookups then fall back to the base bundle).
     * @param preferred device languages, most preferred first.
     * @param available the project's languages from the manifest (may be empty before the first fetch).
     * @param baseLanguage project base language (may be empty before the first fetch).
     */
    fun resolve(
        override: String?,
        preferred: List<String>,
        available: List<String>,
        baseLanguage: String?,
    ): String {
        if (!override.isNullOrBlank()) {
            return match(override, available) ?: normalize(override)
        }
        for (p in preferred) {
            match(p, available)?.let { return it }
        }
        val base = normalize(baseLanguage)
        if (base.isNotEmpty()) return base
        // Nothing known about the project yet: best guess is the device's first language.
        return preferred.firstOrNull()?.let(::normalize).orEmpty()
    }
}
