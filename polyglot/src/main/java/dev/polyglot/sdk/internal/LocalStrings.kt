package dev.polyglot.sdk.internal

import android.content.Context
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap

/**
 * Reflection over the app's generated `R$string`, `R$plurals` and `R$array` classes.
 *
 * With AGP 8's default non-transitive R classes, `<namespace>.R` only contains the app module's
 * own resources, which is exactly the set we want to upload / report (not AndroidX's).
 */
internal object LocalStrings {

    private val namesCache = ConcurrentHashMap<String, Set<String>>()
    private val NOT_FOUND = emptySet<String>()

    /** Candidate packages for the app's R class: applicationId and its parents (strips `.debug` etc.). */
    fun candidatePackages(context: Context): List<String> {
        val out = LinkedHashSet<String>()
        var pkg = context.packageName
        while (pkg.count { it == '.' } >= 1) {
            out += pkg
            pkg = pkg.substringBeforeLast('.')
        }
        context.applicationInfo?.className?.substringBeforeLast('.', "")?.takeIf { it.isNotEmpty() }?.let { out += it }
        return out.toList()
    }

    /** Finds `<pkg>.R$<type>` for the first candidate package that has it. */
    fun rClass(context: Context, type: String, explicitR: Class<*>? = null): Class<*>? {
        if (explicitR != null) {
            explicitR.declaredClasses.firstOrNull { it.simpleName == type }?.let { return it }
            return runCatching { Class.forName("${explicitR.name}\$$type", false, explicitR.classLoader) }.getOrNull()
        }
        val loader = context.classLoader
        for (pkg in candidatePackages(context)) {
            try {
                return Class.forName("$pkg.R\$$type", false, loader)
            } catch (_: ClassNotFoundException) {
            } catch (_: LinkageError) {
            }
        }
        return null
    }

    /** `name → id` of every static int field of the R class for [type]. */
    fun entries(context: Context, type: String, explicitR: Class<*>? = null): Map<String, Int> {
        val cls = rClass(context, type, explicitR) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (f in cls.declaredFields) {
            if (!Modifier.isStatic(f.modifiers) || f.type != Int::class.javaPrimitiveType) continue
            try {
                f.isAccessible = true
                val id = f.getInt(null)
                // Field names have '.' replaced by '_'; resolve the real resource name.
                val name = context.resources.getResourceEntryName(id)
                out[name] = id
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** Names of the app's own resources of [type], or null if the R class can't be found. */
    fun appResourceNames(context: Context, type: String): Set<String>? {
        val cached = namesCache.getOrPut(type) {
            val e = entries(context, type)
            if (e.isEmpty()) NOT_FOUND else e.keys
        }
        return if (cached === NOT_FOUND) null else cached
    }
}
