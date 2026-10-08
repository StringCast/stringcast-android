package dev.stringcast.sdk.internal

import android.os.Build
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** CLDR plural category selection: ICU on API 24+, [PluralFallback] below that or on failure. */
internal object Plurals {

    private val icuCache = ConcurrentHashMap<String, Any>()

    fun category(language: String, quantity: Int): String {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val rules = icuCache.getOrPut(language) {
                    android.icu.text.PluralRules.forLocale(Locale.forLanguageTag(language.ifEmpty { "en" }))
                } as android.icu.text.PluralRules
                return rules.select(quantity.toDouble())
            } catch (t: Throwable) {
                Logger.w("ICU PluralRules failed for $language", t)
            }
        }
        return PluralFallback.select(language, quantity)
    }

    /** One integer sample per category for [language] (used to read local `<plurals>` back). */
    fun samples(language: String): Map<String, Int> {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                val rules = android.icu.text.PluralRules.forLocale(Locale.forLanguageTag(language.ifEmpty { "en" }))
                val out = LinkedHashMap<String, Int>()
                for (k in rules.keywords) {
                    val sample = rules.getSamples(k)?.firstOrNull { it == Math.floor(it) && it < 1_000_000 }
                    if (sample != null) out[k] = sample.toInt()
                }
                if (out.isNotEmpty()) return out
            } catch (t: Throwable) {
                Logger.w("ICU PluralRules samples failed for $language", t)
            }
        }
        val out = LinkedHashMap<String, Int>()
        for (n in listOf(0, 1, 2, 3, 5, 11, 21, 100, 101, 102)) {
            val c = PluralFallback.select(language, n)
            if (!out.containsKey(c)) out[c] = n
        }
        return out
    }
}
