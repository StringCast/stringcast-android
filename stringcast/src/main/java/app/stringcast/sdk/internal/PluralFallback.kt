package app.stringcast.sdk.internal

/**
 * Plural category selection used when android.icu.text.PluralRules is unavailable (API < 24),
 * plus form picking. Pure JVM.
 *
 * Deliberately small: a handful of common rules, everything else is "n == 1 → one, else other".
 */
internal object PluralFallback {

    private val NO_PLURALS = setOf(
        "ja", "zh", "ko", "th", "vi", "id", "ms", "lo", "my", "km", "yo", "ig", "jv", "su", "bo", "dz",
    )
    private val ZERO_ONE_IS_ONE = setOf("fr", "pt", "hy", "kab", "ff")
    private val EAST_SLAVIC = setOf("ru", "uk", "be")
    private val WEST_SLAVIC = setOf("cs", "sk")

    fun select(language: String, quantity: Int): String {
        val lang = LanguageResolver.languageSubtag(language)
        val n = if (quantity < 0) -quantity else quantity
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            lang in NO_PLURALS -> "other"
            // pt-PT follows the English rule; pt / pt-BR treat 0 and 1 as "one".
            lang == "pt" && LanguageResolver.normalize(language).equals("pt-PT", ignoreCase = true) ->
                if (n == 1) "one" else "other"
            lang in ZERO_ONE_IS_ONE -> if (n == 0 || n == 1) "one" else "other"
            lang in EAST_SLAVIC -> when {
                mod10 == 1 && mod100 != 11 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            lang == "pl" -> when {
                n == 1 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            lang in WEST_SLAVIC -> when (n) {
                1 -> "one"
                in 2..4 -> "few"
                else -> "other"
            }
            lang == "ar" -> when {
                n == 0 -> "zero"
                n == 1 -> "one"
                n == 2 -> "two"
                mod100 in 3..10 -> "few"
                mod100 in 11..99 -> "many"
                else -> "other"
            }
            else -> if (n == 1) "one" else "other"
        }
    }

    /**
     * Picks the form for [category] from [forms]: exact category → `other` → any form.
     * Returns null only for an empty map.
     */
    fun pick(forms: Map<String, String>, category: String): String? =
        forms[category] ?: forms["other"] ?: forms.values.firstOrNull()
}
