package dev.stringcast.sdk.internal

/**
 * Runtime placeholder normalisation (contract §2): iOS-style tokens are converted to their
 * java.util.Formatter equivalents. Escaped percents (`%%`) are left untouched.
 *
 * - `%@` → `%s`, `%1$@` → `%1$s`
 * - `%ld`, `%lld`, `%lu`, `%llu`, `%u` → `%d` (with positional index preserved), because
 *   java.util.Formatter throws on these and they are common in strings uploaded from iOS.
 */
internal object Placeholders {

    private val TOKEN = Regex("%%|%(\\d+\\$)?@|%(\\d+\\$)?(?:ll|l)?u|%(\\d+\\$)?(?:ll|l)d")

    fun normalize(value: String): String {
        if (value.indexOf('%') < 0) return value
        return TOKEN.replace(value) { m ->
            val t = m.value
            when {
                t == "%%" -> t
                t.endsWith("@") -> "%" + m.groupValues[1] + "s"
                t.endsWith("u") -> "%" + m.groupValues[2] + "d"
                else -> "%" + m.groupValues[3] + "d"
            }
        }
    }

    /** True if [value] contains at least one format specifier other than `%%`. */
    fun hasFormatSpecifiers(value: String): Boolean {
        var i = value.indexOf('%')
        while (i >= 0 && i < value.length - 1) {
            if (value[i + 1] == '%') {
                i = value.indexOf('%', i + 2)
                continue
            }
            return true
        }
        return false
    }
}
