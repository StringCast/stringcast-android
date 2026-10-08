package dev.stringcast.sdk.internal

/** A localized value as defined by contract §2. */
internal sealed class Value {
    data class Text(val value: String) : Value()

    /** Plural forms keyed by CLDR category (`zero, one, two, few, many, other`). */
    data class Plural(val forms: Map<String, String>) : Value() {
        val other: String? get() = forms["other"]
    }

    data class StringArray(val items: List<String>) : Value()
}

/** A downloaded release bundle (contract §3). Immutable. */
internal data class Bundle(
    val projectId: String,
    val version: Int,
    val language: String,
    val publishedAt: String?,
    val strings: Map<String, Value>,
)

internal data class ManifestLanguage(
    val code: String,
    val url: String,
    val hash: String?,
    val keyCount: Int,
)

/** The SDK manifest (contract §4.2). Immutable. */
internal data class Manifest(
    val projectId: String,
    val version: Int,
    val publishedAt: String?,
    val baseLanguage: String,
    val languages: List<ManifestLanguage>,
) {
    val languageCodes: List<String> get() = languages.map { it.code }

    fun language(code: String): ManifestLanguage? =
        languages.firstOrNull { LanguageResolver.sameTag(it.code, code) }
}
